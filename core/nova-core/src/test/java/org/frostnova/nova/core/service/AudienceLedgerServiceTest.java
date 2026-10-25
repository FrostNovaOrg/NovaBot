package org.frostnova.nova.core.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.analytics.StreamerInsights;
import org.frostnova.nova.core.assistant.AssistantProperties;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.model.LiveSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

class AudienceLedgerServiceTest {
    // All identities and nicknames in these archives are synthetic.
    static final long HOST = 910000001L, VIEWER = 920000001L, OTHER = 920000002L;
    @TempDir Path dir;
    AssistantProperties privacy;
    AudienceLedgerService ledger;

    @BeforeEach void setup() {
        var properties = new NovaCoreProperties();
        properties.getLive().setLiveDataPath(dir.resolve("live.json").toString());
        privacy = new AssistantProperties();
        ledger = new AudienceLedgerService(properties, privacy);
    }

    JSONObject session(long start, Map<String, List<Long>> sets) {
        var s = JSONObject.of("platform", "test", "uid", HOST, "startTime", start,
                "durationSeconds", 601, "endReason", "NORMAL", "userSets", sets,
                "anonymousCounts", Map.of());
        return s;
    }
    void append(JSONObject s) throws Exception {
        Files.writeString(dir.resolve("sessions.jsonl"), s.toJSONString() + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
    Path detail(long start) throws Exception {
        return Files.createDirectories(dir.resolve("details/test-" + HOST + "-" + start));
    }
    void seal(long start) throws Exception { Files.writeString(detail(start).resolve("detail.json"), "{}"); }
    Map<String, List<Long>> users(long... uids) {
        return Map.of("enter_users", java.util.Arrays.stream(uids).boxed().toList());
    }

    @Test @DisplayName("续播不入账，封存后入账，避免把半场当整场")
    void waitsForSeal() throws Exception {
        append(session(1000, users(VIEWER)));
        ledger.updateStreamer("test", HOST);
        assertTrue(ledger.sessions("test", HOST).isEmpty());
        seal(1000);
        Files.writeString(detail(1000).resolve("resumed"), "");
        ledger.updateStreamer("test", HOST);
        assertTrue(ledger.sessions("test", HOST).isEmpty());
        Files.delete(detail(1000).resolve("resumed"));
        ledger.updateStreamer("test", HOST);
        assertEquals(1, ledger.sessionRows("test", HOST, 1000).size());
    }

    @Test @DisplayName("入账后再次续播会撤下旧记录，避免继续展示中途下播的半场")
    void withdrawsPreviouslyIndexedResumedSession() throws Exception {
        append(session(1000, users(VIEWER))); seal(1000);
        ledger.updateStreamer("test", HOST);
        Files.writeString(detail(1000).resolve("resumed"), "");
        ledger.updateStreamer("test", HOST);
        assertTrue(ledger.sessions("test", HOST).isEmpty());
        assertTrue(ledger.sessionRows("test", HOST, 1000).isEmpty());
        assertTrue(ledger.ledgerStart("test", HOST).isEmpty());
        Files.delete(detail(1000).resolve("resumed"));
        append(session(1000, users(VIEWER, OTHER)));
        ledger.updateStreamer("test", HOST);
        assertEquals(2, ledger.sessionRows("test", HOST, 1000).size());
    }

    @Test @DisplayName("清除中断写入的残件，避免拒绝名单观众仍残留在副本中")
    void removesInterruptedCopiesBeforePrivacyUpdate() throws Exception {
        append(session(1000, users(VIEWER))); seal(1000);
        ledger.updateStreamer("test", HOST);
        Path shard = dir.resolve("audience-ledger/test-" + HOST + "/1000.jsonl");
        Files.copy(shard, shard.resolveSibling("1000.jsonl.tmp"));
        Path staging = Files.createDirectories(dir.resolve("audience-ledger.building/test-" + HOST));
        Files.copy(shard, staging.resolve("1000.jsonl"));
        privacy.setExcludedUids(List.of(VIEWER));
        ledger.updateStreamer("test", HOST);
        assertFalse(Files.exists(dir.resolve("audience-ledger.building")));
        assertTrue(snapshot().values().stream().noneMatch(s -> s.contains("\"viewerUid\":" + VIEWER)));
    }

    @Test @DisplayName("重建遇到坏归档保留原账本，避免一次失败丢掉全部历史")
    void failedRebuildKeepsPreviousLedger() throws Exception {
        append(session(1000, users(VIEWER))); seal(1000);
        ledger.updateStreamer("test", HOST);
        var before = snapshot();
        Files.writeString(detail(1000).resolve("events.jsonl"), "broken");
        assertThrows(java.io.IOException.class, ledger::rebuild);
        assertEquals(before, snapshot());
        assertFalse(Files.exists(dir.resolve("audience-ledger.building")));
        assertEquals(1, ledger.sessionRows("test", HOST, 1000).size());
    }

    @Test @DisplayName("上舰单位缺失保留未知月数，避免虚报订阅期限")
    void unknownGuardUnitStaysUnknown() throws Exception {
        append(session(1000, users(VIEWER))); seal(1000);
        Files.writeString(detail(1000).resolve("events.jsonl"),
                event(1200, "guard", "Sample guard", Map.of("n", 1, "lv", 3, "val", 5)));
        ledger.updateStreamer("test", HOST);
        var row = ledger.sessionRows("test", HOST, 1000).get(0);
        assertNull(row.guardMonths());
        assertNull(row.guards().get(0).months());
        assertEquals(1, row.guards().get(0).count());
    }

    @Test @DisplayName("重复场次用最后一行，避免场数与人数翻倍")
    void latestSessionWins() throws Exception {
        append(session(1000, users(OTHER)));
        append(session(1000, users(VIEWER)));
        seal(1000);
        ledger.updateStreamer("test", HOST);
        assertEquals(1, ledger.sessions("test", HOST).size());
        assertEquals(List.of(VIEWER), ledger.sessionRows("test", HOST, 1000).stream().map(r -> r.viewerUid()).toList());
    }

    @Test @DisplayName("旧场缺名单保留未知，避免把后来的所有人算成新人")
    void missingListsAreNotEmptyAudience() throws Exception {
        append(session(1000, null)); seal(1000);
        append(session(2000, users())); seal(2000);
        ledger.updateStreamer("test", HOST);
        var sessions = ledger.sessions("test", HOST);
        assertEquals(2, sessions.size());
        assertFalse(sessions.get(0).hasUserSets());
        assertFalse(sessions.get(0).qualified());
        assertEquals("missing", sessions.get(0).listQuality());
        assertTrue(sessions.get(1).hasUserSets());
        assertTrue(sessions.get(1).qualified());
        assertEquals(1000L, ledger.ledgerStart("test", HOST).orElseThrow());
    }

    @Test @DisplayName("过滤未登录身份，避免出现每场都来的观众零")
    void excludesZeroAndMarksStreamer() throws Exception {
        append(session(1000, users(0, HOST, VIEWER))); seal(1000);
        ledger.updateStreamer("test", HOST);
        var rows = ledger.sessionRows("test", HOST, 1000);
        assertEquals(2, rows.size());
        assertTrue(rows.stream().filter(r -> r.viewerUid() == HOST).findFirst().orElseThrow().streamer());
        assertFalse(rows.stream().filter(r -> r.viewerUid() == VIEWER).findFirst().orElseThrow().streamer());
    }

    @Test @DisplayName("拒绝名单过滤新旧记录，避免拒绝分析的观众仍被点名")
    void purgesExcludedAcrossStreamers() throws Exception {
        privacy.setExcludedUids(List.of(OTHER));
        append(session(1000, users(VIEWER, OTHER))); seal(1000);
        ledger.updateStreamer("test", HOST);
        assertEquals(1, ledger.sessionRows("test", HOST, 1000).size());
        privacy.setExcludedUids(List.of(OTHER, VIEWER));
        ledger.updateStreamer("test", HOST + 1);
        assertTrue(ledger.sessionRows("test", HOST, 1000).isEmpty());
        assertTrue(ledger.viewerHistory("test", HOST, VIEWER).visits().isEmpty());
        assertTrue(snapshot().values().stream().noneMatch(s -> s.contains("\"viewerUid\":" + VIEWER)));
    }

    @Test @DisplayName("增量与删后重建一致，避免同一场的数字前后变化")
    void rebuildMatchesIncrementalIncludingLateOlderSession() throws Exception {
        append(session(3000, users(VIEWER))); seal(3000);
        ledger.updateStreamer("test", HOST);
        append(session(1000, users(VIEWER, OTHER))); seal(1000);
        append(session(2000, users(OTHER))); seal(2000);
        ledger.updateStreamer("test", HOST);
        var history = ledger.viewerHistory("test", HOST, VIEWER);
        assertEquals(1000L, history.ledgerStart());
        assertEquals(2, history.visits().size());
        assertEquals(1, history.visits().get(1).previousSessions());
        assertEquals(1000L, history.visits().get(1).firstSession());
        assertEquals(1000L, history.visits().get(1).previousSession());
        assertNull(history.visits().get(0).previousSession());
        var incremental = snapshot();
        ledger.rebuild();
        assertEquals(incremental, snapshot());
        try (var files = Files.walk(dir.resolve("audience-ledger"))) {
            for (var path : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
        }
        ledger.rebuild();
        assertEquals(incremental, snapshot());
    }
    Map<String, String> snapshot() throws Exception {
        Map<String, String> result = new TreeMap<>();
        try (var paths = Files.walk(dir.resolve("audience-ledger"))) {
            for (Path p : paths.filter(Files::isRegularFile).toList())
                result.put(dir.resolve("audience-ledger").relativize(p).toString(), Files.readString(p));
        }
        return result;
    }

    @Test @DisplayName("按写入格式聚合开口送礼上舰和最后昵称，避免小结张冠李戴或算错金额")
    void readsWriterFieldsAndKeepsOnlyListedPeople() throws Exception {
        append(session(1000, Map.of("enter_users", List.of(VIEWER, OTHER), "like_users", List.of(VIEWER),
                "gift_users", List.of(HOST))));
        var d = detail(1000);
        Files.writeString(d.resolve("detail.json"), JSONObject.of("rankings", Map.of("enter_users", List.of(
                Map.of("uid", VIEWER, "uname", "Sample entrant"), Map.of("uid", OTHER, "uname", "Sample silent")))).toJSONString());
        Files.writeString(d.resolve("danmu.jsonl"),
                danmu(1400, "Sample latest", "EMOJI") + danmu(1100, "Sample earlier", "DANMU")
                        + danmu(1300, "Sample middle", "SUPER_CHAT")
                        + JSONObject.of("uid", 930000001L, "at", 1500, "uname", "Sample unlisted").toJSONString() + "\n");
        Files.writeString(d.resolve("events.jsonl"),
                event(1200, "follow", "Sample follower", Map.of())
                        + event(1250, "gift", "Sample gift", Map.of("val", 0.1, "pay", 9))
                        + event(1260, "box", "Sample box", Map.of("val", 0.2, "pft", -5))
                        + event(1270, "guard", "Sample guard", Map.of("val", 10, "lv", 3, "n", 2, "u", "月"))
                        + event(1280, "guard", "Sample annual", Map.of("val", 20, "lv", 2, "n", 1, "u", "年")));
        ledger.updateStreamer("test", HOST);
        var rows = ledger.sessionRows("test", HOST, 1000);
        assertEquals(3, rows.size());
        var row = rows.stream().filter(r -> r.viewerUid() == VIEWER).findFirst().orElseThrow();
        assertEquals("Sample latest", row.nickname());
        assertTrue(row.entered()); assertTrue(row.liked()); assertTrue(row.spoke()); assertTrue(row.followed());
        assertEquals(1100L, row.firstDanmuAt()); assertEquals(3, row.danmuCount());
        assertEquals(0, new BigDecimal("30.3").compareTo(row.giftYuan()));
        assertEquals(2, row.guardLevel()); assertEquals(14L, row.guardMonths());
        assertEquals(2, row.guards().size());
        assertEquals("Sample silent", rows.stream().filter(r -> r.viewerUid() == OTHER).findFirst().orElseThrow().nickname());
    }
    String danmu(long at, String name, String type) {
        return JSONObject.of("at", at, "uid", VIEWER, "uname", name, "type", type).toJSONString() + "\n";
    }
    String event(long at, String type, String name, Map<String, Object> fields) {
        var row = JSONObject.of("at", at, "uid", VIEWER, "un", name, "t", type);
        row.putAll(fields); return row.toJSONString() + "\n";
    }

    @Test @DisplayName("合格条件和名单质量复用主播页口径，避免短场事故场混入规律统计")
    void qualificationAndQuality() throws Exception {
        for (int i = 1; i <= 4; i++) {
            var s = session(i * 1000L, users(VIEWER));
            if (i == 1) s.put("durationSeconds", 600);
            if (i == 2) s.put("endReason", "UNCLOSED");
            if (i == 3) s.remove("anonymousCounts");
            if (i == 4) s.put("anonymousCounts", Map.of("enter_users", 2));
            append(s); seal(i * 1000L);
        }
        ledger.updateStreamer("test", HOST);
        var sessions = ledger.sessions("test", HOST);
        assertEquals(List.of(false, false, true, true), sessions.stream().map(s -> s.qualified()).toList());
        assertEquals(List.of("complete", "complete", "unknown", "anonymous"), sessions.stream().map(s -> s.listQuality()).toList());
        assertEquals(StreamerInsights.listQuality(JSON.parseObject(session(1000, users(VIEWER)).toJSONString(), LiveSession.class)),
                sessions.get(0).listQuality());
    }

    @Test @DisplayName("已入账场次不重读明细，避免每次更新重扫全部弹幕")
    void indexedSessionsAreNotReprocessed() throws Exception {
        append(session(1000, users(VIEWER))); seal(1000);
        ledger.updateStreamer("test", HOST);
        var before = snapshot();
        Files.writeString(detail(1000).resolve("danmu.jsonl"), "invalid");
        ledger.updateStreamer("test", HOST);
        assertEquals(before, snapshot());
    }
}
