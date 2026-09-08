package com.starlwr.bot.core.alert;

import com.starlwr.bot.core.config.NovaCoreProperties;
import com.starlwr.bot.core.timeline.TimelineWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 告警服务测试
 * <p>
 * 重点不在「能不能发出去」，而在<b>发不出去之后会怎样</b>——需要告警的时候
 * 往往正是出网劣化、QQ 掉登录的时候，而「没收到告警」被读成「没出事」
 * 是这套机制最坏的失效方式。
 */
@DisplayName("告警服务")
class AlertServiceTest {
    private NovaCoreProperties properties;

    private List<AlertChannel> channels;

    private AlertService service;

    @BeforeEach
    void setUp() {
        properties = new NovaCoreProperties();
        channels = new ArrayList<>();

        @SuppressWarnings("unchecked")
        ObjectProvider<AlertChannel> provider = mock(ObjectProvider.class);
        // orderedStream() 每次调用都要拿到一条新的流，用 thenAnswer 而不是 thenReturn
        when(provider.orderedStream()).thenAnswer(invocation -> channels.stream());

        // 这一件问的是收敛、入队与重投，一条也不问日志页；时间线那一头由 TimelineHookTest 量
        service = new AlertService(properties, provider, TimelineWriter.NONE);
    }

    /**
     * 可控的假通道：能配置「可用与否」「这次发不发得出去」，并记下收到的内容
     */
    private static class FakeChannel implements AlertChannel {
        private final String id;
        private final String name;
        boolean available = true;
        boolean failing;
        final List<String> received = new ArrayList<>();

        FakeChannel(String name) {
            this("fake", name);
        }

        FakeChannel(String id, String name) {
            this.id = id;
            this.name = name;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public void send(String subject, String content) {
            if (failing) {
                throw new IllegalStateException("模拟出网中断");
            }
            received.add(subject + "\n" + content);
        }
    }

    @Nested
    @DisplayName("失败入队与补发")
    class Retry {
        @Test
        @DisplayName("出网中断时入队，恢复后补发，且标注原始发生时刻")
        void redeliversAfterRecovery() {
            FakeChannel channel = new FakeChannel("假通道");
            channel.failing = true;
            channels.add(channel);

            service.alert("login-expired", "登录已失效", "凭据复检未通过");

            assertEquals(1, service.pendingCount(), "发不出去的告警必须留在队列里");
            assertTrue(channel.received.isEmpty());

            // 网络恢复
            channel.failing = false;
            service.retryPending();

            assertEquals(0, service.pendingCount());
            assertEquals(1, channel.received.size(), "恢复后应补发");
            String sent = channel.received.get(0);
            assertTrue(sent.contains("【补发】"), "补发要标明自己是补发的");
            assertTrue(sent.contains("原始发生时刻"), "不写原始时刻，人会去查一个早已结束的现场");
            assertTrue(sent.contains("凭据复检未通过"), "原文必须还在");
        }

        @Test
        @DisplayName("补发不该被收敛闸门拦掉")
        void redeliveryIsNotConverged() {
            // 收敛间隔设得很长：如果补发要过闸门，它会被自己首次告警的记录拦住
            properties.getAlert().setConvergenceInterval(86400);

            FakeChannel channel = new FakeChannel("假通道");
            channel.failing = true;
            channels.add(channel);

            service.alert("disk-full", "磁盘将满", "剩余 100 MB");
            channel.failing = false;
            service.retryPending();

            assertEquals(1, channel.received.size(), "补发被收敛拦掉就等于没有重投");
        }

        @Test
        @DisplayName("一个通道成功另一个失败时不入队：人已经收到了")
        void noRetryWhenAnyChannelSucceeds() {
            FakeChannel ok = new FakeChannel("成功的");
            FakeChannel bad = new FakeChannel("失败的");
            bad.failing = true;
            channels.add(ok);
            channels.add(bad);

            service.alert("key", "标题", "内容");

            assertEquals(0, service.pendingCount(), "重投只会让人收到第二条一样的");
            assertEquals(1, ok.received.size());
        }

