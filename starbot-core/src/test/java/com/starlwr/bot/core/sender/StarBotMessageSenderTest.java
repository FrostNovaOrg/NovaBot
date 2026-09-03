package com.starlwr.bot.core.sender;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.enums.PushTargetType;
import com.starlwr.bot.core.health.PushActivityRecorder;
import com.starlwr.bot.core.model.Message;
import com.starlwr.bot.core.model.Sender;
import com.starlwr.bot.core.service.StarBotSenderService;
import com.starlwr.bot.core.timeline.TimelineWriter;
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

        return new StarBotMessageSender(http, senderService, new PushActivityRecorder(TimelineWriter.NONE), new PushGate(properties),
                TimelineWriter.NONE, new com.starlwr.bot.core.service.AtAllQuotaService(properties), resolvers);
    }

    /**
     * 进程内直调（任务 7a）
     * <p>
     * 验收标准：<b>测试桩模拟 6 秒以上的慢响应，文字与图片都不能丢。</b>
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
                    // 验收条件：每次 6 秒以上
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
        return new StarBotMessageSender(http, senderService, new PushActivityRecorder(TimelineWriter.NONE), new PushGate(properties),
                TimelineWriter.NONE, new com.starlwr.bot.core.service.AtAllQuotaService(properties), resolvers);
    }

    /**
     * 含图消息发送失败时剥掉图片段重发纯文字
     * <p>
     * 要求原文：<b>推送文字的可达性不得依赖图片的可取性，任何模板写法下都必须成立。</b>
     * 实测（2026-08-11，NapCat）一条消息里图片下载失败会让<b>整条发送失败</b>，不是只丢图，
     * 于是封面拉不到的那一次，开播通知<b>整条消失</b>。
     * <p>
     * 触发三条件缺一不可：<b>发送失败 + 含图片段 + 剥掉之后还剩东西</b>。
     * 刻意<b>不</b>判断「失败是不是图片引起的」——解析 NapCat 的错误文案是用错量，
     * 版本一改就静默失效，而失效方向是「再也不降级」。不判断成因是设计的一部分：
     * 非图片故障下纯文字重发同样会失败，所以双发不成立。
     */
    @org.junit.jupiter.api.Nested
    @DisplayName("含图消息失败后的纯文字兜底")
    class ImageFallback {
        /** 一条「文字 + 封面」的开播通知，合并在同一条里（用户自行改模板删掉 {next} 的写法） */
        private Message withImage() {
            return Message.create(PLATFORM, PushTargetType.GROUP, 12345L,
                    "某某 开播啦 {image_url=https://example.com/cover.jpg}").get(0);
        }

        /** 图片下载失败时 NapCat 的响应形态：报错、且 id 为空（2026-08-11 实测两次都是这个形状） */
        private JSONObject imageFailure() {
            return new JSONObject()
                    .fluentPut("code", 2)
                    .fluentPut("message", "下载文件失败: Not Found");
        }

        @Test
        @DisplayName("⚠️ 守卫：含图消息发送失败时，文字必须仍然送达")
        void textSurvivesWhenImageSendFails() {
            HttpUtil http = mock(HttpUtil.class);
            List<String> sent = new java.util.ArrayList<>();

            when(http.postJson(anyString(), any(), any())).thenAnswer(invocation -> {
                Map<String, Object> params = invocation.getArgument(2);
                sent.add(String.valueOf(params.get("content")));
                return imageFailure();
            });

            sender(http).sendNow(withImage());

            assertTrue(sent.stream().anyMatch(c -> !c.contains("{image_url=") && c.contains("开播啦")),
                    "含图消息失败后应当有一次不含图片段的纯文字投递，实际投递内容: " + sent);
        }

        @Test
        @DisplayName("尺子先过阳性对照：'只投递一次' 这个断言必须真的数得出双发")
        void deliveryCounterActuallyCountsTwice() {
            // 下面几个用例都靠「只投递了一次」来证明没有双发。
            // 若那个计数根本数不出第二次，它们会全部假绿——先用一次真的双发证明它数得出来
            HttpUtil http = mock(HttpUtil.class);
            when(http.postJson(anyString(), any(), any())).thenReturn(imageFailure());

            StarBotMessageSender sender = sender(http);
            sender.sendNow(withImage());

            // 含图 + 失败 + 剥完还剩文字 → 必然是两次投递（原内容一次、纯文字一次）
            verify(http, times(2)).postJson(anyString(), any(), any());
        }

        @Test
        @DisplayName("A 图片失败：应剥掉图片段重发纯文字并送达")
        void stripsImageAndResendsText() {
            HttpUtil http = mock(HttpUtil.class);
            when(http.postJson(anyString(), any(), any()))
                    .thenReturn(imageFailure())
                    .thenReturn(new JSONObject().fluentPut("code", 0).fluentPut("id", "m2"));

            sender(http).sendNow(withImage());

            ArgumentCaptor<Map<String, Object>> captor = paramsCaptor();
            verify(http, times(2)).postJson(anyString(), any(), captor.capture());

            String second = String.valueOf(captor.getAllValues().get(1).get("content"));
            assertFalse(second.contains("{image_url="), "重发的那条不该再带图片段");
            assertEquals("某某 开播啦", second, "文字必须原样保留，且首尾空白已修剪");
        }

        @Test
        @DisplayName("B 非图片故障：纯文字重发同样失败，所以不会双发")
        void nonImageFailureDoesNotDoubleDeliver() {
            // 被禁言、机器人被踢、群号错、Token 错、出网故障——剥掉图不改变其中任何一个。
            // 这正是「不必判断失败成因」的依据：双发在这条路径上不成立
            HttpUtil http = mock(HttpUtil.class);
            when(http.postJson(anyString(), any(), any()))
                    .thenReturn(new JSONObject().fluentPut("code", 1200).fluentPut("message", "机器人不在该群"));

            sender(http).sendNow(withImage());

            // 两次投递、零次送达：重发也失败，没有任何一条到达
            verify(http, times(2)).postJson(anyString(), any(), any());
        }

        @Test
        @DisplayName("C' 失败但响应带着消息 id：模糊态，不重发")
        void doesNotResendWhenResponseCarriesId() {
            // 接口谎报失败（报错但其实送到了）是唯一可能双发的情形。
            // 收成一个有定义的条件：只在响应没带 id 时才重发。
            // 2026-08-11 实测的两次真失败都是 id 为空且群里一条没出现
            HttpUtil http = mock(HttpUtil.class);
            when(http.postJson(anyString(), any(), any())).thenReturn(
                    new JSONObject().fluentPut("code", 2).fluentPut("message", "超时").fluentPut("id", "m9"));

            sender(http).sendNow(withImage());

            verify(http, times(1)).postJson(anyString(), any(), any());
        }

        @Test
        @DisplayName("D 图片独占一条：剥完为空，不重发也不发空消息")
        void doesNotResendWhenNothingLeftAfterStripping() {
            // 开播模板默认就是「文字{next}封面」，第二条只有封面
            HttpUtil http = mock(HttpUtil.class);
            when(http.postJson(anyString(), any(), any())).thenReturn(imageFailure());

            Message imageOnly = Message.create(PLATFORM, PushTargetType.GROUP, 12345L,
                    "{image_url=https://example.com/cover.jpg}").get(0);
            sender(http).sendNow(imageOnly);

            verify(http, times(1)).postJson(anyString(), any(), any());
        }

        @Test
        @DisplayName("E 纯文字消息失败：不触发兜底，否则所有失败的重试次数都翻倍")
        void plainTextFailureDoesNotTriggerFallback() {
            HttpUtil http = mock(HttpUtil.class);
            when(http.postJson(anyString(), any(), any())).thenReturn(
                    new JSONObject().fluentPut("code", 1200).fluentPut("message", "机器人不在该群"));

            sender(http).sendNow(Message.create(PLATFORM, PushTargetType.GROUP, 12345L, "某某 开播啦").get(0));

            verify(http, times(1)).postJson(anyString(), any(), any());
        }

        @Test
        @DisplayName("降级那行日志必须同时带上原始失败原因与「图片未送达」")
        void degradeLogCarriesRootCauseAndImageLoss() {
            // 降级不能掩盖根因：只说「图没了」而不说为什么，等于把故障藏进一行 WARN 里
            ch.qos.logback.classic.Logger logger =
                    (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(StarBotMessageSender.class);
            ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                    new ch.qos.logback.core.read.ListAppender<>();
            appender.start();
            logger.addAppender(appender);

            try {
                HttpUtil http = mock(HttpUtil.class);
                when(http.postJson(anyString(), any(), any()))
                        .thenReturn(imageFailure())
                        .thenReturn(new JSONObject().fluentPut("code", 0).fluentPut("id", "m2"));

                sender(http).sendNow(withImage());

                String degrade = appender.list.stream()
                        .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                        .filter(m -> m.contains("已剥除图片段重发纯文字"))
                        .findFirst()
                        .orElse(null);

                assertNotNull(degrade, "降级成功必须留下一行日志，实际日志: " + appender.list);
                assertTrue(degrade.contains("图片未送达"), "必须显式写明图没送到: " + degrade);
                assertTrue(degrade.contains("下载文件失败: Not Found"), "必须带上原始失败原因: " + degrade);
            } finally {
                logger.detachAppender(appender);
            }
        }

        @Test
        @DisplayName("降级送达时应触发图片降级回调，供下播报告记录")
        void notifiesImageDegradedCallback() {
            HttpUtil http = mock(HttpUtil.class);
            when(http.postJson(anyString(), any(), any()))
                    .thenReturn(imageFailure())
                    .thenReturn(new JSONObject().fluentPut("code", 0).fluentPut("id", "m2"));

            AtomicInteger degraded = new AtomicInteger();
            Message message = withImage();
            message.addOnImageDegradedCallback(degraded::incrementAndGet);

            sender(http).sendNow(message);

            assertEquals(1, degraded.get(), "文字到了、图没到，应当记一次降级");
        }

        @Test
        @DisplayName("重发仍然失败时不该记成降级——那一条完全没送达")
        void doesNotNotifyCallbackWhenResendAlsoFails() {
            HttpUtil http = mock(HttpUtil.class);
            when(http.postJson(anyString(), any(), any()))
                    .thenReturn(new JSONObject().fluentPut("code", 1200).fluentPut("message", "机器人不在该群"));

            AtomicInteger degraded = new AtomicInteger();
            Message message = withImage();
            message.addOnImageDegradedCallback(degraded::incrementAndGet);

            sender(http).sendNow(message);

            assertEquals(0, degraded.get(), "零送达不是降级");
        }
    }
}
