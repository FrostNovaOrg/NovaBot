package org.frostnova.nova.adapter.onebot.alert;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.adapter.onebot.config.OneBotAdapterPluginProperties;
import org.frostnova.nova.core.alert.AlertChannel;
import org.frostnova.nova.core.alert.AlertRecipientField;
import org.frostnova.nova.core.alert.AlertService;
import org.frostnova.nova.core.config.NovaCoreProperties;
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
import org.frostnova.nova.core.service.NovaStateStore;
import org.frostnova.nova.core.timeline.TimelineEvent;
import org.frostnova.nova.core.timeline.TimelineEventType;
import org.frostnova.nova.core.timeline.TimelineWriter;
import org.frostnova.nova.core.util.HttpUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * QQ 告警通道测试
 * <p>
 * 重点在推送目标类型的取值。该字段与 datasource.json 中推送目标的 type 是同一套编码
 * （{@link PushTargetType}：GROUP 为 1、FRIEND 为 0），一旦填成别的数字，
 * 消息会在发送阶段被解析为「未知类型」直接丢弃，而告警恰恰是出问题时唯一的提示，
 * 它自己静默失效是最糟的情况。
 */
@DisplayName("QQ 告警通道")
class QqAlertChannelTest {
    @Test
    @DisplayName("配置完整且类型合法时应判定为可用")
    void shouldBeAvailableWithValidConfiguration() {
        OneBotAdapterPluginProperties properties = properties(PushTargetType.FRIEND.getCode(), 10000L);

        assertTrue(new QqAlertChannel(properties, mock(NovaMessageSender.class)).isAvailable());
    }

    @Test
    @DisplayName("群聊与私聊两种合法取值都应被接受")
    void shouldAcceptBothValidTypes() {
        NovaMessageSender sender = mock(NovaMessageSender.class);

        assertTrue(new QqAlertChannel(properties(PushTargetType.GROUP.getCode(), 10000L), sender).isAvailable(),
                "群聊（1）应可用");
        assertTrue(new QqAlertChannel(properties(PushTargetType.FRIEND.getCode(), 10000L), sender).isAvailable(),
                "私聊（0）应可用");
    }

    @Test
    @DisplayName("类型取值非法时应判定为不可用, 而不是发出一条注定被丢弃的告警")
    void shouldBeUnavailableWithInvalidType() {
        // 2 是曾经写在文档与默认值里的错误取值，它会被解析为 UNKNOWN
        OneBotAdapterPluginProperties properties = properties(2, 10000L);

        assertEquals(PushTargetType.UNKNOWN, PushTargetType.of(2), "前置条件: 2 不是合法取值");
        assertFalse(new QqAlertChannel(properties, mock(NovaMessageSender.class)).isAvailable());
    }

    @Test
    @DisplayName("未配置平台或号码时应判定为不可用")
    void shouldBeUnavailableWithoutTarget() {
        NovaMessageSender sender = mock(NovaMessageSender.class);

        assertFalse(new QqAlertChannel(properties(PushTargetType.FRIEND.getCode(), null), sender).isAvailable(),
                "未填号码时不可用");

        OneBotAdapterPluginProperties noPlatform = properties(PushTargetType.FRIEND.getCode(), 10000L);
        noPlatform.getAlert().setPlatform("");
        assertFalse(new QqAlertChannel(noPlatform, sender).isAvailable(), "未填平台名时不可用");
    }

    @Test
    @DisplayName("收件人栏申报三键全名与 fill 顺序恰为 sender／kind／num")
    void declaresRecipientFieldsInFillOrder() {
        List<AlertRecipientField> fields = new QqAlertChannel(
                properties(PushTargetType.FRIEND.getCode(), 10000L),
                mock(NovaMessageSender.class)).recipientFields();

        assertEquals(3, fields.size());
        assertEquals("novabot.adapter.onebot.alert.platform", fields.get(0).key());
        assertEquals("sender", fields.get(0).fill());
        assertEquals("novabot.adapter.onebot.alert.type", fields.get(1).key());
        assertEquals("kind", fields.get(1).fill());
        assertEquals("novabot.adapter.onebot.alert.num", fields.get(2).key());
        assertEquals("num", fields.get(2).fill());
    }

    @Test
    @DisplayName("发送的告警应带上配置的目标类型与号码")
    void shouldSendToConfiguredTarget() {
        OneBotAdapterPluginProperties properties = properties(PushTargetType.GROUP.getCode(), 12345L);
        NovaMessageSender sender = mock(NovaMessageSender.class);

        new QqAlertChannel(properties, sender).send("标题", "正文");

        ArgumentCaptor<Message> captured = ArgumentCaptor.forClass(Message.class);
        verify(sender, atLeastOnce()).sendAlert(captured.capture());

        Message message = captured.getValue();
        assertEquals("qq-onebot", message.getPlatform());
        assertEquals(PushTargetType.GROUP, message.getType());
        assertEquals(12345L, message.getNum());
    }

