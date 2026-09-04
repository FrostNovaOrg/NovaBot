package com.starlwr.bot.core.timeline;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.alert.AlertService;
import com.starlwr.bot.core.alert.HealthAlertMonitor;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.datasource.AbstractDataSource;
import com.starlwr.bot.core.enums.PushTargetType;
import com.starlwr.bot.core.event.StarBotExternalBaseEvent;
import com.starlwr.bot.core.handler.StarBotEventHandler;
import com.starlwr.bot.core.health.HealthProbe;
import com.starlwr.bot.core.health.HealthStatus;
import com.starlwr.bot.core.health.PushActivityRecorder;
import com.starlwr.bot.core.listener.StarBotHandlerListener;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import com.starlwr.bot.core.model.Message;
import com.starlwr.bot.core.model.PushMessage;
import com.starlwr.bot.core.model.PushTarget;
import com.starlwr.bot.core.model.PushUser;
import com.starlwr.bot.core.model.Sender;
import com.starlwr.bot.core.sender.AtAllPermissionResolver;
import com.starlwr.bot.core.sender.FirstPushTipService;
import com.starlwr.bot.core.sender.PushGate;
import com.starlwr.bot.core.sender.StarBotMessageSender;
import com.starlwr.bot.core.service.AtAllQuotaService;
import com.starlwr.bot.core.service.StarBotSenderService;
import com.starlwr.bot.core.service.StarBotStateStore;
import com.starlwr.bot.core.util.HttpUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 时间线六个接入点各一阳性
 * <p>
 * <b>为什么六个凑在一件里</b>：接入点这种东西，删掉一处的表现是「时间线上从此少一类事件」，
 * 而少的那一类平时本来就少见——一年不出一次的登录失效，没人会因为它不出现而起疑。
 * 一件里六格并排，删掉一处当场少一格，比散在六个文件里各自沉默要看得见。
 * <p>
 * 这里只证「现场会往时间线上写、写的是哪一类」；写进磁盘那一层由
 * {@link TimelineStoreTest} 证，不在这里重复。
 */
@DisplayName("时间线接入点")
class TimelineHookTest {
    private static final String PLATFORM = "qq-onebot";

    /**
     * 直播平台标识，与推送平台不是一回事：前者是事件从哪来，后者是消息发往哪
     */
    private static final String LIVE_PLATFORM = "bilibili";

    /**
     * 收集写下的事件，不碰磁盘
     */
    private static final class Capture implements TimelineWriter {
        private final List<TimelineEvent> events = new ArrayList<>();

        @Override
        public void record(TimelineEvent event) {
            events.add(event);
        }

        private TimelineEvent only() {
            assertEquals(1, events.size(), "该接入点应恰好记下一条: " + events);
            return events.get(0);
        }
    }

    @Test
    @DisplayName("① 静音时段丢弃的消息应记成「静音丢弃」")
    void recordsMutedDrop() {
        // 静音窗口按当前时刻现算：写死 00:00~23:59 会在午夜前后那一分钟落到窗外，
        // 而那种测试的失败只在半夜跑 CI 时出现一次，谁也复现不了。
        // 前后各一小时的窗口在跨零点那一支上同样成立（PushGate 认得起止倒挂）
        LocalTime now = LocalTime.now();
        StarBotCoreProperties properties = new StarBotCoreProperties();
        properties.getPush().setQuietStart(now.minusHours(1).format(DateTimeFormatter.ofPattern("HH:mm")));
        properties.getPush().setQuietEnd(now.plusHours(1).format(DateTimeFormatter.ofPattern("HH:mm")));

        Capture capture = new Capture();
        sender(properties, capture).send(message());

        TimelineEvent event = capture.only();
        assertEquals(TimelineEventType.PUSH_MUTED, event.type());
        assertEquals(TimelineEvent.Level.WARN, event.level());
        assertEquals("群 12345", event.channel());
        assertTrue(event.text().contains("静音"), "正文应说清是哪一道拦的: " + event.text());
        assertEquals("测试内容", event.detail().get("summary"));
    }

