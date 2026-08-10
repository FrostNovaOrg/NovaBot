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
import org.springframework.beans.factory.ObjectProvider;

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

    @Test
    @DisplayName("机器人没有 @全体成员 权限时应摘掉占位符")
    void stripsAtAllWithoutPermission() {
        // 实测过：无权限的账号经 OneBot 接口发 at:all 竟能真的 @ 到全体，
        // 那是 QQ 的漏洞。钻这个空子有风控风险，因此自己先拦下
        HttpUtil http = okHttp();
        StarBotMessageSender sender = sender(http, new StarBotCoreProperties(), false);

        sender.sendNow(inlineAtAll());

        ArgumentCaptor<Map<String, Object>> captor = paramsCaptor();
        verify(http).postJson(anyString(), any(), captor.capture());
        assertFalse(String.valueOf(captor.getValue().get("content")).contains("{at=all}"));
        assertTrue(String.valueOf(captor.getValue().get("content")).contains("开播啦"), "正文必须保留");
    }

    @Test
    @DisplayName("没有权限时不应消耗配额——那份额度是全账号共享的")
    void noPermissionDoesNotConsumeQuota() {
        StarBotCoreProperties properties = new StarBotCoreProperties();
        properties.getPush().setAtAllDailyLimit(1);

        HttpUtil http = okHttp();
        // 无权限的会话连发两次，都应只是被摘掉，而不该把那 1 次额度吃掉
        StarBotMessageSender denied = sender(http, properties, false);
        denied.sendNow(inlineAtAll());
        denied.sendNow(inlineAtAll());

        // 换一个有权限的发送器共用同一份配置，若额度已被吃掉这里就会被摘
        StarBotMessageSender allowed = sender(http, properties, true);
        allowed.sendNow(inlineAtAll());

        ArgumentCaptor<Map<String, Object>> captor = paramsCaptor();
        verify(http, times(3)).postJson(anyString(), any(), captor.capture());
        assertTrue(String.valueOf(captor.getAllValues().get(2).get("content")).contains("{at=all}"),
                "有权限的那条应仍保有额度");
    }

    @Test
    @DisplayName("有权限时应照常发出 @全体成员")
    void keepsAtAllWithPermission() {
        HttpUtil http = okHttp();
        StarBotMessageSender sender = sender(http, new StarBotCoreProperties(), true);

        sender.sendNow(inlineAtAll());

        ArgumentCaptor<Map<String, Object>> captor = paramsCaptor();
        verify(http).postJson(anyString(), any(), captor.capture());
        assertTrue(String.valueOf(captor.getValue().get("content")).contains("{at=all}"));
    }

    @Test
    @DisplayName("没有任何适配器认领该平台时应放行，保持原有行为")
    void allowsWhenNoResolver() {
        HttpUtil http = okHttp();
        StarBotMessageSender sender = sender(http, new StarBotCoreProperties(), null);

        sender.sendNow(inlineAtAll());

        ArgumentCaptor<Map<String, Object>> captor = paramsCaptor();
        verify(http).postJson(anyString(), any(), captor.capture());
        assertTrue(String.valueOf(captor.getValue().get("content")).contains("{at=all}"));
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

        return sender(http, properties, null);
    }

    /**
     * 造一个带指定权限判定的发送器；resolver 为 null 表示没有任何适配器认领该平台
     */
    private StarBotMessageSender sender(HttpUtil http, StarBotCoreProperties properties, Boolean canAtAll) {
        Sender target = new Sender();
        target.setName(PLATFORM);
        target.setUrl("http://127.0.0.1:7827/onebot/send");
        target.setDelay(0);

        StarBotSenderService senderService = mock(StarBotSenderService.class);
        when(senderService.getSender(PLATFORM)).thenReturn(Optional.of(target));

        @SuppressWarnings("unchecked")
        ObjectProvider<AtAllPermissionResolver> resolvers = mock(ObjectProvider.class);
        List<AtAllPermissionResolver> list = canAtAll == null ? List.of() : List.of(new AtAllPermissionResolver() {
            @Override
            public boolean supports(String platform) {
                return PLATFORM.equals(platform);
            }

            @Override
            public boolean canAtAll(String platform, Long num) {
                return canAtAll;
            }
        });
        when(resolvers.iterator()).thenAnswer(invocation -> list.iterator());

        return new StarBotMessageSender(http, senderService, new PushActivityRecorder(), new PushGate(properties),
                new com.starlwr.bot.core.service.AtAllQuotaService(properties), resolvers);
    }

    /**
     * 进程内直调（任务 7a）
     * <p>
     * 验收标准来自裁决 #7：<b>测试桩模拟 6 秒以上的慢响应，文字与图片都不能丢。</b>
     * <p>
     * 旧路径是核心把消息 POST 给自己的服务端口，再由自己的控制器转给下游。
     * 那一圈自环有个很难查的后果：服务端口的工作线程有限（默认 8 个），
     * 控制器转发下游时同步阻塞，下游一慢线程就被占满，<b>没有线程去读请求体</b>。
     * 体积小的文字一次写进 socket 缓冲区就完事、照常送达；
     * 而图片是几百 KB 的内联 base64，必须服务端一边读才写得完，于是卡满 60 秒超时被丢弃。
     * 2026-08-10 生产上就是这么丢了一条动态配图。
     */
    @org.junit.jupiter.api.Nested
    @DisplayName("进程内直调")
    class LocalDelivery {
        /** 一张图片消息的量级：几百 KB 的内联 base64 */
        private String imageContent() {
            return "{image_base64=" + "A".repeat(400 * 1024) + "}";
        }

        @Test
        @DisplayName("⚠️ 下游每次慢 6 秒时，文字与图片都必须送达")
        void neitherTextNorImageIsLostWhenDownstreamIsSlow() {
            HttpUtil http = mock(HttpUtil.class);
            AtomicInteger delivered = new AtomicInteger();

            Sender.LocalDelivery slow = (headers, params) -> {
                try {
                    // 裁决 #7 指定的验收条件：每次 6 秒以上
                    Thread.sleep(6_100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                delivered.incrementAndGet();
                return new JSONObject().fluentPut("code", 0).fluentPut("id", "m" + delivered.get());
            };

            StarBotMessageSender sender = localSender(http, slow);

            Message text = message();
            text.setContent("开播啦");
            assertEquals(0, sender.sendNow(text).getInteger("code"), "文字消息不该丢");

            Message image = message();
            image.setContent(imageContent());
            assertEquals(0, sender.sendNow(image).getInteger("code"), "图片消息同样不该丢——这正是旧路径丢掉的那种");

            assertEquals(2, delivered.get(), "两条都应真正到达下游");
            verify(http, never()).postJson(anyString(), anyMap(), anyMap());
        }

        @Test
        @DisplayName("配了进程内投递就不该再发本机 HTTP —— 没有 socket 就没有读不完请求体的问题")
        void neverTouchesHttpWhenLocalDeliveryPresent() {
            HttpUtil http = mock(HttpUtil.class);
            StarBotMessageSender sender = localSender(http,
                    (headers, params) -> new JSONObject().fluentPut("code", 0));

            sender.sendNow(message());

            // 这一条是整个 7a 的结构性保证：请求体不再需要「谁来读」
            verify(http, never()).postJson(anyString(), anyMap(), anyMap());
        }

        @Test
        @DisplayName("没配进程内投递时回落到 HTTP，外部推送平台照旧可用")
        void fallsBackToHttpWhenAbsent() {
            HttpUtil http = mock(HttpUtil.class);
            when(http.postJson(anyString(), anyMap(), anyMap()))
                    .thenReturn(new JSONObject().fluentPut("code", 0));

            sender(http).sendNow(message());

            verify(http, times(1)).postJson(anyString(), anyMap(), anyMap());
        }

        @Test
        @DisplayName("进程内投递抛异常时照样重试，语义与走 HTTP 时一致")
        void retriesLikeTheHttpPath() {
            HttpUtil http = mock(HttpUtil.class);
            AtomicInteger attempts = new AtomicInteger();

            StarBotMessageSender sender = localSender(http, (headers, params) -> {
                if (attempts.incrementAndGet() < 3) {
                    throw new IllegalStateException("下游暂时不可用");
                }
                return new JSONObject().fluentPut("code", 0);
            });

            assertEquals(0, sender.sendNow(message()).getInteger("code"));
            assertEquals(3, attempts.get(), "重试次数应与 HTTP 路径相同");
        }

        @Test
        @DisplayName("交给下游的参数与走 HTTP 时逐字段相同")
        void passesTheSameParamsAsHttp() {
            HttpUtil http = mock(HttpUtil.class);
            when(http.postJson(anyString(), anyMap(), anyMap()))
                    .thenReturn(new JSONObject().fluentPut("code", 0));

            ArgumentCaptor<Map<String, Object>> viaHttp = ArgumentCaptor.forClass(Map.class);
            sender(http).sendNow(message());
            verify(http).postJson(anyString(), anyMap(), viaHttp.capture());

            java.util.concurrent.atomic.AtomicReference<Map<String, Object>> viaLocal =
                    new java.util.concurrent.atomic.AtomicReference<>();
            localSender(mock(HttpUtil.class), (headers, params) -> {
                viaLocal.set(params);
                return new JSONObject().fluentPut("code", 0);
            }).sendNow(message());

            // 两条路必须喂给下游同样的东西，否则「改走直调」就成了偷偷改协议
            assertEquals(viaHttp.getValue().keySet(), viaLocal.get().keySet());
            assertEquals(viaHttp.getValue().get("platform"), viaLocal.get().get("platform"));
            assertEquals(viaHttp.getValue().get("type"), viaLocal.get().get("type"));
            assertEquals(viaHttp.getValue().get("num"), viaLocal.get().get("num"));
            assertEquals(viaHttp.getValue().get("content"), viaLocal.get().get("content"));
            assertTrue(viaLocal.get().containsKey("create_time"), "时间戳不能在这条路上丢掉");
        }
    }

    /**
     * 造一个走进程内直调的发送器
     */
    private StarBotMessageSender localSender(HttpUtil http, Sender.LocalDelivery delivery) {
        Sender target = new Sender();
        target.setName(PLATFORM);
        target.setUrl("http://127.0.0.1:7827/onebot/send");
        target.setDelay(0);
        target.setLocalDelivery(delivery);

        StarBotSenderService senderService = mock(StarBotSenderService.class);
        when(senderService.getSender(PLATFORM)).thenReturn(Optional.of(target));

        @SuppressWarnings("unchecked")
        ObjectProvider<AtAllPermissionResolver> resolvers = mock(ObjectProvider.class);
        when(resolvers.iterator()).thenAnswer(invocation -> List.<AtAllPermissionResolver>of().iterator());

        StarBotCoreProperties properties = new StarBotCoreProperties();
        return new StarBotMessageSender(http, senderService, new PushActivityRecorder(), new PushGate(properties),
                new com.starlwr.bot.core.service.AtAllQuotaService(properties), resolvers);
    }
}
