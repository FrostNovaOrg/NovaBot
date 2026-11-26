package org.frostnova.nova.adapter.outbox;

import org.frostnova.nova.core.util.DurableFiles;
import org.sqlite.JDBC;
import org.sqlite.SQLiteConfig;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** 会话和消息共用事务，停用、限量核算与认领均在取得写锁后读取现状。 */
final class OutboxDatabase {
    final Path images;
    private final Path path;
    private final SQLiteConfig config = new SQLiteConfig();
    private boolean initialized;

    OutboxDatabase(Path root) {
        path = root.resolve("outbox.sqlite");
        images = root.resolve("images");
        config.setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE);
        config.enforceForeignKeys(true);
    }

    interface Work<T> {
        T run(Connection db) throws SQLException, IOException;
    }

    <T> T transaction(Work<T> work) {
        try {
            initialize();
            try (var db = connect()) {
                db.setAutoCommit(false);
                try {
                    T result = work.run(db);
                    db.commit();
                    return result;
                } catch (SQLException | IOException | RuntimeException error) {
                    db.rollback();
                    throw error;
                }
            }
        } catch (SQLException | IOException e) {
            throw new IllegalStateException("外发箱读写失败：" + e.getMessage(), e);
        }
    }

    private Connection connect() throws SQLException {
        return JDBC.createConnection("jdbc:sqlite:" + path.toAbsolutePath(), config.toProperties());
    }

    private synchronized void initialize() throws IOException, SQLException {
        if (initialized) return;
        try {
            Files.createDirectories(images, PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rwx------")));
        } catch (UnsupportedOperationException e) {
            Files.createDirectories(images);
        }
        try {
            createPrivateFile(path);
        } catch (FileAlreadyExistsException existing) {
            if (!Files.isRegularFile(path)) throw existing;
        }
        try (var db = connect(); var sql = db.createStatement()) {
            sql.execute("PRAGMA auto_vacuum=FULL");
            db.setAutoCommit(false);
            sql.execute("""
                    CREATE TABLE IF NOT EXISTS conversations (
                        id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, remark TEXT NOT NULL,
                        min_interval_seconds INTEGER NOT NULL, daily_limit INTEGER NOT NULL, enabled INTEGER NOT NULL,
                        last_accepted INTEGER, quota_day TEXT, day_count INTEGER NOT NULL DEFAULT 0)
                    """);
            sql.execute("""
                    CREATE TABLE IF NOT EXISTS records (
                        id INTEGER PRIMARY KEY AUTOINCREMENT, conversation_id INTEGER NOT NULL REFERENCES conversations,
                        conversation_name TEXT NOT NULL, streamer_platform TEXT, streamer_uid INTEGER, streamer_name TEXT,
                        kind TEXT NOT NULL, created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL, status TEXT NOT NULL,
                        at_all INTEGER NOT NULL, segments TEXT NOT NULL, claimant TEXT, claimed_at INTEGER,
                        claim_number INTEGER NOT NULL DEFAULT 0, note TEXT)
                    """);
            sql.execute("CREATE INDEX IF NOT EXISTS pending_times ON records(expires_at) WHERE status='PENDING'");
            sql.execute("CREATE INDEX IF NOT EXISTS claim_times ON records(claimed_at) WHERE status='SENDING'");
            sql.execute("CREATE INDEX IF NOT EXISTS record_age ON records(created_at)");
            sql.execute("""
                    CREATE TABLE IF NOT EXISTS images (
                        name TEXT PRIMARY KEY, record_id INTEGER NOT NULL REFERENCES records(id) ON DELETE CASCADE)
                    """);
            sql.execute("CREATE INDEX IF NOT EXISTS record_images ON images(record_id)");
            db.commit();
        }
        initialized = true;
    }

    static void createPrivateFile(Path path) throws IOException {
        try {
            Files.createFile(path, PosixFilePermissions.asFileAttribute(DurableFiles.OWNER_ONLY));
        } catch (UnsupportedOperationException e) {
            Files.createFile(path);
        }
    }

    static PreparedStatement prepare(Connection db, String sql, Object... args) throws SQLException {
        var statement = db.prepareStatement(sql);
        for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
        return statement;
    }

    static int update(Connection db, String sql, Object... args) throws SQLException {
        try (var statement = prepare(db, sql, args)) {
            return statement.executeUpdate();
        }
    }

    static long insertedId(Connection db) throws SQLException {
        try (var statement = db.createStatement(); ResultSet result = statement.executeQuery("SELECT last_insert_rowid()")) {
            result.next();
            return result.getLong(1);
        }
    }
}