    @Test
    @DisplayName("② 全局开关关闭时丢弃的消息应记成「暂停丢弃」")
    void recordsPausedDrop() {
        StarBotCoreProperties properties = new StarBotCoreProperties();
        properties.getPush().setEnabled(false);

        Capture capture = new Capture();
        sender(properties, capture).send(message());

        TimelineEvent event = capture.only();
        assertEquals(TimelineEventType.PUSH_PAUSED, event.type(),
                "开关关掉与落在静音时段是两回事, 混成一类会让「静音丢弃」的条数虚高");
        assertEquals(TimelineEvent.Level.WARN, event.level());
    }

    @Test
    @DisplayName("③ 推送成功应记成「推送成功」且带上耗时")
    void recordsPushSuccess() {
        Capture capture = new Capture();
        new PushActivityRecorder(capture).recordSuccess(PLATFORM, "群 12345", "开播了", 137);

        TimelineEvent event = capture.only();
        assertEquals(TimelineEventType.PUSH_SENT, event.type());
        assertEquals(TimelineEvent.Level.INFO, event.level());
        assertEquals("群 12345", event.channel());
        assertTrue(event.text().contains("开播了"));
        assertEquals(PLATFORM, event.detail().get("platform"));
        assertEquals("137", event.detail().get("elapsed_ms"),
                "「推送变慢了吗」得有一个数才答得了, 而它只有发的那一刻知道");
    }

    @Test
    @DisplayName("③ 推送失败应记成「推送失败」且带上原因与耗时")
    void recordsPushFailure() {
        Capture capture = new Capture();
        new PushActivityRecorder(capture).recordFailure(PLATFORM, "群 12345", "开播了", "群号不存在", 4200);

        TimelineEvent event = capture.only();
        assertEquals(TimelineEventType.PUSH_FAILED, event.type());
        assertEquals(TimelineEvent.Level.ERROR, event.level());
        assertTrue(event.text().contains("群号不存在"), "失败要说清为什么: " + event.text());
        assertEquals("开播了", event.detail().get("summary"));
        assertEquals("4200", event.detail().get("elapsed_ms"),
                "失败也要记耗时: 秒回的失败与超时的失败, 下一步查的东西完全不同");
    }

    @Test
    @DisplayName("⑥ 静音时段拦下的推送, 在分发那一层只记一条, 含成因、主播与目标数")
    void recordsOneAggregatedDropPerEvent() {
        LocalTime now = LocalTime.now();
        StarBotCoreProperties properties = new StarBotCoreProperties();
        properties.getPush().setQuietStart(now.minusHours(1).format(DateTimeFormatter.ofPattern("HH:mm")));
        properties.getPush().setQuietEnd(now.plusHours(1).format(DateTimeFormatter.ofPattern("HH:mm")));

        Capture capture = new Capture();
        listener(properties, capture, 3).onStarBotExternalBaseEvent(liveEvent());

        TimelineEvent event = capture.only();
        assertEquals(TimelineEventType.PUSH_MUTED, event.type());
        assertEquals(TimelineEvent.Level.WARN, event.level());
        assertEquals("主播甲", event.streamer(), "丢掉的是谁的通知, 是这一条唯一要紧的事");
        assertEquals("3", event.detail().get("targets"),
                "丢了几个会话决定这件事要不要管: 一个群与三十个群不是一回事");
        assertTrue(event.text().contains("静音"), "正文应说清是哪一道拦的: " + event.text());
    }

    @Test
    @DisplayName("⑥ 全局开关关闭时同形, 但记的是「暂停丢弃」")
    void aggregatedDropTellsPausedFromMuted() {
        StarBotCoreProperties properties = new StarBotCoreProperties();
        properties.getPush().setEnabled(false);

        Capture capture = new Capture();
        listener(properties, capture, 2).onStarBotExternalBaseEvent(liveEvent());

        TimelineEvent event = capture.only();
        assertEquals(TimelineEventType.PUSH_PAUSED, event.type(),
                "开关关掉与落在静音时段是两回事, 混成一类会让「静音丢弃」的条数虚高");
        assertEquals("2", event.detail().get("targets"));
    }

    @Test
    @DisplayName("⑥ 没有一个推送目标认领这个事件时, 什么也不记")
    void nothingIsRecordedWhenNobodySubscribed() {
        StarBotCoreProperties properties = new StarBotCoreProperties();
        properties.getPush().setEnabled(false);

        Capture capture = new Capture();
        listener(properties, capture, 0).onStarBotExternalBaseEvent(liveEvent());

        assertTrue(capture.events.isEmpty(),
                "没人订阅的事件本来就不会推, 静音期间为它记一条「丢弃」是凭空造出来的坏消息: "
                        + capture.events);
    }

