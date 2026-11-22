package org.frostnova.nova.console.controller;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.console.assistant.AssistantQueryService;
import org.frostnova.nova.core.analytics.StatisticsDay;
import org.frostnova.nova.core.analytics.LiveDetail;
import org.frostnova.nova.core.analytics.LiveMetricCatalog;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.enums.LiveEndReason;
import org.frostnova.nova.core.model.*;
import org.frostnova.nova.core.service.*;
import org.frostnova.nova.report.analytics.BilibiliLiveMetricCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AssistantQueryServiceTest {
    @TempDir Path dir;
    final String platform = "bilibili";
    final Long uid = 9300000011L;
    final long now = Instant.parse("2026-10-24T08:00:00Z").toEpochMilli();
    final long start = now - 86400000;
    LiveSessionArchive archive;
    LiveDetailArchive details;
    StreamerSnapshotArchive snapshots;
    LiveDataService live;
    StreamerController controller;
    AssistantQueryService query;

    @BeforeEach @SuppressWarnings("unchecked")
    void setup() {
        var props = new NovaCoreProperties();
        props.getLive().setLiveDataPath(dir.resolve("data.json").toString());
        props.getDatasource().setJsonPath(dir.resolve("datasource.json").toString());
        archive = new LiveSessionArchive(props);
        details = new LiveDetailArchive(props);
        snapshots = new StreamerSnapshotArchive(props);
        live = mock(LiveDataService.class);
        var data = mock(AbstractDataSource.class);
        when(data.getAllUsers()).thenReturn(List.of());
        ObjectProvider<LiveMetricCatalog> catalogs = mock(ObjectProvider.class);
        when(catalogs.orderedStream()).thenAnswer(i -> Stream.of(new BilibiliLiveMetricCatalog()));
        ObjectProvider<LiveReportRedrawer> redrawers = mock(ObjectProvider.class);
        var clock = Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC);
        controller = new StreamerController(data, new StreamerDirectory(props), live, archive,
                snapshots, details, catalogs, redrawers, clock);
        var statistics = new StreamerStatistics(data, new StreamerDirectory(props), live, archive, snapshots, catalogs, clock);
        query = new AssistantQueryService(statistics, archive, details, live, props, catalogs, clock);
    }

    // 抓助手流水与主播页不一致，以及礼物价值与实扣混用。
    @Test void revenueMatchesSessionPage() {
        append(start, 3600, 10);
        details.appendEvent(platform, uid, start, start + 1, "guard", Map.of("ot", 1, "val", 100));
        details.appendEvent(platform, uid, start, start + 2, "guard", Map.of("ot", 2, "val", 98));
        details.store(detail(Map.of(), Map.of("revenue_users", List.of(
                new UserScore(9300000021L, "合成观众甲", 200), new UserScore(9300000022L, "合成观众乙", 38)))));
        var page = controller.sessions(platform, uid, null, null, null, null, null, 1, 20)
                .getBody().getJSONObject("sessions");
        var metrics = page.getJSONArray("items").getJSONObject(0).getJSONObject("metrics");
        double expected = page.getJSONArray("revenueKeys").stream().mapToDouble(k -> metrics.getDoubleValue(k.toString())).sum();
        var value = query.revenue(platform, uid, start);
        assertEquals(expected, value.getDouble("totalAmount"));
        assertEquals(99, value.getDoubleValue("giftPaid"));
        assertEquals(100, value.getDoubleValue("guardOpen"));
        assertEquals(98, value.getDoubleValue("guardRenew"));
        assertNull(value.get("payingUsers"));
        assertNotNull(value.getString("paymentReason"));
        assertEquals(1, value.getDoubleValue("top3Share"));
        assertTrue(value.getBooleanValue("concentrated"));
        assertEquals(200, value.getJSONArray("contributors").getJSONObject(0).getDoubleValue("amount"));
    }

    // 抓背包礼物的正价值被错当成真实付费人数。
    @Test void payingUsersExcludeUnchargedGifts() {
        archive.append(new LiveSession(platform, uid, "合成主播", 9300000012L, start, start + 60000, 60,
                Map.of("gift_value", 100d, "gift_paid", 10d, "super_chat_value", 0d, "guard_value", 0d), Map.of()));
        details.appendEvent(platform, uid, start, start + 1, "gift", Map.of("uid", 9300000021L, "val", 50d, "pay", 10d));
        details.appendEvent(platform, uid, start, start + 2, "gift", Map.of("uid", 9300000022L, "val", 50d, "pay", 0d));
        details.store(detail(Map.of(), Map.of("revenue_users", List.of(
                new UserScore(9300000021L, "合成观众甲", 50), new UserScore(9300000022L, "合成观众乙", 50)))));
        var result = query.revenue(platform, uid, start);
        assertEquals(1, result.getInteger("payingUsers"));
        assertEquals(100, result.getDoubleValue("totalAmount"));
    }

    // 抓醒目留言付费者漏算，以及不完整原文仍被当作完整付费证据。
    @Test void payingUsersIncludeVerifiedSuperChatSpeakers() {
        archive.append(new LiveSession(platform, uid, "合成主播", 9300000012L, start, start + 60000, 60,
                Map.of("gift_value", 0d, "gift_paid", 0d, "super_chat_value", 60d,
                        "super_chat_count", 2d, "guard_value", 0d), Map.of()));
        for (long person : List.of(9300000021L, 9300000022L)) details.appendDanmu(platform, uid, start,
                new DanmuRecord(start, person, "合成留言观众", "合成留言", DanmuRecord.Type.SUPER_CHAT));
        assertEquals(2, query.revenue(platform, uid, start).getInteger("payingUsers"));
        details.appendDanmu(platform, uid, start, new DanmuRecord(start, 9300000023L,
                "合成额外观众", "额外留言", DanmuRecord.Type.SUPER_CHAT));
        var incomplete = query.revenue(platform, uid, start);
        assertNull(incomplete.get("payingUsers"));
        assertNotNull(incomplete.getString("paymentReason"));
    }

    // 抓相同参数下周期数、趋势或粉丝变化偏离主播页算法。
    @Test void periodQueriesMatchController() {
        append(start, 3600, 10);
        snapshots.append(new StreamerSnapshot(platform, uid, "合成主播", now - 8 * 86400000L, Map.of("fans", 100d)));
        snapshots.append(new StreamerSnapshot(platform, uid, "合成主播", start, Map.of("fans", 130d)));
        for (int days : List.of(7, 30, 90, 180, 360)) {
            assertEquals(normal(controller.insights(platform, uid, "" + days).getBody().get("insights")),
                    normal(query.insights(platform, uid, days).get("insights")));
            var expectedFans = normal(controller.fans(platform, uid, "" + days).getBody().get("fans"));
            var actualFans = normal(query.fans(platform, uid, days).get("fans"));
            assertEquals(expectedFans.get("points"), actualFans.get("points"));
            assertEquals(expectedFans.get("change"), actualFans.get("change"));
            assertEquals(expectedFans.get("partial"), actualFans.get("partial"));
            for (String period : List.of("day", "week", "month")) {
                var expected = normal(controller.rangeTrend(platform, uid, period, "" + days).getBody().get("trend"));
                var actual = query.trend(platform, uid, period, days).getJSONObject("trend");
                var buckets = expected.getJSONArray("buckets");
                assertEquals(buckets.subList(Math.max(0, buckets.size() - 60), buckets.size()), normal(actual).getJSONArray("buckets"));
                assertEquals(buckets.size(), actual.getIntValue("total"));
                assertEquals(buckets.size() > 60, actual.getBooleanValue("truncated"));
            }
        }
    }

    // 抓住粉丝折线只剩窗口末尾、起点值没报或净增与曲线首尾对不上。
    @Test void fanCurveSamplesAcrossTheWindowAndReportsBothEndpoints() {
        append(start, 3600, 10);
        long windowStart = StatisticsDay.startOf(LocalDate.of(2026, 9, 25)).toEpochMilli();
        snapshots.append(new StreamerSnapshot(platform, uid, "合成主播", windowStart - 60_000, Map.of("fans", 900d)));
        int count = 300;
        long step = Duration.ofHours(2).toMillis();
        for (int i = 0; i < count; i++) {
            snapshots.append(new StreamerSnapshot(platform, uid, "合成主播",
                    windowStart + (i + 1) * step, Map.of("fans", 1000d + i)));
        }
        var fans = query.fans(platform, uid, 30).getJSONObject("fans");
        if (Boolean.getBoolean("assistant.aggregate.printFixtures")) System.out.println("粉丝趋势 " + fans);
        var points = fans.getJSONArray("points");
        assertEquals(101, points.size());
        assertEquals(count, fans.getIntValue("total"));
        assertTrue(fans.getBooleanValue("truncated"));
        var first = points.getJSONObject(0);
        var last = points.getJSONObject(points.size() - 1);
        assertEquals(windowStart + step, first.getLongValue("at"));
        assertEquals(windowStart + count * step, last.getLongValue("at"));
        for (int i = 1; i < points.size(); i++) {
            long gap = points.getJSONObject(i).getLongValue("at") - points.getJSONObject(i - 1).getLongValue("at");
            assertTrue(gap >= 2 * step && gap <= 3 * step);
        }
        assertEquals(windowStart, fans.getLongValue("windowFromAt"));
        assertEquals(now, fans.getLongValue("windowToAtExclusive"));
        assertEquals(first.getLong("at"), fans.getLong("fromAt"));
        assertEquals(last.getLong("at"), fans.getLong("toAt"));
        assertEquals(1000d, fans.getDouble("fromValue"));
        assertEquals(1299d, fans.getDouble("toValue"));
        assertEquals(299d, fans.getDouble("pointChange"));
        assertEquals(399d, fans.getDouble("change"));
    }

    // 抓住按快照行号抽点时，密集的后半段挤占大多数点，让匀速涨粉看起来变慢。
    @Test void fanCurveSamplesUnevenSnapshotsByTime() {
        append(start, 3600, 10);
        long windowStart = StatisticsDay.startOf(LocalDate.of(2026, 9, 25)).toEpochMilli();
        long width = now - windowStart;
        long middle = windowStart + width / 2;
        var recorded = new TreeMap<Long, Double>();
        for (int i = 0; i < 61; i++) recorded.put(windowStart + i * (width / 2) / 61, 0d);
        for (int i = 0; i < 346; i++) recorded.put(middle + i * (width / 2) / 346, 0d);
        recorded.replaceAll((at, unused) -> 1000d + (at - windowStart) / 3600000d);
        recorded.forEach((at, value) -> snapshots.append(new StreamerSnapshot(platform, uid, "合成主播", at,
                Map.of("fans", value))));

        var fans = query.fans(platform, uid, 30).getJSONObject("fans");
        if (Boolean.getBoolean("assistant.aggregate.printFixtures")) System.out.println("疏密不均粉丝趋势 " + fans);
        var points = fans.getJSONArray("points");
        var sampled = points.stream().map(JSONObject.class::cast).map(p -> p.getLongValue("at")).toList();
        assertEquals(407, fans.getIntValue("total"));
        assertTrue(sampled.size() <= 120);
        assertEquals(recorded.firstKey(), sampled.get(0));
        assertEquals(recorded.lastKey(), sampled.get(sampled.size() - 1));
        assertEquals(60, sampled.stream().filter(at -> at < middle).count());
        assertEquals(60, sampled.stream().filter(at -> at >= middle).count());
        var expected = new TreeSet<>(List.of(recorded.firstKey(), recorded.lastKey()));
        double segment = width / 118d;
        for (int i = 0; i < 118; i++) {
            double lower = i * segment, upper = (i + 1) * segment, center = (lower + upper) / 2;
            recorded.keySet().stream().filter(at -> at - windowStart >= lower && at - windowStart < upper)
                    .min(Comparator.comparingDouble(at -> Math.abs(at - windowStart - center))).ifPresent(expected::add);
        }
        assertEquals(List.copyOf(expected), sampled);
        for (int i = 0; i < points.size(); i++) {
            assertEquals(recorded.get(sampled.get(i)), points.getJSONObject(i).getDouble("value"));
        }
    }

    // 抓抽稀导致五分钟掉人时段的端点或数值被说错。
    @Test void largestDropKeepsExactFiveMinuteEndpoints() {
        var points = new TreeMap<Long, Double>();
        points.put(start, 900d);
        points.put(start + 60000, 1000d);
        points.put(start + 360000, 400d);
        points.put(start + 660000, 500d);
        details.store(detail(Map.of("online_count", points), Map.of()));
        var value = query.curve(platform, uid, start, null, 2);
        var drop = value.getJSONObject("drops").getJSONArray("items").getJSONObject(0);
        assertEquals(start + 60000, drop.getLongValue("fromAt"));
        assertEquals(start + 360000, drop.getLongValue("toAt"));
        assertEquals(1000, drop.getDoubleValue("fromValue"));
        assertEquals(400, drop.getDoubleValue("toValue"));
        assertEquals(600, drop.getDoubleValue("drop"));
        assertEquals(.6, drop.getDoubleValue("dropRatio"));
        var curve = value.getJSONObject("curve");
        assertEquals(4, curve.getIntValue("total"));
        assertTrue(curve.getBooleanValue("truncated"));
        assertEquals(start, curve.getJSONArray("items").getJSONObject(0).getLongValue("at"));
        assertEquals(start + 660000, curve.getJSONArray("items").getJSONObject(1).getLongValue("at"));
    }

    // 抓窗口外弹幕混入、说话人数算错、原话超条数或超字数。
    @Test void danmuWindowCountsAndBoundsSamples() {
        details.appendDanmu(platform, uid, start, new DanmuRecord(start - 1, 9300000099L, "合成窗外观众", "窗外", DanmuRecord.Type.DANMU));
        for (int i = 0; i < 40; i++) {
            details.appendDanmu(platform, uid, start, new DanmuRecord(start + i, 9300000021L + i % 3,
                    "合成观众" + i % 3, "合成词语 ".repeat(30) + "😀", DanmuRecord.Type.values()[i % 3]));
        }
        details.appendDanmu(platform, uid, start, new DanmuRecord(start + 40, 9300000098L, "合成边界观众", "边界", DanmuRecord.Type.DANMU));
        var value = query.danmu(platform, uid, start, start, start + 40, 100);
        assertEquals(40, value.getIntValue("total"));
        assertEquals(3, value.getIntValue("speakers"));
        assertEquals(14, value.getJSONObject("byType").getIntValue("DANMU"));
        assertEquals(13, value.getJSONObject("byType").getIntValue("EMOJI"));
        assertEquals(13, value.getJSONObject("byType").getIntValue("SUPER_CHAT"));
        assertEquals(30, value.getJSONArray("items").size());
        assertTrue(value.getBooleanValue("truncated"));
        value.getJSONArray("items").forEach(item -> {
            var text = ((JSONObject) item).getString("text");
            assertTrue(text.codePointCount(0, text.length()) <= 60);
        });
        assertTrue(value.getJSONObject("words").getJSONArray("items").size() <= 10);
    }

    // 抓正在直播时把未知下播时刻和时长写成零。
    @Test void unfinishedSessionHasNullEndAndDuration() {
        when(live.getLiveStatus(platform, uid)).thenReturn(Optional.of(true));
        when(live.getLiveStartTime(platform, uid)).thenReturn(Optional.of(start));
        when(live.getLiveMetrics(platform, uid)).thenReturn(Map.of("gift_value", 5d));
        var value = query.session(platform, uid, start);
        assertTrue(value.getBooleanValue("unfinished"));
        var current = value.getJSONObject("current");
        assertTrue(current.containsKey("endTime"));
        assertNull(current.get("endTime"));
        assertNull(current.get("durationSeconds"));
        assertNull(current.get("effectiveSeconds"));
        assertNull(current.get("endReason"));
        assertNull(current.get("messages"));
        assertNull(current.get("peakOnline"));
        when(live.getLiveMetrics(platform, uid)).thenReturn(Map.of("danmu_count", 42d));
        assertNull(query.session(platform, uid, start).getJSONObject("current").get("revenue"));
        assertNull(query.revenue(platform, uid, start).get("totalAmount"));
    }

    // 抓只有两场历史样本时仍给出充足样本的结论。
    @Test void twoPreviousSessionsAreInsufficient() {
        append(start - 2 * 86400000L, 600, 2);
        append(start - 86400000L, 1800, 8);
        append(start, 3600, 20);
        var value = query.session(platform, uid, start);
        assertTrue(value.getBooleanValue("sampleInsufficient"));
        assertEquals(2, value.getJSONObject("median").getIntValue("sampleCount"));
        assertEquals(1200, value.getJSONObject("median").getDoubleValue("durationSeconds"));
        assertEquals(start - 86400000L, value.getJSONObject("previous").getLongValue("startTime"));
        assertEquals(1800, value.getJSONObject("versusPrevious").getJSONObject("durationSeconds").getDoubleValue("difference"));
    }

    // 抓场次列表未封顶、窗口混入旧场次、跨主播混数或场次字段偏离页面。
    @Test void sessionsAreBoundedAndMatchPage() {
        for (int i = 0; i < 25; i++) append(start - i * 60000L, 60, i);
        append(now - 40 * 86400000L, 60, 500);
        archive.append(new LiveSession(platform, 9300000099L, "合成其他主播", 9300000098L,
                start, start + 60000, 60, Map.of("gift_value", 999d), Map.of()));
        var result = query.sessions(platform, uid, 30, 200);
        assertEquals(25, result.getIntValue("total"));
        assertTrue(result.getBooleanValue("truncated"));
        assertEquals(20, result.getJSONArray("items").size());
        var actual = result.getJSONArray("items").getJSONObject(0);
        var expected = controller.sessions(platform, uid, null, null, null, null, null, 1, 20)
                .getBody().getJSONObject("sessions").getJSONArray("items").getJSONObject(0);
        for (String key : List.of("startTime", "endTime", "durationSeconds", "effectiveSeconds", "listQuality",
                "metrics", "userCounts", "peaks", "endReason", "interrupted", "audience")) assertEquals(expected.get(key), actual.get(key), key);
        assertEquals(start, actual.getLongValue("startTime"));
        assertEquals(expected.getJSONArray("titles"), actual.getJSONObject("titles").getJSONArray("items"));
        assertFalse(actual.containsKey("userSets"));
    }

    // 抓缺失文件被说成零，以及无效参数触发异常或路径越界。
    @Test void missingDataAndInvalidParametersHaveReasons() throws Exception {
        for (JSONObject result : List.of(query.danmu(platform, uid, start, start, now, 30),
                query.curve(platform, uid, start, null, 120), query.session(platform, uid, start),
                query.revenue(platform, uid, start), query.insights(platform, uid, 7),
                query.trend(platform, uid, "day", 7), query.fans(platform, uid, 7),
                query.danmu("../other", uid, start, start, now, 30), query.sessions(platform, uid, 0, 20))) {
            assertNotNull(result.getString("source"));
            assertNotNull(result.getString("reason"));
        }
        assertNull(query.curve(platform, uid, start, null, 120).get("curve"));
        assertNull(query.danmu(platform, uid, start, start, now, 30).get("items"));
        var path = dir.resolve("details").resolve(platform + "-" + uid + "-" + start).resolve("danmu.jsonl");
        Files.createDirectories(path.getParent());
        Files.writeString(path, "");
        var empty = query.danmu(platform, uid, start, start, now, 30);
        assertEquals(0, empty.getLongValue("total"));
        assertEquals(0, empty.getJSONArray("items").size());
        assertFalse(empty.getBooleanValue("partial"));
    }

    // 抓结构合法但字段损坏的一行连累整场统计，以及四字节字符被截断。
    @Test void malformedRowsAreSkippedAndUnicodeSamplesStayWhole() throws Exception {
        details.appendDanmu(platform, uid, start, new DanmuRecord(start, 9300000021L, "合成观众甲",
                "字".repeat(59) + "😀😀", DanmuRecord.Type.DANMU));
        var path = dir.resolve("details").resolve(platform + "-" + uid + "-" + start).resolve("danmu.jsonl");
        Files.writeString(path, "{broken}\n{\"at\":\"broken\"}\n", java.nio.file.StandardOpenOption.APPEND);
        var result = query.danmu(platform, uid, start, start, now, 30);
        assertEquals(1, result.getIntValue("total"));
        assertEquals(2, result.getIntValue("invalidLines"));
        assertTrue(result.getBooleanValue("partial"));
        String text = result.getJSONArray("items").getJSONObject(0).getString("text");
        assertEquals("字".repeat(59) + "😀", text);
    }

    // 抓超过五十万行仍继续扫描、缓存整份原文或样本超限。
    @Test void danmuScanStopsAtArchiveLineLimit() throws Exception {
        var path = dir.resolve("details").resolve(platform + "-" + uid + "-" + start).resolve("danmu.jsonl");
        Files.createDirectories(path.getParent());
        try (var writer = Files.newBufferedWriter(path)) {
            String row = JSON.toJSONString(new DanmuRecord(start, 9300000021L, "合成观众甲", "", DanmuRecord.Type.DANMU)) + "\n";
            for (int i = 0; i < 500001; i++) writer.write(row);
        }
        var result = query.danmu(platform, uid, start, start, now, 2);
        assertEquals(500000, result.getLongValue("total"));
        assertEquals(1, result.getIntValue("speakers"));
        assertEquals(2, result.getJSONArray("items").size());
        assertTrue(result.getBooleanValue("partial"));
        assertTrue(result.getBooleanValue("truncated"));
    }

    // 抓查询写回归档、触发采集变更，或未知的贡献榜和大航海拆分被编成零。
    @Test void queriesLeaveFilesAndLiveStateUnchanged() throws Exception {
        append(start, 3600, 10);
        details.appendDanmu(platform, uid, start, new DanmuRecord(start, 9300000021L, "合成观众甲", "合成词语", DanmuRecord.Type.DANMU));
        details.store(detail(Map.of("online_count", Map.of(start, 100d)), Map.of()));
        var before = fileContents();
        query.sessions(platform, uid, 30, 20);
        query.session(platform, uid, start);
        query.curve(platform, uid, start, null, 120);
        query.danmu(platform, uid, start, start, now, 30);
        var value = query.revenue(platform, uid, start);
        query.insights(platform, uid, 30);
        query.trend(platform, uid, "week", 30);
        query.fans(platform, uid, 30);
        assertNull(value.get("guardOpen"));
        assertNull(value.get("guardRenew"));
        assertNull(value.get("contributors"));
        assertNull(value.get("payingUsers"));
        assertNull(value.get("concentrated"));
        assertNotNull(value.getString("guardReason"));
        assertNotNull(value.getString("reason"));
        assertEquals(before, fileContents());
        verify(live, never()).setLiveStatus(any(), any(), anyBoolean());
        verify(live, never()).setLiveStartTime(any(), any(), anyLong());
    }

    // 抓历史中位数把第六场、更晚场次或尚未结束的场次混进样本。
    @Test void medianUsesOnlyFiveFinishedEarlierSessions() {
        for (int i = 1; i <= 6; i++) append(start - i * 86400000L, i * 100L, i);
        append(start, 1000, 50);
        archive.append(new LiveSession(platform, uid, "合成主播", 9300000012L,
                start - 60000, 0, 0, Map.of(), Map.of()));
        append(start + 86400000L, 9999, 500);
        var result = query.session(platform, uid, start);
        assertEquals(5, result.getJSONObject("median").getIntValue("sampleCount"));
        assertEquals(300, result.getJSONObject("median").getDoubleValue("durationSeconds"));
        assertFalse(result.getBooleanValue("sampleInsufficient"));
    }

    // 抓周期明细的场次标识、粉丝点和贡献者名单绕过列表上限。
    @Test void supportingListsAndContributorsAreBounded() {
        for (int i = 0; i < 125; i++) append(start + i * 1000L, 60, 1);
        for (int i = 0; i < 130; i++) snapshots.append(new StreamerSnapshot(platform, uid, "合成主播",
                start + i * 1000L, Map.of("fans", (double) i)));
        var insights = query.insights(platform, uid, 7).getJSONObject("insights");
        var day = insights.getJSONArray("daily").stream().map(JSONObject.class::cast)
                .filter(d -> d.getIntValue("sessions") == 125).findFirst().orElseThrow();
        assertEquals(125, day.getIntValue("startsTotal"));
        assertEquals(120, day.getJSONArray("starts").size());
        assertTrue(day.getBooleanValue("startsTruncated"));
        var fans = query.fans(platform, uid, 7).getJSONObject("fans");
        assertEquals(130, fans.getIntValue("total"));
        assertEquals(2, fans.getJSONArray("points").size());
        assertTrue(fans.getBooleanValue("truncated"));
        var ranking = new ArrayList<UserScore>();
        for (int i = 0; i < 12; i++) ranking.add(new UserScore(9300000021L + i, "合成观众" + i, 10));
        archive.append(new LiveSession(platform, uid, "合成主播", 9300000012L, start, start + 60000, 60,
                Map.of("gift_value", 120d, "super_chat_value", 0d, "guard_value", 0d), Map.of()));
        details.store(detail(Map.of(), Map.of("revenue_users", ranking)));
        var revenue = query.revenue(platform, uid, start);
        assertEquals(12, revenue.getIntValue("total"));
        assertEquals(10, revenue.getJSONArray("contributors").size());
        assertTrue(revenue.getBooleanValue("truncated"));
        assertEquals(.25, revenue.getDoubleValue("top3Share"));
        assertEquals(100d / 120, revenue.getDoubleValue("top10Share"));
        assertFalse(revenue.getBooleanValue("concentrated"));
    }

    // 抓住本地身份查询遗漏历史昵称、未入选观众、当前观众，或混入其他主播。
    @Test void localAudienceIncludesArchivedAndCurrentIdentities() {
        append(start, 60, 0);
        details.appendDanmu(platform, uid, start, new DanmuRecord(start, 9300000022L,
                "合成旧昵称", "原话", DanmuRecord.Type.DANMU));
        details.appendDanmu(platform, uid, start, new DanmuRecord(start + 1, 9300000022L,
                "合成新昵称", "原话", DanmuRecord.Type.DANMU));
        details.store(detail(Map.of(), Map.of("revenue_users", List.of(new UserScore(9300000023L, "合成榜单观众", 42)))));
        archive.append(new LiveSession(platform, 9300000099L, "其他主播", 2L, start, start + 60000, 60,
                Map.of(), Map.of(), LiveEndReason.NORMAL, List.of(), 0,
                Map.of("enter_users", List.of(9300000088L)), 0, Map.of(), Map.of()));
        when(live.getLiveStatus(platform, uid)).thenReturn(Optional.of(true));
        when(live.getLiveStartTime(platform, uid)).thenReturn(Optional.of(now));
        when(live.getLiveMetricUserSets(platform, uid)).thenReturn(Map.of("enter_users", List.of(9300000024L)));
        when(live.getLiveUserRanking(platform, uid, "enter_users", Integer.MAX_VALUE))
                .thenReturn(List.of(new UserScore(9300000024L, "合成当前观众", 1)));
        details.appendDanmu(platform, 9300000099L, start, new DanmuRecord(start, 9300000089L,
                "合成拒绝昵称", "原话", DanmuRecord.Type.DANMU));
        var identities = query.localAudience(platform, uid, List.of(9300000089L));
        assertEquals(Set.of("合成拒绝昵称"), identities.get(9300000089L).nicknames());
        assertTrue(identities.containsKey(9300000021L));
        assertEquals(Set.of("合成旧昵称", "合成新昵称"), identities.get(9300000022L).nicknames());
        assertEquals("合成新昵称", identities.get(9300000022L).latestNickname());
        assertEquals(Set.of("合成榜单观众"), identities.get(9300000023L).nicknames());
        assertEquals(Set.of("合成当前观众"), identities.get(9300000024L).nicknames());
        assertFalse(identities.containsKey(9300000088L));
        assertFalse(identities.containsKey(uid));
    }

    Map<String, String> fileContents() throws Exception {
        var result = new TreeMap<String, String>();
        try (var files = Files.walk(dir)) {
            for (var path : files.filter(Files::isRegularFile).toList()) result.put(dir.relativize(path).toString(), Files.readString(path));
        }
        return result;
    }

    void append(long at, long duration, double gift) {
        archive.append(new LiveSession(platform, uid, "合成主播", 9300000012L, at, at + duration * 1000, duration,
                Map.of("gift_value", gift, "gift_paid", 99d, "super_chat_value", 30d, "guard_value", 198d, "danmu_count", 4d),
                Map.of("enter_users", 1), LiveEndReason.NORMAL, List.of(new RoomInfoSnapshot(at, "合成标题", "合成分区")), 0,
                Map.of("enter_users", List.of(9300000021L)), 0, Map.of("online_count", new SeriesPeak(at, 1000)), Map.of()));
    }

    LiveDetail detail(Map<String, Map<Long, Double>> series, Map<String, List<UserScore>> rankings) {
        return new LiveDetail(LiveDetail.VERSION, platform, uid, "合成主播", 9300000012L,
                start, start + 3600000, 3600, Map.of(), Map.of(), series, rankings, Map.of(), List.of(),
                List.of(new RoomInfoSnapshot(start, "合成标题", "合成分区")), List.of(), Map.of(), List.of());
    }

    JSONObject normal(Object value) {
        var result = JSON.parseObject(JSON.toJSONString(value));
        if (result != null) removeListMetadata(result);
        return result;
    }

    void removeListMetadata(JSONObject value) {
        value.keySet().removeIf(k -> k.endsWith("Total") || k.endsWith("Truncated"));
        value.values().forEach(item -> {
            if (item instanceof JSONObject child) removeListMetadata(child);
            else if (item instanceof com.alibaba.fastjson2.JSONArray array) array.forEach(one -> {
                if (one instanceof JSONObject child) removeListMetadata(child);
            });
        });
    }
}
