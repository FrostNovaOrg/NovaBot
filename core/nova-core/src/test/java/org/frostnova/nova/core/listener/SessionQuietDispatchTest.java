package org.frostnova.nova.core.listener;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.event.NovaExternalBaseEvent;
import org.frostnova.nova.core.handler.NovaEventHandler;
import org.frostnova.nova.core.health.PushActivityRecorder;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import org.frostnova.nova.core.model.Message;
import org.frostnova.nova.core.model.PushMessage;
import org.frostnova.nova.core.model.PushTarget;
import org.frostnova.nova.core.model.PushUser;
import org.frostnova.nova.core.sender.AtAllPermissionResolver;
import org.frostnova.nova.core.sender.FirstPushTipService;
import org.frostnova.nova.core.sender.NovaMessageSender;
import org.frostnova.nova.core.sender.PushGate;
import org.frostnova.nova.core.service.AtAllQuotaService;
import org.frostnova.nova.core.service.NovaSenderService;
import org.frostnova.nova.core.service.NovaStateStore;
import org.frostnova.nova.core.service.SessionQuietHoursService;
import org.frostnova.nova.core.service.SessionQuietHoursService.Mode;
import org.frostnova.nova.core.timeline.TimelineEvent;
import org.frostnova.nova.core.timeline.TimelineEventType;
import org.frostnova.nova.core.timeline.TimelineWriter;
import org.frostnova.nova.core.util.HttpUtil;
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
 * 按会话静音：分发那一层逐个会话判，发送器那一层逐条判
 * <p>
 * 从前分发那一层是整件事件一刀拦：全局静音时一个会话都不发。会话有了自己那一档之后，
 * 同一件事件推往的几个会话可能一个在静音、一个不在，那一刀就必须拆成逐个会话。
 */
@DisplayName("按会话静音的分发与发送")
class SessionQuietDispatchTest {
    private static final String PLATFORM = "qq-onebot";

    private static final String LIVE_PLATFORM = "bilibili";

    private static final Long MUTED = 30001L;

    private static final Long OPEN = 30002L;

    private static final class Capture implements TimelineWriter {
        private final List<TimelineEvent> events = new ArrayList<>();

        @Override
        public void record(TimelineEvent event) {
            events.add(event);
        }
    }

    /**
     * 记下自己被叫去处理的是哪几个会话
     */
    private static final class RecordingHandler implements NovaEventHandler {
        private final List<Long> handled = new ArrayList<>();

        @Override
        public void handle(NovaExternalBaseEvent baseEvent, PushMessage pushMessage) {
            handled.add(pushMessage.getTarget().getNum());
        }

        @Override
        public Class<? extends NovaExternalBaseEvent> getEventType() {
            return NovaExternalBaseEvent.class;
        }

        @Override
        public JSONObject getDefaultParams() {
            return new JSONObject();
        }
    }

    @Test
    @DisplayName("同一位主播推两个群、其中一个落在自己的静音时段里：只丢那一个，另一个照发，丢弃记 1 个会话")
    void dropsOnlyTheMutedSession() {
        SessionQuietHoursService sessions = sessions();
        sessions.set(PLATFORM, MUTED, Mode.CUSTOM, aroundNow()[0], aroundNow()[1]);

        Capture capture = new Capture();
        RecordingHandler handler = new RecordingHandler();
        NovaHandlerListener listener = new NovaHandlerListener(dataSource(handler, MUTED, OPEN),
                new PushGate(new NovaCoreProperties(), sessions), capture);

        listener.onNovaExternalBaseEvent(liveEvent());

        assertEquals(List.of(OPEN), handler.handled, "只有没静音的那个群该交给处理器");
        assertEquals(1, capture.events.size(), "丢弃只记一条: " + capture.events);
        TimelineEvent drop = capture.events.get(0);
        assertEquals(TimelineEventType.PUSH_MUTED, drop.type());
        assertEquals("1", drop.detail().get("targets"), "会话数只算被拦的那几个");
        assertTrue(drop.text().contains("1 个会话"), drop.text());
        assertEquals("主播甲", drop.streamer());
    }

    @Test
    @DisplayName("全局静音中、一个群设了「不静音」：那个群照发，另一个被丢")
    void offSessionPassesDuringGlobalQuiet() {
        SessionQuietHoursService sessions = sessions();
        sessions.set(PLATFORM, OPEN, Mode.OFF, null, null);

        Capture capture = new Capture();
        RecordingHandler handler = new RecordingHandler();
        NovaHandlerListener listener = new NovaHandlerListener(dataSource(handler, MUTED, OPEN),
                new PushGate(quietNow(), sessions), capture);

        listener.onNovaExternalBaseEvent(liveEvent());

        assertEquals(List.of(OPEN), handler.handled);
        assertEquals(1, capture.events.size());
        assertEquals("1", capture.events.get(0).detail().get("targets"));
    }

