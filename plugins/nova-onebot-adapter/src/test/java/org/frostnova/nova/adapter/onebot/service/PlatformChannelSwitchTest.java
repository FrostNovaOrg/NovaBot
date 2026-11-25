package org.frostnova.nova.adapter.onebot.service;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.adapter.onebot.alert.QqAlertChannel;
import org.frostnova.nova.adapter.onebot.config.OneBotAdapterPluginProperties;
import org.frostnova.nova.adapter.onebot.config.OneBotRuntimeAppliers;
import org.frostnova.nova.adapter.onebot.controller.OneBotController;
import org.frostnova.nova.adapter.onebot.converter.OneBotMessageConverter;
import org.frostnova.nova.adapter.onebot.dto.MessageDTO;
import org.frostnova.nova.adapter.onebot.health.OneBotConnectionState;
import org.frostnova.nova.adapter.onebot.health.OneBotConnectionTester;
import org.frostnova.nova.adapter.onebot.http.OneBotHttpAdapter;
import org.frostnova.nova.adapter.onebot.security.PushApiTokenStore;
import org.frostnova.nova.core.alert.AlertChannel;
import org.frostnova.nova.core.alert.AlertService;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.ConfigUiController;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.health.PushActivityRecorder;
import org.frostnova.nova.core.model.Message;
import org.frostnova.nova.core.model.Sender;
import org.frostnova.nova.core.sender.AtAllPermissionResolver;
import org.frostnova.nova.core.sender.FirstPushTipService;
import org.frostnova.nova.core.sender.NovaMessageSender;
import org.frostnova.nova.core.sender.PushGate;
import org.frostnova.nova.core.service.AtAllQuotaService;
import org.frostnova.nova.core.service.NovaSenderService;
import org.frostnova.nova.core.timeline.TimelineEvent;
import org.frostnova.nova.core.timeline.TimelineEventType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.socket.WebSocketSession;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlatformChannelSwitchTest {
    private static final String NAPCAT = "qq-onebot";
    private static final String OFFICIAL = "qq-official";
    private final OneBotAdapterPluginProperties properties = new OneBotAdapterPluginProperties();
    private final OneBotHttpAdapter api = mock(OneBotHttpAdapter.class);
    private final TaskScheduler scheduler = mock(TaskScheduler.class);
    private final ScheduledFuture<?> periodic = mock(ScheduledFuture.class);
    private final StaticListableBeanFactory beans = new StaticListableBeanFactory();
    private final OneBotConnectionState state = new OneBotConnectionState();
    private final NovaCoreProperties core = new NovaCoreProperties();
    private final NovaSenderService platforms = new NovaSenderService(core);
    private final List<TimelineEvent> timeline = new CopyOnWriteArrayList<>();
    private final PushActivityRecorder history = mock(PushActivityRecorder.class);
    private final List<String> officialPosts = new CopyOnWriteArrayList<>();
    private OneBotWebsocketService websocket;
    private OneBotController controller;
    private OneBotTargetDirectory directory;
    private OneBotConnectionManager connections;
    private OneBotRuntimeAppliers settings;
    private NovaMessageSender messages;

    @BeforeEach
    void start() {
        var workers = mock(ThreadPoolTaskExecutor.class);
        var http = new OneBotHttpService(scheduler, workers, properties, api, new OneBotMessageConverter(), state);
        websocket = new OneBotWebsocketService(scheduler, workers, properties, http, state, mock(ApplicationEventPublisher.class));
        directory = new OneBotTargetDirectory(api, http, properties);
        beans.addBean("targets", directory);
        var web = mock(WebServerApplicationContext.class);
        when(web.getWebServer()).thenReturn(mock(WebServer.class));
        controller = new OneBotController(web, mock(RequestMappingHandlerMapping.class), properties, platforms, http,
                new PushApiTokenStore());
        connections = new OneBotConnectionManager(properties, controller, websocket, http, state,
                beans.getBeanProvider(OneBotTargetDirectory.class));
        beans.addBean("connections", connections);
        settings = new OneBotRuntimeAppliers(properties, beans.getBeanProvider(OneBotConnectionManager.class));

        when(api.getVersionInfo(any(), any())).thenReturn(JSONObject.of("app_version", "test"));
        when(api.getLoginInfo(any(), any())).thenReturn(JSONObject.of("nickname", "test"));
        when(api.getStatus(any(), any())).thenReturn(JSONObject.of("online", true, "good", true));
        doReturn(periodic).when(scheduler).scheduleAtFixedRate(any(Runnable.class), any(Instant.class), any(Duration.class));
        when(api.sendGroupMsg(any(), any())).thenReturn(JSONObject.of("message_id", "test"));
        connections.apply("localhost", 3000, 3001, "test", "test");
        properties.getSenders().get(0).setDelay(0);
        platforms.addSender(new Sender(OFFICIAL, "", "", 0, (headers, body) -> {
            officialPosts.add(body.get("content").toString());
            return JSONObject.of("code", 0);
        }));
        messages = new NovaMessageSender(null, platforms, history, new PushGate(core), timeline::add,
                new AtAllQuotaService(core), beans.getBeanProvider(AtAllPermissionResolver.class), mock(FirstPushTipService.class));
        messages.setPlatformGates(List.of(connections));
    }

    @AfterEach
    void stop() {
        if (messages != null) messages.shutdown();
    }

    private void enabled(boolean value) {
        settings.appliers().get(OneBotAdapterPluginProperties.NapCat.ENABLED_KEY).accept(Boolean.toString(value));
    }

    private Message group(String platform, String text) {
        return Message.create(platform, PushTargetType.GROUP, 12345L, text).get(0);
    }

    @Test
    @DisplayName("停用只丢弃个人号推送，官方机器人仍送达，测试消息与推送接口当场返回原因")
    void blocksOnlyThisPlatform() throws Exception {
        properties.getAlert().setPlatform(NAPCAT);
        properties.getAlert().setNum(12345L);
        var alerts = new QqAlertChannel(properties, messages, platforms, state);
        var tester = new OneBotConnectionTester(api, properties, connections);
        assertFalse(tester.channelSwitch().orElseThrow().alertWarning().isEmpty());
        properties.getAlert().setPlatform(OFFICIAL);
        assertTrue(tester.channelSwitch().orElseThrow().alertWarning().isEmpty());
        properties.getAlert().setPlatform(NAPCAT);
        assertTrue(alerts.isAvailable());
        enabled(false);
        assertFalse(alerts.isAvailable());
        assertTrue(directory.snapshots(true).isEmpty());
        messages.send(group(NAPCAT, "开播通知"));
        assertEquals(0, messages.getPendingCount());
        assertEquals(TimelineEventType.PUSH_PAUSED, timeline.get(0).type());
        assertEquals("NapCat 通道已停用，没有发出", timeline.get(0).text());
        messages.send(Message.create(NAPCAT, PushTargetType.FRIEND, 12345L, "命令回复").get(0));
        assertEquals(0, messages.getPendingCount());
        assertEquals(TimelineEventType.PUSH_PAUSED, timeline.get(1).type());
        var console = mock(ConfigUiController.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(console, "messageSender", messages);
        var response = console.testMessage(JSONObject.of("platform", NAPCAT, "type", PushTargetType.GROUP.getCode(), "num", 12345));
        assertFalse(response.getBooleanValue("success"));
        assertEquals("NapCat 通道已停用", response.getString("message"));
        var dto = new MessageDTO();
        dto.setPlatform(NAPCAT);
        assertEquals("NapCat 通道已停用", controller.send(dto).getString("message"));
        verify(api, never()).sendGroupMsg(any(), any());
        verify(api, never()).sendPrivateMsg(any(), any());
        verifyNoInteractions(history);

        var official = group(OFFICIAL, "开播通知");
        var sent = new CountDownLatch(1);
        official.addOnSuccessCallback(sent::countDown);
        messages.send(official);
        assertTrue(sent.await(10, TimeUnit.SECONDS));
        assertEquals(List.of("开播通知"), officialPosts);
    }

    @Test
    @DisplayName("其它通道重试停用期间的告警时，即使 NapCat 已重开也不会转发到 QQ")
    void otherChannelsRetryWithoutReplayingOldQqAlerts() {
        properties.getAlert().setPlatform(NAPCAT);
        properties.getAlert().setType(PushTargetType.GROUP.getCode());
        properties.getAlert().setNum(12345L);
        var qq = spy(new QqAlertChannel(properties, messages, platforms, state));
        var webhook = mock(AlertChannel.class);
        when(webhook.isAvailable()).thenReturn(true);
        when(webhook.name()).thenReturn("Webhook");
        doAnswer(call -> {
            call.<Consumer<AlertChannel.SendResult>>getArgument(2).accept(AlertChannel.SendResult.failed("暂时无法送达"));
            return null;
        }).doAnswer(call -> {
            call.<Consumer<AlertChannel.SendResult>>getArgument(2).accept(AlertChannel.SendResult.sent());
            return null;
        }).when(webhook).sendReporting(anyString(), anyString(), any());
        beans.addBean("qq", qq);
        beans.addBean("webhook", webhook);
        var alerts = new AlertService(core, beans.getBeanProvider(AlertChannel.class), timeline::add);
        try {
            enabled(false);
            alerts.alert("connection", "连接提醒", "请检查");
            assertEquals(1, alerts.pendingCount());
            enabled(true);
            ReflectionTestUtils.invokeMethod(alerts, "retryPending");
            verify(webhook, times(2)).sendReporting(anyString(), anyString(), any());
            verify(qq, never()).sendReporting(anyString(), anyString(), any());
            assertEquals(0, alerts.pendingCount());
        } finally {
            alerts.onContextClosedEvent();
        }
    }

    @Test
    @DisplayName("停用立即断开并撤掉定时体检，保存地址后重新启用用新地址恢复收发")
    void reconnectsOnEnable() throws Exception {
        var session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        websocket.handlerOf(NAPCAT).afterConnectionEstablished(session);
        enabled(false);
        verify(session).close();
        verify(periodic).cancel(false);
        assertNull(websocket.handlerOf(NAPCAT));
        clearInvocations(api);
        var saved = connections.apply("localhost", 3100, 3101, "", "");
        assertFalse(saved.live());
        assertEquals("3100", saved.configuration().get("one-bot-http-port"));
        assertEquals("test", saved.configuration().get("one-bot-http-token"));
        verifyNoInteractions(api);
        enabled(true);
        assertNotNull(websocket.handlerOf(NAPCAT));
        verify(api).getStatus(any(), any());
        assertEquals(0, messages.sendNow(group(NAPCAT, "恢复")).getIntValue("code"));
        verify(api).sendGroupMsg(argThat(sender -> sender.getOneBotHttpPort() == 3100), any());
    }

    @Test
    @DisplayName("关后马上重开，排在首条请求后面的旧消息也不会补发")
    void queuedMessageExpiresAcrossSwitchCycle() throws Exception {
        var inFlight = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(api.sendGroupMsg(any(), any())).thenAnswer(invocation -> {
            inFlight.countDown();
            assertTrue(release.await(15, TimeUnit.SECONDS));
            return JSONObject.of("message_id", "first");
        });
        try {
            messages.send(group(NAPCAT, "第一条"));
            assertTrue(inFlight.await(10, TimeUnit.SECONDS));
            var queued = group(NAPCAT, "已排队");
            var finished = new CountDownLatch(1);
            queued.addOnCompleteCallback(finished::countDown);
            messages.send(queued);
            assertEquals(1, messages.getPendingCount());
            enabled(false);
            enabled(true);
            release.countDown();
            assertTrue(finished.await(10, TimeUnit.SECONDS));
            assertTrue(queued.isBlockedByPushSwitch());
            assertEquals(1, timeline.stream().filter(event -> event.type() == TimelineEventType.PUSH_PAUSED).count());
            verify(api, times(1)).sendGroupMsg(any(), any());
        } finally {
            release.countDown();
        }
    }
}
