package org.frostnova.nova.adapter.outbox;

import com.alibaba.fastjson2.JSON;
import org.frostnova.nova.adapter.outbox.OutboxConversations.Conversation;
import org.frostnova.nova.adapter.outbox.OutboxRecord.Segment;
import org.frostnova.nova.adapter.outbox.OutboxRecord.SegmentType;
import org.frostnova.nova.adapter.outbox.OutboxRecord.Status;
import org.frostnova.nova.adapter.outbox.OutboxRecord.Streamer;
import org.frostnova.nova.core.model.Message.PushKind;
import org.frostnova.nova.core.model.MessagePlaceholders;
import org.frostnova.nova.core.timeline.TimelineEvent;
import org.frostnova.nova.core.timeline.TimelineEventType;
import org.frostnova.nova.core.timeline.TimelineWriter;
import org.springframework.scheduling.annotation.Scheduled;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import static org.frostnova.nova.adapter.outbox.OutboxDatabase.prepare;
import static org.frostnova.nova.adapter.outbox.OutboxDatabase.update;
import static org.frostnova.nova.adapter.outbox.OutboxRecord.Status.*;

/** 外发箱的本机取件、发送状态与管理入口。所有时刻均以本机时钟为准。 */
public class OutboxService {
    private static final Pattern NEXT = Pattern.compile(Pattern.quote(MessagePlaceholders.NEXT));
    private final OutboxDatabase database;
    private final OutboxSettings settings;
    private final Clock clock;
    private final TimelineWriter timeline;
    private boolean needsImageCleanup = true;

    OutboxService(OutboxDatabase database, OutboxSettings settings, Clock clock, TimelineWriter timeline) {
        this.database = database;
        this.settings = settings;
        this.clock = clock;
        this.timeline = timeline;
    }

    /** 每个分条单独入箱并核算会话限量。 */
    public synchronized List<OutboxRecord> enqueue(long conversationId, Streamer streamer, PushKind kind, String content) {
        Objects.requireNonNull(streamer);
        Objects.requireNonNull(kind);
        if (content == null) throw new IllegalArgumentException("外发内容为空");
        var records = new ArrayList<OutboxRecord>();
        for (String part : NEXT.split(content)) {
            if (part.isEmpty()) continue;
            var parsed = OutboxContent.parse(part);
            try {
                records.add(database.transaction(db -> insert(db, OutboxConversations.active(db, conversationId),
                        streamer, kind, parsed, clock.instant())));
            } catch (RuntimeException failure) {
                // 单条写入失败时，清理尚未随记录提交的图片。
                needsImageCleanup = true;
                throw failure;
            }
        }
        if (records.isEmpty()) throw new IllegalArgumentException("外发内容为空");
        return List.copyOf(records);
    }

    private OutboxRecord insert(Connection db, Conversation conversation, Streamer streamer, PushKind kind,
                                OutboxContent content, Instant now) throws SQLException, IOException {
        String limited = reserveQuota(db, conversation, now);
        var status = limited == null ? PENDING : LIMITED;
        update(db, """
                INSERT INTO records(conversation_id,conversation_name,streamer_platform,streamer_uid,streamer_name,
                    kind,created_at,expires_at,status,at_all,segments,note) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
                """, conversation.id(), conversation.name(), streamer.platform(), streamer.uid(), streamer.name(),
                kind.name(), now.toEpochMilli(), now.plus(settings.ttl(kind)).toEpochMilli(), status.name(),
                content.atAll(), JSON.toJSONString(content.segments()), limited);
        long id = OutboxDatabase.insertedId(db);
        for (var picture : content.pictures().entrySet()) {
            String name = imageName(id, picture.getKey());
            Path path = database.images.resolve(name);
            // SQLite 回滚的编号可能复用。此时同名文件属于上一次未提交的投递。
            Files.deleteIfExists(path);
            OutboxDatabase.createPrivateFile(path);
            Files.write(path, picture.getValue(), StandardOpenOption.WRITE, StandardOpenOption.SYNC);
            update(db, "INSERT INTO images(name,record_id) VALUES(?,?)", name, id);
        }
        return require(db, id);
    }

