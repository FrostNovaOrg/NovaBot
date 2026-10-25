package org.frostnova.nova.core.service;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.analytics.SessionRecap;
import org.frostnova.nova.core.assistant.AssistantProperties;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SessionRecapServiceTest {
    // All viewer identities and words in these fixtures are synthetic.
    static final long HOST = 910000001L, BASE = 920000000L;
    static final long DAY = 86_400_000L;
    static final long START = ZonedDateTime.parse("2026-01-01T18:00:00+08:00").toInstant().toEpochMilli();
    @TempDir Path dir;
    AudienceLedgerService ledger;
    AssistantProperties privacy;
    SessionRecapService service;

    @BeforeEach void setup() {
        var properties = new NovaCoreProperties();
        properties.getLive().setLiveDataPath(dir.resolve("live.json").toString());
        privacy = new AssistantProperties();
        ledger = new AudienceLedgerService(properties, privacy);
        service = new SessionRecapService(ledger, new LiveSessionArchive(properties),
                new LiveDetailArchive(properties), privacy);
    }
    long at(int day) { return START + day * DAY; }
    Path detail(long start) throws Exception {
        return Files.createDirectories(dir.resolve("details/test-" + HOST + "-" + start));
    }
    void add(int day, List<Long> users, List<Long> speakers, String quality, double watched, double gift) throws Exception {
        addAt(at(day), users, speakers, quality, watched, gift);
    }
    void addAt(long start, List<Long> users, List<Long> speakers, String quality, double watched, double gift) throws Exception {
        var s = JSONObject.of("platform", "test", "uid", HOST, "startTime", start,
                "endTime", start + 3_600_000, "durationSeconds", 3600, "endReason", "NORMAL",
                "metrics", Map.of("watched_count", watched, "gift_value", gift,
                        "super_chat_value", 999, "guard_value", 777),
                "userSets", Map.of("enter_users", users), "anonymousCounts", Map.of());
        if (quality.equals("missing")) s.remove("userSets");
        if (quality.equals("unknown")) s.remove("anonymousCounts");
        if (quality.equals("anonymous")) s.put("anonymousCounts", Map.of("enter_users", 2));
        Files.writeString(dir.resolve("sessions.jsonl"), s.toJSONString() + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        Files.writeString(detail(start).resolve("detail.json"), "{}");
        StringBuilder danmu = new StringBuilder();
        for (long uid : speakers) danmu.append(JSONObject.of("uid", uid, "at", start + 1,
                "type", "DANMU", "uname", "Sample viewer").toJSONString()).append('\n');
        Files.writeString(detail(start).resolve("danmu.jsonl"), danmu);
    }
    void warmup() throws Exception {
        for (int i = 0; i < 10; i++) add(i, List.of(), List.of(), "complete", 10, 1);
    }
    SessionRecap.Result calculate(int day) throws Exception {
        ledger.updateStreamer("test", HOST);
        return service.calculate("test", HOST, at(day));
    }
    SessionRecap.Item item(SessionRecap.Result result, String key) {
        return java.util.stream.Stream.concat(result.facts().stream(), result.patterns().stream())
                .filter(i -> i.key().equals(key)).findFirst().orElseThrow(() -> new AssertionError("Missing " + key));
    }
    boolean has(SessionRecap.Result result, String key) {
        return java.util.stream.Stream.concat(result.facts().stream(), result.patterns().stream()).anyMatch(i -> i.key().equals(key));
    }
    double number(SessionRecap.Item item, String key) { return ((Number) item.values().get(key)).doubleValue(); }

    @Test @DisplayName("安装不足十场不报新人和回流，避免把所有人说成新朋友")
    void shortHistory() throws Exception {
        for (int i = 0; i < 10; i++) add(i, List.of(BASE + i), List.of(BASE + i), "complete", 10, 1);
        var result = calculate(9);
        assertTrue(has(result, "duration"));
        for (String key : List.of("newcomers", "returning_newcomers", "newcomer_speak_return")) assertFalse(has(result, key));
        add(10, List.of(BASE + 10), List.of(BASE + 10), "complete", 10, 1);
        assertEquals(1, number(item(calculate(10), "newcomers"), "newcomers"));
    }

    @Test @DisplayName("不完整名单不产生新人或回流，但此前见过的人仍不能再算新人")
    void incompleteListsAndStreamer() throws Exception {
        warmup();
        int day = 10;
        for (String quality : List.of("anonymous", "unknown", "missing")) {
            add(day, List.of(BASE, HOST), List.of(BASE, HOST), quality, 10, 1);
            var result = calculate(day++);
            assertFalse(has(result, "newcomers"));
            assertFalse(has(result, "returning_newcomers"));
            assertTrue(has(result, "watched_rank"));
        }
        add(13, List.of(BASE, BASE + 1, HOST), List.of(BASE, BASE + 1, HOST), "complete", 10, 1);
        var result = calculate(13);
        assertEquals(1, number(item(result, "newcomers"), "newcomers"));
        assertEquals(1, number(item(result, "newcomers"), "newcomerSpoke"));
        assertTrue(result.facts().stream().allMatch(i -> !i.people().contains(HOST)));
    }

    @Test @DisplayName("名次仅用最近十个合格场次，避免短场或陈旧纪录改变本场排名")
    void recentRanks() throws Exception {
        for (int i = 0; i < 12; i++) add(i, List.of(), List.of(), "complete", i == 0 ? 999 : i, i == 0 ? 999 : i);
        add(12, List.of(), List.of(), "anonymous", 9999, 9999);
        add(13, List.of(), List.of(), "complete", 10, 10);
        var result = calculate(13);
        for (String key : List.of("watched_rank", "gift_rank")) {
            assertEquals(2, number(item(result, key), "rank"));
            assertEquals(10, number(item(result, key), "outOf"));
            assertEquals(10, item(result, key).sample().sessions());
        }
        assertEquals(10, number(item(result, "gift_rank"), "gift"));
    }

    @Test @DisplayName("灯牌关注礼物榜热词复用封存报告数据，拒绝名单与主播不会被点名")
    void detailFactsAndPrivacy() throws Exception {
        warmup();
        add(10, List.of(BASE, HOST), List.of(BASE, HOST), "complete", 123, 4.5);
        Files.writeString(detail(at(10)).resolve("detail.json"), JSONObject.of(
                "gifts", List.of(Map.of("name", "粉丝团灯牌", "count", 7, "price", 0.1),
                        Map.of("name", "Sample gift", "count", 90, "price", 2)),
                "rankings", Map.of("gift_users", List.of(
                        Map.of("uid", HOST, "score", 100), Map.of("uid", BASE, "score", 90),
                        Map.of("uid", BASE + 1, "score", 8), Map.of("uid", BASE + 2, "score", 4),
                        Map.of("uid", BASE + 3, "score", 2), Map.of("uid", BASE + 4, "score", 1))),
                "words", Map.of("sample-a", 9, "sample-b", 7, "sample-c", 5, "sample-d", 1)).toJSONString());
        Files.writeString(detail(at(10)).resolve("events.jsonl"), "{\"t\":\"follow\"}\n{\"t\":\"gift\"}\n{\"t\":\"follow\"}\n");
        privacy.setExcludedUids(List.of(BASE));
        var result = calculate(10);
        assertEquals(7, number(item(result, "fan_club"), "fanClub"));
        assertEquals(2, number(item(result, "follows"), "follows"));
        assertEquals(List.of(BASE + 1, BASE + 2, BASE + 3), item(result, "top_gifters").people());
        assertEquals(List.of(8d, 4d, 2d), item(result, "top_gifters").values().get("gift"));
        assertEquals(List.of("sample-a", "sample-b", "sample-c"), item(result, "top_words").values().get("words"));
        assertEquals(0, number(item(result, "newcomers"), "newcomers"));
        assertTrue(result.facts().stream().allMatch(i -> !i.evidence().isEmpty()));
    }

    @Test @DisplayName("缺失数据不冒充零，查询不偷偷补账本")
    void unavailableAndReadOnly() throws Exception {
        add(0, List.of(), List.of(), "complete", 10, 1);
        assertTrue(service.calculate("test", HOST, at(0)).facts().isEmpty());
        var result = calculate(0);
        for (String key : List.of("fan_club", "follows", "top_gifters", "top_words")) assertFalse(has(result, key));
    }

    @Test @DisplayName("上场开口新人跨过不完整场次找回流，避免未登录采集改变回来名单")
    void returningNewcomers() throws Exception {
        warmup();
        add(10, List.of(BASE, BASE + 1), List.of(BASE), "complete", 10, 1);
        add(11, List.of(BASE + 2), List.of(BASE + 2), "anonymous", 10, 1);
        add(12, List.of(BASE, BASE + 2, HOST), List.of(), "complete", 10, 1);
        var returning = item(calculate(12), "returning_newcomers");
        assertEquals(1, number(returning, "previousSpoke"));
        assertEquals(1, number(returning, "returned"));
        assertEquals(List.of(BASE), returning.people());
    }

    List<Long> group(int offset, int n) {
        return java.util.stream.LongStream.range(BASE + offset, BASE + offset + n).boxed().toList();
    }
    List<Long> join(List<Long> a, List<Long> b) {
        var all = new ArrayList<>(a); all.addAll(b); return all;
    }

    @Test @DisplayName("只统计完整观察三场的新人，避免拿未来回流或未成熟批次制造规律")
    void matureCohortsAndReplay() throws Exception {
        warmup();
        var spoke = group(0, 30); var silent = group(100, 30);
        add(10, join(spoke, silent), spoke, "complete", 10, 1);
        add(11, List.of(BASE), List.of(), "complete", 10, 1);
        add(12, List.of(BASE + 100), List.of(), "anonymous", 10, 1);
        add(13, List.of(BASE + 1), List.of(), "complete", 10, 1);
        assertFalse(has(calculate(13), "newcomer_speak_return"));
        add(14, List.of(BASE + 200, BASE + 201), List.of(BASE + 200), "complete", 10, 1);
        var before = calculate(14);
        var pattern = item(before, "newcomer_speak_return");
        assertEquals(30, number(pattern, "spokeN"));
        assertEquals(30, number(pattern, "silentN"));
        assertEquals(2d / 30, number(pattern, "spokeReturn"), 1e-9);
        assertEquals(0, number(pattern, "silentReturn"));
        assertEquals(0.5, number(pattern, "todayRate"));
        assertEquals(0.5, number(pattern, "usualRate"));
        assertEquals("weak", pattern.strength());
        for (int i = 15; i < 20; i++) add(i, join(spoke, silent), spoke, "complete", 999, 999);
        var replay = calculate(14);
        assertEquals(before, replay);
        Files.writeString(dir.resolve("sessions.jsonl"), Files.readAllLines(dir.resolve("sessions.jsonl")).stream()
                .filter(line -> JSONObject.parseObject(line).getLongValue("startTime") <= at(14))
                .collect(java.util.stream.Collectors.joining("\n", "", "\n")));
        ledger.rebuild();
        assertEquals(replay, service.calculate("test", HOST, at(14)));
    }

    @Test @DisplayName("时段各三场才比较，六场且看过恰好两倍时才报强规律")
    void slotThresholds() throws Exception {
        for (int i = 0; i < 6; i++) {
            addAt(at(i) - 12 * 3_600_000L, List.of(), List.of(), "complete", 100, 5);
            add(i, List.of(), List.of(), "complete", 200, 10);
            var result = calculate(i);
            if (i < 2) assertFalse(has(result, "slot_compare"));
            else assertEquals(i < 5 ? "weak" : "strong", item(result, "slot_compare").strength());
        }
        var pattern = item(calculate(5), "slot_compare");
        assertEquals(6, number(pattern, "sessionsA"));
        assertEquals(6, number(pattern, "sessionsB"));
        assertEquals(100, number(pattern, "watchedA"));
        assertEquals(200, number(pattern, "watchedB"));
    }

    void pk(int day, double pkGift, double normalGift) throws Exception {
        long start = at(day);
        String events = JSONObject.of("t", "pk_open", "at", start, "pk", 1).toJSONString() + "\n"
                + JSONObject.of("t", "gift", "at", start + 1, "val", pkGift, "pay", 999).toJSONString() + "\n"
                + JSONObject.of("t", "pk_settle", "at", start + 1_800_000, "pk", 1).toJSONString() + "\n"
                + JSONObject.of("t", "box", "at", start + 1_800_000, "val", normalGift / 2, "pft", 999).toJSONString() + "\n"
                + JSONObject.of("t", "guard", "at", start + 1_800_001, "val", normalGift / 2).toJSONString() + "\n";
        Files.writeString(detail(start).resolve("events.jsonl"), events);
    }

    @Test @DisplayName("PK按区间分钟和礼物价值计，避免用付款或盲盒盈亏替代收入")
    void pkThresholdAndRates() throws Exception {
        for (int i = 0; i < 6; i++) {
            add(i, List.of(), List.of(), "complete", 1, 1); pk(i, 60, 30);
            var result = calculate(i);
            if (i < 2) assertFalse(has(result, "pk_share"));
            else assertEquals(i < 5 ? "weak" : "strong", item(result, "pk_share").strength());
        }
        var pattern = item(calculate(5), "pk_share");
        assertEquals(6, number(pattern, "pkSessions"));
        assertEquals(2, number(pattern, "pkGiftPerMin"));
        assertEquals(1, number(pattern, "normalGiftPerMin"));
    }

    @Test @DisplayName("常客至少三场且四成，最近五场缺席才列名，不完整场次不影响判断")
    void regularsThresholdsAndOrder() throws Exception {
        var regulars = group(0, 20);
        for (int i = 0; i < 5; i++) add(i, i < 3 ? regulars : List.of(BASE), List.of(), "complete", 1, 1);
        for (int i = 5; i < 10; i++) add(i, List.of(BASE + 10, HOST), List.of(), "complete", 1, 1);
        add(10, regulars, List.of(), "anonymous", 1, 1);
        var pattern = item(calculate(10), "regulars_absent");
        assertEquals(20, number(pattern, "regulars"));
        assertEquals(19, number(pattern, "absent"));
        assertEquals(BASE, pattern.people().get(0));
        assertEquals("strong", pattern.strength());
        privacy.setExcludedUids(group(0, 10));
        pattern = item(calculate(10), "regulars_absent");
        assertEquals(10, number(pattern, "regulars"));
        assertEquals("weak", pattern.strength());
        privacy.setExcludedUids(group(0, 11));
        assertFalse(has(calculate(10), "regulars_absent"));
    }

    @ParameterizedTest @ValueSource(ints = {29, 30, 59, 60})
    @DisplayName("新人样本不足三十不报规律，六十人且回流差两倍才报强规律")
    void newcomerBoundaries(int count) throws Exception {
        warmup();
        var spoke = group(0, count); var silent = group(100, count);
        add(10, join(spoke, silent), spoke, "complete", 1, 1);
        add(11, join(spoke.subList(0, count / 2), silent.subList(0, count / 4)), List.of(), "complete", 1, 1);
        add(12, List.of(), List.of(), "complete", 1, 1);
        add(13, group(200, 2), group(200, 1), "complete", 1, 1);
        var result = calculate(13);
        if (count < 30) assertFalse(has(result, "newcomer_speak_return"));
        else assertEquals(count < 60 ? "weak" : "strong", item(result, "newcomer_speak_return").strength());
    }

    @ParameterizedTest
    @CsvSource({"59,60,30,2,1,weak", "60,59,30,2,1,weak", "60,60,30,2,1,strong",
            "60,60,30,1.99,1,medium", "6,6,3,0,0,medium", "6,6,3,1,0,strong"})
    @DisplayName("强度先判样本再判两倍差距，避免小样本和双方零值夸大规律")
    void strengthBoundaries(long aN, long bN, int threshold, double a, double b, String expected) {
        assertEquals(expected, SessionRecapService.strength(aN, bN, threshold, a, b));
    }

    @ParameterizedTest @CsvSource({"9,9,none", "10,10,weak", "19,19,weak", "20,10,strong", "20,9,medium"})
    @DisplayName("常客未到十人不报规律，二十人时缺席一半才报强规律")
    void regularBoundaries(int count, int absent, String expected) throws Exception {
        var regulars = group(0, count);
        for (int i = 0; i < 5; i++) add(i, i < 3 ? regulars : List.of(), List.of(), "complete", 1, 1);
        for (int i = 5; i < 10; i++) add(i, regulars.subList(absent, count), List.of(), "complete", 1, 1);
        var result = calculate(9);
        if (expected.equals("none")) assertFalse(has(result, "regulars_absent"));
        else assertEquals(expected, item(result, "regulars_absent").strength());
    }

    @Test @DisplayName("PK重叠区间只计一次，缺结算场次不冒充已知的非PK时段")
    void overlappingPkAndUnsettledSession() throws Exception {
        for (int i = 0; i < 4; i++) {
            add(i, List.of(), List.of(), "complete", 1, 1);
            long start = at(i);
            String events = JSONObject.of("t", "pk_open", "at", start, "pk", 1).toJSONString() + "\n"
                    + JSONObject.of("t", "pk_open", "at", start + 600_000, "pk", 2).toJSONString() + "\n"
                    + JSONObject.of("t", "pk_settle", "at", start + 1_200_000, "pk", 1).toJSONString() + "\n"
                    + JSONObject.of("t", "pk_settle", "at", start + 1_800_000, "pk", 2).toJSONString() + "\n"
                    + JSONObject.of("t", "gift", "at", start + 1, "val", 60).toJSONString() + "\n";
            if (i == 3) events += JSONObject.of("t", "pk_open", "at", start + 2_000_000, "pk", 3).toJSONString() + "\n";
            Files.writeString(detail(start).resolve("events.jsonl"), events);
        }
        var pattern = item(calculate(3), "pk_share");
        assertEquals(3, number(pattern, "pkSessions"));
        assertEquals(2, number(pattern, "pkGiftPerMin"));
        assertEquals(0, number(pattern, "normalGiftPerMin"));
    }

    @Test @DisplayName("批量五场与各场独算完全相同，避免样稿回放共享状态后混入未来数据")
    void batchMatchesIndividualReplays() throws Exception {
        var regulars = group(0, 20);
        for (int day = 0; day < 25; day++) {
            var newcomers = group(100 + day * 100, 60);
            var users = join(day < 15 ? regulars : regulars.subList(10, 20), newcomers);
            if (day > 10) users = join(users, group(100 + (day - 1) * 100, 20));
            addAt(at(day) - (day % 2) * 12 * 3_600_000L, users, newcomers.subList(0, 30),
                    day == 22 ? "anonymous" : "complete", day + 1, day + 2);
            if (day % 2 == 0) pk(day, 60, 30);
        }
        privacy.setExcludedUids(List.of(BASE));
        ledger.updateStreamer("test", HOST);
        var starts = java.util.stream.IntStream.range(20, 25)
                .mapToObj(day -> at(day) - (day % 2) * 12 * 3_600_000L).toList();
        var expected = new ArrayList<SessionRecap.Result>();
        for (long start : starts) expected.add(service.calculate("test", HOST, start));
        assertEquals(expected, service.calculateBatch("test", HOST, starts));
        var reordered = List.of(starts.get(4), starts.get(0), at(99), starts.get(4));
        assertEquals(List.of(expected.get(4), expected.get(0), new SessionRecap.Result(List.of(), List.of()),
                expected.get(4)), service.calculateBatch("test", HOST, reordered));
        assertEquals(List.of(), service.calculateBatch("test", HOST, List.of()));
    }

    @Test @DisplayName("记录为空的事件文件可确认零关注，读取故障则报错以免显示假零")
    void emptyEventsAndReadFailure() throws Exception {
        add(0, List.of(), List.of(), "complete", 1, 1);
        Files.writeString(detail(at(0)).resolve("events.jsonl"), "");
        assertEquals(0, number(item(calculate(0), "follows"), "follows"));
        Files.delete(detail(at(0)).resolve("events.jsonl"));
        Files.createDirectory(detail(at(0)).resolve("events.jsonl"));
        assertThrows(java.io.IOException.class, () -> service.calculate("test", HOST, at(0)));
    }
}
