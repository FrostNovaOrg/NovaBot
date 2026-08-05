package com.starlwr.bot.core.sender;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.enums.PushTargetType;
import com.starlwr.bot.core.health.PushActivityRecorder;
import com.starlwr.bot.core.model.Message;
import com.starlwr.bot.core.model.Sender;
import com.starlwr.bot.core.service.StarBotSenderService;
import com.starlwr.bot.core.util.HttpUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 消息发送器测试
 * <p>
 * 覆盖投递环节的三处加固：网络抖动重试、响应缺字段不再空指针、静音时不投递。
 */
@DisplayName("消息发送器")
class StarBotMessageSenderTest {
    private static final String PLATFORM = "qq-onebot";

    @Test
    @DisplayName("请求失败应重试, 成功后不再继续")
    void retriesOnFailure() {
        HttpUtil http = mock(HttpUtil.class);
        AtomicInteger attempts = new AtomicInteger();

        when(http.postJson(anyString(), anyMap(), anyMap())).thenAnswer(invocation -> {
            if (attempts.incrementAndGet() < 3) {
                throw new IllegalStateException("连接被拒绝");
            }
            return new JSONObject().fluentPut("code", 0).fluentPut("id", "m1");
        });

        JSONObject result = sender(http).sendNow(message());

        assertEquals(3, attempts.get(), "应重试到第三次");
        assertEquals(0, result.getInteger("code"));
    }

    @Test
    @DisplayName("重试用尽后应返回错误结果, 而非抛出异常")
    void returnsErrorAfterRetriesExhausted() {
        HttpUtil http = mock(HttpUtil.class);
        when(http.postJson(anyString(), anyMap(), anyMap())).thenThrow(new IllegalStateException("连接被拒绝"));

        JSONObject result = sender(http).sendNow(message());

        assertNotNull(result);
        assertNotEquals(0, result.getInteger("code"));
        assertTrue(result.getString("message").contains("投递失败"), result.getString("message"));
    }

    @Test
    @DisplayName("响应缺少 code 字段时不应空指针")
    void toleratesMissingCode() {
        HttpUtil http = mock(HttpUtil.class);
        // 缺 code 字段时旧实现会在自动拆箱处抛出 NPE，表现为消息静默丢失而日志指向别处
        when(http.postJson(anyString(), anyMap(), anyMap())).thenReturn(new JSONObject().fluentPut("msg", "ok"));

        assertDoesNotThrow(() -> sender(http).sendNow(message()));
    }

    @Test
    @DisplayName("静音期间入队的消息应被丢弃, 但测试消息不受影响")
    void quietHoursBlockQueueButNotTestMessage() {
        HttpUtil http = mock(HttpUtil.class);
        when(http.postJson(anyString(), anyMap(), anyMap())).thenReturn(new JSONObject().fluentPut("code", 0));

        StarBotCoreProperties properties = new StarBotCoreProperties();
        properties.getPush().setEnabled(false);

        StarBotMessageSender messageSender = sender(http, properties);

        messageSender.send(message());
        assertEquals(0, messageSender.getPendingCount(), "全局开关关闭时不应入队");

        // 测试消息用于验证配置，被静音拦下只会让人误以为配置又出了问题
        assertDoesNotThrow(() -> messageSender.sendNow(message()));
        verify(http, atLeastOnce()).postJson(anyString(), anyMap(), anyMap());
    }

    private Message message() {
        List<Message> messages = Message.create(PLATFORM, PushTargetType.GROUP, 12345L, "测试内容");
        Message message = messages.get(0);
        message.setCreateTime(Instant.now());
        return message;
    }

    private StarBotMessageSender sender(HttpUtil http) {
        return sender(http, new StarBotCoreProperties());
    }

