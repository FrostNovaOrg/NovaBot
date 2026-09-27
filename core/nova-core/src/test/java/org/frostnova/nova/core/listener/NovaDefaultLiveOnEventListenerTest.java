package org.frostnova.nova.core.listener;

import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.event.live.common.LiveOnEvent;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import org.frostnova.nova.core.service.LiveDataService;
import org.frostnova.nova.core.service.LiveSessionRecovery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.event.EventListener;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 开播事件的断线重连检测
 * <p>
 * 重连检测排在所有开播监听最前（HIGHEST_PRECEDENCE），同步广播里它一抛，
 * 排在它后面的开播记事（BilibiliLiveTimelineRecorder，-10001）、开播数据写入（-10000）
 * 与再往后的推送全都收不到这场开播——读上一场下播时间出错会让这场开播整个消失。
 * 这里量的就是：读不出上一场时，广播照常往下走，这场按新开播处理。
 */
@DisplayName("开播监听：断线重连检测")
class NovaDefaultLiveOnEventListenerTest {
    private static final String PLATFORM = "bilibili";
    private static final long UID = 42L;
    private static final long ROOM_ID = 1001L;
    private static final long START = 1_700_000_000_000L;

    private NovaCoreProperties properties;
    private LiveDataService liveDataService;
    private LiveSessionRecovery sessionRecovery;
    private NovaDefaultLiveOnEventListener listener;

    @BeforeEach
    void setUp() {
        properties = new NovaCoreProperties();
        properties.getLive().setReconnectInterval(300);
        liveDataService = mock(LiveDataService.class);
        sessionRecovery = mock(LiveSessionRecovery.class);
        listener = new NovaDefaultLiveOnEventListener(properties, liveDataService, sessionRecovery);
    }

    private static LiveOnEvent liveOnAt(long at) {
        return new LiveOnEvent(PLATFORM, new LiveStreamerInfo(UID, "主播甲", ROOM_ID), Instant.ofEpochMilli(at));
    }

    /**
     * 摘监听器落下的全部 WARN 原文（格式化后），INFO 不进这张单子
     */
    private static List<String> captureWarns(Runnable action) {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(NovaDefaultLiveOnEventListener.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            action.run();
            return appender.list.stream()
                    .filter(event -> event.getLevel() == ch.qos.logback.classic.Level.WARN)
                    .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                    .toList();
        } finally {
            logger.detachAppender(appender);
        }
    }

    /**
     * 读得出时的三种取值：间隔内标重连，间隔外与没有上一场都按新开播——
     * 接异常的那一笔不许顺带动这三条路的判法
     */
    @Test
    @DisplayName("阳/阴对照：间隔内标重连；间隔外、无上一场均按新开播")
    void intervalDecidesReconnect() {
        when(liveDataService.getLiveEndTime(PLATFORM, UID)).thenReturn(Optional.of(START - 100_000));
        LiveOnEvent within = liveOnAt(START);
        listener.onLiveOnEventCheckReconnect(within);
        assertTrue(within.isReconnect(), "间隔 100 秒 ≤ 300 秒应标断线重连");

        when(liveDataService.getLiveEndTime(PLATFORM, UID)).thenReturn(Optional.of(START - 301_000));
        LiveOnEvent outside = liveOnAt(START);
        listener.onLiveOnEventCheckReconnect(outside);
        assertFalse(outside.isReconnect(), "间隔 301 秒 ＞ 300 秒应按新开播");

        when(liveDataService.getLiveEndTime(PLATFORM, UID)).thenReturn(Optional.empty());
        LiveOnEvent noRecord = liveOnAt(START);
        listener.onLiveOnEventCheckReconnect(noRecord);
        assertFalse(noRecord.isReconnect(), "没有上一场记录应按新开播");
    }

    /**
     * 排在重连检测之后的监听：开播记事（-10001）、数据写入（-10000）与推送的代表。
     * 不加 {@code @Order}，默认最低优先级，天然排在 HIGHEST_PRECEDENCE 的重连检测之后
     */
    static class DownstreamProbe {
        final List<LiveOnEvent> received = new ArrayList<>();

        @EventListener
        void onLiveOn(LiveOnEvent event) {
            received.add(event);
        }
    }

