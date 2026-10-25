package org.frostnova.nova.core.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.analytics.AudienceLedger;
import org.frostnova.nova.core.analytics.AudienceLedger.Guard;
import org.frostnova.nova.core.analytics.AudienceLedger.Row;
import org.frostnova.nova.core.analytics.AudienceLedger.Session;
import org.frostnova.nova.core.analytics.StreamerInsights;
import org.frostnova.nova.core.assistant.AssistantProperties;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.enums.LiveEndReason;
import org.frostnova.nova.core.lang.JsonlFiles;
import org.frostnova.nova.core.model.LiveSession;
import org.frostnova.nova.core.util.DurableFiles;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * 观众账本的显式维护与只读查询。没有启动、定时或下播回调。
 * 每场一个 JSONL，首行为场次信息，其余为按观众编号排序的记录。
 * 更新最多保留一场名单与聚合结果，不缓存全主播的弹幕或观众历史。
 */
@Service
public class AudienceLedgerService {
    private static final Object LOCK = new Object();
    private static final int VERSION = 1;
    private final NovaCoreProperties properties;
    private final AssistantProperties assistant;

    public AudienceLedgerService(NovaCoreProperties properties, AssistantProperties assistant) {
        this.properties = properties;
        this.assistant = assistant;
    }

    /** 仅补这位主播尚未入账的封存场次；同时清理所有主播的拒绝名单记录。 */
    public void updateStreamer(String platform, long uid) throws IOException {
        validate(platform, uid);
        synchronized (LOCK) {
            recover();
            Set<Long> excluded = excluded();
            purgeExcluded(excluded);
            index(root(), platform, uid, excluded);
            writeStart(root(), platform, uid);
        }
    }

    /** 整份重建成功后换上新目录；失败保留原账本。也可在删掉账本后调用。 */
    public void rebuild() throws IOException {
        synchronized (LOCK) {
            recover();
            Path staging = sibling(".building");
            deleteTree(staging);
            Files.createDirectories(staging);
            try {
                Set<Long> excluded = excluded();
                index(staging, null, 0, excluded);
                try (var dirs = Files.list(staging)) {
                    for (Path dir : dirs.filter(Files::isDirectory).toList()) {
                        Session session = readHeader(shards(dir).get(0)).session();
                        writeStart(staging, session.platform(), session.streamerUid());
                    }
                }
                writeExclusions(staging, excluded);
                Path previous = sibling(".previous");
                if (Files.exists(root())) move(root(), previous);
                try {
                    move(staging, root());
                } catch (IOException failed) {
                    if (Files.exists(previous)) move(previous, root());
                    throw failed;
                }
                deleteTree(previous);
            } finally {
                deleteTree(staging);
            }
        }
    }

    /** 含缺名单、短场和异常结束的场次，按开播时刻升序；调用方按 qualified 筛选。 */
    public List<Session> sessions(String platform, long uid) throws IOException {
        validate(platform, uid);
        synchronized (LOCK) {
            recover();
            List<Session> result = new ArrayList<>();
            for (Path file : shards(streamer(root(), platform, uid))) result.add(readHeader(file).session());
            result.sort(Comparator.comparingLong(Session::startTime));
            return List.copyOf(result);
        }
    }

    /** 最早入账场次，包括缺名单的旧场；不代表更早没有观众。 */
    public Optional<Long> ledgerStart(String platform, long uid) throws IOException {
        return sessions(platform, uid).stream().map(Session::startTime).min(Long::compare);
    }

    /** 一场的全部观众行；场次是否缺名单请配合 sessions 查询。 */
    public List<Row> sessionRows(String platform, long uid, long start) throws IOException {
        validate(platform, uid);
        synchronized (LOCK) {
            recover();
            List<Row> result = new ArrayList<>();
            scanRows(streamer(root(), platform, uid).resolve(start + ".jsonl"), excluded(), result::add);
            return List.copyOf(result);
        }
    }

