package org.frostnova.nova.console.assistant;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.analytics.LiveDetail;
import org.frostnova.nova.core.analytics.LiveMetricCatalog;
import org.frostnova.nova.core.analytics.StatisticsDay;
import org.frostnova.nova.core.assistant.AssistantClient;
import org.frostnova.nova.core.assistant.AssistantLocalStore;
import org.frostnova.nova.core.assistant.AssistantProperties;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.model.DanmuRecord;
import org.frostnova.nova.core.model.LiveSession;
import org.frostnova.nova.core.model.SeriesPeak;
import org.frostnova.nova.core.service.LiveDataService;
import org.frostnova.nova.core.service.LiveDetailArchive;
import org.frostnova.nova.core.service.LiveSessionArchive;
import org.frostnova.nova.report.analytics.BilibiliLiveMetricCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AssistantAggregateQueryTest {
    private static final String PLATFORM = "bilibili";
    private static final long ROOM = 9300010000L;
    private static final long VIEWER = 9300020000L;
    private static final long EXCLUDED = VIEWER + 99;
    private static final long CROSSING = at("2026-11-01T05:30");
    private static final long FIRST = at("2026-11-01T20:00");
    private static final long GIFTS = at("2026-11-12T20:00");
    private static final long FOLLOWS = at("2026-11-14T18:00");
    private static final long MISSING = at("2026-11-16T21:00");
    private static final long LIVE = at("2026-11-22T19:00");
    private static final String FROM = "2026-11-01";
    private static final String TO = "2026-11-22";
    private static final AssistantQueryContext HIDDEN = new AssistantQueryContext(false);
    @TempDir Path dir;
    private LiveSessionArchive archive;
    private LiveDetailArchive details;
    private LiveDataService live;
    private AssistantQueryService service;
    private AssistantLocalStore local;
    private Clock clock;

    @BeforeEach @SuppressWarnings("unchecked")
    void setup() {
        var properties = new NovaCoreProperties();
        properties.getLive().setLiveDataPath(dir.resolve("data.json").toString());
        archive = new LiveSessionArchive(properties);
        details = new LiveDetailArchive(properties);
        live = mock(LiveDataService.class);
        ObjectProvider<LiveMetricCatalog> catalogs = mock(ObjectProvider.class);
        when(catalogs.orderedStream()).thenAnswer(i -> Stream.of(new BilibiliLiveMetricCatalog()));
        clock = Clock.fixed(Instant.ofEpochMilli(at("2026-11-22T21:00")), ZoneOffset.UTC);
        service = new AssistantQueryService(null, archive, details, live, properties, catalogs, clock);
        local = new AssistantLocalStore(dir, clock);
    }

    // 抓住凌晨场次被拆开或归到日历上的月初，导致跨月流水被漏计或重计。
    @Test void sessionsStayWholeInTheirOpeningStatisticsDay() throws IOException {
        fixture();
        var daily = query(request(List.of("session"), "2026-10-24", TO, "sum:amount"),
                "groupBy", List.of("day"));
        table("每天流水", daily, List.of("day", "sum:amount"), List.of(
                List.of("2026-10-31", 120), List.of(FROM, 249), List.of("2026-11-12", 349),
                List.of("2026-11-14", 0), List.of("2026-11-16", 21)), List.of("合计", 739));
        assertEquals(at("2026-10-24T06:00"), daily.getLongValue("fromAt"));
        assertEquals(at("2026-11-23T06:00"), daily.getLongValue("toAtExclusive"));
        assertTrue(daily.getString("statisticsDay").contains("06:00"));
        assertTrue(daily.getBooleanValue("liveUnarchived"));
        assertEquals(LIVE, daily.getLongValue("liveUnarchivedStart"));
        assertEquals(0, daily.getIntValue("scannedLines"));
        assertEquals(0, daily.getIntValue("missingDetailsCount"));
        table("跨月流水", query(request(List.of("session"), "2026-10-24", TO, "sum:amount"),
                "groupBy", List.of("month")), List.of("month", "sum:amount"),
                List.of(List.of("2026-10", 120), List.of("2026-11", 619)), List.of("合计", 739));
        table("场均流水", query(request(List.of("session"), FROM, TO, "avg:amount"),
                "groupBy", List.of("hour")), List.of("hour", "avg:amount"),
                List.of(List.of(18, 0), List.of(20, 299), List.of(21, 21)), List.of("合计", new BigDecimal("154.75")));
    }

    // 抓住盒名和开出礼物名混淆、事件随场次归日，或事件正好落在六点被漏计。
    @Test void boxQuantitiesUseBoxNameAndEventStatisticsDay() throws IOException {
        fixture();
        var request = request(List.of("box"), FROM, TO, "sum:quantity");
        request.put("filters", List.of(filter("name", "eq", "心动盲盒")));
        var sum = query(request);
        table("心动盲盒个数", sum, List.of("sum:quantity"), List.of(List.of(6)), List.of(6));
        assertEquals(1, sum.getIntValue("missingDetailsCount"));
        assertEquals(MISSING, sum.getJSONArray("missingDetails").getJSONObject(0).getLongValue("session"));
        assertEquals(List.of("events.jsonl"), sum.getJSONArray("missingDetails").getJSONObject(0).getJSONArray("files"));
        assertNotNull(sum.getString("coverageNote"));
        table("每天心动盲盒", query(request, "groupBy", List.of("day")), List.of("day", "sum:quantity"),
                List.of(List.of(FROM, 4), List.of("2026-11-02", 2)), List.of("合计", 6));
        request.put("filters", List.of(filter("openedGiftName", "contains", "银星")));
        assertNumber(6, query(request).getJSONArray("totals").get(0));
    }

    // 抓住无礼物的场次被报成缺明细，导致零变成 null 或缺失场次数虚高。
    @Test void giftlessSessionsCountAsZeroWithoutInflatingMissingDetails() {
        long empty = at("2026-11-15T18:00");
        long end = empty + 10_000;
        archive.append(new LiveSession(PLATFORM, ROOM, "合成主播", 1L, empty, end, 10, Map.of(), Map.of()));
        seal(empty, Map.of());
        summary(MISSING, MISSING + 10_000, 21, 0, 0);
        seal(MISSING, Map.of("gift_value", 21d));
        long zero = at("2026-11-17T18:00");
        summary(zero, zero + 10_000, 0, 0, 0);

        var emptyOnly = query(request(List.of("gift"), "2026-11-15", "2026-11-15", "count", "sum:quantity"));
        table("无礼物场次", emptyOnly, List.of("count", "sum:quantity"),
                List.of(List.of(0, 0)), List.of(0, 0));
        assertEquals(0, emptyOnly.getIntValue("missingDetailsCount"));
        assertFalse(emptyOnly.containsKey("coverageNote"));

        var mixed = query(request(List.of("gift"), "2026-11-15", "2026-11-17", "count"));
        table("无礼物与缺明细场次", mixed, List.of("count"), List.of(List.of(0)), List.of(0));
        assertEquals(1, mixed.getIntValue("missingDetailsCount"));
        assertEquals(MISSING, mixed.getJSONArray("missingDetails").getJSONObject(0).getLongValue("session"));

        var combined = query(request(List.of("gift", "super_chat"), "2026-11-16", "2026-11-16", "count"));
        assertNumber(0, combined.getJSONArray("totals").get(0));
        assertEquals(List.of("events.jsonl"), combined.getJSONArray("missingDetails").getJSONObject(0).getJSONArray("files"));
    }

    // 抓住凌晨事件的星期几与统计周错开，以及六点整仍被算到前一天。
    @Test void eventWeekdayFollowsStatisticsDayAtMondayBoundary() {
        long start = at("2026-11-08T23:30");
        summary(start, at("2026-11-09T07:00"), 2, 0, 0);
        gift(start, at("2026-11-09T05:59"), 0, "合成礼物", 1, 1, 1);
        gift(start, at("2026-11-09T06:00"), 0, "合成礼物", 1, 1, 1);
        var result = query(request(List.of("gift"), "2026-11-08", "2026-11-09", "count"),
                "groupBy", List.of("week", "weekday"));
        table("跨周凌晨礼物", result, List.of("week", "weekday", "count"),
                List.of(List.of("2026-11-02", 7, 1), List.of("2026-11-09", 1, 1)),
                Arrays.asList("合计", null, 2));
    }

    // 抓住礼物按消息条数排序，或截断前五时也把合计裁掉。
    @Test void giftTopFiveUsesTotalQuantityAndKeepsTheFullTotal() throws IOException {
        fixture();
        var request = request(List.of("gift"), "2026-11-09", "2026-11-15", "sum:quantity");
        request.put("groupBy", List.of("name"));
        request.put("sort", sort("sum:quantity", "desc"));
        request.put("limit", 5);
        var answer = query(request);
        table("礼物前五", answer, List.of("name", "sum:quantity"), List.of(List.of("合成礼物一", 9),
                List.of("合成礼物二", 8), List.of("合成礼物三", 7), List.of("合成礼物四", 6),
                List.of("合成礼物五", 5)), List.of("合计", 39));
        assertEquals(6, answer.getIntValue("groupCount"));
        assertTrue(answer.getBooleanValue("truncated"));
        assertTrue(answer.getString("truncationNote").contains("5"));
    }

    // 抓住开打也被算成结算、胜负平归错，或正好 100 票的胜场被算进「过 100」。
    @Test void pkCountsRespectResultAndStrictVoteThreshold() throws IOException {
        fixture();
        var request = request(List.of("pk_settle"), FROM, TO, "count");
        table("PK 胜负平", query(request, "groupBy", List.of("pkResult")), List.of("pkResult", "count"),
                List.of(List.of("draw", 1), List.of("loss", 1), List.of("win", 2)), List.of("合计", 4));
        var strict = List.of(filter("pkResult", "eq", "win"), filter("pkMyVotes", "gt", 100));
        table("过 100 票的胜场", query(request, "filters", strict), List.of("count"),
                List.of(List.of(1)), List.of(1));
        assertNumber(2, query(request, "filters", List.of(filter("pkResult", "eq", "win"),
                filter("pkMyVotes", "gte", 100))).getJSONArray("totals").get(0));
        assertNumber(1, query(request, "filters", List.of(filter("pkOpponentVotes", "eq", 110)))
                .getJSONArray("totals").get(0));
        assertNumber(2, query(request, "filters", List.of(filter("pkMyVotes", "lt", 101)))
                .getJSONArray("totals").get(0));
        assertNumber(2, query(request, "filters", List.of(filter("pkMyVotes", "lte", 100)))
                .getJSONArray("totals").get(0));
    }

    // 抓住大航海续费混进新开，或上舰等级从错误的归档码读取。
    @Test void guardOperationsAndLevelsAreSeparate() throws IOException {
        fixture();
        var request = request(List.of("guard"), FROM, TO, "count", "sum:amount");
        table("上舰新开续费", query(request, "groupBy", List.of("guardOperation")),
                List.of("guardOperation", "count", "sum:amount"), List.of(List.of("open", 1, 100),
                        List.of("renew", 1, 80)), List.of("合计", 2, 180));
        table("上舰等级", query(request, "groupBy", List.of("guardOperation", "guardLevel")),
                List.of("guardOperation", "guardLevel", "count", "sum:amount"),
                List.of(List.of("open", "captain", 1, 100), List.of("renew", "commander", 1, 80)),
                Arrays.asList("合计", null, 2, 180));
    }

    // 抓住四种付费来源少算、同一个人分成几行，或拒绝名单上的观众出现在送礼榜和合计里。
    @Test void combinedRankingUsesAliasesAndOmitsExcludedAudience() throws IOException {
        fixture();
        var privacy = privacy(List.of(EXCLUDED));
        var request = request(List.of("gift", "box", "guard", "super_chat"), FROM, TO, "sum:amount");
        request.put("groupBy", List.of("audience"));
        request.put("sort", sort("sum:amount", "desc"));
        request.put("limit", 10);
        var result = query(request, AssistantQueryContext.CONSOLE, privacy);
        table("送礼前十", result, List.of("audience", "sum:amount"), List.of(
                List.of(alias(0), 151), List.of(alias(2), 80), List.of(alias(1), 57), List.of(alias(8), 11),
                List.of(alias(3), 8), List.of(alias(4), 7), List.of(alias(5), 6), List.of(alias(6), 5),
                List.of(alias(7), 2), List.of(alias(9), 1)), List.of("合计", 328));
        assertFalse(result.getBooleanValue("truncated"));
        String outgoing = result.toJSONString();
        for (String forbidden : List.of(local.alias(PLATFORM, EXCLUDED), "合成拒绝观众", EXCLUDED + "",
                "合成观众0", VIEWER + "", "合成留言原文"))
            assertFalse(outgoing.contains(forbidden), forbidden);
        assertEquals("合成观众0 151 元", privacy.display(alias(0) + " 151 元"));
        request.put("sort", sort("audience", "asc"));
        request.put("limit", 1);
        var firstAlias = result.getJSONArray("rows").stream().map(JSONArray.class::cast)
                .map(row -> row.getString(0)).min(Comparator.naturalOrder()).orElseThrow();
        assertEquals(firstAlias, query(request, AssistantQueryContext.CONSOLE, privacy)
                .getJSONArray("rows").getJSONArray(0).getString(0));
        var distinct = request(List.of("gift", "box", "guard", "super_chat"), FROM, TO, "distinctAudience");
        assertNumber(10, query(distinct, AssistantQueryContext.CONSOLE, privacy).getJSONArray("totals").get(0));
        distinct.put("filters", List.of(filter("audience", "eq", alias(1))));
        distinct.put("metrics", List.of("distinctAudience", "sum:amount"));
        table("观众代号筛选", query(distinct, AssistantQueryContext.CONSOLE, privacy),
                List.of("distinctAudience", "sum:amount"), List.of(List.of(1, 57)), List.of(1, 57));
        distinct.put("filters", List.of(filter("audience", "eq", local.alias(PLATFORM, EXCLUDED))));
        assertNumber(0, query(distinct, AssistantQueryContext.CONSOLE, privacy).getJSONArray("totals").get(0));
    }

    // 抓住问答链路未把汇总交给模型，或历史回答和页面的观众身份没有按各自口径处理。
    @Test void consoleQuestionSendsOnlyAliasesAndRestoresNicknameForDisplay() throws IOException {
        fixture();
        var properties = new AssistantProperties();
        properties.setExcludedUids(List.of(EXCLUDED));
        var client = mock(AssistantClient.class);
        var question = mock(AssistantClient.Question.class);
        var status = mock(AssistantClient.Status.class);
        when(client.beginQuestion("member")).thenReturn(question);
        when(client.status()).thenReturn(status);
        when(status.dailyQuestionsPerAccount()).thenReturn(20);
        when(status.questionsToday()).thenReturn(Map.of("member", 1));
        when(status.spentYuan()).thenReturn(BigDecimal.ZERO);
        when(status.monthlyBudgetYuan()).thenReturn(BigDecimal.TEN);
        when(question.totalCostYuan()).thenReturn(BigDecimal.ZERO);
        var request = request(List.of("gift", "box", "guard", "super_chat"), FROM, TO, "sum:amount");
        request.put("groupBy", List.of("audience"));
        request.put("sort", sort("sum:amount", "desc"));
        request.put("limit", 1);
        var toolCall = JSONObject.of("id", "aggregate-result", "type", "function",
                "function", JSONObject.of("name", "aggregate", "arguments", request.toJSONString()));
        var usage = new AssistantClient.Usage(0, 100, 10);
        when(question.complete(any(), any())).thenAnswer(call -> {
            AssistantClient.Request sent = call.getArgument(0);
            String system = sent.messages().get(0).getString("content");
            assertTrue(system.contains("aggregate"));
            assertTrue(system.contains(TO));
            var result = sent.messages().stream().filter(m -> "tool".equals(m.getString("role"))).findFirst();
            if (result.isEmpty()) return new AssistantClient.Response(null, null, List.of(toolCall), usage, BigDecimal.ZERO);
            var table = JSON.parseObject(result.orElseThrow().getString("content"));
            var row = table.getJSONArray("rows").getJSONArray(0);
            assertEquals(alias(0), row.getString(0));
            assertNumber(151, row.get(1));
            for (String forbidden : List.of("合成观众0", VIEWER + "", "合成拒绝观众", EXCLUDED + ""))
                assertFalse(sent.messages().toString().contains(forbidden));
            return new AssistantClient.Response("本月送礼最多的是 " + row.getString(0) + "，"
                    + row.getBigDecimal(1).stripTrailingZeros().toPlainString() + " 元（" + table.getString("source")
                    + "）。有 " + table.getIntValue("missingDetailsCount") + " 场缺明细，正在直播的一场还没归档。",
                    null, List.of(), usage, BigDecimal.ZERO);
        });
        var ask = new AssistantAskService(service, client, properties, local, clock);
        try {
            var result = ask.ask(PLATFORM, ROOM, "member", JSONObject.of("question", "本月送礼最多的是谁？"));
            show("控制台回答", result);
            assertTrue(result.getBooleanValue("answered"));
            assertTrue(result.getString("answer").contains("合成观众0，151 元"));
            assertTrue(result.getString("answerForHistory").contains(alias(0)));
            assertFalse(result.getString("answerForHistory").contains("合成观众0"));
            assertTrue(result.getJSONArray("unverifiedNumbers").isEmpty());
        } finally { ask.close(); }
    }

    // 抓住场次其他字段取错、缺少名单被当成零、或统计周使用了午夜边界。
    @Test void sessionFieldsAndWeekUseArchivedValues() {
        long start = at("2026-11-09T05:30");
        long end = at("2026-11-09T06:30");
        archive.append(new LiveSession(PLATFORM, ROOM, "合成主播", 1L, start, end, 3600,
                Map.of("gift_value", 10d, "watched_count", 90d, "danmu_count", 25d), Map.of(), null, List.of(), 180,
                Map.of("danmu_users", List.of(VIEWER, VIEWER + 1), "enter_users", List.of(VIEWER, VIEWER + 2)),
                0, Map.of("online_count", new SeriesPeak(start + 10_000, 33d))));
        var request = request(List.of("session"), "2026-11-08", "2026-11-09", "min:startTime", "max:endTime",
                "sum:durationSeconds", "sum:effectiveSeconds", "sum:messages", "max:peakOnline",
                "max:watched", "max:sessionAudience");
        request.put("groupBy", List.of("week", "weekday"));
        table("场次其他字段", query(request), List.of("week", "weekday", "min:startTime", "max:endTime",
                        "sum:durationSeconds", "sum:effectiveSeconds", "sum:messages", "max:peakOnline",
                        "max:watched", "max:sessionAudience"),
                List.of(List.of("2026-11-02", 7, start, end, 3600, 3420, 25, 33, 90, 3)),
                Arrays.asList("合计", null, start, end, 3600, 3420, 25, 33, 90, 3));
    }

    // 抓住金额隐藏时仍能通过求和、极值、平均或金额门槛取到金额相关数据。
    @Test void hiddenAmountRejectsEveryMonetaryOperationAndFilter() throws IOException {
        fixture();
        for (String metric : List.of("sum:amount", "max:amount", "min:amount", "avg:amount")) {
            var request = request(List.of("session"), FROM, TO, metric);
            request.put("amountVisible", true);
            refused("隐藏金额 " + metric, query(request, HIDDEN, privacy(List.of())), "这里不显示金额");
        }
        var filter = request(List.of("box"), FROM, TO, "count");
        filter.put("filters", List.of(filter("amount", "gt", 1)));
        refused("隐藏金额门槛", query(filter, HIDDEN, privacy(List.of())), "这里不显示金额");
        var counts = query(request(List.of("box"), FROM, TO, "count", "sum:quantity"), HIDDEN, privacy(List.of()));
        table("隐藏金额仍可查个数", counts, List.of("count", "sum:quantity"),
                List.of(List.of(4, 10)), List.of(4, 10));
        assertFalse(counts.containsKey("amountUnit"));
        assertFalse(counts.containsKey("amountBasis"));
    }

    // 抓住把盲盒盒价或背包实扣拿来和场次流水比较，导致助手与报告金额口径不一致。
    @Test void boxAndBagValueMatchExistingSessionRevenue() throws IOException {
        fixture();
        var boxes = query(request(List.of("box"), FROM, TO, "sum:amount"));
        assertNumber(60, boxes.getJSONArray("totals").get(0));
        assertEquals("元", boxes.getString("amountUnit"));
        assertTrue(boxes.getString("amountBasis").contains("开出物"));
        var paid = request(List.of("gift", "box", "guard", "super_chat"), "2026-10-31", TO, "sum:amount");
        var combined = query(paid, "groupBy", List.of("session"));
        for (long start : List.of(CROSSING, FIRST, GIFTS)) {
            var row = combined.getJSONArray("rows").stream().map(JSONArray.class::cast)
                    .filter(r -> r.getLongValue(0) == start).findFirst().orElseThrow();
            assertNumber(service.revenue(PLATFORM, ROOM, start).getBigDecimal("totalAmount"), row.get(1));
        }
    }

    // 抓住不支持的参数引发异常、读档或返回貌似合理的零。
    @Test void invalidQueriesExplainUnsupportedRangesKeysKindsAndFields() {
        refused("超长日期", query(request(List.of("session"), "2025-01-01", TO, "count")), "366");
        refused("三个分组", query(request(List.of("gift"), FROM, TO, "count"),
                "groupBy", List.of("name", "day", "hour")), "分组键");
        refused("未知种类", query(request(List.of("arbitrary"), FROM, TO, "count")), "种类");
        refused("种类缺字段", query(request(List.of("gift"), FROM, TO, "count"),
                "filters", List.of(filter("pkMyVotes", "gt", 100))), "pkMyVotes");
        refused("合选不能求个数", query(request(List.of("gift", "box"), FROM, TO, "sum:quantity")), "合选");
        refused("无效前 N", query(request(List.of("gift"), FROM, TO, "count"), "limit", 51), "50");
        refused("无效日期", query(request(List.of("session"), "2026-11-31", TO, "count")), "日期");
        assertEquals(0, query(request(List.of("session"), "2025-11-22", TO, "count"))
                .getJSONArray("totals").getIntValue(0));
        verify(live, times(1)).getLiveStatus(PLATFORM, ROOM);
    }

    // 抓住跨分组把同一观众重复累加，以及找不到的字段被悄悄当成实际的零元。
    @Test void distinctGrandTotalAndMissingValuesAreExplicit() throws IOException {
        fixture();
        var distinct = query(request(List.of("box"), FROM, TO, "distinctAudience"), "groupBy", List.of("day"));
        table("跨日去重", distinct, List.of("day", "distinctAudience"), List.of(
                List.of(FROM, 1), List.of("2026-11-02", 1), List.of("2026-11-12", 1)), List.of("合计", 3));
        var window = request(List.of("box"), "2026-10-31", TO, "distinctAudience");
        window.put("groupBy", List.of("session"));
        assertEquals(3, query(window).getJSONArray("totals").getIntValue(1));
        details.appendDanmu(PLATFORM, ROOM, FIRST, new DanmuRecord(FIRST + 1, VIEWER, "合成观众0",
                "旧留言", DanmuRecord.Type.SUPER_CHAT));
        var unknown = query(request(List.of("super_chat"), FROM, TO, "count", "sum:amount"));
        assertEquals(2, unknown.getJSONArray("totals").getIntValue(0));
        assertNull(unknown.getJSONArray("totals").get(1));
        assertEquals(1, unknown.getIntValue("unknownMetricRows"));
        assertNull(query(request(List.of("gift"), "2026-11-16", "2026-11-16", "count"))
                .getJSONArray("totals").get(0));
    }

    // 抓住分组回包超过行数限制，或裁掉的行漏出合计。
    @Test void responseKeepsAtMostSixtyTableRowsIncludingTotal() throws IOException {
        long start = at("2026-09-01T06:00");
        int days = 65;
        long day = Duration.ofDays(1).toMillis();
        summary(start, start + days * day, 0, 0, 0);
        for (int i = 0; i < days; i++) event(start, start + i * day, "follow", Map.of("uid", VIEWER));
        var result = query(request(List.of("follow"), "2026-09-01", "2026-11-04", "count"),
                "groupBy", List.of("day"));
        assertEquals(59, result.getJSONArray("rows").size());
        assertEquals(days, result.getIntValue("groupCount"));
        assertEquals(days, result.getJSONArray("totals").getIntValue(1));
        assertTrue(result.getBooleanValue("truncated"));
    }

    // 抓住换场或换到醒目留言文件后重置扫描计数，导致超过上限仍返回数字。
    @Test void scanLimitIsSharedAcrossFilesAndSessions() throws IOException {
        summary(FIRST, FIRST + 10_000, 0, 0, 0);
        summary(GIFTS, GIFTS + 10_000, 0, 0, 0);
        writeRepeated(file(FIRST, "events.jsonl"), "{\"t\":\"pk_open\"}", 200_000);
        writeRepeated(file(FIRST, "danmu.jsonl"), "{\"type\":\"DANMU\"}", 200_000);
        writeRepeated(file(GIFTS, "events.jsonl"), "{\"t\":\"pk_open\"}", 100_001);
        refused("扫描太多", query(request(List.of("gift", "super_chat"), FROM, TO, "count")), "500000");
    }

    // 抓住过长的坏行撑大查询内存，或错误被解释为没有记录。
    @Test void oversizedDetailLineIsRejected() throws IOException {
        summary(FIRST, FIRST + 10_000, 0, 0, 0);
        writeRepeated(file(FIRST, "events.jsonl"), "x".repeat(30_000), 1);
        refused("超长明细", query(request(List.of("gift"), FROM, TO, "count")), "超长");
    }

    private void fixture() throws IOException {
        summary(CROSSING, at("2026-11-01T07:30"), 120, 0, 0);
        gift(CROSSING, at("2026-11-01T05:59"), 0, "合成早班礼物", 1, 90, 0);
        box(CROSSING, at("2026-11-01T06:05"), 0, "心动盲盒", "合成银星", 1, 30, 15);
        emptyDanmu(CROSSING);
        summary(FIRST, at("2026-11-02T07:00"), 19, 180, 50);
        box(FIRST, FIRST + 1, 0, "心动盲盒", "合成银星", 3, 12, 45);
        box(FIRST, at("2026-11-02T06:00"), 1, "心动盲盒", "合成银星", 2, 7, 30);
        event(FIRST, FIRST + 3, "guard", JSONObject.of("uid", VIEWER, "un", "合成观众0", "ot", 1, "lv", 3, "val", 100, "n", 1));
        event(FIRST, FIRST + 4, "guard", JSONObject.of("uid", VIEWER + 2, "un", "合成观众2", "ot", 2, "lv", 2, "val", 80, "n", 1));
        details.appendDanmu(PLATFORM, ROOM, FIRST, new DanmuRecord(FIRST + 2, VIEWER + 1, "合成观众1",
                "合成留言原文", DanmuRecord.Type.SUPER_CHAT, false, 50d));
        summary(GIFTS, at("2026-11-12T23:00"), 349, 0, 0);
        gift(GIFTS, GIFTS + 1, 0, "合成礼物一", 9, 9, 0);
        gift(GIFTS, GIFTS + 2, 3, "合成礼物二", 8, 8, 8);
        gift(GIFTS, GIFTS + 3, 4, "合成礼物三", 7, 7, 7);
        gift(GIFTS, GIFTS + 4, 5, "合成礼物四", 6, 6, 6);
        gift(GIFTS, GIFTS + 5, 6, "合成礼物五", 5, 5, 5);
        gift(GIFTS, GIFTS + 6, 7, "合成礼物六", 2, 2, 2);
        gift(GIFTS, GIFTS + 7, 9, "合成礼物六", 1, 1, 1);
        event(GIFTS, GIFTS + 8, "gift", JSONObject.of("uid", EXCLUDED, "un", "合成拒绝观众",
                "gn", "合成礼物六", "n", 1, "val", 300, "pay", 300));
        box(GIFTS, GIFTS + 9, 8, "合成其他盒", "合成其他礼物", 4, 11, 60);
        pk(GIFTS + 10, 1, 100, List.of(40, 70));
        pk(GIFTS + 11, 1, 101, List.of(98));
        pk(GIFTS + 12, 0, 150, List.of(170));
        pk(GIFTS + 13, 2, 30, List.of(30));
        event(GIFTS, GIFTS + 14, "pk_open", Map.of("mv", 999));
        emptyDanmu(GIFTS);
        summary(FOLLOWS, FOLLOWS + 10_000, 0, 0, 0);
        event(FOLLOWS, FOLLOWS + 1, "follow", Map.of("uid", VIEWER));
        emptyDanmu(FOLLOWS);
        summary(MISSING, MISSING + 10_000, 21, 0, 0);
        summary(LIVE, LIVE + 10_000, 999, 0, 0);
        when(live.getLiveStatus(PLATFORM, ROOM)).thenReturn(Optional.of(true));
        when(live.getLiveStartTime(PLATFORM, ROOM)).thenReturn(Optional.of(LIVE));
        box(LIVE, LIVE + 1, 0, "心动盲盒", "合成银星", 999, 999, 999);
    }

    private JSONObject query(JSONObject request) { return query(request, AssistantQueryContext.CONSOLE, privacy(List.of())); }

    private JSONObject query(JSONObject request, String key, Object value) {
        var copy = new JSONObject(request);
        copy.put(key, value);
        return query(copy);
    }

    private JSONObject query(JSONObject request, AssistantQueryContext context, AssistantPrivacy privacy) {
        var raw = service.aggregate(PLATFORM, ROOM, request, context, privacy);
        return privacy.prepare("aggregate", raw, AssistantQueryContext.CONSOLE);
    }

    private static JSONObject request(List<String> kinds, String from, String to, String... metrics) {
        return JSONObject.of("kinds", kinds, "from", from, "to", to, "metrics", List.of(metrics));
    }

    private static JSONObject filter(String field, String comparison, Object value) {
        return JSONObject.of("field", field, "op", comparison, "value", value);
    }

    private static JSONObject sort(String by, String direction) {
        return JSONObject.of("by", by, "direction", direction);
    }

    private AssistantPrivacy privacy(List<Long> excluded) {
        return new AssistantPrivacy(local, PLATFORM, ROOM, excluded, Map::of);
    }

    private String alias(int offset) { return local.alias(PLATFORM, VIEWER + offset); }

    private static void table(String name, JSONObject actual, List<String> columns,
                              List<? extends List<?>> rows, List<?> total) {
        show(name, actual);
        assertEquals(columns, actual.getJSONArray("columns"), name);
        var actualRows = actual.getJSONArray("rows");
        assertEquals(rows.size(), actualRows.size(), name);
        for (int i = 0; i < rows.size(); i++) assertRow(rows.get(i), actualRows.getJSONArray(i));
        assertRow(total, actual.getJSONArray("totals"));
    }

    private static void refused(String name, JSONObject value, String reason) {
        show(name, value);
        assertNull(value.get("rows"), name);
        assertTrue(value.getString("reason").contains(reason), value.toJSONString());
    }

    private static void assertRow(List<?> expected, JSONArray actual) {
        assertEquals(expected.size(), actual.size());
        for (int index = 0; index < expected.size(); index++) {
            Object value = expected.get(index);
            if (value instanceof Number number) assertNumber(number, actual.get(index));
            else assertEquals(value, actual.get(index));
        }
    }

    private static void assertNumber(Number expected, Object actual) {
        assertInstanceOf(Number.class, actual);
        assertEquals(0, new BigDecimal(expected.toString()).compareTo(new BigDecimal(actual.toString())),
                () -> "expected " + expected + ", actual " + actual);
    }

    private static void show(String name, JSONObject response) {
        if (Boolean.getBoolean("assistant.aggregate.printFixtures"))
            System.out.println(name + " " + JSON.toJSONString(response, com.alibaba.fastjson2.JSONWriter.Feature.WriteNulls));
    }

    private static long at(String beijing) {
        return LocalDateTime.parse(beijing).atZone(StatisticsDay.ZONE).toInstant().toEpochMilli();
    }

    private void summary(long start, long end, double gift, double guard, double chat) {
        archive.append(new LiveSession(PLATFORM, ROOM, "合成主播", 1L, start, end, (end - start) / 1000,
                Map.of("gift_value", gift, "guard_value", guard, "super_chat_value", chat), Map.of()));
    }

    private void seal(long start, Map<String, Double> metrics) {
        details.store(new LiveDetail(LiveDetail.VERSION, PLATFORM, ROOM, "合成主播", 1L, start, start + 10_000,
                10, metrics, Map.of(), Map.of(), Map.of(), Map.of(), List.of(), List.of(), List.of(), Map.of(), List.of()));
    }

    private void gift(long start, long at, int person, String name, int quantity, int value, int paid) {
        event(start, at, "gift", JSONObject.of("uid", VIEWER + person, "un", "合成观众" + person,
                "gn", name, "n", quantity, "val", value, "pay", paid, "bag", paid == 0));
    }

    private void box(long start, long at, int person, String name, String opened, int quantity, int value, int price) {
        event(start, at, "box", JSONObject.of("uid", VIEWER + person, "un", "合成观众" + person,
                "bn", name, "gn", opened, "n", quantity, "val", value, "bp", price));
    }

    private void pk(long at, int result, int votes, List<Integer> opponentVotes) {
        event(GIFTS, at, "pk_settle", JSONObject.of("rs", result, "mv", votes,
                "op", opponentVotes.stream().map(v -> JSONObject.of("v", v)).toList()));
    }

    private void emptyDanmu(long start) throws IOException { Files.createFile(file(start, "danmu.jsonl")); }

    private void event(long start, long at, String type, Map<String, Object> fields) {
        details.appendEvent(PLATFORM, ROOM, start, at, type, fields);
    }

    private Path file(long start, String name) {
        return dir.resolve("details").resolve(PLATFORM + "-" + ROOM + "-" + start).resolve(name);
    }

    private static void writeRepeated(Path file, String line, int count) throws IOException {
        Files.createDirectories(file.getParent());
        String row = line + "\n";
        String block = row.repeat(Math.min(count, 1000));
        try (var out = Files.newBufferedWriter(file)) {
            for (int i = 0; i < count / 1000; i++) out.write(block);
            out.write(row.repeat(count % 1000));
        }
    }
}
