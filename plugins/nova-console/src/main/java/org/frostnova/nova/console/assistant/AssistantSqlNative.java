package org.frostnova.nova.console.assistant;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.sun.jna.Callback;
import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;

import java.lang.ref.Reference;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.frostnova.nova.console.assistant.AssistantSqlDatabase.HEAP_BYTES;
import static org.frostnova.nova.console.assistant.AssistantSqlDatabase.MAX_COLUMNS;
import static org.frostnova.nova.console.assistant.AssistantSqlDatabase.RESULT_BYTES;
import static org.frostnova.nova.console.assistant.AssistantSqlRequest.rejected;

/** SQLite 读回内存快照，在预编译与执行期间启用原生权限、内存和时限检查。 */
final class AssistantSqlNative {
    private static final int OK = 0, DENY = 1;
    private static final int INTERRUPT = 9, NOMEM = 7, FULL = 13, TOOBIG = 18, AUTH = 23, READONLY = 8;
    private static final int ERROR = 1, ROW = 100, DONE = 101;
    private static final int READ = 20, SELECT = 21, FUNCTION = 31, RECURSIVE = 33;
    private static final int INTEGER = 1, FLOAT = 2, TEXT = 3, BLOB = 4;
    private static final int CONFIG_MEMSTATUS = 9;
    private static final int DBCONFIG_ENABLE_LOAD_EXTENSION = 1005, DBCONFIG_DQS_DML = 1013;
    private static final int LIMIT_LENGTH = 0, LIMIT_SQL_LENGTH = 1, LIMIT_COLUMN = 2, LIMIT_ATTACHED = 7,
            LIMIT_LIKE_PATTERN_LENGTH = 8, LIMIT_WORKER_THREADS = 11;
    private static final int OPEN_READWRITE = 2, OPEN_CREATE = 4, OPEN_MEMORY = 0x80;
    private static final int DESERIALIZE_FREEONCLOSE = 1, DESERIALIZE_READONLY = 4;
    private static final int PREPARE_NO_VTAB = 4;
    private static final int RESULT_ROWS = 200;
    private static final long EXECUTION_NANOS = TimeUnit.SECONDS.toNanos(5);
    private static final Set<String> FUNCTIONS = Set.of(
            "abs", "avg", "cast", "ceil", "ceiling", "char", "coalesce", "concat", "concat_ws", "count",
            "cume_dist", "date", "datetime", "dense_rank", "first_value", "floor", "format", "glob",
            "group_concat", "hex", "if", "ifnull", "iif", "instr", "julianday", "lag", "last_value", "lead", "length",
            "like", "likelihood", "likely", "lower", "ltrim", "max", "min", "mod", "nth_value", "ntile", "nullif",
            "percent_rank", "pow", "power", "printf", "quote", "random", "randomblob", "rank", "replace", "round",
            "row_number", "rtrim", "sign", "sqrt", "strftime", "string_agg", "substr", "substring", "sum", "time",
            "timediff", "total", "trim", "typeof", "unhex", "unicode", "unixepoch", "unlikely", "upper", "zeroblob");

    private AssistantSqlNative() {}

    private static final class Engine {
        private static final Sqlite API = findEngine();
    }

    static boolean available() { return Engine.API != null; }

    private static Sqlite findEngine() {
        List<String> names = Platform.isMac()
                ? List.of("sqlite3", "/opt/homebrew/opt/sqlite/lib/libsqlite3.0.dylib",
                        "/usr/local/opt/sqlite/lib/libsqlite3.0.dylib")
                : Platform.isWindows() ? List.of("sqlite3", "winsqlite3") : List.of("sqlite3");
        for (String name : names) {
            try {
                var library = NativeLibrary.getInstance(name);
                library.getFunction("sqlite3_deserialize");
                library.getFunction("sqlite3_set_authorizer");
                var api = Native.load(name, Sqlite.class, Map.of(Library.OPTION_STRING_ENCODING, "UTF-8"));
                if (api.sqlite3_libversion_number() < 3_036_000 || api.sqlite3_compileoption_used("TEMP_STORE=0") != 0) continue;
                // 仅在首次初始化前可设置。若已初始化，仍可核对引擎是否已启用统计。
                api.sqlite3_config(CONFIG_MEMSTATUS, 1);
                if (hasHardHeapLimit(api)) return api;
            } catch (LinkageError | RuntimeException ignored) {
                // 下一个候选。只在能够确认护栏生效的引擎上执行模型 SQL。
            }
        }
        return null;
    }

    private static boolean hasHardHeapLimit(Sqlite api) {
        long previous = api.sqlite3_hard_heap_limit64(-1);
        try {
            long limit = lowerLimit(previous);
            api.sqlite3_hard_heap_limit64(limit);
            if (api.sqlite3_hard_heap_limit64(-1) != limit) return false;
            long before = api.sqlite3_memory_used();
            Pointer allocation = api.sqlite3_malloc64(1024);
            if (allocation == null) return false;
            try { return api.sqlite3_memory_used() >= before + 1024; }
            finally { api.sqlite3_free(allocation); }
        } finally {
            api.sqlite3_hard_heap_limit64(previous);
        }
    }