    @Test
    @DisplayName("@全体成员 独占一条时，超配额应整条跳过而不是发出一条空消息")
    void skipsStandaloneAtAllWhenQuotaExhausted() {
        // Message.create 在 {next} 处就把消息拆开了，at_all 拼出的
        // 「{at=all}{next}正文」会变成两条，第一条只有占位符
        StarBotCoreProperties properties = new StarBotCoreProperties();
        properties.getPush().setAtAllDailyLimit(1);

        HttpUtil http = okHttp();
        StarBotMessageSender sender = sender(http, properties);

        sender.sendNow(standaloneAtAll());
        sender.sendNow(standaloneAtAll());

        ArgumentCaptor<Map<String, Object>> captor = paramsCaptor();
        // 第二条整条跳过，因此只该有一次投递
        verify(http, times(1)).postJson(anyString(), any(), captor.capture());
        assertEquals("{at=all}", captor.getValue().get("content"));
    }

    @Test
    @DisplayName("@全体成员 与正文同条时，超配额应只摘掉占位符、保留正文")
    void stripsAtAllButKeepsContent() {
        StarBotCoreProperties properties = new StarBotCoreProperties();
        properties.getPush().setAtAllDailyLimit(1);

        HttpUtil http = okHttp();
        StarBotMessageSender sender = sender(http, properties);

        sender.sendNow(inlineAtAll());
        sender.sendNow(inlineAtAll());

        ArgumentCaptor<Map<String, Object>> captor = paramsCaptor();
        verify(http, times(2)).postJson(anyString(), any(), captor.capture());
        assertTrue(String.valueOf(captor.getAllValues().get(0).get("content")).contains("{at=all}"));
        assertFalse(String.valueOf(captor.getAllValues().get(1).get("content")).contains("{at=all}"));
        assertTrue(String.valueOf(captor.getAllValues().get(1).get("content")).contains("开播啦"),
                "正文必须保留");
    }

    @Test
    @DisplayName("私聊不占 @全体成员 的配额")
    void privateChatDoesNotConsumeQuota() {
        StarBotCoreProperties properties = new StarBotCoreProperties();
        properties.getPush().setAtAllDailyLimit(1);

        HttpUtil http = okHttp();
        StarBotMessageSender sender = sender(http, properties);

        sender.sendNow(Message.create(PLATFORM, PushTargetType.FRIEND, 10000L, "{at=all}开播啦").get(0));
        // 私聊没消耗额度，群聊这一条仍应保留占位符
        sender.sendNow(inlineAtAll());

        ArgumentCaptor<Map<String, Object>> captor = paramsCaptor();
        verify(http, times(2)).postJson(anyString(), any(), captor.capture());
        assertTrue(String.valueOf(captor.getAllValues().get(1).get("content")).contains("{at=all}"));
    }

    private HttpUtil okHttp() {
        HttpUtil http = mock(HttpUtil.class);
        when(http.postJson(anyString(), any(), any())).thenReturn(
                new JSONObject().fluentPut("code", 0).fluentPut("id", "1"));
        return http;
    }

    private Message standaloneAtAll() {
        return Message.create(PLATFORM, PushTargetType.GROUP, 10000003L, "{at=all}{next}开播啦").get(0);
    }

    private Message inlineAtAll() {
        return Message.create(PLATFORM, PushTargetType.GROUP, 10000003L, "{at=all} 开播啦").get(0);
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<Map<String, Object>> paramsCaptor() {
        return ArgumentCaptor.forClass(Map.class);
    }

    private StarBotMessageSender sender(HttpUtil http, StarBotCoreProperties properties) {
        Sender target = new Sender();
        target.setName(PLATFORM);
        target.setUrl("http://127.0.0.1:7827/onebot/send");
        target.setDelay(0);

        StarBotSenderService senderService = mock(StarBotSenderService.class);
        when(senderService.getSender(PLATFORM)).thenReturn(Optional.of(target));

        return new StarBotMessageSender(http, senderService, new PushActivityRecorder(), new PushGate(properties),
                new com.starlwr.bot.core.service.AtAllQuotaService(properties));
    }
}