        @Test
        @DisplayName("压根没有配置通道时不入队：没有出口，重投一万次也是失败")
        void noRetryWithoutAnyChannel() {
            FakeChannel unconfigured = new FakeChannel("未配置的");
            unconfigured.available = false;
            channels.add(unconfigured);

            service.alert("key", "标题", "内容");

            assertEquals(0, service.pendingCount(), "队列会被填满然后开始丢东西");
        }

        @Test
        @DisplayName("超过最大重投次数后放弃，不无限占着队列")
        void givesUpAfterMaxAttempts() {
            properties.getAlert().setRetryMaxAttempts(3);

            FakeChannel channel = new FakeChannel("假通道");
            channel.failing = true;
            channels.add(channel);

            service.alert("key", "标题", "内容");
            for (int i = 0; i < 3; i++) {
                assertEquals(1, service.pendingCount(), "第 " + (i + 1) + " 轮还应在队列里");
                service.retryPending();
            }

            assertEquals(0, service.pendingCount(), "投到上限就该放弃并写日志");
        }

        @Test
        @DisplayName("队列满了丢最旧的，长度不超上限")
        void dropsOldestWhenFull() {
            properties.getAlert().setRetryQueueSize(3);
            // 收敛按 key 走，这里每条用不同的 key，模拟多个不同的问题同时发不出去
            FakeChannel channel = new FakeChannel("假通道");
            channel.failing = true;
            channels.add(channel);

            for (int i = 0; i < 6; i++) {
                service.alert("key-" + i, "问题 " + i, "内容");
            }

            assertEquals(3, service.pendingCount(), "上限必须是硬的");

            channel.failing = false;
            service.retryPending();

            assertEquals(3, channel.received.size());
            // 丢的是最旧的三条，留下的是 3、4、5
            assertTrue(channel.received.stream().anyMatch(text -> text.contains("问题 5")));
            assertFalse(channel.received.stream().anyMatch(text -> text.contains("问题 0")),
                    "满了该丢最旧的，新问题比旧问题值钱");
        }

        @Test
        @DisplayName("队列为空时走一轮什么也不做")
        void emptyQueueIsNoop() {
            FakeChannel channel = new FakeChannel("假通道");
            channels.add(channel);

            service.retryPending();

            assertTrue(channel.received.isEmpty());
            assertEquals(0, service.pendingCount());
        }
    }

    @Nested
    @DisplayName("补发正文")
    class Redelivery {
        @Test
        @DisplayName("延迟时长与投递次数都写进正文")
        void describesDelayAndAttempts() {
            PendingAlert alert = new PendingAlert("key", "标题", "原文",
                    java.time.Instant.now().minusSeconds(3725), 2);

            String content = alert.contentForRedelivery(java.time.Instant.now());

            assertTrue(content.contains("已延迟 1 时 2 分"), "实际内容: " + content);
            assertTrue(content.contains("第 2 次投递失败后重投"));
            assertTrue(content.startsWith("【补发】"), "手机通知栏往往只显示前一两行，说明必须在开头");
        }

        @Test
        @DisplayName("刚发生就补发时说「不到 1 秒」而不是留白")
        void describesZeroDelay() {
            java.time.Instant now = java.time.Instant.now();
            PendingAlert alert = new PendingAlert("key", "标题", "原文", now, 1);

            assertTrue(alert.contentForRedelivery(now).contains("不到 1 秒"));
        }
    }

    @Nested
    @DisplayName("原有行为不变")
    class Existing {
        @Test
        @DisplayName("告警关闭时既不发也不入队")
        void disabledSendsNothing() {
            properties.getAlert().setEnabled(false);
            FakeChannel channel = new FakeChannel("假通道");
            channel.failing = true;
            channels.add(channel);

            service.alert("key", "标题", "内容");

            assertEquals(0, service.pendingCount());
            assertTrue(channel.received.isEmpty());
        }

        @Test
        @DisplayName("同一问题在收敛期内只发一次，resolve 之后可以再发")
        void convergesUntilResolved() {
            properties.getAlert().setConvergenceInterval(86400);
            FakeChannel channel = new FakeChannel("假通道");
            channels.add(channel);

            service.alert("key", "标题", "内容");
            service.alert("key", "标题", "内容");
            assertEquals(1, channel.received.size());

            service.resolve("key");
            service.alert("key", "标题", "内容");
            assertEquals(2, channel.received.size(), "故障恢复后再次出现必须能再告警");
        }
    }
}
