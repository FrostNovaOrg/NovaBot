package org.frostnova.nova.adapter.outbox;

import org.frostnova.nova.core.config.ui.PushTargetDirectory;
import org.frostnova.nova.core.config.ui.PushTargetLookup;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAccount;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.plugin.NovaComponent;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.frostnova.nova.adapter.outbox.OutboxDatabase.prepare;
import static org.frostnova.nova.adapter.outbox.OutboxPluginAutoConfiguration.PLATFORM;

@NovaComponent
public class OutboxConversations implements PushTargetDirectory, PushTargetLookup {
    public record Conversation(long id, String name, String remark, int minIntervalSeconds, int dailyLimit, boolean enabled) {}
    private final OutboxDatabase database;

    OutboxConversations(OutboxDatabase database) { this.database = database; }

    public List<Conversation> list() {
        return database.transaction(db -> {
            var result = new ArrayList<Conversation>();
            try (var statement = db.createStatement();
                 var rows = statement.executeQuery("SELECT * FROM conversations ORDER BY id")) {
                while (rows.next()) result.add(read(rows));
            }
            return List.copyOf(result);
        });
    }

    public Conversation create(String name, String remark, int minIntervalSeconds, int dailyLimit) {
        validate(name, minIntervalSeconds, dailyLimit);
        return database.transaction(db -> {
            OutboxDatabase.update(db, """
                    INSERT INTO conversations(name,remark,min_interval_seconds,daily_limit,enabled) VALUES(?,?,?,?,1)
                    """, name.strip(), remark == null ? "" : remark, minIntervalSeconds, dailyLimit);
            return require(db, OutboxDatabase.insertedId(db));
        });
    }

    /** 未提供的字段沿用当前值，编号与启停状态保留。 */
    public Conversation update(long id, String name, String remark, Integer minIntervalSeconds, Integer dailyLimit) {
        return database.transaction(db -> {
            Conversation previous = require(db, id);
            String nextName = name == null ? previous.name() : name.strip();
            int interval = minIntervalSeconds == null ? previous.minIntervalSeconds() : minIntervalSeconds;
            int limit = dailyLimit == null ? previous.dailyLimit() : dailyLimit;
            validate(nextName, interval, limit);
            OutboxDatabase.update(db, """
                    UPDATE conversations SET name=?, remark=?, min_interval_seconds=?, daily_limit=? WHERE id=?
                    """, nextName, remark == null ? previous.remark() : remark, interval, limit, id);
            return require(db, id);
        });
    }

    public Conversation disable(long id) {
        return database.transaction(db -> {
            OutboxDatabase.update(db, "UPDATE conversations SET enabled=0 WHERE id=?", id);
            return require(db, id);
        });
    }

    private static void validate(String name, int interval, int limit) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("外发会话名称不能为空");
        if (interval < 0 || limit < 0) throw new IllegalArgumentException("会话限量不能为负数");
    }

    static Conversation require(Connection db, long id) throws SQLException {
        try (var statement = prepare(db, "SELECT * FROM conversations WHERE id=?", id);
             var rows = statement.executeQuery()) {
            if (!rows.next()) throw new IllegalArgumentException("外发会话不存在");
            return read(rows);
        }
    }

    static Conversation active(Connection db, long id) throws SQLException {
        var conversation = require(db, id);
        if (!conversation.enabled()) throw new IllegalStateException("外发会话已停用");
        return conversation;
    }

    private static Conversation read(ResultSet row) throws SQLException {
        return new Conversation(row.getLong("id"), row.getString("name"), row.getString("remark"),
                row.getInt("min_interval_seconds"), row.getInt("daily_limit"), row.getBoolean("enabled"));
    }

    @Override public String id() { return PLATFORM; }

    @Override public boolean supports(String platform) { return PLATFORM.equals(platform); }

    @Override public Result lookup(String platform, PushTargetType type, long num, ConfigUiAccount account) {
        if (!supports(platform) || type != PushTargetType.GROUP) return new Result(false, null, "请选择外发会话");
        return database.transaction(db -> {
            try {
                var conversation = require(db, num);
                return conversation.enabled() ? new Result(true, conversation.name(), null)
                        : new Result(false, null, "外发会话已停用");
            } catch (IllegalArgumentException missing) {
                return new Result(false, null, missing.getMessage());
            }
        });
    }

    @Override public List<Snapshot> snapshots(boolean force) {
        var groups = list().stream().filter(Conversation::enabled)
                .map(c -> new Group(c.id(), c.name(), null, null, c.remark())).toList();
        Instant now = Instant.now();
        return List.of(new Snapshot(PLATFORM, groups, List.of(), now, now, false, null));
    }
}