    private String reserveQuota(Connection db, Conversation conversation, Instant now) throws SQLException {
        String today = now.atZone(clock.getZone()).toLocalDate().toString();
        long count;
        try (var sql = prepare(db, "SELECT last_accepted,quota_day,day_count FROM conversations WHERE id=?", conversation.id());
             var row = sql.executeQuery()) {
            row.next();
            long last = row.getLong("last_accepted");
            if (!row.wasNull() && conversation.minIntervalSeconds() > 0
                    && now.isBefore(Instant.ofEpochMilli(last).plusSeconds(conversation.minIntervalSeconds()))) {
                return "未达到会话最小间隔（" + conversation.minIntervalSeconds() + " 秒）";
            }
            count = today.equals(row.getString("quota_day")) ? row.getLong("day_count") : 0;
            if (conversation.dailyLimit() > 0 && count >= conversation.dailyLimit()) {
                return "已达到会话每日上限（" + conversation.dailyLimit() + " 条）";
            }
        }
        update(db, "UPDATE conversations SET last_accepted=?,quota_day=?,day_count=? WHERE id=?",
                now.toEpochMilli(), today, count + 1, conversation.id());
        return null;
    }

    /** 编号升序，afterId 为上一页末条编号，首页用 0。status 为空时包含全部状态。 */
    public synchronized List<OutboxRecord> list(Status status, long afterId, int limit, Predicate<Streamer> visible) {
        return page(status, afterId, limit, visible, false);
    }

    /** 编号倒序，beforeId 为上一页末条编号，首页用 0。 */
    public synchronized List<OutboxRecord> newest(Status status, long beforeId, int limit, Predicate<Streamer> visible) {
        return page(status, beforeId, limit, visible, true);
    }

    private List<OutboxRecord> page(Status status, long cursor, int limit, Predicate<Streamer> visible, boolean newest) {
        Objects.requireNonNull(visible, "须提供主播可见范围");
        if (cursor < 0 || limit <= 0) throw new IllegalArgumentException("外发箱游标或条数无效");
        advance(clock.instant());
        return database.transaction(db -> {
            var result = new ArrayList<OutboxRecord>();
            String range = newest && cursor != 0 ? "id<?" : "id>?";
            String query = "SELECT * FROM records WHERE " + range + (status == null ? "" : " AND status=?")
                    + (newest ? " ORDER BY id DESC" : " ORDER BY id");
            try (var sql = db.prepareStatement(query)) {
                sql.setLong(1, cursor);
                if (status != null) sql.setString(2, status.name());
                try (var rows = sql.executeQuery()) {
                    while (result.size() < limit && rows.next()) {
                        var streamer = streamer(rows);
                        if (visible.test(streamer)) result.add(read(rows, streamer));
                    }
                }
            }
            return List.copyOf(result);
        });
    }

    /** 当前待发、发送中，以及最近一天进入异常结果的可见记录。聚合时无需读正文。 */
    synchronized Map<Status, Long> summary(Predicate<Streamer> visible) {
        Instant now = clock.instant();
        advance(now);
        return database.transaction(db -> {
            var counts = new EnumMap<Status, Long>(Status.class);
            for (var status : List.of(PENDING, SENDING, FAILED, UNKNOWN, EXPIRED)) counts.put(status, 0L);
            try (var sql = prepare(db, """
                    SELECT status, streamer_platform, streamer_uid, streamer_name, count(*) AS total FROM records
                    WHERE status IN ('PENDING','SENDING') OR (status IN ('FAILED','UNKNOWN','EXPIRED') AND
                        COALESCE(result_at, CASE status WHEN 'EXPIRED' THEN expires_at
                            WHEN 'UNKNOWN' THEN claimed_at + ? ELSE claimed_at END) >= ?)
                    GROUP BY status, streamer_platform, streamer_uid, streamer_name
                    """, settings.getClaimTimeout().toMillis(), now.minus(Duration.ofDays(1)).toEpochMilli());
                 var rows = sql.executeQuery()) {
                while (rows.next()) {
                    if (visible.test(streamer(rows))) {
                        counts.merge(Status.valueOf(rows.getString("status")), rows.getLong("total"), Long::sum);
                    }
                }
            }
            return counts;
        });
    }