    private static long lowerLimit(long previous) { return previous > 0 ? Math.min(previous, HEAP_BYTES) : HEAP_BYTES; }

    static JSONObject select(byte[] snapshot, Set<String> tables, String sql) {
        Sqlite api = Engine.API;
        if (api == null) return unavailable();
        long previous = api.sqlite3_hard_heap_limit64(-1);
        try {
            long limit = lowerLimit(previous);
            api.sqlite3_hard_heap_limit64(limit);
            if (api.sqlite3_hard_heap_limit64(-1) != limit) return unavailable();
            var database = new PointerByReference();
            int opened = api.sqlite3_open_v2(":memory:", database, OPEN_READWRITE | OPEN_CREATE | OPEN_MEMORY, null);
            Pointer db = database.getValue();
            try {
                require(opened);
                restore(api, db, snapshot);
                configure(api, db);
                return query(api, db, tables, sql);
            } finally {
                if (db != null) api.sqlite3_close_v2(db);
            }
        } catch (Failure error) {
            return rejected(switch (error.code) {
                case INTERRUPT -> "SQL 查询已超时或中止，执行限时 5 秒";
                case NOMEM, FULL -> "查询超过 SQLite 的 64 MiB 内存上限，请缩短时间范围或简化查询";
                case TOOBIG -> "SQL 文本或单个值过大，请简化查询";
                case AUTH, READONLY -> "只读查询禁止写入、打开文件或加载扩展";
                case ERROR -> "SQL 语法错误、表或列不存在，或使用了不可用的函数或操作；请核对工具说明";
                default -> "SQLite 未能执行查询，请简化查询后重试";
            });
        } finally {
            api.sqlite3_hard_heap_limit64(previous);
        }
    }

    static JSONObject unavailable() {
        return rejected("此机器缺少可启用 64 MiB 硬堆上限的 SQLite 3.36 或更新版本");
    }

    private static void restore(Sqlite api, Pointer db, byte[] snapshot) {
        Pointer data = api.sqlite3_malloc64(snapshot.length);
        if (data == null) throw new Failure(NOMEM);
        data.write(0, snapshot, 0, snapshot.length);
        // FREEONCLOSE 让 SQLite 在连接关闭或恢复失败时归还这段内存。
        require(api.sqlite3_deserialize(db, "main", data, snapshot.length, snapshot.length,
                DESERIALIZE_FREEONCLOSE | DESERIALIZE_READONLY));
    }

    private static void configure(Sqlite api, Pointer db) {
        require(api.sqlite3_exec(db, "PRAGMA temp_store=MEMORY; PRAGMA query_only=ON; PRAGMA trusted_schema=OFF;",
                null, null, null));
        require(api.sqlite3_db_config(db, DBCONFIG_ENABLE_LOAD_EXTENSION, 0, null));
        require(api.sqlite3_db_config(db, DBCONFIG_DQS_DML, 0, null));
        api.sqlite3_limit(db, LIMIT_ATTACHED, 0);
        api.sqlite3_limit(db, LIMIT_WORKER_THREADS, 0);
        api.sqlite3_limit(db, LIMIT_LENGTH, RESULT_BYTES);
        api.sqlite3_limit(db, LIMIT_SQL_LENGTH, AssistantSqlRequest.SQL_BYTES);
        api.sqlite3_limit(db, LIMIT_COLUMN, MAX_COLUMNS);
        api.sqlite3_limit(db, LIMIT_LIKE_PATTERN_LENGTH, 512);
    }

    private static JSONObject query(Sqlite api, Pointer db, Set<String> tables, String sql) {
        Authorizer authorizer = (context, action, first, second, schema, source) -> switch (action) {
            case SELECT, RECURSIVE -> OK;
            // COUNT(*) 和只引用常量的子查询会收到没有列名的读操作。
            case READ -> schema == null && "".equals(second)
                    || "main".equals(schema) && first != null && tables.contains(first) ? OK : DENY;
            case FUNCTION -> second != null && FUNCTIONS.contains(second.toLowerCase(Locale.ROOT)) ? OK : DENY;
            default -> DENY;
        };
        require(api.sqlite3_set_authorizer(db, authorizer, null));
        long start = System.nanoTime();
        Progress progress = context -> expired(start) ? 1 : 0;
        api.sqlite3_progress_handler(db, 1_000, progress, null);
        var handle = new PointerByReference();
        byte[] utf8 = sql.getBytes(StandardCharsets.UTF_8);
        try (var buffer = new Memory(utf8.length + 1L)) {
            buffer.write(0, utf8, 0, utf8.length);
            buffer.setByte(utf8.length, (byte) 0);
            var tail = new PointerByReference();
            require(api.sqlite3_prepare_v3(db, buffer, -1, PREPARE_NO_VTAB, handle, tail));
            if (!AssistantSqlRequest.onlyTrivia(tail.getValue().getString(0, "UTF-8"))) {
                return rejected(AssistantSqlRequest.SINGLE_QUERY);
            }
            Pointer statement = handle.getValue();
            if (statement == null) throw new Failure(ERROR);
            if (api.sqlite3_stmt_readonly(statement) == 0) throw new Failure(READONLY);
            return readRows(api, statement, start);
        } finally {
            if (handle.getValue() != null) api.sqlite3_finalize(handle.getValue());
            Reference.reachabilityFence(authorizer);
            Reference.reachabilityFence(progress);
        }
    }

