package org.frostnova.nova.bilibili.service;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.frostnova.nova.bilibili.service.BilibiliConnectorHarness.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BilibiliMonitoringTest {
    @ParameterizedTest(name = "{displayName}：{0}")
    @ValueSource(strings = {"authenticate", "closed", "transport", "reconnect", "close"})
    @DisplayName("监看记录失败只告警一次且不影响重连断线补录与关闭")
    void monitoringFailureDoesNotInterruptLifecycle(String action) {
        var harness = new BilibiliConnectorHarness();
        harness.connect();
        harness.fireVerifySuccess();
        if (action.equals("authenticate")) {
            harness.fireConnectionClosed(1006);
            harness.runQueuedReconnects();
        }
        var failure = new IllegalStateException("monitoring unavailable");
        doThrow(failure).when(harness.getLiveDataService()).recordMonitoring(any(),any(),any(),anyLong(),anyBoolean());
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(BilibiliLiveRoomConnector.class);
        var logs = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        logs.start();
        logger.addAppender(logs);
        try {
            assertDoesNotThrow(() -> {
                switch (action) {
                    case "authenticate" -> harness.fireVerifySuccess();
                    case "closed" -> harness.fireConnectionClosed(1006);
                    case "transport" -> {
                        var session = (org.springframework.web.socket.WebSocketSession)
                                org.springframework.test.util.ReflectionTestUtils.getField(harness.connector(),"session");
                        harness.connector().handleTransportError(session,new java.io.IOException("connection lost"));
                    }
                    case "reconnect" -> { harness.failNextSend(); harness.fireHeartbeat(); }
                    case "close" -> harness.connector().close();
                    default -> fail("unexpected action");
                }
            });
            assertEquals(1,logs.list.stream().filter(event -> event.getLevel() == ch.qos.logback.classic.Level.WARN
                    && event.getFormattedMessage().contains("监看")).count(),"每次失败只记一条监看告警");
            if (action.equals("authenticate")) {
                verify(harness.getLiveDataService()).recordRoomOutage(eq("bilibili"),eq(STREAMER_UID),anyLong(),anyLong());
                assertEquals(2,harness.handshakes());
            }
            if (action.equals("closed") || action.equals("reconnect")) assertEquals(1,harness.queuedReconnects());
            if (action.equals("reconnect") || action.equals("close")) assertTrue(harness.sessionClosed());
            if (java.util.List.of("closed","transport","reconnect").contains(action)) {
                if (!action.equals("closed")) harness.fireConnectionClosed(1006);
                harness.runQueuedReconnects();
                harness.fireVerifySuccess();
                assertEquals(2,harness.handshakes());
                verify(harness.getLiveDataService()).recordRoomOutage(eq("bilibili"),eq(STREAMER_UID),anyLong(),anyLong());
            }
            // 停收失败后再次 close 也必须已经完成清理。
            if (action.equals("close")) assertDoesNotThrow(harness.connector()::close);
        } finally {
            logger.detachAppender(logs);
            logs.stop();
            reset(harness.getLiveDataService());
            harness.connector().close();
        }
    }
    @Test @DisplayName("未开播房间认证后开始监看且断线立即停止证据")
    void offlineRoomLifecycle() {
        var harness = new BilibiliConnectorHarness();
        harness.connect();
        verify(harness.getLiveDataService(),never()).recordMonitoring(any(),any(),any(),anyLong(),eq(true));
        harness.fireVerifySuccess();
        verify(harness.getLiveDataService()).recordMonitoring(eq("bilibili"),eq(STREAMER_UID),eq(ROOM_ID),anyLong(),eq(true));
        harness.fireConnectionClosed(1006);
        verify(harness.getLiveDataService()).recordMonitoring(eq("bilibili"),eq(STREAMER_UID),eq(ROOM_ID),anyLong(),eq(false));
        harness.runQueuedReconnects();
        harness.fireVerifySuccess();
        verify(harness.getLiveDataService(),times(2)).recordMonitoring(eq("bilibili"),eq(STREAMER_UID),eq(ROOM_ID),anyLong(),eq(true));
        harness.connector().close();
    }
    @Test @DisplayName("移出或主动关闭房间立即停止监看证据")
    void closeStopsMonitoring() {
        var harness = new BilibiliConnectorHarness();
        harness.connect(); harness.fireVerifySuccess(); harness.connector().close();
        verify(harness.getLiveDataService()).recordMonitoring(eq("bilibili"),eq(STREAMER_UID),eq(ROOM_ID),anyLong(),eq(false));
    }
    @Test @DisplayName("本端重连不等关闭回调就停止监看证据")
    void reconnectStopsImmediately() {
        var harness = new BilibiliConnectorHarness();
        harness.connect(); harness.fireVerifySuccess();
        harness.failNextSend(); harness.fireHeartbeat();
        verify(harness.getLiveDataService()).recordMonitoring(eq("bilibili"),eq(STREAMER_UID),eq(ROOM_ID),anyLong(),eq(false));
        harness.connector().close();
    }

    @Test @DisplayName("传输错误尚未回调关闭时不能继续声称监看")
    void transportErrorStopsMonitoring() {
        var harness = new BilibiliConnectorHarness();
        harness.connect(); harness.fireVerifySuccess();
        var session = (org.springframework.web.socket.WebSocketSession)
                org.springframework.test.util.ReflectionTestUtils.getField(harness.connector(), "session");
        harness.connector().handleTransportError(session,new java.io.IOException("connection lost"));
        verify(harness.getLiveDataService()).recordMonitoring(eq("bilibili"),eq(STREAMER_UID),eq(ROOM_ID),anyLong(),eq(false));
        harness.connector().close();
    }

}