    @Test
    @DisplayName("⑥ 没被拦下时, 分发那一层一条也不记, 事件照常交给处理器")
    void dispatchesNormallyWhenAllowed() {
        Capture capture = new Capture();
        CountingHandler handler = new CountingHandler();
        StarBotHandlerListener listener = listener(new StarBotCoreProperties(), capture, 3, handler);

        listener.onStarBotExternalBaseEvent(liveEvent());

        assertEquals(3, handler.handled, "三个目标各处理一次");
        assertTrue(capture.events.isEmpty(), "没拦下就没有「丢弃」这回事: " + capture.events);
    }

    @Test
    @DisplayName("④ 探针变色应记成「状态变化」, 含恢复正常")
    void recordsProbeChange() {
        Capture capture = new Capture();
        StubProbe probe = new StubProbe("机器人连接", false);
        HealthAlertMonitor monitor = monitor(probe, capture);

        probe.status = HealthStatus.down("连不上", "检查 OneBot 是否启动");
        monitor.check();
        probe.status = HealthStatus.ok("正常");
        monitor.check();

        assertEquals(2, capture.events.size(), "变坏与回绿各记一条");

        TimelineEvent broke = capture.events.get(0);
        assertEquals(TimelineEventType.PROBE_CHANGED, broke.type());
        assertEquals(TimelineEvent.Level.ERROR, broke.level());
        assertEquals("未知", broke.detail().get("from"), "首次检查没有「之前」, 不能编一个 OK");
        assertEquals("DOWN", broke.detail().get("to"));

        TimelineEvent recovered = capture.events.get(1);
        assertEquals(TimelineEventType.PROBE_CHANGED, recovered.type());
        assertEquals(TimelineEvent.Level.INFO, recovered.level());
        assertTrue(recovered.text().contains("恢复"), recovered.text());
    }

    @Test
    @DisplayName("⑤ 登录探针由正常转为不正常应单独记成「登录失效」")
    void recordsLoginLost() {
        Capture capture = new Capture();
        StubProbe probe = new StubProbe("哔哩哔哩登录", true);
        HealthAlertMonitor monitor = monitor(probe, capture);

        probe.status = HealthStatus.ok("正常（uid 1）");
        monitor.check();
        assertTrue(capture.events.isEmpty(), "一直正常不该记");

        probe.status = HealthStatus.down("未登录", "请扫码");
        monitor.check();

        TimelineEvent event = capture.only();
        assertEquals(TimelineEventType.LOGIN_LOST, event.type(),
                "登录掉了要人去扫码, 混在别的变色里会被当成又一次网络抖动");
        assertTrue(event.text().contains("登录已失效"), event.text());
        assertEquals("OK", event.detail().get("from"));
    }

    @Test
    @DisplayName("⑤ 登录恢复正常仍记成「状态变化」, 只有失效方向单列")
    void loginRecoveryIsAPlainChange() {
        Capture capture = new Capture();
        StubProbe probe = new StubProbe("哔哩哔哩登录", true);
        HealthAlertMonitor monitor = monitor(probe, capture);

        probe.status = HealthStatus.down("未登录", "请扫码");
        monitor.check();
        probe.status = HealthStatus.ok("正常（uid 1）");
        monitor.check();

        assertEquals(List.of(TimelineEventType.PROBE_CHANGED, TimelineEventType.PROBE_CHANGED),
                capture.events.stream().map(TimelineEvent::type).toList(),
                "首次检查即为异常不算「由正常转为不正常」, 恢复也不算");
    }

    @Test
    @DisplayName("非登录探针掉线不会被记成登录失效")
    void nonLoginProbeIsNeverLoginLost() {
        Capture capture = new Capture();
        StubProbe probe = new StubProbe("机器人连接", false);
        HealthAlertMonitor monitor = monitor(probe, capture);

        probe.status = HealthStatus.ok("正常");
        monitor.check();
        probe.status = HealthStatus.down("连不上", "检查 OneBot");
        monitor.check();

        assertEquals(TimelineEventType.PROBE_CHANGED, capture.only().type());
    }

    // —— 以下为夹具 ——

    /**
     * 可摆布状态的探针
     */
    private static final class StubProbe implements HealthProbe {
        private final String name;

