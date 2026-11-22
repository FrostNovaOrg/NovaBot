package org.frostnova.nova.console.assistant;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.analytics.LiveDetail;
import org.frostnova.nova.core.analytics.LiveMetricCatalog;
import org.frostnova.nova.core.analytics.StatisticsDay;
import org.frostnova.nova.core.assistant.AssistantLocalStore;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.model.DanmuRecord;
import org.frostnova.nova.core.model.LiveSession;
import org.frostnova.nova.core.service.LiveDataService;
import org.frostnova.nova.core.service.LiveDetailArchive;
import org.frostnova.nova.core.service.LiveSessionArchive;
import org.frostnova.nova.report.analytics.BilibiliLiveMetricCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AssistantSqlQueryTest {
    private static final String PLATFORM = "bilibili";
    private static final long STREAMER = 9300010000L;
    private static final long VIEWER = 9300010001L;
    private static final long DENIED = 9300010002L;
    private static final long ROSTER_ONLY = 9300010003L;
    private static final long CROSSING = at("2026-11-01T05:58");
    private static final long WINDOW = at("2026-11-01T06:00");
    private static final long START = at("2026-11-01T18:00");
    private static final long NEXT_DAY = at("2026-11-02T06:00");
    private static final List<String> ALL_TABLES = List.of("sessions", "events", "danmu", "minutes", "audience");
    @TempDir Path directory;
    private LiveSessionArchive archive;
    private LiveDetailArchive details;
    private LiveDataService live;
    private AssistantQueryService service;
    private AssistantLocalStore local;
    private AssistantPrivacy privacy;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        var properties = new NovaCoreProperties();
        properties.getLive().setLiveDataPath(directory.resolve("data.json").toString());
        archive = new LiveSessionArchive(properties);
        details = new LiveDetailArchive(properties);
        live = mock(LiveDataService.class);
        ObjectProvider<LiveMetricCatalog> catalogs = mock(ObjectProvider.class);
        when(catalogs.orderedStream()).thenAnswer(call -> Stream.of(new BilibiliLiveMetricCatalog()));
        service = new AssistantQueryService(null, archive, details, live, properties, catalogs);
        local = new AssistantLocalStore(directory, Clock.systemUTC());
        privacy = new AssistantPrivacy(local, PLATFORM, STREAMER, List.of(DENIED),
                () -> Map.of(VIEWER, Set.of("合成观众甲"), DENIED, Set.of("合成观众乙")));
    }

    @Test
    @DisplayName("模型不能借 CTE 改数据、串接指令、打开文件或加载扩展")
    void enforcesReadOnlyAndFileGuardsInSQLite() throws Exception {
        session(STREAMER, START, START + 600_000);
        assertReason(visible(List.of("sessions"), "WITH x(n) AS (SELECT 1) DELETE FROM sessions RETURNING start_at"), "只读查询禁止写入");
        assertReason(visible(List.of("sessions"), "SELECT 1; SELECT 2"), "一条");
        assertReason(visible(List.of("sessions"), "SELECT COUNT( FROM sessions"), "语法错误");
        Path file = directory.resolve("outside.db");
        String attach = "ATTACH DATABASE '" + file + "' AS outside";
        assertReason(visible(List.of("sessions"), attach), "SELECT 或 WITH");
        assertReason(visible(List.of("sessions"), "SELECT load_extension('" + file + "')"), "不可用");
        assertReason(visible(List.of("sessions"), "SELECT readfile('" + file + "')"), "不可用");
        try (var db = AssistantSqlDatabase.open(); var statement = db.createStatement()) {
            // 直接检查 SQLite 的限制，哪怕调用者绕开单语句检查也不能附加数据库或加载扩展。
            assertTrue(assertThrows(SQLException.class, () -> statement.execute(attach)).getMessage().contains("attached"));
            assertTrue(assertThrows(SQLException.class, () -> statement.executeQuery("SELECT load_extension('" + file + "')"))
                    .getMessage().contains("not authorized"));
            try (var checks = statement.executeQuery("SELECT * FROM pragma_temp_store")) {
                assertTrue(checks.next());
                assertEquals(2, checks.getInt(1));
            }
            statement.execute("CREATE TABLE sessions(start_at INTEGER)");
            var snapshot = db.getDatabase().serialize("main");
            assertReason(AssistantSqlNative.select(snapshot, Set.of("sessions"), attach), "禁止");
            assertReason(AssistantSqlNative.select(snapshot, Set.of("sessions"), "PRAGMA query_only=OFF"), "禁止");
        }
        assertFalse(Files.exists(file));
        assertEquals(1, rows(visible(List.of("sessions"), "/* ; */ SELECT ';' AS value FROM sessions; -- ;")).size());
    }

    @Test
    @DisplayName("无穷递归查询会在时限内中止，下一次查数仍能执行")
    void interruptsRunawayQuery() {
        assertTimeoutPreemptively(Duration.ofSeconds(12), () -> assertReason(visible(List.of("sessions"), """
                WITH RECURSIVE seq(n) AS (VALUES(1) UNION ALL SELECT n + 1 FROM seq)
                SELECT COUNT(*) FROM seq
                """), "限时 5 秒"));
        assertEquals(1, rows(visible(List.of("sessions"), "SELECT 1 AS ok")).getJSONArray(0).getIntValue(0));
    }

    @Test
    @DisplayName("隐藏金额时通配查询、显式查询和工具说明都拿不到金额列")
    void noMoneyColumnsExistInRestrictedChats() {
        session(STREAMER, START, START + 600_000);
        event(STREAMER, START, START, "box", VIEWER);
        var restricted = new AssistantQueryContext(false, false);
        var result = query(restricted, List.of("sessions", "events"), "SELECT * FROM sessions JOIN events");
        assertFalse(result.containsKey("reason"), result::toJSONString);
        for (String column : List.of("revenue", "amount", "paid_amount", "gift_amount", "guard_amount")) {
            assertFalse(result.getJSONArray("columns").contains(column), column);
            assertReason(query(restricted, List.of("sessions", "events"),
                    "SELECT " + column + " FROM sessions JOIN events"), "表或列不存在");
        }
        assertFalse(result.toJSONString().contains("81.75"));
        assertFalse(result.toJSONString().contains("32.25"));
        assertEquals(32.25, rows(visible(List.of("events"), "SELECT amount FROM events WHERE kind = 'box'"))
                .getJSONArray(0).getDoubleValue(0));
        var tool = AssistantTools.definitions(restricted).stream().map(item -> item.getJSONObject("function"))
                .filter(function -> "query_sql".equals(function.getString("name"))).findFirst().orElseThrow();
        assertFalse(tool.toJSONString().contains("paid_amount"));
    }

    @Test
    @DisplayName("昵称、编号、弹幕原文和被拒绝的观众代号进不了结果，事件金额仍计入合计")
    void onlyAttributesAllowedViewerAliases() {
        session(STREAMER, CROSSING, START - 60_000);
        session(STREAMER, START, START + 600_000);
        event(STREAMER, CROSSING, CROSSING, "gift", VIEWER);
        event(STREAMER, START, START + 1, "gift", VIEWER);
        event(STREAMER, START, START + 2, "box", DENIED);
        details.appendDanmu(PLATFORM, STREAMER, START, new DanmuRecord(START + 1, VIEWER, "合成观众甲",
                "虚构😀", DanmuRecord.Type.DANMU));
        details.appendDanmu(PLATFORM, STREAMER, START, new DanmuRecord(START + 2, DENIED, "合成观众乙",
                "测试留言正文", DanmuRecord.Type.SUPER_CHAT, false, 17.5));

        String alias = local.alias(PLATFORM, VIEWER);
        var audience = visible(ALL_TABLES, """
                SELECT audience, first_day FROM audience
                UNION ALL SELECT audience, kind FROM events
                UNION ALL SELECT audience, CAST(characters AS TEXT) FROM danmu
                """);
        String json = audience.toJSONString();
        assertFalse(audience.containsKey("reason"), audience::toJSONString);
        assertTrue(json.contains(alias));
        assertEquals("2026-10-31", audience.getString("historyFrom"));
        assertTrue(json.contains("2026-10-31"));
        for (String hidden : List.of(Long.toString(VIEWER), Long.toString(DENIED), "合成观众甲",
                "合成观众乙", "虚构", "测试留言正文", local.alias(PLATFORM, DENIED))) {
            assertFalse(json.contains(hidden), hidden);
        }
        var total = rows(visible(ALL_TABLES, """
                SELECT COUNT(DISTINCT audience) AS viewers, SUM(amount) AS total,
                (SELECT characters FROM danmu) AS characters
                FROM events WHERE kind IN ('gift', 'box', 'super_chat')
                """)).getJSONArray(0);
        assertEquals(1, total.getIntValue(0));
        assertEquals(82, total.getDoubleValue(1));
        assertEquals(3, total.getIntValue(2));
    }

    @Test
    @DisplayName("北京时间六点前后的明细、其他主播和还未下播的场次不会混入")
    void isolatesStreamerAndStatisticsDay() {
        session(STREAMER, CROSSING, WINDOW + 120_000);
        session(STREAMER, START, NEXT_DAY + 120_000);
        session(STREAMER + 99, START, START + 600_000);
        long unfinishedStart = START + 60_000;
        session(STREAMER, unfinishedStart, unfinishedStart + 600_000);
        when(live.getLiveStatus(PLATFORM, STREAMER)).thenReturn(java.util.Optional.of(true));
        when(live.getLiveStartTime(PLATFORM, STREAMER)).thenReturn(java.util.Optional.of(unfinishedStart));
        event(STREAMER, CROSSING, WINDOW - 1, "gift", VIEWER);
        event(STREAMER, CROSSING, WINDOW, "box", VIEWER);
        event(STREAMER, START, NEXT_DAY, "gift", VIEWER);
        event(STREAMER, unfinishedStart, unfinishedStart, "gift", VIEWER);
        event(STREAMER + 99, START, START, "gift", VIEWER);
        details.appendEvent(PLATFORM, STREAMER, START, START + 1, "pk_settle", Map.of("rs", 1));
        details.appendEvent(PLATFORM, STREAMER, START, START + 2, "pk_settle", Map.of("rs", 0));
        details.appendDanmu(PLATFORM, STREAMER, START,
                new DanmuRecord(NEXT_DAY - 1, VIEWER, "合成观众甲", "末条", DanmuRecord.Type.DANMU));
        details.appendDanmu(PLATFORM, STREAMER, START,
                new DanmuRecord(NEXT_DAY, VIEWER, "合成观众甲", "下一日", DanmuRecord.Type.DANMU));
        details.store(new LiveDetail(LiveDetail.VERSION, PLATFORM, STREAMER, "合成主播", 9300090000L,
                START, NEXT_DAY + 120_000, 43_320, Map.of(), Map.of(),
                Map.of("online_count", Map.of(START, 17.0, NEXT_DAY, 91.0),
                        "watched_count", Map.of(START + 60_000, 7.0)), Map.of(), Map.of(),
                List.of(), List.of(), List.of(), Map.of(), List.of()));

        var result = visible(ALL_TABLES, """
                SELECT (SELECT COUNT(*) FROM sessions) AS sessions,
                (SELECT pk_result FROM sessions) AS pk,
                (SELECT SUM(pk_wins) FROM sessions) AS wins,
                (SELECT COUNT(*) FROM events WHERE kind = 'gift') AS gifts,
                (SELECT MIN(at) FROM events WHERE kind = 'box') AS box_at,
                (SELECT MIN(at) FROM minutes) AS first_minute, (SELECT MAX(at) FROM minutes) AS last_minute,
                (SELECT MAX(online) FROM minutes) AS peak,
                (SELECT COUNT(*) FROM events WHERE kind = 'attendance') AS attendance,
                (SELECT COUNT(*) FROM danmu) AS messages
                """);
        var row = rows(result).getJSONArray(0);
        assertEquals(1, row.getIntValue(0));
        assertEquals("mixed", row.getString(1));
        assertEquals(1, row.getIntValue(2));
        assertEquals(0, row.getIntValue(3));
        assertEquals(WINDOW, row.getLongValue(4));
        assertEquals(WINDOW, row.getLongValue(5));
        assertEquals(NEXT_DAY - 60_000, row.getLongValue(6));
        assertEquals(17, row.getIntValue(7));
        assertEquals(1, row.getIntValue(8));
        assertEquals(1, row.getIntValue(9));
        var samples = rows(visible(List.of("minutes"), "SELECT online, watched FROM minutes WHERE session_start = "
                + START + " ORDER BY at LIMIT 2"));
        assertEquals(17, samples.getJSONArray(0).getIntValue(0));
        assertNull(samples.getJSONArray(0).get(1));
        assertNull(samples.getJSONArray(1).get(0));
        assertEquals(7, samples.getJSONArray(1).getIntValue(1));
    }

    @Test
    @DisplayName("连续到场查询能连接场次、名单和逐条互动，并按人按场去重")
    void combinesArchivedAttendanceAcrossThreeSessions() {
        for (int i = 0; i < 3; i++) {
            long start = START + i * 900_000L;
            session(STREAMER, start, start + 600_000);
            details.appendDanmu(PLATFORM, STREAMER, start,
                    new DanmuRecord(start + 1, VIEWER, "合成观众甲", "在场", DanmuRecord.Type.DANMU));
            if (i < 2) event(STREAMER, start, start + 1, "gift", VIEWER);
        }
        var result = visible(List.of("sessions", "events", "danmu"), """
                WITH seen AS (
                  SELECT session_start, audience FROM events
                  UNION SELECT session_start, audience FROM danmu
                ), repeated AS (
                  SELECT seen.audience FROM seen JOIN sessions ON start_at = session_start
                  WHERE seen.audience IS NOT NULL
                  GROUP BY seen.audience HAVING COUNT(*) = 3
                )
                SELECT COUNT(*) AS attended FROM repeated
                """);
        assertEquals(2, rows(result).getJSONArray(0).getIntValue(0));
    }

    @Test
    @DisplayName("只返回前 200 行，告诉模型截断，恰好 200 行时不误报")
    void capsRowsAndSignalsTruncation() {
        String sequence = "WITH RECURSIVE seq(n) AS (VALUES(1) UNION ALL SELECT n + 1 FROM seq WHERE n < %d)"
                + " SELECT n FROM seq ORDER BY n";
        var exact = visible(List.of("sessions"), sequence.formatted(200));
        assertFalse(exact.getBooleanValue("truncated"));
        assertEquals(200, rows(exact).size());
        var over = visible(List.of("sessions"), sequence.formatted(201));
        assertTrue(over.getBooleanValue("truncated"));
        assertTrue(over.getString("note").contains("200"));
        assertEquals(200, rows(over).size());
        assertEquals(200, rows(over).getJSONArray(199).getIntValue(0));
    }

    @Test
    @DisplayName("大中间表碰到 SQLite 内存上限时能返回原因")
    void rejectsOversizedIntermediateTable() {
        assertTimeoutPreemptively(Duration.ofSeconds(12), () -> assertReason(visible(List.of("sessions"), """
                WITH RECURSIVE seq(n) AS (VALUES(1) UNION ALL SELECT n + 1 FROM seq WHERE n < 5000),
                data AS MATERIALIZED (SELECT randomblob(32000) AS payload FROM seq)
                SELECT SUM(length(payload)) FROM data
                """), "内存上限"));
        assertEquals(1, rows(visible(List.of("sessions"), "SELECT 1 AS ok")).getJSONArray(0).getIntValue(0));
    }

    @Test
    @DisplayName("超过 366 个统计日或 50 万行时给出可重试的具体原因")
    void reportsDateAndLoadLimits() {
        long from = StatisticsDay.startOf(LocalDate.parse("2025-01-01")).toEpochMilli();
        long end = StatisticsDay.startOf(LocalDate.parse("2026-01-02")).toEpochMilli();
        session(STREAMER, from, end);
        var args = arguments(List.of("minutes"), "SELECT COUNT(*) FROM minutes");
        args.put("from", "2025-01-01");
        args.put("to", "2026-01-01");
        assertReason(service.querySql(PLATFORM, STREAMER, args, AssistantQueryContext.CONSOLE, privacy, n -> false), "50 万行");
        args.put("to", "2026-01-02");
        assertReason(service.querySql(PLATFORM, STREAMER, args, AssistantQueryContext.CONSOLE, privacy, n -> false), "366 天");
    }

    private void session(long uid, long start, long end) {
        archive.append(new LiveSession(PLATFORM, uid, "合成主播", 9300090000L, start, end, (end - start) / 1000,
                Map.of("gift_value", 81.75, "guard_value", 26.5, "watched_count", 96.0, "danmu_count", 1.0),
                Map.of(), null, List.of(), 0, Map.of("enter_users", List.of(ROSTER_ONLY)), 0));
    }

    private void event(long streamer, long start, long at, String kind, long viewer) {
        details.appendEvent(PLATFORM, streamer, start, at, kind,
                Map.of("uid", viewer, "un", "合成观众甲", "gn", "合成礼物", "bn", "心动盲盒", "n", 1,
                        "val", 32.25, "pay", 19.5));
    }

    private JSONObject visible(List<String> tables, String sql) {
        return query(AssistantQueryContext.CONSOLE, tables, sql);
    }

    private JSONObject query(AssistantQueryContext context, List<String> tables, String sql) {
        return service.querySql(PLATFORM, STREAMER, arguments(tables, sql), context, privacy, n -> false);
    }

    private static JSONObject arguments(List<String> tables, String sql) {
        return JSONObject.of("from", "2026-11-01", "to", "2026-11-01", "tables", tables, "sql", sql);
    }

    private static JSONArray rows(JSONObject result) {
        assertFalse(result.containsKey("reason"), result::toJSONString);
        return result.getJSONArray("rows");
    }

    private static void assertReason(JSONObject result, String expected) {
        assertTrue(result.containsKey("reason"), result::toJSONString);
        assertTrue(result.getString("reason").contains(expected), result::toJSONString);
    }

    private static long at(String localTime) {
        return LocalDateTime.parse(localTime).atZone(StatisticsDay.ZONE).toInstant().toEpochMilli();
    }
}
