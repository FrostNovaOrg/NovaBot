package org.frostnova.nova.console.assistant;

import org.sqlite.JDBC;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteConnection;
import org.sqlite.SQLiteException;
import org.sqlite.SQLiteLimits;
import org.sqlite.SQLiteOpenMode;

import java.sql.SQLException;

/** Xerial 只执行本机生成的建表和插入语句。内存快照交给有硬堆限制的 SQLite 执行模型查询。 */
final class AssistantSqlDatabase {
    static final int HEAP_BYTES = 64 * 1024 * 1024;
    static final int RESULT_BYTES = 64 * 1024;
    static final int MAX_COLUMNS = 32;
    private static final int PAGE_BYTES = 4096;
    // 序列化时 SQLite 同时持有页面和快照，另留空间给页缓存开销。
    private static final int MAX_PAGES = 28 * 1024 * 1024 / PAGE_BYTES;

    private AssistantSqlDatabase() {}

    static SQLiteConnection open() throws SQLException {
        var config = new SQLiteConfig();
        config.setOpenMode(SQLiteOpenMode.OPEN_MEMORY);
        config.setTempStore(SQLiteConfig.TempStore.MEMORY);
        config.setJournalMode(SQLiteConfig.JournalMode.OFF);
        config.setPageSize(PAGE_BYTES);
        config.enableLoadExtension(false);
        config.setBusyTimeout(0);
        SQLiteConnection db = JDBC.createConnection("jdbc:sqlite::memory:", config.toProperties());
        try {
            db.setLimit(SQLiteLimits.SQLITE_LIMIT_ATTACHED, 0);
            db.setLimit(SQLiteLimits.SQLITE_LIMIT_WORKER_THREADS, 0);
            db.setLimit(SQLiteLimits.SQLITE_LIMIT_LENGTH, RESULT_BYTES);
            db.setLimit(SQLiteLimits.SQLITE_LIMIT_COLUMN, MAX_COLUMNS);
            try (var statement = db.createStatement();
                 var limit = statement.executeQuery("PRAGMA max_page_count = " + MAX_PAGES)) {
                if (!limit.next() || limit.getInt(1) != MAX_PAGES) throw new SQLException("SQLite page limit unavailable");
            }
            return db;
        } catch (SQLException | RuntimeException error) {
            try { db.close(); }
            catch (SQLException closing) { error.addSuppressed(closing); }
            throw error;
        }
    }

    static String reason(SQLException error) {
        if (error instanceof SQLiteException sqlite) {
            return switch (sqlite.getResultCode()) {
                case SQLITE_NOMEM, SQLITE_FULL -> "装库超过 SQLite 内存上限，请缩短时间范围或减少所选表";
                case SQLITE_TOOBIG -> "归档行过大，请缩短时间范围";
                default -> "SQLite 无法装入归档，请缩短时间范围后重试";
            };
        }
        return "SQLite 无法建立临时数据库";
    }
}
