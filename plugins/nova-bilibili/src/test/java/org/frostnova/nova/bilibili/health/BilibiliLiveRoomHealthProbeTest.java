package org.frostnova.nova.bilibili.health;

import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.enums.ConnectStatus;
import org.frostnova.nova.bilibili.service.BilibiliConnectGate;
import org.frostnova.nova.bilibili.service.BilibiliEventParser;
import org.frostnova.nova.bilibili.service.BilibiliLiveRoomConnector;
import org.frostnova.nova.bilibili.service.BilibiliLiveRoomService;
import org.frostnova.nova.bilibili.service.BilibiliLiveStateGate;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.health.HealthStatus;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import org.frostnova.nova.core.service.LiveDataService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.client.WebSocketClient;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 直播间连接探针：连续多分钟没收到弹幕礼物时，要点出是哪几间，并且只说看到了什么。
 */
@DisplayName("直播间连接探针")
class BilibiliLiveRoomHealthProbeTest {
    /**
     * 抓的故障：两间直播间都连续多分钟没收到弹幕礼物时，自检只写「2 个」，看不出是谁。
     */
    @Test
    @DisplayName("两间同处该状态：摘要里两个主播名和房间号都在")
    void summaryNamesEveryRoomInThatState() throws Exception {
        BilibiliLiveRoomHealthProbe probe = probeWith(
                room("星见", 1001L, ConnectStatus.RISK),
                room("月见", 1002L, ConnectStatus.RISK));

        String summary = probe.check().summary();

        assertTrue(summary.contains("星见"), "摘要要点出第一位主播，实际 " + summary);
        assertTrue(summary.contains("1001"), "摘要要点出第一间房间号，实际 " + summary);
        assertTrue(summary.contains("月见"), "摘要要点出第二位主播，实际 " + summary);
        assertTrue(summary.contains("1002"), "摘要要点出第二间房间号，实际 " + summary);
    }

    /**
     * 抓的故障：自检把「没人说话或画面卡住」说成账号受限、让人改用其他账号。
     */
    @Test
    @DisplayName("摘要与处理建议只说看到了什么")
    void summaryAndAdviceDoNotClaimRestriction() throws Exception {
        HealthStatus status = probeWith(room("星见", 1001L, ConnectStatus.RISK)).check();
        String text = status.summary() + "\n" + status.advice();

        assertFalse(text.contains("风控"), text);
        assertFalse(text.contains("断流"), text);
        assertFalse(text.contains("账号或 IP 受限"), text);
        assertFalse(text.contains("改用其他账号"), text);
    }

    /**
     * 抓的故障：超过五间时摘要把全部房间号堆进一条告警，读的人找不到头。
     */
    @Test
    @DisplayName("超过五间只列前五个，余下写等几个")
    void summaryListsFiveThenTheRest() throws Exception {
        BilibiliLiveRoomHealthProbe probe = probeWith(
                room("甲", 1L, ConnectStatus.RISK),
                room("乙", 2L, ConnectStatus.RISK),
                room("丙", 3L, ConnectStatus.RISK),
                room("丁", 4L, ConnectStatus.RISK),
                room("戊", 5L, ConnectStatus.RISK),
                room("己", 6L, ConnectStatus.RISK));

        String summary = probe.check().summary();

        assertTrue(summary.contains("甲"), summary);
        assertTrue(summary.contains("戊"), summary);
        assertTrue(summary.contains("等 1 个"), summary);
        assertFalse(summary.contains("己"), summary);
    }

    private static BilibiliLiveRoomHealthProbe probeWith(BilibiliLiveRoomConnector... connectors) throws Exception {
        NovaBilibiliProperties properties = new NovaBilibiliProperties();
        TaskScheduler scheduler = mock(TaskScheduler.class);
        BilibiliLiveRoomService service = new BilibiliLiveRoomService(
                mock(BilibiliApiUtil.class),
                mock(BilibiliEventParser.class),
                properties,
                mock(ApplicationEventPublisher.class),
                scheduler,
                mock(BilibiliLiveStateGate.class),
                new BilibiliConnectGate(properties, scheduler),
                new BilibiliRiskMetrics(),
                new BilibiliDisconnectDigest(properties, scheduler),
                mock(LiveDataService.class));
        for (BilibiliLiveRoomConnector connector : connectors) {
            put(service, connector);
        }
        return new BilibiliLiveRoomHealthProbe(service, properties);
    }

    private static BilibiliLiveRoomConnector room(String name, long roomId, ConnectStatus status) throws Exception {
        NovaBilibiliProperties properties = new NovaBilibiliProperties();
        TaskScheduler scheduler = mock(TaskScheduler.class);
        BilibiliLiveRoomConnector connector = new BilibiliLiveRoomConnector(
                new LiveStreamerInfo(roomId, name, roomId),
                mock(BilibiliApiUtil.class),
                mock(BilibiliEventParser.class),
                properties,
                mock(ApplicationEventPublisher.class),
                scheduler,
                mock(WebSocketClient.class),
                mock(BilibiliLiveStateGate.class),
                new BilibiliConnectGate(properties, scheduler),
                new BilibiliRiskMetrics(),
                new BilibiliDisconnectDigest(properties, scheduler),
                mock(LiveDataService.class),
                new AtomicBoolean(true));
        Field field = BilibiliLiveRoomConnector.class.getDeclaredField("status");
        field.setAccessible(true);
        field.set(connector, status);
        return connector;
    }

    @SuppressWarnings("unchecked")
    private static void put(BilibiliLiveRoomService service, BilibiliLiveRoomConnector connector) throws Exception {
        Field field = BilibiliLiveRoomService.class.getDeclaredField("connectors");
        field.setAccessible(true);
        Map<Long, BilibiliLiveRoomConnector> connectors =
                (Map<Long, BilibiliLiveRoomConnector>) field.get(service);
        connectors.put(connector.getSource().getRoomId(), connector);
    }
}