    /** segmentIndex 从 0 开始，仅内联图片段有本地字节。 */
    public synchronized byte[] readImage(long id, int segmentIndex) {
        return readImage(id, segmentIndex, streamer -> true);
    }

    synchronized byte[] readImage(long id, int segmentIndex, Predicate<Streamer> visible) {
        advance(clock.instant());
        return database.transaction(db -> {
            var segments = require(db, id, visible).segments();
            if (segmentIndex < 0 || segmentIndex >= segments.size() || segments.get(segmentIndex).type() != SegmentType.IMAGE_BYTES) {
                throw new IllegalArgumentException("这段没有本地图片");
            }
            return Files.readAllBytes(database.images.resolve(imageName(id, segmentIndex)));
        });
    }

    public synchronized OutboxRecord claim(long id, String claimant) {
        if (claimant == null || claimant.isBlank()) throw new IllegalArgumentException("请填写认领者名称");
        advance(clock.instant());
        var record = database.transaction(db -> {
            long now = clock.millis();
            int claimed = update(db, """
                    UPDATE records SET status='SENDING', claimant=?, claimed_at=?, claim_number=claim_number+1
                    WHERE id=? AND status='PENDING' AND expires_at>?
                    """, claimant.strip(), now, id, now);
            if (claimed == 0) {
                expect(require(db, id), PENDING);
                // 等写锁期间到期，直接记为过期。
                update(db, "UPDATE records SET status='EXPIRED' WHERE id=?", id);
            }
            return require(db, id);
        });
        if (record.status() == EXPIRED) {
            timeline.record(event(record, EXPIRED, clock.instant()));
            expect(record, SENDING);
        }
        return record;
    }

    /** claimNumber 取自本次 claim 的返回值，旧认领迟到的结果不会覆盖新认领。 */
    public synchronized OutboxRecord receipt(long id, long claimNumber, Status outcome, String note) {
        if (outcome != SENT && outcome != FAILED && outcome != SKIPPED) throw new IllegalArgumentException("发送结果无效");
        Instant now = clock.instant();
        advance(now);
        var result = database.transaction(db -> {
            var claimed = require(db, id);
            expect(claimed, SENDING);
            if (claimNumber != claimed.claimNumber()) throw new IllegalStateException("该次认领已结束");
            update(db, "UPDATE records SET status=?, note=?, result_at=? WHERE id=?", outcome.name(), note, now.toEpochMilli(), id);
            return require(db, id);
        });
        if (outcome == FAILED) timeline.record(event(result, FAILED, now));
        return result;
    }

    public synchronized OutboxRecord requeue(long id) {
        Instant now = clock.instant();
        advance(now);
        return database.transaction(db -> {
            var record = require(db, id);
            expect(record, FAILED, UNKNOWN, EXPIRED);
            update(db, """
                    UPDATE records SET status='PENDING', expires_at=?, claimant=NULL, claimed_at=NULL, note=NULL, result_at=NULL WHERE id=?
                    """, now.plus(settings.ttl(record.kind())).toEpochMilli(), id);
            return require(db, id);
        });
    }

    public synchronized OutboxRecord voidPending(long id) {
        advance(clock.instant());
        return database.transaction(db -> {
            expect(require(db, id), PENDING);
            update(db, "UPDATE records SET status='VOID' WHERE id=?", id);
            return require(db, id);
        });
    }

    private static void expect(OutboxRecord record, Status... allowed) {
        for (var status : allowed) if (record.status() == status) return;
        throw new StatusConflict("记录当前为「" + record.status().label() + "」，无法执行此操作");
    }

    static class StatusConflict extends IllegalStateException {
        StatusConflict(String message) { super(message); }
    }

    private static String imageName(long id, int part) { return id + "-" + part + ".bin"; }

    private static OutboxRecord require(Connection db, long id) throws SQLException {
        return require(db, id, streamer -> true);
    }

    private static OutboxRecord require(Connection db, long id, Predicate<Streamer> visible) throws SQLException {
        try (var sql = prepare(db, "SELECT * FROM records WHERE id=?", id); var rows = sql.executeQuery()) {
            if (!rows.next()) throw new IllegalArgumentException("外发记录不存在");
            var owner = streamer(rows);
            if (!visible.test(owner)) throw new IllegalArgumentException("外发记录不存在");
            return read(rows, owner);
        }
    }