    /** 来场史按开播时刻升序，包含每次来场之前的场数、首次与上次场次。 */
    public AudienceLedger.History viewerHistory(String platform, long uid, long viewer) throws IOException {
        validate(platform, uid);
        synchronized (LOCK) {
            recover();
            List<Row> rows = new ArrayList<>();
            Set<Long> excluded = excluded();
            if (viewer > 0 && !excluded.contains(viewer)) {
                for (Path file : shards(streamer(root(), platform, uid))) {
                    scanRows(file, excluded, row -> { if (row.viewerUid() == viewer) rows.add(row); });
                }
            }
            rows.sort(Comparator.comparingLong(Row::startTime));
            List<AudienceLedger.Visit> visits = new ArrayList<>();
            for (int i = 0; i < rows.size(); i++) {
                visits.add(new AudienceLedger.Visit(rows.get(i), i, rows.get(0).startTime(),
                        i == 0 ? null : rows.get(i - 1).startTime()));
            }
            return new AudienceLedger.History(ledgerStart(platform, uid).orElse(null), List.copyOf(visits));
        }
    }

    /* Only keys and line numbers survive the first pass, never all sessions' userSets. */
    private void index(Path destination, String platform, long uid, Set<Long> excluded) throws IOException {
        Path source = data().resolve("sessions.jsonl");
        Map<Key, Long> latest = new HashMap<>();
        try (var lines = JsonlFiles.lines(source)) {
            long ordinal = 0;
            for (String line : (Iterable<String>) lines::iterator) {
                if (!line.isBlank()) {
                    JSONObject json = parse(line);
                    String p = json.getString("platform");
                    long u = json.getLongValue("uid");
                    validate(p, u);
                    if (platform == null || (platform.equals(p) && uid == u))
                        latest.put(new Key(p, u, json.getLongValue("startTime")), ordinal);
                }
                ordinal++;
            }
        } catch (NoSuchFileException absent) {
            return;
        } catch (UncheckedIOException failure) {
            throw failure.getCause();
        }
        try (var lines = JsonlFiles.lines(source)) {
            long ordinal = 0;
            for (String line : (Iterable<String>) lines::iterator) {
                if (!line.isBlank()) {
                    JSONObject json = parse(line);
                    Key key = new Key(json.getString("platform"), json.getLongValue("uid"), json.getLongValue("startTime"));
                    Long last = latest.get(key);
                    Path target = streamer(destination, key.platform(), key.uid()).resolve(key.start() + ".jsonl");
                    if (last != null && last == ordinal) {
                        Path detail = data().resolve("details").resolve(key.platform() + "-" + key.uid() + "-" + key.start());
                        if (!sealed(detail)) Files.deleteIfExists(target);
                        else if (!Files.exists(target)) build(target, detail, JSON.parseObject(line, LiveSession.class), excluded);
                    }
                }
                ordinal++;
            }
        } catch (UncheckedIOException failure) {
            throw failure.getCause();
        }
    }