    /**
     * 最小的事件广播装配：AnnotationConfigApplicationContext 用 registerBean 把真监听与探针装成 bean，
     * {@code @EventListener} 注解由 EventListenerMethodProcessor 按生产同一条路登记，
     * publishEvent 走 SimpleApplicationEventMulticaster 同步按序广播——与 nova-bilibili 里
     * publisher.publishEvent(new BilibiliLiveOnEvent(...)) 是同一套机制，只有三个依赖是假的。
     */
    private AnnotationConfigApplicationContext broadcast() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean(NovaCoreProperties.class, () -> properties);
        context.registerBean(LiveDataService.class, () -> liveDataService);
        context.registerBean(LiveSessionRecovery.class, () -> sessionRecovery);
        context.registerBean(NovaDefaultLiveOnEventListener.class,
                () -> new NovaDefaultLiveOnEventListener(properties, liveDataService, sessionRecovery));
        context.registerBean(DownstreamProbe.class, DownstreamProbe::new);
        context.refresh();
        return context;
    }

    /**
     * 抓的用户故障：读上一场下播时间出错时，这场开播没记进直播记录、后面的开播推送也没发——
     * 重连检测排在最前，它一抛，同一次广播里后面的监听全被跳过。
     * 这里连装配一起量：读下播时间抛异常，广播不得因此断掉，这场按新开播（不标重连）交给后面的监听。
     */
    @Test
    @DisplayName("读上一场下播时间抛异常：方法不外抛、这场按新开播、后面的监听照样收到")
    void downstreamListenersStillReceiveWhenReadingLastLiveEndTimeFails() {
        when(liveDataService.getLiveEndTime(PLATFORM, UID)).thenThrow(new RuntimeException("读下播时间出错"));

        try (AnnotationConfigApplicationContext context = broadcast()) {
            DownstreamProbe probe = context.getBean(DownstreamProbe.class);
            LiveOnEvent event = liveOnAt(START);

            RuntimeException[] escaped = new RuntimeException[1];
            List<String> warns = captureWarns(() -> {
                try {
                    context.publishEvent(event);
                } catch (RuntimeException e) {
                    escaped[0] = e;
                }
            });

            assertNull(escaped[0], "广播不该因重连检测读不到上一场而中断: " + escaped[0]);
            assertEquals(1, probe.received.size(), "排在后面的监听必须照样收到这次开播");
            assertFalse(event.isReconnect(), "读不出上一场按没有上一场办：不标断线重连");

            assertEquals(1, warns.size(), "应恰记一句 WARN，实际: " + warns);
            assertTrue(warns.get(0).contains(PLATFORM), "那句 WARN 要点名平台，实际: " + warns.get(0));
            assertTrue(warns.get(0).contains(String.valueOf(UID)), "那句 WARN 要点名主播 uid，实际: " + warns.get(0));
        }
    }

    /**
     * 前置自证：装配真的在按序广播——读得出上一场时，探针收到的是重连检测标过的事件。
     * 少了这一条，上面「照样收到」在装配根本没把探针接进广播时同样是绿的。
     */
    @Test
    @DisplayName("装配自证：探针真的接进了广播，收到的是重连检测标过的事件")
    void probeIsReallyWiredIntoTheBroadcast() throws Exception {
        when(liveDataService.getLiveEndTime(PLATFORM, UID)).thenReturn(Optional.of(START - 100_000));

        try (AnnotationConfigApplicationContext context = broadcast()) {
            DownstreamProbe probe = context.getBean(DownstreamProbe.class);
            LiveOnEvent event = liveOnAt(START);
            context.publishEvent(event);

            assertEquals(1, probe.received.size(), "装配应把探针接进广播");
            assertTrue(probe.received.get(0).isReconnect(), "探针收到的应是重连检测标过的事件，"
                    + "否则它排在检测之后的说法不成立");
        }
        // Method 引用只为了让编译器确认探针的监听方法还挂着注解：注解被挪走时这里编译期就红
        Method onLiveOn = DownstreamProbe.class.getDeclaredMethod("onLiveOn", LiveOnEvent.class);
        assertTrue(onLiveOn.isAnnotationPresent(EventListener.class));
    }

    /**
     * 抓的用户故障：开播时读上一场下播时间出错，日志只说「出错」，看不出是什么错。
     * 要求：带异常类名（简名），不带异常原文——原文可能带着连接串，栈更不往这句里放。
     */
    @Test
    @DisplayName("读上一场出错那句 WARN 带异常类名、不带异常原文")
    void warnCarriesExceptionClassNameNotItsMessage() {
        when(liveDataService.getLiveEndTime(PLATFORM, UID))
                .thenThrow(new IllegalStateException("jdbc:mysql://db.example:3306/nova?password=hunter2 炸了"));

        List<String> warns = captureWarns(() -> listener.onLiveOnEventCheckReconnect(liveOnAt(START)));

        assertEquals(1, warns.size(), "应恰记一句 WARN，实际: " + warns);
        assertTrue(warns.get(0).contains("IllegalStateException"),
                "那句 WARN 要带异常类名，实际: " + warns.get(0));
        assertFalse(warns.get(0).contains("hunter2"), "异常原文漏进日志了: " + warns.get(0));
        assertFalse(warns.get(0).contains("jdbc:"), "异常原文漏进日志了: " + warns.get(0));
    }
}