    @Test
    @DisplayName("全局开关关着：两个群都丢，记「暂停丢弃」2 个会话（与现行同）")
    void masterSwitchStillDropsAll() {
        SessionQuietHoursService sessions = sessions();
        sessions.set(PLATFORM, OPEN, Mode.OFF, null, null);
        NovaCoreProperties properties = new NovaCoreProperties();
        properties.getPush().setEnabled(false);

        Capture capture = new Capture();
        RecordingHandler handler = new RecordingHandler();
        NovaHandlerListener listener = new NovaHandlerListener(dataSource(handler, MUTED, OPEN),
                new PushGate(properties, sessions), capture);

        listener.onNovaExternalBaseEvent(liveEvent());

        assertTrue(handler.handled.isEmpty());
        assertEquals(1, capture.events.size());
        assertEquals(TimelineEventType.PUSH_PAUSED, capture.events.get(0).type());
        assertEquals("2", capture.events.get(0).detail().get("targets"));
    }

    @Test
    @DisplayName("命令回复发往正在静音的会话：那条被丢并逐条记时间线，发往别的会话的不记丢弃")
    void commandReplyToMutedSessionIsDropped() {
        SessionQuietHoursService sessions = sessions();
        sessions.set(PLATFORM, MUTED, Mode.CUSTOM, aroundNow()[0], aroundNow()[1]);

        Capture capture = new Capture();
        NovaMessageSender sender = sender(new PushGate(new NovaCoreProperties(), sessions), capture);

        sender.send(reply(MUTED));
        sender.send(reply(OPEN));

        List<TimelineEvent> drops = capture.events.stream()
                .filter(event -> event.type() == TimelineEventType.PUSH_MUTED)
                .toList();
        assertEquals(1, drops.size(), "只有发往静音会话的那一条被丢: " + capture.events);
        assertEquals("群 " + MUTED, drops.get(0).channel());
        assertEquals("命令回复", drops.get(0).detail().get("summary"));
    }

    private static String[] aroundNow() {
        // 按此刻现算前后各一小时：写死的窗口会在午夜前后那一分钟落到窗外
        LocalTime now = LocalTime.now();
        DateTimeFormatter format = DateTimeFormatter.ofPattern("HH:mm");
        return new String[]{now.minusHours(1).format(format), now.plusHours(1).format(format)};
    }

    private static NovaCoreProperties quietNow() {
        NovaCoreProperties properties = new NovaCoreProperties();
        properties.getPush().setQuietStart(aroundNow()[0]);
        properties.getPush().setQuietEnd(aroundNow()[1]);
        return properties;
    }

    private static SessionQuietHoursService sessions() {
        return new SessionQuietHoursService(new NovaStateStore(new NovaCoreProperties()));
    }

    private static NovaExternalBaseEvent liveEvent() {
        return new NovaExternalBaseEvent(LIVE_PLATFORM, new LiveStreamerInfo(10001L, "主播甲", 20002L));
    }

    private static AbstractDataSource dataSource(NovaEventHandler handler, Long... groups) {
        PushUser user = new PushUser();
        user.setPlatform(LIVE_PLATFORM);
        user.setUid(10001L);
        user.setUname("主播甲");

        for (Long num : groups) {
            PushTarget target = new PushTarget();
            target.setPlatform(PLATFORM);
            target.setType(PushTargetType.GROUP);
            target.setNum(num);

            PushMessage message = new PushMessage();
            message.setTarget(target);
            message.setHandlerInstance(handler);
            message.setEventClass(NovaExternalBaseEvent.class);
            target.getMessages().add(message);
            user.getTargets().add(target);
        }

        AbstractDataSource dataSource = mock(AbstractDataSource.class);
        when(dataSource.getUser(LIVE_PLATFORM, 10001L)).thenReturn(Optional.of(user));
        return dataSource;
    }

    private static NovaMessageSender sender(PushGate gate, TimelineWriter timeline) {
        // 不给推送平台：没被拦的那一条走「找不到平台」那一支就停，不起发送线程
        NovaSenderService senderService = mock(NovaSenderService.class);
        when(senderService.getSender(PLATFORM)).thenReturn(Optional.empty());

        @SuppressWarnings("unchecked")
        ObjectProvider<AtAllPermissionResolver> resolvers = mock(ObjectProvider.class);
        when(resolvers.iterator()).thenAnswer(invocation -> List.<AtAllPermissionResolver>of().iterator());

        NovaCoreProperties properties = new NovaCoreProperties();
        return new NovaMessageSender(mock(HttpUtil.class), senderService,
                new PushActivityRecorder(TimelineWriter.NONE), gate,
                timeline, new AtAllQuotaService(properties), resolvers,
                new FirstPushTipService(new NovaStateStore(properties)));
    }

    private static Message reply(Long num) {
        Message message = Message.create(PLATFORM, PushTargetType.GROUP, num, "命令回复").get(0);
        message.setCreateTime(Instant.now());
        return message;
    }
}