    private static Streamer streamer(ResultSet row) throws SQLException {
        long uid = row.getLong("streamer_uid");
        Long optionalUid = row.wasNull() ? null : uid;
        return new Streamer(row.getString("streamer_platform"), optionalUid, row.getString("streamer_name"));
    }

    private static OutboxRecord read(ResultSet row, Streamer streamer) throws SQLException {
        long claimed = row.getLong("claimed_at");
        Instant claimedAt = row.wasNull() ? null : Instant.ofEpochMilli(claimed);
        return new OutboxRecord(row.getLong("id"), row.getLong("conversation_id"), row.getString("conversation_name"),
                streamer, PushKind.valueOf(row.getString("kind")), Instant.ofEpochMilli(row.getLong("created_at")),
                Instant.ofEpochMilli(row.getLong("expires_at")), Status.valueOf(row.getString("status")),
                JSON.parseArray(row.getString("segments"), Segment.class), row.getBoolean("at_all"),
                row.getString("claimant"), claimedAt, row.getLong("claim_number"), row.getString("note"));
    }

    /** 启动后立即执行，此后定时处理无人访问的记录。读取和状态操作也会核对截止时刻。 */
    @Scheduled(fixedDelay = 60_000)
    public synchronized void maintain() {
        advance(clock.instant());
        if (needsImageCleanup) {
            database.transaction(db -> {
                var kept = new HashSet<String>();
                try (var sql = db.createStatement(); var rows = sql.executeQuery("SELECT name FROM images")) {
                    while (rows.next()) kept.add(rows.getString(1));
                }
                try (var files = Files.newDirectoryStream(database.images, "*.bin")) {
                    for (Path file : files) {
                        if (!kept.contains(file.getFileName().toString())) Files.delete(file);
                    }
                }
                return null;
            });
            needsImageCleanup = false;
        }
    }

    private void advance(Instant now) {
        var events = new ArrayList<TimelineEvent>();
        int deleted = database.transaction(db -> {
            long claimCutoff = now.minus(settings.getClaimTimeout()).toEpochMilli();
            try (var sql = prepare(db, """
                    SELECT * FROM records WHERE (status='PENDING' AND expires_at<=?) OR (status='SENDING' AND claimed_at<=?)
                    """, now.toEpochMilli(), claimCutoff); var rows = sql.executeQuery()) {
                while (rows.next()) {
                    var record = read(rows, streamer(rows));
                    events.add(event(record, record.status() == PENDING ? EXPIRED : UNKNOWN, now));
                }
            }
            if (!events.isEmpty()) {
                update(db, "UPDATE records SET status='EXPIRED' WHERE status='PENDING' AND expires_at<=?", now.toEpochMilli());
                update(db, "UPDATE records SET status='UNKNOWN', result_at=claimed_at+? WHERE status='SENDING' AND claimed_at<=?",
                        settings.getClaimTimeout().toMillis(), claimCutoff);
            }
            return update(db, "DELETE FROM records WHERE created_at<=?", now.minus(settings.getRetention()).toEpochMilli());
        });
        needsImageCleanup |= deleted > 0;
        events.forEach(timeline::record);
    }

    private TimelineEvent event(OutboxRecord record, Status status, Instant now) {
        var type = status == UNKNOWN ? TimelineEventType.PUSH_UNCERTAIN : TimelineEventType.PUSH_FAILED;
        var streamer = record.streamer();
        String uid = streamer.uid() == null ? null : streamer.uid().toString();
        String name = streamer.name() == null || streamer.name().isBlank() ? uid : streamer.name();
        return TimelineEvent.of(type, TimelineEvent.Level.WARN).at(now)
                .streamer(name).channel("外发 " + record.conversationName())
                .text("外发记录 #" + record.id() + " " + status.label())
                .detail("streamerPlatform", streamer.platform())
                .detail("streamerUid", uid)
                .detail("platform", OutboxPluginAutoConfiguration.PLATFORM)
                .detail("kind", record.kind().name()).build();
    }
}
