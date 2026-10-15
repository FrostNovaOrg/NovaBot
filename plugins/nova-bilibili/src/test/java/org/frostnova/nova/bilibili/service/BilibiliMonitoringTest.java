package org.frostnova.nova.bilibili.service;

import org.junit.jupiter.api.*;
import static org.frostnova.nova.bilibili.service.BilibiliConnectorHarness.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BilibiliMonitoringTest {
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