        private final boolean loginState;

        private HealthStatus status = HealthStatus.ok("正常");

        private StubProbe(String name, boolean loginState) {
            this.name = name;
            this.loginState = loginState;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public HealthStatus check() {
            return status;
        }

        @Override
        public boolean loginState() {
            return loginState;
        }
    }

    /**
     * 数自己被调了几次的处理器
     * <p>
     * 「静音时不推送」这件事在发送器那一层也成立，因此单看「群里有没有收到」分不出
     * 分发这一层到底拦没拦——拦住了与照常走一遍再被发送器丢掉，结果一模一样。
     * 数调用次数才分得开：拦住的那一次，处理器一次也不该被叫到。
     */
    private static final class CountingHandler implements StarBotEventHandler {
        private int handled;

        @Override
        public void handle(StarBotExternalBaseEvent baseEvent, PushMessage pushMessage) {
            handled++;
        }

        @Override
        public Class<? extends StarBotExternalBaseEvent> getEventType() {
            return StarBotExternalBaseEvent.class;
        }

        @Override
        public JSONObject getDefaultParams() {
            return new JSONObject();
        }
    }

    private StarBotExternalBaseEvent liveEvent() {
        return new StarBotExternalBaseEvent(LIVE_PLATFORM, new LiveStreamerInfo(10001L, "主播甲", 20002L));
    }

    private StarBotHandlerListener listener(StarBotCoreProperties properties, TimelineWriter timeline, int targets) {
        return listener(properties, timeline, targets, new CountingHandler());
    }

    /**
     * 造一个订阅了本事件的主播，名下挂 {@code targets} 个推送目标
     */
    private StarBotHandlerListener listener(StarBotCoreProperties properties, TimelineWriter timeline,
                                            int targets, StarBotEventHandler handler) {
        PushUser user = new PushUser();
        user.setPlatform(LIVE_PLATFORM);
        user.setUid(10001L);
        user.setUname("主播甲");

        for (int i = 0; i < targets; i++) {
            PushTarget target = new PushTarget();
            target.setPlatform(PLATFORM);
            target.setType(PushTargetType.GROUP);
            target.setNum(30000L + i);

            PushMessage message = new PushMessage();
            message.setTarget(target);
            message.setHandlerInstance(handler);
            message.setEventClass(StarBotExternalBaseEvent.class);
            target.getMessages().add(message);
            user.getTargets().add(target);
        }

        AbstractDataSource dataSource = mock(AbstractDataSource.class);
        when(dataSource.getUser(LIVE_PLATFORM, 10001L)).thenReturn(Optional.of(user));

        return new StarBotHandlerListener(dataSource, new PushGate(properties), timeline);
    }

    private HealthAlertMonitor monitor(HealthProbe probe, TimelineWriter timeline) {
        @SuppressWarnings("unchecked")
        ObjectProvider<HealthProbe> probes = mock(ObjectProvider.class);
        when(probes.orderedStream()).thenAnswer(invocation -> java.util.stream.Stream.of(probe));

        return new HealthAlertMonitor(probes, mock(AlertService.class), timeline);
    }

    private StarBotMessageSender sender(StarBotCoreProperties properties, TimelineWriter timeline) {
        Sender target = new Sender();
        target.setName(PLATFORM);
        target.setUrl("http://127.0.0.1:7827/onebot/send");
        target.setDelay(0);

        StarBotSenderService senderService = mock(StarBotSenderService.class);
        when(senderService.getSender(PLATFORM)).thenReturn(Optional.of(target));

        @SuppressWarnings("unchecked")
        ObjectProvider<AtAllPermissionResolver> resolvers = mock(ObjectProvider.class);
        when(resolvers.iterator()).thenAnswer(invocation -> List.<AtAllPermissionResolver>of().iterator());

        return new StarBotMessageSender(mock(HttpUtil.class), senderService,
                new PushActivityRecorder(TimelineWriter.NONE), new PushGate(properties),
                timeline, new AtAllQuotaService(properties), resolvers,
                new FirstPushTipService(new StarBotStateStore(properties)));
    }

    private Message message() {
        Message message = Message.create(PLATFORM, PushTargetType.GROUP, 12345L, "测试内容").get(0);
        message.setCreateTime(Instant.now());
        return message;
    }
}