    /**
     * 静音时段与总开关
     * <p>
     * 静音的本意是「不想被机器人吵」，而告警恰恰是出了事要叫人的那一条——
     * 半夜出的问题也得有人知道。总开关则照旧拦告警：它的说明写的是「关闭后所有推送都会被丢弃」。
     * <p>
     * 这几格都走<b>真的</b>发送器（真闸门、真队列）：mock 掉的发送器根本不过闸，
     * 「静音时段里告警照发」这样的缺口在 mock 上永远是绿的。
     */
    @Nested
    @DisplayName("静音时段与总开关")
    class Gates {
        @Test
        @DisplayName("⚠️ 静音时段里告警必须照发——半夜出的问题也得叫到人")
        void alertStillDeliveredDuringQuietHours() {
            NovaCoreProperties core = quietHoursAroundNow();

            CountDownLatch delivered = new CountDownLatch(1);
            NovaMessageSender sender = realSender(core, (headers, params) -> {
                delivered.countDown();
                return new JSONObject().fluentPut("code", 0).fluentPut("id", "m1");
            });

            new QqAlertChannel(properties(PushTargetType.FRIEND.getCode(), 10000L), sender)
                    .send("标题", "正文");

            assertDelivered(delivered, "静音时段里 QQ 告警必须进队列并送达");
        }

        @Test
        @DisplayName("⚠️ 总开关关着时「发一条测试」不谎报已发出，要说清没发出去和原因")
        void testDoesNotClaimDeliveryWhenMasterSwitchOff() {
            NovaCoreProperties core = new NovaCoreProperties();
            core.getPush().setEnabled(false);

            NovaMessageSender sender = realSender(core,
                    (headers, params) -> new JSONObject().fluentPut("code", 0));
            QqAlertChannel channel = new QqAlertChannel(properties(PushTargetType.FRIEND.getCode(), 10000L), sender);

            AlertService.TestResult result = alertService(core, channel).test("qq");

            assertNotEquals(AlertService.TestResult.Status.DELIVERED, result.status(),
                    "没发出去就不许说已发出: " + result.message());
            assertTrue(result.message().contains("全局推送开关"), "要说清原因: " + result.message());
        }

        @Test
        @DisplayName("⚠️ 总开关关着时真告警不记「已报出」，要记成没发出去")
        void realAlertRecordsFailureNotSentWhenMasterSwitchOff() {
            NovaCoreProperties core = new NovaCoreProperties();
            core.getPush().setEnabled(false);

            NovaMessageSender sender = realSender(core,
                    (headers, params) -> new JSONObject().fluentPut("code", 0));
            QqAlertChannel channel = new QqAlertChannel(properties(PushTargetType.FRIEND.getCode(), 10000L), sender);

            List<TimelineEvent> recorded = new ArrayList<>();
            @SuppressWarnings("unchecked")
            ObjectProvider<AlertChannel> provider = mock(ObjectProvider.class);
            when(provider.orderedStream()).thenAnswer(invocation -> List.of((AlertChannel) channel).stream());

            new AlertService(core, provider, recorded::add).alert("link.lost", "连接已断开", "内容");

            assertTrue(recorded.stream().noneMatch(event -> event.type() == TimelineEventType.ALERT_SENT),
                    "没发出去就不许记已报出: " + recorded);
            assertTrue(recorded.stream().anyMatch(event -> event.type() == TimelineEventType.ALERT_FAILED
                            && event.text().contains("发不出去")),
                    "要记成没发出去及原因: " + recorded);
        }

        @Test
        @DisplayName("总开关关着时告警不进队列，抛出说明原因的异常")
        void alertNotQueuedWhenMasterSwitchOff() {
            NovaCoreProperties core = new NovaCoreProperties();
            core.getPush().setEnabled(false);

            List<Map<String, Object>> deliveries = new ArrayList<>();
            NovaMessageSender sender = realSender(core, (headers, params) -> {
                deliveries.add(params);
                return new JSONObject().fluentPut("code", 0);
            });

            QqAlertChannel channel = new QqAlertChannel(properties(PushTargetType.FRIEND.getCode(), 10000L), sender);

            IllegalStateException rejected = assertThrows(IllegalStateException.class,
                    () -> channel.send("标题", "正文"));
            assertTrue(rejected.getMessage().contains("全局推送开关"),
                    "异常要说清原因: " + rejected.getMessage());

            // 稍等片刻再数：若被错误地放进了队列，平台线程会在这一小段时间里把它发出去
            assertNothingDelivered(deliveries, "总开关关着，一条也不该进队列");
        }