    private static JSONObject readRows(Sqlite api, Pointer statement, long start) {
        int width = api.sqlite3_column_count(statement);
        var columns = new JSONArray(width);
        Set<String> names = new HashSet<>();
        for (int i = 0; i < width; i++) {
            String name = AssistantChartRequest.clean(api.sqlite3_column_name(statement, i), 80);
            if (name.isEmpty() || !names.add(name)) {
                name = "column_" + (i + 1);
                while (!names.add(name)) name += "_";
            }
            columns.add(name);
        }
        var rows = new JSONArray();
        int bytes = 0;
        boolean truncated = false;
        int code;
        while ((code = api.sqlite3_step(statement)) == ROW) {
            if (expired(start)) throw new Failure(INTERRUPT);
            if (rows.size() == RESULT_ROWS) {
                truncated = true;
                break;
            }
            var row = new JSONArray(width);
            for (int i = 0; i < width; i++) row.add(cell(api, statement, i));
            bytes += row.toJSONString(JSONWriter.Feature.WriteNulls).getBytes(StandardCharsets.UTF_8).length + 1;
            if (bytes > RESULT_BYTES) {
                truncated = true;
                break;
            }
            rows.add(row);
        }
        if (!truncated && code != DONE) throw new Failure(code);
        var data = JSONObject.of("source", AssistantSqlRequest.SOURCE, "columns", columns, "rows", rows, "truncated", truncated);
        if (truncated) data.put("note", "只读查询最多返回 200 行、64 KiB 的数据行，结果已截断");
        return data;
    }

    private static Object cell(Sqlite api, Pointer statement, int index) {
        return switch (api.sqlite3_column_type(statement, index)) {
            case INTEGER -> api.sqlite3_column_int64(statement, index);
            case FLOAT -> {
                double n = api.sqlite3_column_double(statement, index);
                yield Double.isFinite(n) ? n : null;
            }
            case TEXT -> {
                Pointer text = api.sqlite3_column_text(statement, index);
                yield text == null ? null : new String(text.getByteArray(0, api.sqlite3_column_bytes(statement, index)),
                        StandardCharsets.UTF_8);
            }
            case BLOB -> {
                Pointer blob = api.sqlite3_column_blob(statement, index);
                yield blob == null ? "" : HexFormat.of().formatHex(blob.getByteArray(0, api.sqlite3_column_bytes(statement, index)));
            }
            default -> null;
        };
    }

    private static boolean expired(long start) {
        return Thread.currentThread().isInterrupted() || System.nanoTime() - start >= EXECUTION_NANOS;
    }

    private static void require(int code) {
        if (code != OK) throw new Failure(code);
    }

    private static final class Failure extends RuntimeException {
        final int code;
        Failure(int code) { this.code = code & 0xff; }
    }

    public interface Authorizer extends Callback {
        int authorize(Pointer context, int action, String first, String second, String schema, String source);
    }

    public interface Progress extends Callback {
        int progress(Pointer context);
    }

    public interface Sqlite extends Library {
        int sqlite3_libversion_number();
        int sqlite3_compileoption_used(String name);
        int sqlite3_config(int option, Object... args);
        long sqlite3_memory_used();
        long sqlite3_hard_heap_limit64(long limit);
        Pointer sqlite3_malloc64(long bytes);
        void sqlite3_free(Pointer bytes);
        int sqlite3_open_v2(String filename, PointerByReference db, int flags, String vfs);
        int sqlite3_close_v2(Pointer db);
        int sqlite3_deserialize(Pointer db, String schema, Pointer bytes, long size, long capacity, int flags);
        int sqlite3_exec(Pointer db, String sql, Pointer callback, Pointer context, PointerByReference error);
        int sqlite3_db_config(Pointer db, int option, Object... args);
        int sqlite3_limit(Pointer db, int kind, int limit);
        int sqlite3_set_authorizer(Pointer db, Authorizer authorizer, Pointer context);
        void sqlite3_progress_handler(Pointer db, int instructions, Progress progress, Pointer context);
        int sqlite3_prepare_v3(Pointer db, Pointer sql, int length, int flags,
                               PointerByReference statement, PointerByReference tail);
        int sqlite3_stmt_readonly(Pointer statement);
        int sqlite3_step(Pointer statement);
        int sqlite3_finalize(Pointer statement);
        int sqlite3_column_count(Pointer statement);
        String sqlite3_column_name(Pointer statement, int index);
        int sqlite3_column_type(Pointer statement, int index);
        long sqlite3_column_int64(Pointer statement, int index);
        double sqlite3_column_double(Pointer statement, int index);
        Pointer sqlite3_column_text(Pointer statement, int index);
        Pointer sqlite3_column_blob(Pointer statement, int index);
        int sqlite3_column_bytes(Pointer statement, int index);
    }
}
