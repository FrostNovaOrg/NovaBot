package com.starlwr.bot.adapter.onebot.service;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.adapter.onebot.model.OneBotSender;
import com.starlwr.bot.core.event.remote.StarBotRemoteMessageEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import java.lang.reflect.Constructor;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 上报消息的应答面
 * <p>
 * 适配器收到聊天消息后只该做一件事：把它变成事件交给核心，由命令分发器统一决定说不说话、说什么。
 * 在这里直接回话会绕过全部横切约束——不看有没有 @、不看这个会话配没配推送、不吃冷却，
 * 也不进菜单。这把尺就守这一条：<b>除了发事件，适配器自己不往回说话</b>。
 * <p>
 * 直接驱动真的 WebSocket 处理器，而不是读源码找关键字：换个写法就绕过去的判据，
 * 量的是写法不是行为。
 */
@DisplayName("上报消息的应答面")
class OneBotRawMessageReplyTest {
    private static final String SENDER_NAME = "测试机器人";

    private WebSocketSession session;

    private ApplicationEventPublisher publisher;

    private WebSocketHandler handler;

    @BeforeEach
    void setUp() throws Exception {
        session = mock(WebSocketSession.class);
        publisher = mock(ApplicationEventPublisher.class);

        OneBotSender sender = new OneBotSender();
        sender.setName(SENDER_NAME);

        handler = newHandler(newService(), sender);
    }

    @Test
    @DisplayName("裸「status」不该被直接回话")
    void bareStatusGetsNoReply() throws Exception {
        handler.handleMessage(session, message("status"));

        verify(session, never()).sendMessage(any());
    }

    @Test
    @DisplayName("大小写变体同样不回话")
    void statusVariantsGetNoReply() throws Exception {
        for (String text : new String[]{"STATUS", "Status"}) {
            handler.handleMessage(session, message(text));
        }

        verify(session, never()).sendMessage(any());
    }

    @Test
    @DisplayName("阳性对照：消息本身仍会作为事件发给核心")
    void messageStillReachesTheCore() throws Exception {
        handler.handleMessage(session, message("菜单"));

        verify(publisher).publishEvent(any(StarBotRemoteMessageEvent.class));
    }

    /**
     * 一条群聊上报
     */
    private WebSocketMessage<?> message(String rawMessage) {
        JSONObject json = new JSONObject();
        json.put("post_type", "message");
        json.put("message_type", "group");
        json.put("group_id", 30003L);
        json.put("user_id", 2000000002L);
        json.put("self_id", 1000000001L);
        json.put("raw_message", rawMessage);
        json.put("message", rawMessage);
        return new TextMessage(json.toJSONString());
    }

    /**
     * 造一个依赖全为替身的服务
     * <p>
     * 构造参数按类型现填，不写死参数表：这把尺守的是行为，不该因为服务少了个依赖就编不过。
     */
    private OneBotWebsocketService newService() throws Exception {
        Constructor<?> constructor = OneBotWebsocketService.class.getDeclaredConstructors()[0];
        Class<?>[] types = constructor.getParameterTypes();
        Object[] args = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            if (types[i] == ThreadPoolTaskExecutor.class) {
                args[i] = inlineExecutor();
            } else if (types[i] == ApplicationEventPublisher.class) {
                args[i] = publisher;
            } else {
                args[i] = mock(types[i]);
            }
        }

        constructor.setAccessible(true);
        return (OneBotWebsocketService) constructor.newInstance(args);
    }

    /**
     * 就地跑完提交进来的活，免得断言跑在处理之前
     */
    private ThreadPoolTaskExecutor inlineExecutor() {
        ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return CompletableFuture.completedFuture(null);
        }).when(executor).submit(any(Runnable.class));
        return executor;
    }

    private WebSocketHandler newHandler(OneBotWebsocketService service, OneBotSender sender) throws Exception {
        Class<?> type = Class.forName(OneBotWebsocketService.class.getName() + "$OneBotWebSocketHandler");
        Constructor<?> constructor = type.getDeclaredConstructor(OneBotWebsocketService.class, OneBotSender.class);
        constructor.setAccessible(true);
        return (WebSocketHandler) constructor.newInstance(service, sender);
    }
}
