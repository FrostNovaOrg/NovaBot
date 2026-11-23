package org.frostnova.nova.core.command;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.event.remote.NovaRemoteMessageEvent;
import org.frostnova.nova.core.health.PushActivityRecorder;
import org.frostnova.nova.core.model.Message;
import org.frostnova.nova.core.model.PushTarget;
import org.frostnova.nova.core.model.PushUser;
import org.frostnova.nova.core.model.Sender;
import org.frostnova.nova.core.sender.AtAllPermissionResolver;
import org.frostnova.nova.core.sender.FirstPushTipService;
import org.frostnova.nova.core.sender.NovaMessageSender;
import org.frostnova.nova.core.sender.PushGate;
import org.frostnova.nova.core.service.AtAllQuotaService;
import org.frostnova.nova.core.service.NovaSenderService;
import org.frostnova.nova.core.service.NovaStateStore;
import org.frostnova.nova.core.timeline.TimelineWriter;
import org.frostnova.nova.core.util.HttpUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommandReplyDeliveryTest {
    private static final String PLATFORM = "test-platform";
    private static final long GROUP = 30003L;
    private static final String COMMAND = "测试命令";
    private static final String REPLY = "已执行";

    private final BlockingQueue<Map<String, Object>> deliveries = new LinkedBlockingQueue<>();
    private CommandFallback command;
    private CommandFollowUp followUp;
    private CommandSettingsService settings;
    private NovaMessageSender sender;
    private CommandDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        NovaCoreProperties properties = new NovaCoreProperties();

        PushTarget target = new PushTarget();
        target.setPlatform(PLATFORM);
        target.setType(PushTargetType.GROUP);
        target.setNum(GROUP);
        PushUser user = new PushUser();
        user.setTargets(List.of(target));
        AbstractDataSource dataSource = mock(AbstractDataSource.class);
        when(dataSource.getAllUsers()).thenReturn(List.of(user));

        Sender adapter = new Sender();
        adapter.setName(PLATFORM);
        adapter.setDelay(0);
        adapter.setLocalDelivery((headers, params) -> {
            deliveries.add(params);
            if (params.get("content").toString().contains("{image_url=")) {
                return JSONObject.of("code", 2, "message", "图片不可用");
            }
            return JSONObject.of("code", 0);
        });
        NovaSenderService adapters = mock(NovaSenderService.class);
        when(adapters.getSender(PLATFORM)).thenReturn(Optional.of(adapter));

        command = mock(CommandFallback.class, CALLS_REAL_METHODS);
        when(command.name()).thenReturn(COMMAND);
        when(command.execute(any())).thenReturn(CommandReply.of(REPLY));
        followUp = mock(CommandFollowUp.class);
        StaticListableBeanFactory beans = new StaticListableBeanFactory(Map.of("command", command, "followUp", followUp));

        sender = spy(new NovaMessageSender(mock(HttpUtil.class), adapters,
                new PushActivityRecorder(TimelineWriter.NONE), new PushGate(properties), TimelineWriter.NONE,
                new AtAllQuotaService(properties), beans.getBeanProvider(AtAllPermissionResolver.class),
                mock(FirstPushTipService.class)));
        settings = new CommandSettingsService(new NovaStateStore(properties));
        dispatcher = new CommandDispatcher(beans.getBeanProvider(NovaCommand.class),
                beans.getBeanProvider(CommandFollowUp.class), settings,
                dataSource, sender, properties, TimelineWriter.NONE, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
    }

    @AfterEach
    void stopSender() {
        if (sender != null) sender.shutdown();
    }

    @Test
    void deliversReplyWithOriginalMessageId() throws InterruptedException {
        // 官方群里 @ 机器人发命令，回复被当成主动消息；群主没开开关时一句都回不出来。
        NovaRemoteMessageEvent event = JSONObject.of("platform", PLATFORM, "messageType", "group",
                "num", GROUP, "senderUid", 1L, "text", COMMAND, "mentionsBot", true,
                "messageId", "m1").toJavaObject(NovaRemoteMessageEvent.class);

        dispatcher.onRemoteMessage(event);

        Map<String, Object> params = takeDelivery();
        assertEquals(REPLY, params.get("content"));
        assertEquals("m1", params.get("reply_to"));
        verify(sender).send(any(Message.class));

        ArgumentCaptor<CommandContext> contexts = ArgumentCaptor.forClass(CommandContext.class);
        verify(command).beforeCooldown(contexts.capture());
        verify(command).execute(contexts.capture());
        assertEquals(List.of("m1", "m1"), contexts.getAllValues().stream().map(CommandContext::getMessageId).toList());
    }

    @Test
    void deliversExactlyTheExistingParametersWithoutAnOriginalMessageId() throws InterruptedException {
        // 接入官方机器人后，原有适配器收到的投递参数变了，原有群的回复出错。
        dispatcher.onRemoteMessage(new NovaRemoteMessageEvent(PLATFORM, "group", GROUP, 1L, COMMAND, null, true));

        ArgumentCaptor<Message> reply = ArgumentCaptor.forClass(Message.class);
        verify(sender).send(reply.capture());
        assertEquals(Map.of("platform", PLATFORM, "type", 1, "num", GROUP, "content", REPLY,
                "sequence", reply.getValue().getSequence(),
                "create_time", reply.getValue().getCreateTime().toEpochMilli()), takeDelivery());
    }

    @Test
    void splitRepliesAllReferToTheSameMessage() throws InterruptedException {
        when(command.execute(any())).thenReturn(CommandReply.of("甲{next}乙{next}丙"));

        dispatcher.onRemoteMessage(groupMessage(COMMAND, true, "m1"));

        verify(sender, times(3)).send(any(Message.class));
        for (String content : List.of("甲", "乙", "丙")) {
            Map<String, Object> params = takeDelivery();
            assertEquals(content, params.get("content"));
            assertEquals("m1", params.get("reply_to"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " \t\n\u3000"})
    void omitsBlankMessageId(String messageId) throws InterruptedException {
        dispatcher.onRemoteMessage(groupMessage(COMMAND, true, messageId));

        assertFalse(takeDelivery().containsKey("reply_to"));
    }

    @Test
    void repliesToTheClaimedFollowUpMessage() throws InterruptedException {
        when(followUp.claim(any())).thenReturn(CommandFollowUp.Claimed.answer(CommandReply.of("已选择第二项")));

        dispatcher.onRemoteMessage(groupMessage("2", false, "m2"));

        Map<String, Object> params = takeDelivery();
        assertEquals("已选择第二项", params.get("content"));
        assertEquals("m2", params.get("reply_to"));
        ArgumentCaptor<CommandContext> context = ArgumentCaptor.forClass(CommandContext.class);
        verify(followUp).claim(context.capture());
        assertEquals("m2", context.getValue().getMessageId());
    }

    @Test
    void exposesTheMessageIdWhileChoosingAFallbackCommand() throws InterruptedException {
        when(command.acceptsFallback(any())).thenReturn(true);

        dispatcher.onRemoteMessage(groupMessage("查询", true, "m1"));

        assertEquals("m1", takeDelivery().get("reply_to"));
        ArgumentCaptor<CommandContext> context = ArgumentCaptor.forClass(CommandContext.class);
        verify(command).acceptsFallback(context.capture());
        assertEquals("m1", context.getValue().getMessageId());
    }

    @Test
    void cooldownNoticeUsesTheCurrentMessageId() throws InterruptedException {
        dispatcher.onRemoteMessage(groupMessage(COMMAND, true, "m1"));
        dispatcher.onRemoteMessage(groupMessage(COMMAND, true, "m2"));
        when(command.beforeCooldown(any())).thenReturn(CommandReply.of("请稍后再试"));
        dispatcher.onRemoteMessage(groupMessage(COMMAND, true, "m3"));

        verify(command).execute(any());
        verify(sender, times(2)).send(any(Message.class));
        assertEquals("m1", takeDelivery().get("reply_to"));
        Map<String, Object> notice = takeDelivery();
        assertEquals("请稍后再试", notice.get("content"));
        assertEquals("m3", notice.get("reply_to"));
    }

    @Test
    void disabledCommandNoticeRefersToTheOriginalMessage() throws InterruptedException {
        settings.disable(PLATFORM, PushTargetType.GROUP, GROUP, COMMAND);

        dispatcher.onRemoteMessage(groupMessage(COMMAND, true, "m1"));

        Map<String, Object> params = takeDelivery();
        assertTrue(params.get("content").toString().contains("已关闭"));
        assertEquals("m1", params.get("reply_to"));
    }

    @Test
    void permissionNoticeRefersToTheOriginalMessage() throws InterruptedException {
        when(command.requiresAdmin()).thenReturn(true);

        dispatcher.onRemoteMessage(groupMessage(COMMAND, true, "m1"));

        Map<String, Object> params = takeDelivery();
        assertTrue(params.get("content").toString().contains("仅群主"));
        assertEquals("m1", params.get("reply_to"));
    }

    @Test
    void imageFallbackRetainsTheOriginalMessageId() throws InterruptedException {
        when(command.execute(any())).thenReturn(CommandReply.of("图片说明 {image_url=https://example.test/cover.jpg}"));

        dispatcher.onRemoteMessage(groupMessage(COMMAND, true, "m1"));

        Map<String, Object> first = takeDelivery();
        Map<String, Object> retry = takeDelivery();
        assertEquals("m1", first.get("reply_to"));
        assertEquals("图片说明", retry.get("content"));
        assertEquals("m1", retry.get("reply_to"));
        assertEquals(first.get("sequence"), retry.get("sequence"));
    }

    @Test
    void pushAfterACommandReplyHasNoOriginalMessageId() throws InterruptedException {
        dispatcher.onRemoteMessage(groupMessage(COMMAND, true, "m1"));
        sender.send(Message.create(PLATFORM, PushTargetType.GROUP, GROUP, "开播啦").get(0));

        assertEquals("m1", takeDelivery().get("reply_to"));
        Map<String, Object> push = takeDelivery();
        assertEquals("开播啦", push.get("content"));
        assertFalse(push.containsKey("reply_to"));
    }

    @Test
    void throwingCommandRemainsSilentEvenWithAMessageId() {
        when(command.execute(any())).thenThrow(new IllegalStateException("命令执行失败"));

        dispatcher.onRemoteMessage(groupMessage(COMMAND, true, "m1"));

        verify(command).execute(any());
        verify(sender, never()).send(any(Message.class));
    }

    private NovaRemoteMessageEvent groupMessage(String text, boolean mentionsBot, String messageId) {
        NovaRemoteMessageEvent event = new NovaRemoteMessageEvent(PLATFORM, "group", GROUP, 1L, text, "member", mentionsBot);
        event.setMessageId(messageId);
        return event;
    }

    private Map<String, Object> takeDelivery() throws InterruptedException {
        Map<String, Object> params = deliveries.poll(5, TimeUnit.SECONDS);
        assertNotNull(params, "未收到投递参数");
        return params;
    }
}
