package org.frostnova.nova.core.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.analytics.StreamerInsights;
import org.frostnova.nova.core.assistant.AssistantProperties;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.model.LiveSession;
import org.frostnova.nova.core.model.DanmuRecord;
import org.frostnova.nova.core.lang.JsonlFiles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

class AudienceLedgerServiceTest {
    // All identities and nicknames in these archives are synthetic.
    static final long HOST = 910000001L, VIEWER = 920000001L, OTHER = 920000002L;
    @TempDir Path dir;
    AssistantProperties privacy;
    AudienceLedgerService ledger;
    NovaCoreProperties properties;

    @BeforeEach void setup() {
        properties = new NovaCoreProperties();
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

    @Test @DisplayName("共用恢复检查遇到失败后仍恢复残件，避免临时文件留下被拒绝观众")
    void sharedRecoveryResetsAfterFailure() throws Exception {
        append(session(1000, users(VIEWER))); seal(1000);
        ledger.updateStreamer("test", HOST);
        Path shard = dir.resolve("audience-ledger/test-" + HOST + "/1000.jsonl");
        assertThrows(java.io.IOException.class, () -> ledger.withRecoveredLedger(() -> {
            ledger.withRecoveredLedger(() -> assertEquals(1, ledger.sessions("test", HOST).size()));
            throw new java.io.IOException("synthetic failure");
        }));
        Path copy = shard.resolveSibling("1000.jsonl.tmp");
        Files.copy(shard, copy);
        ledger.sessions("test", HOST);
        assertFalse(Files.exists(copy));
    }

    @Test @DisplayName("连续两轮共用恢复检查仍清理新残件，避免临时副本留下被拒绝观众")
    void sharedRecoveryResetsBetweenRounds() throws Exception {
        append(session(1000, users(VIEWER))); seal(1000);
        ledger.withRecoveredLedger(() -> ledger.updateStreamer("test", HOST));
        Path shard = dir.resolve("audience-ledger/test-" + HOST + "/1000.jsonl");
        Path copy = shard.resolveSibling("1000.jsonl.tmp");
        Files.copy(shard, copy);
        ledger.withRecoveredLedger(() -> assertEquals(1, ledger.sessions("test", HOST).size()));
        assertFalse(Files.exists(copy), "第二轮必须重新恢复并清掉新残件");
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

    @Test @DisplayName("重建遇到读取失败保留原账本，避免一次失败丢掉全部历史")
    void failedRebuildKeepsPreviousLedger() throws Exception {
        append(session(1000, users(VIEWER))); seal(1000);
        ledger.updateStreamer("test", HOST);
        var before = snapshot();
        Files.createDirectory(detail(1000).resolve("events.jsonl"));
        assertThrows(java.io.IOException.class, ledger::rebuild);
        assertEquals(before, snapshot());
        assertFalse(Files.exists(dir.resolve("audience-ledger.building")));
        assertEquals(1, ledger.sessionRows("test", HOST, 1000).size());
    }

    @Test @DisplayName("重建写不进去时保留原账本并报错，避免磁盘故障清空全部历史")
    void failedRebuildWriteKeepsPreviousLedger() throws Exception {
        append(session(1000, users(VIEWER))); seal(1000);
        ledger.updateStreamer("test", HOST);
        var before = snapshot();
        Path temporary = dir.resolve("audience-ledger.building/test-" + HOST + "/1000.jsonl.tmp");
        try (var mocked = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            mocked.when(() -> Files.newBufferedWriter(temporary)).thenThrow(new java.io.IOException("write unavailable"));
            assertThrows(java.io.IOException.class, ledger::rebuild);
        }
        assertEquals(before, snapshot());
        assertFalse(Files.exists(dir.resolve("audience-ledger.building")));
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

    Path shard(long start) {
        return dir.resolve("audience-ledger/test-" + HOST + "/" + start + ".jsonl");
    }

    JSONObject header(long start) throws Exception {
        try (var reader = Files.newBufferedReader(shard(start))) {
            return JSON.parseObject(reader.readLine());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"uid\":{}}", "{\"uid\":\"viewer-x\"}",
            "{\"uid\":920000001,\"at\":\"later\"}"
    })
    void skipsMistypedDanmuLine(String invalid) throws Exception {
        append(session(1000, users(VIEWER))); seal(1000);
        append(session(2000, users(OTHER))); seal(2000);
        Files.writeString(detail(1000).resolve("danmu.jsonl"), invalid + "\n"
                + danmu(1100, "Sample viewer", "DANMU") + invalid + "\n"
                + "{\"uid\":null}\n{}\n");
        ledger.updateStreamer("test", HOST);
        assertEquals(2, header(1000).getLongValue("skippedLines"));
        assertEquals(0, header(2000).getLongValue("skippedLines"));
        assertEquals(1, ledger.sessionRows("test", HOST, 1000).get(0).danmuCount());
        var before = snapshot();
        ledger.rebuild();
        assertEquals(before, snapshot());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"uid\":{}}", "{\"uid\":\"viewer-x\"}",
            "{\"uid\":920000001,\"at\":1400,\"un\":\"Invalid name\",\"t\":\"gift\",\"val\":\"coins\"}",
            "{\"uid\":920000001,\"at\":1400,\"un\":\"Invalid name\",\"t\":\"guard\",\"val\":9,\"n\":\"many\"}",
            "{\"uid\":920000001,\"at\":1400,\"un\":\"Invalid name\",\"t\":\"guard\",\"val\":9,\"n\":1,\"lv\":{}}"
    })
    void skipsWholeMistypedEventLine(String invalid) throws Exception {
        append(session(1000, users(VIEWER))); seal(1000);
        Files.writeString(detail(1000).resolve("events.jsonl"),
                invalid + "\n" + event(1200, "gift", "Sample viewer", Map.of("val", 2)) + invalid + "\n");
        ledger.updateStreamer("test", HOST);
        assertEquals(2, header(1000).getLongValue("skippedLines"));
        var row = ledger.sessionRows("test", HOST, 1000).get(0);
        assertEquals("Sample viewer", row.nickname());
        assertEquals(0, new BigDecimal("2").compareTo(row.giftYuan()));
        assertTrue(row.guards().isEmpty());
        var before = snapshot();
        ledger.rebuild();
        assertEquals(before, snapshot());
    }

    @ParameterizedTest
    @ValueSource(strings = {"durationSeconds", "userSets"})
    void skipsSessionConversionFailure(String field) throws Exception {
        append(session(1000, users(VIEWER))); seal(1000);
        var invalid = session(2000, users(VIEWER));
        invalid.put(field, field.equals("userSets") ? Map.of("enter_users", List.of(VIEWER, "viewer-x")) : "long");
        append(invalid); seal(2000);
        append(session(3000, users(OTHER))); seal(3000);
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(AudienceLedgerService.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            ledger.updateStreamer("test", HOST);
            assertEquals(List.of(1000L, 3000L), ledger.sessions("test", HOST).stream().map(s -> s.startTime()).toList());
            assertEquals(1, appender.list.stream().filter(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN).count());
            assertEquals(0, header(1000).getLongValue("skippedLines"));
            var before = snapshot();
            ledger.rebuild();
            assertEquals(before, snapshot());
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test @DisplayName("真实写入的 PK 行没有观众身份，避免打过 PK 的主播账本永久更新失败")
    void pkEventsWithoutViewerDoNotBlockIndexing() throws Exception {
        append(session(1000, users(VIEWER)));
        var archive = new LiveDetailArchive(properties);
        archive.appendEvent("test", HOST, 1000, 1100, "pk_open", Map.of(
                "pk", 7, "tp", 1, "nc", 2, "ps", 1100, "pe", 1200,
                "op", List.of(Map.of("u", OTHER, "r", 9))));
        archive.appendEvent("test", HOST, 1000, 1200, "pk_settle", Map.of(
                "pk", 7, "er", 0, "en", 1200, "mv", 3, "mr", 1, "rs", 1,
                "op", List.of(Map.of("u", OTHER, "r", 9, "v", 2, "k", 2))));
        archive.appendEvent("test", HOST, 1000, 1300, "gift", Map.of("uid", VIEWER, "val", 2));
        seal(1000);
        ledger.updateStreamer("test", HOST);
        var row = ledger.sessionRows("test", HOST, 1000).get(0);
        assertEquals(0, new BigDecimal("2").compareTo(row.giftYuan()));
        var incremental = snapshot();
        ledger.rebuild();
        assertEquals(incremental, snapshot());
    }

    @Test @DisplayName("空发送者弹幕不属于任何观众，避免无身份弹幕卡住更新与重建")
    void nullDanmuViewerDoesNotBlockIndexing() throws Exception {
        append(session(1000, users(VIEWER)));
        var archive = new LiveDetailArchive(properties);
        archive.appendDanmu("test", HOST, 1000,
                new DanmuRecord(1100, null, null, "Sample message", DanmuRecord.Type.DANMU));
        archive.appendDanmu("test", HOST, 1000,
                new DanmuRecord(1200, VIEWER, "Sample viewer", "Sample message", DanmuRecord.Type.DANMU));
        seal(1000);
        ledger.updateStreamer("test", HOST);
        assertEquals(1, ledger.sessionRows("test", HOST, 1000).get(0).danmuCount());
        var incremental = snapshot();
        ledger.rebuild();
        assertEquals(incremental, snapshot());
    }

    @Test @DisplayName("两次更新间续播再封存也刷新场次，避免半场人数与重建对不上")
    void refreshesSessionResealedBetweenUpdates() throws Exception {
        var first = session(1000, users(VIEWER));
        first.put("endTime", 602000);
        append(first); seal(1000);
        ledger.updateStreamer("test", HOST);
        String fingerprint = header(1000).getString("sourceFingerprint");
        new LiveDetailArchive(properties).reopen("test", HOST, 1000);
        var last = session(1000, users(VIEWER, OTHER));
        last.put("durationSeconds", 1201);
        last.put("endTime", 1202000);
        append(last);
        seal(1000);
        Files.delete(detail(1000).resolve("resumed"));
        ledger.updateStreamer("test", HOST);
        assertEquals(2, ledger.sessionRows("test", HOST, 1000).size());
        assertNotEquals(fingerprint, header(1000).getString("sourceFingerprint"));
        var incremental = snapshot();
        ledger.rebuild();
        assertEquals(incremental, snapshot());
    }

    @Test @DisplayName("跳过半行并逐场计数，避免一条损坏的弹幕或事件阻断所有历史")
    void skipsMalformedDetailLinesAndRecordsCount() throws Exception {
        append(session(1000, users(VIEWER))); seal(1000);
        append(session(2000, users(OTHER))); seal(2000);
        Files.writeString(detail(1000).resolve("danmu.jsonl"),
                "{\"uid\":\n" + danmu(1100, "Sample viewer", "DANMU") + "{\"at\":");
        Files.writeString(detail(1000).resolve("events.jsonl"),
                "broken\n" + event(1200, "gift", "Sample viewer", Map.of("val", 2)));
        ledger.updateStreamer("test", HOST);
        assertEquals(3, header(1000).getLongValue("skippedLines"));
        assertEquals(0, header(2000).getLongValue("skippedLines"));
        var row = ledger.sessionRows("test", HOST, 1000).get(0);
        assertEquals(1, row.danmuCount());
        assertEquals(0, new BigDecimal("2").compareTo(row.giftYuan()));
        var incremental = snapshot();
        ledger.rebuild();
        assertEquals(incremental, snapshot());
    }

    @Test @DisplayName("跳过损坏或非法场次行，避免一个主播的坏归档挡住所有主播")
    void skipsInvalidSessionLines() throws Exception {
        for (var invalid : List.of(session(2000, users(VIEWER)), session(3000, users(VIEWER)),
                session(4000, users(VIEWER)), session(5000, users(VIEWER)))) {
            if (invalid.getLongValue("startTime") == 2000) invalid.put("platform", "../test");
            else if (invalid.getLongValue("startTime") == 3000) invalid.put("uid", 0);
            else if (invalid.getLongValue("startTime") == 4000) invalid.put("uid", -1);
            else invalid.remove("platform");
            append(invalid);
        }
        append(session(1000, users(VIEWER))); seal(1000);
        var another = session(6000, users(OTHER));
        another.put("uid", HOST + 1); append(another);
        Path anotherDetail = Files.createDirectories(dir.resolve("details/test-" + (HOST + 1) + "-6000"));
        Files.writeString(anotherDetail.resolve("detail.json"), "{}");
        ledger.updateStreamer("test", HOST);
        assertEquals(1, ledger.sessions("test", HOST).size());
        ledger.rebuild();
        assertEquals(1, ledger.sessions("test", HOST).size());
        assertEquals(1, ledger.sessionRows("test", HOST + 1, 6000).size());
    }

    @Test @DisplayName("场次归档半行不吞掉后续完整场次，避免一个坏行让历史永远停在旧场")
    void skipsMalformedSessionLines() throws Exception {
        Files.writeString(dir.resolve("sessions.jsonl"), "{\"platform\":\n");
        append(session(1000, users(VIEWER))); seal(1000);
        Files.writeString(dir.resolve("sessions.jsonl"), "null\n{\"uid\":", StandardOpenOption.APPEND);
        ledger.updateStreamer("test", HOST);
        assertEquals(1, ledger.sessions("test", HOST).size());
        var incremental = snapshot();
        ledger.rebuild();
        assertEquals(incremental, snapshot());
    }

    @Test @DisplayName("未登录身份不落盘，避免观众零被其他文件读取方当作真人")
    void zeroViewerIsNeverWritten() throws Exception {
        append(session(1000, users(0, VIEWER))); seal(1000);
        ledger.updateStreamer("test", HOST);
        assertFalse(Files.readString(shard(1000)).contains("\"viewerUid\":0"));
        ledger.rebuild();
        assertFalse(Files.readString(shard(1000)).contains("\"viewerUid\":0"));
    }

    @Test @DisplayName("拒绝名单从首次入账就不落盘，避免拒绝分析的观众仍留下可读记录")
    void excludedViewerIsNeverWritten() throws Exception {
        privacy.setExcludedUids(List.of(VIEWER));
        append(session(1000, users(VIEWER, OTHER))); seal(1000);
        ledger.updateStreamer("test", HOST);
        assertFalse(Files.readString(shard(1000)).contains("\"viewerUid\":" + VIEWER));
        ledger.rebuild();
        assertFalse(Files.readString(shard(1000)).contains("\"viewerUid\":" + VIEWER));
    }

    @Test @DisplayName("清除无同名场次的临时副本，避免中断后留下拒绝分析观众的记录")
    void removesOrphanTemporaryFiles() throws Exception {
        append(session(1000, users(VIEWER))); seal(1000);
        ledger.updateStreamer("test", HOST);
        Path orphan = shard(1000).resolveSibling("9999.jsonl.tmp");
        Files.copy(shard(1000), orphan);
        privacy.setExcludedUids(List.of(VIEWER));
        ledger.updateStreamer("test", HOST);
        assertFalse(Files.exists(orphan));
    }

    @Test @DisplayName("拒绝名单缩减明确要求重建，避免恢复分析后历史仍静默缺人")
    void shrinkingExclusionsRequiresRebuild() throws Exception {
        privacy.setExcludedUids(List.of(VIEWER));
        append(session(1000, users(VIEWER))); seal(1000);
        ledger.updateStreamer("test", HOST);
        privacy.setExcludedUids(List.of());
        var error = assertThrows(java.io.IOException.class, () -> ledger.updateStreamer("test", HOST));
        assertTrue(error.getMessage().contains("重建"));
        assertTrue(ledger.sessionRows("test", HOST, 1000).isEmpty());
        ledger.rebuild();
        ledger.updateStreamer("test", HOST);
        assertEquals(1, ledger.sessionRows("test", HOST, 1000).size());
    }

    @Test @DisplayName("读明细期间续播也不发布半场，避免读取结束前的封存状态被忽略")
    void checksSealAgainAfterReadingDetails() throws Exception {
        append(session(1000, users(VIEWER))); seal(1000);
        Path events = detail(1000).resolve("events.jsonl");
        Path resumed = detail(1000).resolve("resumed");
        Files.writeString(events, event(1200, "follow", "Sample viewer", Map.of()));
        try (var mocked = mockStatic(JsonlFiles.class, CALLS_REAL_METHODS)) {
            mocked.when(() -> JsonlFiles.lines(events)).thenAnswer(call -> {
                java.util.stream.Stream<String> lines = (java.util.stream.Stream<String>) call.callRealMethod();
                return lines.onClose(() -> {
                    try { Files.writeString(resumed, ""); }
                    catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
                });
            });
            ledger.updateStreamer("test", HOST);
        }
        assertTrue(Files.exists(resumed));
        assertFalse(Files.exists(shard(1000)));
        assertTrue(ledger.sessions("test", HOST).isEmpty());
        Files.delete(resumed);
        ledger.updateStreamer("test", HOST);
        assertEquals(1, ledger.sessionRows("test", HOST, 1000).size());
    }

    @Test @DisplayName("昵称投影再次过滤当前拒绝名单，避免旧账本昵称流入样稿")
    void sessionNicknamesRespectCurrentExclusions() throws Exception {
        append(session(1000, users(VIEWER, OTHER))); seal(1000);
        Files.writeString(detail(1000).resolve("danmu.jsonl"),
                JSONObject.of("uid", VIEWER, "uname", "合成观众甲", "type", "DANMU").toJSONString() + "\n"
                + JSONObject.of("uid", OTHER, "uname", "合成观众乙", "type", "DANMU").toJSONString() + "\n");
        ledger.updateStreamer("test", HOST);
        var wanted = java.util.Set.of(VIEWER, OTHER);
        assertEquals(Map.of(VIEWER, "合成观众甲", OTHER, "合成观众乙"), ledger.sessionNicknames("test", HOST, 1000, wanted));
        privacy.setExcludedUids(List.of(VIEWER));
        assertEquals(Map.of(OTHER, "合成观众乙"), ledger.sessionNicknames("test", HOST, 1000, wanted));
    }

}