        @Test
        @DisplayName("⚠️ 总开关关着时被拦下的告警不进重投队列——开关打开后也不许攒着补发")
        void blockedAlertDoesNotEnterRetryQueue() {
            NovaCoreProperties core = new NovaCoreProperties();
            core.getPush().setEnabled(false);

            NovaMessageSender sender = realSender(core,
                    (headers, params) -> new JSONObject().fluentPut("code", 0));
            QqAlertChannel channel = new QqAlertChannel(properties(PushTargetType.FRIEND.getCode(), 10000L), sender);

            List<TimelineEvent> recorded = new ArrayList<>();
            @SuppressWarnings("unchecked")
            ObjectProvider<AlertChannel> provider = mock(ObjectProvider.class);
            when(provider.orderedStream()).thenAnswer(invocation -> List.of((AlertChannel) channel).stream());

            AlertService service = new AlertService(core, provider, recorded::add);
            service.alert("link.lost", "连接已断开", "内容");

            assertEquals(0, service.pendingCount(), "被总开关拦下算这一次的最终失败, 不进重投队列");
            assertTrue(recorded.stream().noneMatch(event -> event.type() == TimelineEventType.ALERT_SENT),
                    "没发出去就不许记已报出: " + recorded);
            assertEquals(1, recorded.stream().filter(event -> event.type() == TimelineEventType.ALERT_FAILED).count(),
                    "记一条没发出去及原因就够, 不许按重投轮数刷屏: " + recorded);
        }

        /**
         * 造一段必然盖住此刻的静音时段（前后各一小时，跨零点也成立）
         * <p>
         * 写死「23:00 ~ 08:00」会在白天的某几分钟假绿；闸门读的是墙钟，测试只好跟着现配。
         */
        private NovaCoreProperties quietHoursAroundNow() {
            NovaCoreProperties core = new NovaCoreProperties();
            DateTimeFormatter hhmm = DateTimeFormatter.ofPattern("HH:mm");
            LocalTime now = LocalTime.now();
            core.getPush().setQuietStart(now.minusHours(1).format(hhmm));
            core.getPush().setQuietEnd(now.plusHours(1).format(hhmm));

            assertFalse(new PushGate(core).allowed(), "前置条件: 此刻确在静音时段内");
            return core;
        }

        /**
         * 造一个真的发送器：真闸门、真队列、真平台线程，只在最末端的投递处替换成进程内回调
         */
        private NovaMessageSender realSender(NovaCoreProperties core, Sender.LocalDelivery delivery) {
            Sender target = new Sender();
            target.setName("qq-onebot");
            target.setUrl("http://127.0.0.1:7827/onebot/send");
            target.setDelay(0);
            target.setLocalDelivery(delivery);

            NovaSenderService senderService = mock(NovaSenderService.class);
            when(senderService.getSender("qq-onebot")).thenReturn(Optional.of(target));

            @SuppressWarnings("unchecked")
            ObjectProvider<AtAllPermissionResolver> resolvers = mock(ObjectProvider.class);
            when(resolvers.iterator()).thenAnswer(invocation -> List.<AtAllPermissionResolver>of().iterator());

            return new NovaMessageSender(mock(HttpUtil.class), senderService,
                    new PushActivityRecorder(TimelineWriter.NONE), new PushGate(core), TimelineWriter.NONE,
                    new AtAllQuotaService(core), resolvers,
                    new FirstPushTipService(new NovaStateStore(core)));
        }

        /**
         * 造一个只含指定通道的告警服务
         */
        private AlertService alertService(NovaCoreProperties core, AlertChannel channel) {
            @SuppressWarnings("unchecked")
            ObjectProvider<AlertChannel> provider = mock(ObjectProvider.class);
            when(provider.orderedStream()).thenAnswer(invocation -> List.of(channel).stream());
            return new AlertService(core, provider, TimelineWriter.NONE);
        }

        /**
         * 等到投递发生；超时即失败
         */
        private void assertDelivered(CountDownLatch delivered, String message) {
            try {
                assertTrue(delivered.await(2, TimeUnit.SECONDS), message);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail(message + "（等待被中断）");
            }
        }

        /**
         * 留出平台线程投递的时间后再断言一条也没发出去
         */
        private void assertNothingDelivered(List<Map<String, Object>> deliveries, String message) {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            assertTrue(deliveries.isEmpty(), message + "，实际投递: " + deliveries);
        }
    }

    /**
     * 构造告警配置
     * @param qqType 目标类型
     * @param qqNum 群号或 QQ 号
     * @return 配置
     */
    private OneBotAdapterPluginProperties properties(int type, Long num) {
        OneBotAdapterPluginProperties properties = new OneBotAdapterPluginProperties();
        properties.getAlert().setPlatform("qq-onebot");
        properties.getAlert().setType(type);
        properties.getAlert().setNum(num);
        return properties;
    }
}