    private void build(Path target, Path detail, LiveSession source, Set<Long> excluded) throws IOException {
        Map<Long, Accumulator> users = new TreeMap<>();
        if (source.hasUserSets()) {
            source.userSets().values().forEach(list -> list.forEach(uid -> {
                if (uid != null && uid > 0 && !excluded.contains(uid)) users.computeIfAbsent(uid, Accumulator::new);
            }));
        }
        for (Long uid : source.userSet("enter_users")) if (users.containsKey(uid)) users.get(uid).entered = true;
        for (Long uid : source.userSet("like_users")) if (users.containsKey(uid)) users.get(uid).liked = true;
        JSONObject rankings = parse(Files.readString(detail.resolve("detail.json"))).getJSONObject("rankings");
        if (rankings != null && rankings.getJSONArray("enter_users") != null) {
            for (Object raw : rankings.getJSONArray("enter_users")) {
                JSONObject user = (JSONObject) raw;
                Accumulator row = users.get(user.getLong("uid"));
                if (row != null) row.nickname(user.getString("uname"), Long.MIN_VALUE);
            }
        }
        scanSource(detail.resolve("danmu.jsonl"), json -> {
            Accumulator row = users.get(json.getLong("uid"));
            if (row == null) return;
            long at = json.getLongValue("at");
            row.nickname(json.getString("uname"), at);
            row.danmuCount++;
            row.firstDanmuAt = row.firstDanmuAt == null ? at : Math.min(at, row.firstDanmuAt);
        });
        scanSource(detail.resolve("events.jsonl"), json -> {
            Accumulator row = users.get(json.getLong("uid"));
            if (row == null) return;
            long at = json.getLongValue("at");
            row.nickname(json.getString("un"), at);
            String type = json.getString("t");
            if ("follow".equals(type)) row.followed = true;
            if (Set.of("gift", "box", "guard").contains(type)) {
                BigDecimal value = json.getBigDecimal("val");
                if (value != null) row.giftYuan = row.giftYuan.add(value);
            }
            if ("guard".equals(type)) {
                int n = json.getIntValue("n");
                String unit = json.getString("u");
                Long months = null;
                if ("月".equals(unit)) months = (long) n;
                else if ("年".equals(unit)) months = n * 12L;
                row.guards.add(new Guard(at, json.getIntValue("lv"), n, unit, months));
            }
        });
        // A resumed marker appearing during the read means this was only an intermediate snapshot.
        if (!sealed(detail)) return;
        Session session = new Session(source.platform(), source.uid(), source.startTime(), source.hasUserSets(),
                source.durationSeconds() > 600 && source.endReason() != LiveEndReason.UNCLOSED && source.hasUserSets(),
                StreamerInsights.listQuality(source));
        Files.createDirectories(target.getParent());
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            try (BufferedWriter writer = Files.newBufferedWriter(temp)) {
                writeLine(writer, new Header(VERSION, session));
                for (Accumulator row : users.values()) writeLine(writer, row.row(source));
            }
            move(temp, target);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private void purgeExcluded(Set<Long> excluded) throws IOException {
        Files.createDirectories(root());
        Path mark = root().resolve("privacy.json");
        Set<Long> previous = Files.exists(mark)
                ? new HashSet<>(JSON.parseArray(Files.readString(mark), Long.class)) : Set.of();
        if (!excluded.containsAll(previous))
            throw new IOException("拒绝名单缩减后请整份重建观众账本");
        if (!Files.exists(mark) || !previous.equals(excluded)) {
            try (var paths = Files.walk(root())) {
                for (Path file : paths.filter(p -> p.toString().endsWith(".jsonl")).toList()) {
                    Path temp = file.resolveSibling(file.getFileName() + ".tmp");
                    try {
                        try (BufferedWriter writer = Files.newBufferedWriter(temp)) {
                            writeLine(writer, readHeader(file));
                            scanRows(file, excluded, row -> {
                                try { writeLine(writer, row); }
                                catch (IOException failure) { throw new UncheckedIOException(failure); }
                            });
                        }
                        move(temp, file);
                    } catch (UncheckedIOException failure) {
                        throw failure.getCause();
                    } finally {
                        Files.deleteIfExists(temp);
                    }
                }
            }
            writeExclusions(root(), excluded);
        }
    }

    private void writeExclusions(Path directory, Set<Long> excluded) throws IOException {
        DurableFiles.replace(directory.resolve("privacy.json"), JSON.toJSONString(excluded.stream().sorted().toList()));
    }

    private void writeStart(Path base, String platform, long uid) throws IOException {
        List<Path> files = shards(streamer(base, platform, uid));
        if (files.isEmpty()) {
            Files.deleteIfExists(streamer(base, platform, uid).resolve("index.json"));
            return;
        }
        long start = Long.MAX_VALUE;
        for (Path file : files) start = Math.min(start, readHeader(file).session().startTime());
        DurableFiles.replace(streamer(base, platform, uid).resolve("index.json"), JSON.toJSONString(
                new StreamerIndex(VERSION, platform, uid, start)));
    }

    private void scanRows(Path path, Set<Long> excluded, Consumer<Row> consumer) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            header(reader.readLine());
            String line;
            while ((line = reader.readLine()) != null) {
                Row row = JSON.parseObject(line, Row.class);
                if (row.viewerUid() > 0 && !excluded.contains(row.viewerUid())) consumer.accept(row);
            }
        } catch (NoSuchFileException absent) {
            // No shard means this session has not been indexed.
        }
    }

    private void scanSource(Path path, Consumer<JSONObject> consumer) throws IOException {
        try (var lines = JsonlFiles.lines(path)) {
            for (String line : (Iterable<String>) lines::iterator) if (!line.isBlank()) consumer.accept(parse(line));
        } catch (NoSuchFileException absent) {
            // Older archives may not have a message or event file.
        } catch (UncheckedIOException failure) {
            throw failure.getCause();
        }
    }

    private static JSONObject parse(String line) throws IOException {
        try {
            JSONObject object = JSON.parseObject(line);
            if (object == null) throw new IOException("归档中存在空记录，账本更新未完成");
            return object;
        } catch (com.alibaba.fastjson2.JSONException invalid) {
            throw new IOException("归档格式损坏，账本更新未完成", invalid);
        }
    }

    private Header readHeader(Path file) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(file)) { return header(reader.readLine()); }
    }
    private Header header(String line) throws IOException {
        Header header = line == null ? null : JSON.parseObject(line, Header.class);
        if (header == null || header.version() != VERSION || header.session() == null)
            throw new IOException("观众账本格式不兼容或不完整，请整份重建");
        return header;
    }
    private static void writeLine(BufferedWriter writer, Object value) throws IOException {
        writer.write(JSON.toJSONString(value)); writer.newLine();
    }
    private List<Path> shards(Path dir) throws IOException {
        if (!Files.exists(dir)) return List.of();
        try (var paths = Files.list(dir)) {
            return paths.filter(p -> p.getFileName().toString().matches("-?[0-9]+\\.jsonl")).sorted().toList();
        }
    }
    private Path data() {
        Path parent = Path.of(properties.getLive().getLiveDataPath()).getParent();
        return parent == null ? Path.of(".") : parent;
    }
    private Path root() { return data().resolve("audience-ledger"); }
    private Path sibling(String suffix) { return data().resolve("audience-ledger" + suffix); }
    private static Path streamer(Path base, String platform, long uid) { return base.resolve(platform + "-" + uid); }
    private Set<Long> excluded() { return Set.copyOf(assistant.getExcludedUids()); }
    private static boolean sealed(Path dir) {
        return Files.exists(dir.resolve("detail.json")) && !Files.exists(dir.resolve("resumed"));
    }
    private static void validate(String platform, long uid) {
        if (platform == null || !platform.matches("[A-Za-z0-9_-]{1,32}") || uid <= 0)
            throw new IllegalArgumentException("平台或主播编号无效");
    }
    private void recover() throws IOException {
        deleteTree(sibling(".building"));
        Path previous = sibling(".previous");
        if (Files.exists(previous)) {
            if (!Files.exists(root())) move(previous, root());
            else deleteTree(previous);
        }
        if (Files.exists(root())) {
            try (var paths = Files.walk(root())) {
                for (Path file : paths.filter(p -> p.getFileName().toString().endsWith(".tmp")).toList())
                    Files.delete(file);
            }
        }
    }
    private static void move(Path from, Path to) throws IOException {
        try { Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }
    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var paths = Files.walk(dir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }
    private record Key(String platform, long uid, long start) {}
    public record Header(int version, Session session) {}
    public record StreamerIndex(int version, String platform, long streamerUid, long ledgerStart) {}

    private static final class Accumulator {
        final long uid;
        String nickname;
        long nicknameAt = Long.MIN_VALUE;
        boolean entered, liked, followed;
        Long firstDanmuAt;
        long danmuCount;
        BigDecimal giftYuan = BigDecimal.ZERO;
        final List<Guard> guards = new ArrayList<>();
        Accumulator(long uid) { this.uid = uid; }
        void nickname(String name, long at) {
            if (name != null && !name.isBlank() && at >= nicknameAt) { nickname = name; nicknameAt = at; }
        }
        Row row(LiveSession source) {
            guards.sort(Comparator.comparingLong(Guard::at));
            Long months = 0L;
            for (Guard guard : guards) {
                if (guard.months() == null) { months = null; break; }
                months += guard.months();
            }
            return new Row(source.platform(), source.uid(), source.startTime(), uid, nickname, uid == source.uid(),
                    entered, liked, danmuCount > 0, firstDanmuAt, danmuCount, followed, giftYuan,
                    guards.isEmpty() ? null : guards.get(guards.size() - 1).level(), months, List.copyOf(guards));
        }
    }
}
