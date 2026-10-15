package org.frostnova.nova.bilibili.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.core.read.ListAppender;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.event.live.BilibiliDanmuEvent;
import org.frostnova.nova.bilibili.event.live.BilibiliSuperChatEvent;
import org.frostnova.nova.bilibili.health.BilibiliRiskMetrics;
import org.frostnova.nova.bilibili.model.BilibiliLiveMetric;
import org.frostnova.nova.bilibili.model.BilibiliUserInfo;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import org.frostnova.nova.core.service.DefaultLiveDataService;
import org.frostnova.nova.core.service.LiveDetailArchive;
import org.frostnova.nova.core.util.HttpUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import ch.qos.logback.core.AppenderBase;
import ch.qos.logback.classic.spi.ILoggingEvent;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BilibiliGapRecoveryTest {
    private static final LiveStreamerInfo ROOM = new LiveStreamerInfo(3000003L, "测试主播", 4000004L);
    private static final Instant FROM = Instant.parse("2026-08-01T12:00:58.500Z");
    private static final Instant TO = FROM.plusSeconds(5);
    private static final long START = FROM.minusSeconds(600).toEpochMilli();
    @TempDir Path dir;
    private final HttpUtil http = mock(HttpUtil.class);
    private final NovaBilibiliProperties properties = new NovaBilibiliProperties();
    private DefaultLiveDataService data;
    private LiveDetailArchive archive;
    private BilibiliLiveStatsAggregator stats;
    private BilibiliApiUtil api;
    private JSONArray danmus;
    private JSONArray chats;

    @BeforeEach
    void setup() {
        NovaCoreProperties core = new NovaCoreProperties();
        core.getLive().setLiveDataPath(dir.resolve("data.json").toString());
        data = new DefaultLiveDataService(core);
        data.setLiveStartTime("bilibili", ROOM.getUid(), START);
        data.setLiveStatus("bilibili", ROOM.getUid(), true);
        archive = new LiveDetailArchive(core);
        stats = new BilibiliLiveStatsAggregator(data, archive);
        api = new BilibiliApiUtil(http, properties, new BilibiliRiskMetrics());
        danmus = new JSONArray();
        chats = new JSONArray();
        when(http.getJson(anyString(), any())).thenAnswer(call -> {
            JSONObject body = new JSONObject();
            body.put(call.<String>getArgument(0).contains("gethistory") ? "room" : "list",
                    call.<String>getArgument(0).contains("gethistory") ? danmus : chats);
            return JSONObject.of("code", 0, "data", body);
        });
    }

    @Test
    void missingDanmusReachCountsOriginalMinuteAndArchive() throws Exception {
        // 用户故障：断线期间的弹幕漏计、漏存，或曲线错误地记在重连那一分钟。
        for (int offset : new int[]{-8, -7, -6, 0, 1, 2, 3, 4, 6, 7}) {
            danmus.add(danmu(offset, "测试发言" + offset));
        }
        stats.onDanmu(event(0, "测试发言0"));
        recover();
        assertEquals(5, metric(BilibiliLiveMetric.DANMU_COUNT));
        assertEquals(2, data.getLiveMetricUserCount("bilibili", ROOM.getUid(), BilibiliLiveMetric.DANMU_USERS));
        assertEquals(2.0, data.getLiveSeries("bilibili", ROOM.getUid(), BilibiliLiveMetric.DANMU_COUNT)
                .get(FROM.toEpochMilli() / 60000 * 60000));
        var records = archive.readDanmu("bilibili", ROOM.getUid(), START);
        assertEquals(5, records.size());
        assertEquals(4, records.stream().filter(org.frostnova.nova.core.model.DanmuRecord::recovered).count());
        assertFalse(records.get(0).recovered());
        try (var files = Files.walk(dir)) {
            List<String> lines = Files.readAllLines(files.filter(p -> p.getFileName().toString().equals("danmu.jsonl"))
                    .findFirst().orElseThrow());
            assertEquals(4, lines.stream().map(JSONObject::parseObject)
                    .filter(j -> j.getBooleanValue("recovered")).count());
        }
        assertTrue(!data.getLiveWordFrequencies("bilibili", ROOM.getUid()).isEmpty());
    }

    @Test
    void boundarySecondDoesNotCountRealtimeTwice() throws Exception {
        // 用户故障：缺口起止边界所在秒的实时弹幕被补录重复计算。
        stats.onDanmu(event(0, "边界甲"));
        stats.onDanmu(event(5, "边界乙"));
        danmus.add(danmu(0, "边界甲"));
        danmus.add(danmu(5, "边界乙"));
        danmus.add(danmu(1, "中间"));
        recover();
        recover();
        assertEquals(3, metric(BilibiliLiveMetric.DANMU_COUNT));
    }

    @Test
    void responseFieldNamesAreVisibleWithoutPrivateValues() throws Exception {
        // 用户故障：回包使用不同的编号字段名，日志却误报没有编号，无法核实接口能力。
        JSONObject item = danmu(2, "private-text");
        item.remove("msg_id");
        item.put("dm_id", 9000009L);
        item.put("history_key", "private-key");
        danmus.add(item);
        Logger logger = (Logger) LoggerFactory.getLogger(BilibiliGapRecovery.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            recover();
            String message = logs.list.get(0).getFormattedMessage();
            assertTrue(message.contains("dm_id"));
            assertTrue(message.contains("history_key"));
            assertFalse(message.contains("private-text"));
            assertFalse(message.contains("private-key"));
            assertFalse(message.contains("测试观众"));
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }
    }

    @Test
    void failureIsOnceAndLogsSafeReason() throws Exception {
        // 用户故障：网络失败仍多次重试，并把私人响应写进日志。
        when(http.getJson(contains("gethistory"), any())).thenThrow(new IllegalStateException("private-text"));
        Logger logger = (Logger) LoggerFactory.getLogger("org.frostnova.nova.bilibili.service.BilibiliGapRecovery");
        ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            recover();
            verify(http, times(1)).getJson(contains("gethistory"), any());
            verify(http, times(1)).getJson(contains("getMessageList"), any());
            assertTrue(logs.list.stream().anyMatch(e -> e.getFormattedMessage().contains("失败")));
            assertTrue(logs.list.stream().anyMatch(e -> e.getFormattedMessage().contains("NetworkException")));
            assertFalse(logs.list.stream().anyMatch(e -> e.getFormattedMessage().contains("private-text")));
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }
    }

    @Test
    void recoveredDataNeverPublishesRealtimeEventsOrUsesConnectGate() throws Exception {
        // 用户故障：补录发言再次触发外部协议、指令或推送，或者占住其它房间的建连队列。
        danmus.add(danmu(2, "只入统计"));
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        BilibiliConnectGate gate = mock(BilibiliConnectGate.class);
        CountDownLatch completed = new CountDownLatch(1);
        Logger logger = (Logger) LoggerFactory.getLogger(BilibiliGapRecovery.class);
        AppenderBase<ILoggingEvent> appender = completionAppender(completed);
        logger.addAppender(appender);
        try (BilibiliGapRecovery recovery = new BilibiliGapRecovery(api, properties, data, stats)) {
            BilibiliLiveRoomConnector connector = connector(recovery, publisher, gate);
            ReflectionTestUtils.setField(connector, "authenticatedOnce", true);
            ReflectionTestUtils.setField(connector, "disconnectedAt", FROM);
            authenticate(connector);
            assertTrue(completed.await(15, TimeUnit.SECONDS), "补录应当完成");
            assertEquals(1, metric(BilibiliLiveMetric.DANMU_COUNT));
            verifyNoInteractions(publisher, gate);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void firstAuthenticationNeverRecoversStartupGap() {
        // 用户故障：首次启动认证把停机期间的数据也混入重连补录。
        try (BilibiliGapRecovery recovery = new BilibiliGapRecovery(api, properties, data, stats)) {
            BilibiliGapRecovery observed = spy(recovery);
            BilibiliLiveRoomConnector connector = connector(observed, mock(ApplicationEventPublisher.class),
                    mock(BilibiliConnectGate.class));
            ReflectionTestUtils.setField(connector, "disconnectedAt", FROM);
            authenticate(connector);
            verify(observed, never()).recover(any(), any(), any());
            verifyNoInteractions(http);
        }
    }

    @Test
    void slowRecoveryDoesNotBlockAuthentication() throws Exception {
        // 用户故障：补录接口缓慢时收包线程被 HTTP 请求卡住。
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        when(http.getJson(contains("gethistory"), any())).thenAnswer(call -> {
            entered.countDown();
            assertTrue(release.await(15, TimeUnit.SECONDS), "请求应当获准返回");
            return JSONObject.of("code", 0, "data", JSONObject.of("room", danmus));
        });
        Logger logger = (Logger) LoggerFactory.getLogger(BilibiliGapRecovery.class);
        AppenderBase<ILoggingEvent> appender = completionAppender(completed);
        logger.addAppender(appender);
        try (BilibiliGapRecovery recovery = new BilibiliGapRecovery(api, properties, data, stats)) {
            BilibiliLiveRoomConnector connector = connector(recovery, mock(ApplicationEventPublisher.class),
                    mock(BilibiliConnectGate.class));
            ReflectionTestUtils.setField(connector, "authenticatedOnce", true);
            ReflectionTestUtils.setField(connector, "disconnectedAt", FROM);
            try {
                assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () -> authenticate(connector));
                assertTrue(entered.await(15, TimeUnit.SECONDS), "后台请求应当开始");
            } finally {
                release.countDown();
            }
            assertTrue(completed.await(15, TimeUnit.SECONDS), "后台请求应当完成");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void superChatsOnlyRecoverNewIdsInsideGap() throws Exception {
        // 用户故障：缺口里的醒目留言漏计，或收到过的编号再次计数。
        BilibiliSuperChatEvent live = new BilibiliSuperChatEvent(ROOM, user(), "实时留言", 30.0, FROM);
        live.setMessageId(100L);
        stats.onSuperChat(live);
        chats.add(chat(100L, 1, 30));
        chats.add(chat(101L, 2, 50));
        chats.add(chat(102L, -1, 100));
        recover();
        recover();
        assertEquals(2, metric(BilibiliLiveMetric.SUPER_CHAT_COUNT));
        assertEquals(80, metric(BilibiliLiveMetric.SUPER_CHAT_VALUE));
        assertEquals(80, data.getLiveUserMetric("bilibili", ROOM.getUid(), BilibiliLiveMetric.SUPER_CHAT_USERS, 5000005L));
        var records = archive.readDanmu("bilibili", ROOM.getUid(), START);
        assertEquals(2, records.size());
        assertEquals(org.frostnova.nova.core.model.DanmuRecord.Type.SUPER_CHAT, records.get(1).type());
        assertTrue(records.get(1).recovered());
    }

    @Test
    void missingChatIdOrTimeRejectsEntireList() throws Exception {
        // 用户故障：没有可靠编号或开始时刻的列表造成醒目留言误补或重复。
        chats.add(chat(101L, 2, 50));
        chats.add(chat(null, 2, 30));
        recover();
        assertEquals(0, metric(BilibiliLiveMetric.SUPER_CHAT_COUNT));
        chats.clear();
        JSONObject noTime = chat(102L, 2, 30);
        noTime.remove("start_time");
        chats.add(noTime);
        recover();
        assertEquals(0, metric(BilibiliLiveMetric.SUPER_CHAT_COUNT));
        verify(http, times(2)).getJson(contains("getMessageList"), any());
    }

    @Test
    void invalidTimelineNeverFallsBackToNow() throws Exception {
        // 用户故障：无法解析的历史时间被当作当前时刻补进来。
        JSONObject invalid = danmu(2, "无时间");
        invalid.put("timeline", "invalid");
        danmus.add(invalid);
        Instant now = Instant.now();
        JSONObject valid = danmu(3, "有效时间");
        valid.put("timeline", DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneId.of("Asia/Shanghai")).format(now.minusSeconds(2)));
        danmus.add(valid);
        recover(now.minusSeconds(5), now);
        assertEquals(1, metric(BilibiliLiveMetric.DANMU_COUNT));
        assertEquals("有效时间", archive.readDanmu("bilibili", ROOM.getUid(), START).get(0).text());
    }

    @Test
    void offlineDoesNotRequestEitherEndpoint() throws Exception {
        // 用户故障：主播未开播的重连也发出补录请求。
        data.setLiveStatus("bilibili", ROOM.getUid(), false);
        recover();
        verifyNoInteractions(http);
    }

    @Test
    void disabledDoesNotRequestEitherEndpoint() throws Exception {
        // 用户故障：关闭补录开关仍访问接口。
        properties.getLive().setReconnectRecovery(false);
        recover();
        verifyNoInteractions(http);
    }

    @Test
    void lateRealtimeDeliveryDoesNotCountRecoveredDataTwice() throws Exception {
        // 用户故障：认证同批数据包的实时事件晚于补录返回，造成同条消息重复累计。
        danmus.add(danmu(2, "延迟发言"));
        chats.add(chat(101L, 2, 50));
        recover();
        stats.onDanmu(event(2, "延迟发言"));
        BilibiliSuperChatEvent event = new BilibiliSuperChatEvent(ROOM, user(), "测试留言", 50.0, FROM.plusSeconds(2));
        event.setMessageId(101L);
        stats.onSuperChat(event);
        assertEquals(1, metric(BilibiliLiveMetric.DANMU_COUNT));
        assertEquals(1, metric(BilibiliLiveMetric.SUPER_CHAT_COUNT));
    }

    @Test
    void changedSessionRejectsPendingRecovery() throws Exception {
        // 用户故障：请求返回前已换场，旧场补录污染新场统计。
        danmus.add(danmu(2, "上一场发言"));
        when(http.getJson(contains("gethistory"), any())).thenAnswer(call -> {
            data.setLiveStartTime("bilibili", ROOM.getUid(), TO.plusSeconds(30).toEpochMilli());
            return JSONObject.of("code", 0, "data", JSONObject.of("room", danmus));
        });
        recover();
        assertEquals(0, metric(BilibiliLiveMetric.DANMU_COUNT));
    }

    private BilibiliLiveRoomConnector connector(BilibiliGapRecovery recovery,
                                                ApplicationEventPublisher publisher, BilibiliConnectGate gate) {
        BilibiliLiveRoomConnector connector = new BilibiliLiveRoomConnector(ROOM, api,
                mock(BilibiliEventParser.class), properties, publisher,
                mock(org.springframework.scheduling.TaskScheduler.class),
                mock(org.springframework.web.socket.client.WebSocketClient.class),
                mock(BilibiliLiveStateGate.class), gate, new BilibiliRiskMetrics(),
                mock(org.frostnova.nova.bilibili.health.BilibiliDisconnectDigest.class), data,
                new java.util.concurrent.atomic.AtomicBoolean(true));
        connector.setGapRecovery(recovery);
        return connector;
    }

    private void authenticate(BilibiliLiveRoomConnector connector) {
        ReflectionTestUtils.invokeMethod(connector, "handlePacket",
                new org.frostnova.nova.bilibili.protocol.BilibiliPacket(8, 1, new byte[0]));
    }

    private AppenderBase<ILoggingEvent> completionAppender(CountDownLatch completed) {
        AppenderBase<ILoggingEvent> appender = new AppenderBase<>() {
            @Override protected void append(ILoggingEvent event) {
                if (event.getFormattedMessage().contains("断线补录")) { completed.countDown(); }
            }
        };
        appender.start();
        return appender;
    }

    private void recover() throws Exception {
        recover(FROM, TO);
    }

    private void recover(Instant from, Instant to) throws Exception {
        try (BilibiliGapRecovery recovery = new BilibiliGapRecovery(api, properties, data, stats)) {
            recovery.recover(ROOM, from, to).get(15, TimeUnit.SECONDS);
        }
    }

    private double metric(String name) { return data.getLiveMetric("bilibili", ROOM.getUid(), name); }
    private BilibiliUserInfo user() { return new BilibiliUserInfo(5000005L, "测试观众"); }
    private BilibiliDanmuEvent event(int offset, String text) {
        return new BilibiliDanmuEvent(ROOM, user(), text, text, FROM.plusSeconds(offset));
    }
    private JSONObject danmu(int offset, String text) {
        return JSONObject.of("uid", offset == 1 ? 6000006L : 5000005L, "nickname", "测试观众", "text", text, "timeline",
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Shanghai"))
                        .format(FROM.plusSeconds(offset)), "msg_id", "fake-id");
    }
    private JSONObject chat(Long id, int offset, int price) {
        return JSONObject.of("id", id, "start_time", FROM.plusSeconds(offset).getEpochSecond(),
                "uid", 5000005L, "user_info", JSONObject.of("uname", "测试观众"), "message", "测试留言", "price", price);
    }
}
