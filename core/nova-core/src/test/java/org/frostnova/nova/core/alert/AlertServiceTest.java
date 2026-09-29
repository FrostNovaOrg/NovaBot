package org.frostnova.nova.core.alert;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.OutputStreamAppender;
import ch.qos.logback.core.read.ListAppender;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.timeline.TimelineEvent;
import org.frostnova.nova.core.timeline.TimelineEventType;
import org.frostnova.nova.core.timeline.TimelineWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
        boolean blockedByMasterSwitch;
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
            if (blockedByMasterSwitch) {
                throw new AlertBlockedException("全局推送开关已关闭，这条告警没有发出");
            }
            if (failing) {
                throw new IllegalStateException("模拟出网中断");
            }
            received.add(subject + "\n" + content);
        }

        /**
         * 非空时不再真发，直接回报这个结果。用来走送达不明那一支
         */
        AlertChannel.SendResult forced;

        @Override
        public void sendReporting(String subject, String content,
                                   java.util.function.Consumer<AlertChannel.SendResult> callback) {
            if (forced != null) {
                callback.accept(forced);
                return;
            }
            AlertChannel.super.sendReporting(subject, content, callback);
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

        @Test
        @DisplayName("被全局推送开关拦下时不入队：算这一次的最终失败")
        void noRetryWhenBlockedByMasterSwitch() {
            FakeChannel channel = new FakeChannel("假通道");
            channel.blockedByMasterSwitch = true;
            channels.add(channel);

            service.alert("key", "标题", "内容");

            assertEquals(0, service.pendingCount(), "开关打开后也不补发——被拦下的这一条不进重投");
            assertTrue(channel.received.isEmpty());
        }

        @Test
        @DisplayName("别的通道只是暂时坏、而有一路被开关拦下时，同样不入队")
        void noRetryWhenAnyChannelBlocked() {
            FakeChannel flaky = new FakeChannel("暂时坏的");
            flaky.failing = true;
            FakeChannel blocked = new FakeChannel("被拦的");
            blocked.blockedByMasterSwitch = true;
            channels.add(flaky);
            channels.add(blocked);

            service.alert("key", "标题", "内容");

            // 重投按整条告警重投，分不出「只补没被拦的那一路」；留在队里的话，
            // 开关一打开，被拦的那一路就会跟着【补发】涌出去
            assertEquals(0, service.pendingCount(), "被拦下的一路不许因重投而补发");
        }

        @Test
        @DisplayName("重投撞上全局推送开关关闭：从队列拿掉，不再放回去")
        void dropsPendingWhenRetryHitsMasterSwitchOff() {
            FakeChannel channel = new FakeChannel("假通道");
            channel.failing = true;
            channels.add(channel);

            service.alert("key", "标题", "内容");
            assertEquals(1, service.pendingCount(), "前置：出网中断时照旧入队");

            // 修网途中使用者把总开关关了
            channel.failing = false;
            channel.blockedByMasterSwitch = true;
            service.retryPending();

            assertEquals(0, service.pendingCount(), "开关打开后也不许收到攒下的这一批");
            assertTrue(channel.received.isEmpty());
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

    /**
     * 一个通道都没配时，出的事得在时间线上看得出来
     * <p>
     * 这一支比「发出去」更要紧：发不出去的那一刻往往正是机器掉线、最需要叫人的那一刻，
     * 而只往日志写一行的话，看时间线的人会读成「这天没出过事」。
     */
    @Nested
    @DisplayName("没有配好的通道时也要看得出来")
    class NoChannelRecorded {

        private final List<TimelineEvent> recorded = new ArrayList<>();

        private AlertService capturing() {
            @SuppressWarnings("unchecked")
            ObjectProvider<AlertChannel> provider = mock(ObjectProvider.class);
            when(provider.orderedStream()).thenAnswer(invocation -> channels.stream());
            return new AlertService(properties, provider, recorded::add);
        }

        @Test
        @DisplayName("写着没发出去, 并带上完整的告警标题")
        void recordsWhyNothingWasSent() {
            // 标题刻意长过 24 个字：只留前 24 个字的话，看的人分不出是哪一条告警
            String subject = "机器人与 OneBot 的连接已断开，正在重试，连续 3 次都失败了";

            capturing().alert("link.lost", subject, "内容");

            assertEquals(1, recorded.size(), "该记一条；得到：" + recorded);
            TimelineEvent event = recorded.get(0);
            assertEquals(TimelineEventType.ALERT_FAILED, event.type());
            assertTrue(event.text().contains("没有配置告警通道，没发出去"),
                    "要说清是没发出去；得到：" + event.text());
            assertTrue(event.text().contains(subject), "标题不许截断；得到：" + event.text());
        }

        @Test
        @DisplayName("同一告警在收敛期内只记一条")
        void recordsOnceWithinConvergence() {
            AlertService capturing = capturing();

            capturing.alert("link.lost", "标题", "内容");
            capturing.alert("link.lost", "标题", "内容");

            assertEquals(1, recorded.size(), "收敛期内只该记一条；得到：" + recorded);
        }
    }

    /**
     * 点「发一条测试」往工程日志里写了什么
     * <p>
     * 量的是<b>落盘的那一份文本</b>：按生产 logback.xml 里工程日志那个 appender 的模式
     * 渲染出来再看，不是只看日志调用传了什么参数——异常作为末参交给日志框架时会附上栈迹，
     * 而翻工程日志的人每点一次就看到一大段堆栈，读到的是「出大错了」。
     */
    @Nested
    @DisplayName("测试发送的工程日志")
    class TestLog {

        /**
         * 照 logback.xml 里 RollingFile（工程日志）那个 appender 的模式，只略去 PID 一栏
         */
        private static final String PATTERN =
                "%d{yyyy-MM-dd HH:mm:ss.SSS} %5p --- [%20.20t] %-40.40logger{39} : %msg%n";

        private final Pattern stackFrame = Pattern.compile("(?m)^\\s+at\\s");

        @Test
        @DisplayName("被总开关拦下时只记一行 WARN、写明原因，不打栈")
        void blockedByMasterSwitchLogsOneWarnLineWithoutStack() {
            FakeChannel channel = new FakeChannel("假通道");
            channel.blockedByMasterSwitch = true;
            channels.add(channel);

            String rendered = captureRendered(() -> service.test("fake"));

            assertFalse(stackFrame.matcher(rendered).find(),
                    "被拦下是使用者自己关的开关，不是故障，不该打出堆栈:\n" + rendered);
            String[] lines = rendered.strip().split("\n");
            assertEquals(1, lines.length, "只该记一行，落盘长这样:\n" + rendered);
            assertTrue(lines[0].contains("WARN"), "这一行该是 WARN 不是 ERROR: " + lines[0]);
            assertTrue(lines[0].contains("全局推送开关已关闭，这条告警没有发出"),
                    "那一行要写明没发出去的原因: " + lines[0]);
        }

        @Test
        @DisplayName("对照：别的异常仍带栈，真出错时排查看得见")
        void otherFailuresStillLogStack() {
            FakeChannel channel = new FakeChannel("假通道");
            channel.failing = true;
            channels.add(channel);

            String rendered = captureRendered(() -> service.test("fake"));

            // 这一条看得见栈，上一条的「没有栈」才是真的没有，不是渲染器压根不画栈
            assertTrue(stackFrame.matcher(rendered).find(), "别的异常必须带栈:\n" + rendered);
            assertTrue(rendered.contains("ERROR"), "别的异常照旧记 ERROR: " + rendered);
            assertTrue(rendered.contains("模拟出网中断"), "原因要在: " + rendered);
        }

        /**
         * 把这一趟工程日志按生产的模式渲染出来，返回落盘会长的样子
         */
        private String captureRendered(Runnable action) {
            LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();

            PatternLayoutEncoder encoder = new PatternLayoutEncoder();
            encoder.setContext(context);
            encoder.setPattern(PATTERN);
            encoder.setCharset(StandardCharsets.UTF_8);
            encoder.start();

            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            OutputStreamAppender<ILoggingEvent> appender = new OutputStreamAppender<>();
            appender.setContext(context);
            appender.setEncoder(encoder);
            appender.setOutputStream(sink);
            appender.start();

            ch.qos.logback.classic.Logger logger =
                    (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(AlertService.class);
            boolean additive = logger.isAdditive();
            logger.setAdditive(false);
            logger.addAppender(appender);
            try {
                action.run();
            } finally {
                logger.detachAppender(appender);
                logger.setAdditive(additive);
                appender.stop();
            }
            return sink.toString(StandardCharsets.UTF_8);
        }
    }

    /**
     * 重投时通道没了，以及某一路自己出错
     * <p>
     * 重投撞上一条通道都没配：仍要有次数上限，到了就停。不停的话，
     * 这条告警会一直占着队列。
     * 某一路出了意外，不能让后面几路一起不发。
     */
    @Nested
    @DisplayName("通道中途没了，或某一路自己出错")
    class ChannelGoneOrThrows {

        private final List<TimelineEvent> recorded = new ArrayList<>();

        private AlertService capturing() {
            @SuppressWarnings("unchecked")
            ObjectProvider<AlertChannel> provider = mock(ObjectProvider.class);
            when(provider.orderedStream()).thenAnswer(invocation -> channels.stream());
            return new AlertService(properties, provider, recorded::add);
        }

        private static AlertChannel throwing(String name) {
            return new AlertChannel() {
                @Override
                public String id() {
                    return "boom";
                }

                @Override
                public String name() {
                    return name;
                }

                @Override
                public boolean isAvailable() {
                    return true;
                }

                @Override
                public void send(String subject, String content) {
                }

                @Override
                public void sendReporting(String subject, String content,
                                           java.util.function.Consumer<SendResult> callback) {
                    throw new IllegalStateException("这一路自己出错");
                }
            };
        }

        @Test
        @DisplayName("重投时一条通道都没了：到上限放弃，之后日志页不再每轮多一条")
        void givesUpWhenRetryFindsNoChannel() {
            properties.getAlert().setRetryMaxAttempts(3);
            FakeChannel channel = new FakeChannel("假通道");
            channel.failing = true;
            channels.add(channel);

            AlertService capturing = capturing();
            capturing.alert("key", "标题", "内容");
            assertEquals(1, capturing.pendingCount(), "前置：发不出去时应留在队列里");

            channel.available = false;

            ch.qos.logback.classic.Logger logger =
                    (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(AlertService.class);
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            logger.addAppender(appender);
            try {
                for (int i = 0; i < 3; i++) {
                    capturing.retryPending();
                }
                assertEquals(0, capturing.pendingCount(), "到上限应放弃，不再占着队列");
                int stopped = recorded.size();
                capturing.retryPending();
                assertEquals(stopped, recorded.size(), "放弃之后，日志页不该再多一条没发出去");
            } finally {
                logger.detachAppender(appender);
                appender.stop();
            }

            assertTrue(appender.list.stream().anyMatch(event ->
                            event.getFormattedMessage().contains("重投 3 次仍失败, 放弃")),
                    "到上限要写明放弃；得到：" + appender.list);
            long noChannel = recorded.stream()
                    .filter(event -> event.text() != null && event.text().contains("没有配置告警通道，没发出去"))
                    .count();
            assertEquals(0, noChannel,
                    "重投时没通道不逐趟进日志页；得到 " + noChannel + " 条");
            long abandoned = recorded.stream()
                    .filter(event -> event.text() != null && event.text().contains("不再重投"))
                    .count();
            assertEquals(1, abandoned, "到上限按告警记一条放弃；得到：" + recorded);
        }

        @Test
        @DisplayName("某一路抛错时后面几路照样试，抛错的那一路只记一次")
        void laterChannelsStillRunWhenOneThrows() {
            channels.add(throwing("会炸的"));
            FakeChannel later = new FakeChannel("后面的");
            channels.add(later);

            AlertService capturing = capturing();
            capturing.alert("key", "标题", "内容");

            assertEquals(1, later.received.size(), "前面一路出错，后面一路仍要送到");
            assertEquals(0, capturing.pendingCount(), "有一路送到了，不该整批再试");
            assertEquals(List.of("这一路自己出错"), reasons(recorded, "会炸的"),
                    "抛错的那一路只记一次，原因进详情");

            channels.clear();
            recorded.clear();
            channels.add(throwing("会炸的"));
            AlertService alone = capturing();
            alone.alert("only", "标题", "内容");

            assertEquals(1, alone.pendingCount(), "只有这一路且它抛错时，按发不出去入队一次");
            assertEquals(List.of("这一路自己出错"), reasons(recorded, "会炸的"),
                    "只记一次发不出去，不多记");
        }

        private static List<String> reasons(List<TimelineEvent> events, String channelName) {
            return events.stream()
                    .filter(event -> event.type() == TimelineEventType.ALERT_FAILED
                            && channelName.equals(event.channel()))
                    .map(event -> event.detail().get("reason"))
                    .toList();
        }
    }

    /**
     * 发一条测试过了时限还没等到回话
     * <p>
     * 这一路标成发不出去时设置页是红的。人会去改配置，其实信可能已经到了，
     * 该去那一路上看一眼。
     */
    @Nested
    @DisplayName("发一条测试过了时限还没等到结果")
    class TestStillWaiting {

        @Test
        @DisplayName("QQ 过了时限还没回话却被当成发不出去：人会去改配置，其实信可能已经到了")
        void timeoutIsNotShownAsSendFailure() {
            channels.add(new NeverReplies("qq", "QQ"));

            @SuppressWarnings("unchecked")
            ObjectProvider<AlertChannel> provider = mock(ObjectProvider.class);
            when(provider.orderedStream()).thenAnswer(invocation -> channels.stream());

            AlertService waiting = new AlertService(properties, provider, TimelineWriter.NONE, Duration.ZERO);
            AlertService.TestResult result = waiting.test("qq");

            assertEquals(AlertService.TestResult.Status.UNCERTAIN, result.status(),
                    "还没等到结果该和送达不明一样，设置页才显示成警告");
            assertNotEquals(AlertService.TestResult.Status.FAILED, result.status(),
                    "不该标成发不出去");
            assertFalse(result.delivered(), "还没等到结果不算已经发出去");
            assertEquals("QQ 这一路还没等到结果。请到 QQ 上看一眼，或到日志页「推送」里查看。",
                    result.message());
        }

        /**
         * 可用，但发送后不回报。调用方只能等到时限。
         */
        private static final class NeverReplies implements AlertChannel {
            private final String id;
            private final String name;

            NeverReplies(String id, String name) {
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
                return true;
            }

            @Override
            public void send(String subject, String content) {
            }

            @Override
            public void sendReporting(String subject, String content,
                                       java.util.function.Consumer<AlertChannel.SendResult> callback) {
            }
        }
    }

    /**
     * 日志页上重投和首投长得一样
     * <p>
     * 发不出去的重投不进时间线，否则几秒一条，日志页被刷满。
     * 重投发出去、被拦下、送达不明仍要写明第几次，第一次投递不写。
     */
    @Nested
    @DisplayName("日志页上重投和首投长得一样")
    class RetryLooksLikeFirstSend {

        private final List<TimelineEvent> recorded = new ArrayList<>();

        private AlertService capturing() {
            @SuppressWarnings("unchecked")
            ObjectProvider<AlertChannel> provider = mock(ObjectProvider.class);
            when(provider.orderedStream()).thenAnswer(invocation -> channels.stream());
            return new AlertService(properties, provider, recorded::add);
        }

        private ListAppender<ILoggingEvent> attachLog() {
            ch.qos.logback.classic.Logger logger =
                    (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(AlertService.class);
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            logger.addAppender(appender);
            return appender;
        }

        private static void detachLog(ListAppender<ILoggingEvent> appender) {
            ch.qos.logback.classic.Logger logger =
                    (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(AlertService.class);
            logger.detachAppender(appender);
            appender.stop();
        }

        @Test
        @DisplayName("同一条告警几秒内「发不出去」两遍，看不出第二遍是重投：重投失败不进日志页，首投不标，放弃记一条")
        void failedRetrySaysWhichAttempt() {
            FakeChannel channel = new FakeChannel("qq", "QQ");
            channel.failing = true;
            channels.add(channel);

            AlertService capturing = capturing();
            capturing.alert("login-expired", "登录已失效", "凭据复检未通过");
            capturing.retryPending();
            capturing.retryPending();

            assertEquals(List.of("QQ发不出去：登录已失效"),
                    recorded.stream().map(TimelineEvent::text).toList(),
                    "重投发不出去不进时间线，首投不标；得到：" + recorded);

            properties.getAlert().setRetryMaxAttempts(1);
            channels.clear();
            recorded.clear();
            FakeChannel once = new FakeChannel("qq", "QQ");
            once.failing = true;
            channels.add(once);
            AlertService limited = capturing();
            ListAppender<ILoggingEvent> appender = attachLog();
            try {
                limited.alert("give-up", "登录已失效", "凭据复检未通过");
                limited.retryPending();
            } finally {
                detachLog(appender);
            }
            assertEquals("QQ发不出去：登录已失效", recorded.get(0).text());
            assertEquals("重投 1 次仍没发出去，不再重投：登录已失效", recorded.get(1).text(),
                    "放弃那条要和工程日志同一个次数；得到：" + recorded.get(1).text());
            assertTrue(appender.list.stream().anyMatch(event ->
                            event.getFormattedMessage().contains("重投 1 次仍失败")),
                    "放弃那句的次数要和日志页同一个数；得到：" + appender.list);
            assertEquals(0, limited.pendingCount(), "到上限应放弃");
        }

        @Test
        @DisplayName("重投发出去、被拦下、送达不明时，日志页也和首投长得一样：这些同样标明第几次，首投不标")
        void otherRetryLinesSayWhichAttempt() {
            FakeChannel channel = new FakeChannel("qq", "QQ");
            channel.failing = true;
            channels.add(channel);
            AlertService service = capturing();
            ListAppender<ILoggingEvent> appender = attachLog();
            try {
                service.alert("recovered", "登录已失效", "凭据复检未通过");
                channel.failing = false;
                service.retryPending();
            } finally {
                detachLog(appender);
            }
            assertEquals("QQ发不出去：登录已失效", recorded.get(0).text());
            assertEquals("QQ已报出（重投第 1 次）：登录已失效", recorded.get(1).text(),
                    "补发成功也要标明第几次；得到：" + recorded.get(1).text());
            assertTrue(appender.list.stream().anyMatch(event ->
                            event.getFormattedMessage().contains("已尝试 1 次）")),
                    "「已尝试」的次数要和日志页同一个数；得到：" + appender.list);

            channels.clear();
            recorded.clear();
            FakeChannel blockedFirst = new FakeChannel("qq", "QQ");
            blockedFirst.blockedByMasterSwitch = true;
            channels.add(blockedFirst);
            capturing().alert("blocked-first", "登录已失效", "凭据复检未通过");
            assertEquals("QQ发不出去（全局推送开关已关闭，这条告警没有发出）：登录已失效",
                    recorded.get(0).text(),
                    "第一次被拦下不标重投；得到：" + recorded.get(0).text());

            channels.clear();
            recorded.clear();
            FakeChannel blockedRetry = new FakeChannel("qq", "QQ");
            blockedRetry.failing = true;
            channels.add(blockedRetry);
            AlertService blocked = capturing();
            blocked.alert("blocked-retry", "登录已失效", "凭据复检未通过");
            blockedRetry.failing = false;
            blockedRetry.blockedByMasterSwitch = true;
            blocked.retryPending();
            assertEquals("QQ发不出去（全局推送开关已关闭，这条告警没有发出）（重投第 1 次）：登录已失效",
                    recorded.get(1).text(),
                    "重投被拦下也要标明第几次；得到：" + recorded.get(1).text());

            channels.clear();
            recorded.clear();
            FakeChannel uncertainFirst = new FakeChannel("qq", "QQ");
            uncertainFirst.forced = AlertChannel.SendResult.uncertain("没等到回包");
            channels.add(uncertainFirst);
            AlertService uncertain = capturing();
            uncertain.alert("uncertain-first", "登录已失效", "凭据复检未通过");
            assertEquals("QQ送达不明：登录已失效", recorded.get(0).text(),
                    "第一次送达不明不标重投；得到：" + recorded.get(0).text());
            assertEquals(0, uncertain.pendingCount(), "送达不明不入队");

            channels.clear();
            recorded.clear();
            FakeChannel uncertainRetry = new FakeChannel("qq", "QQ");
            uncertainRetry.failing = true;
            channels.add(uncertainRetry);
            AlertService uncertainLater = capturing();
            uncertainLater.alert("uncertain-retry", "登录已失效", "凭据复检未通过");
            uncertainRetry.failing = false;
            uncertainRetry.forced = AlertChannel.SendResult.uncertain("没等到回包");
            uncertainLater.retryPending();
            assertEquals("QQ送达不明（重投第 1 次）：登录已失效", recorded.get(1).text(),
                    "重投送达不明也要标明第几次；得到：" + recorded.get(1).text());
            assertEquals(0, uncertainLater.pendingCount(), "重投撞上送达不明也不再入队");
        }
    }

    /**
     * 入队那句分不清是通道失败还是没有通道
     * <p>
     * 有通道但都没发出去，和一条可用通道都没有，工程日志原先是同一句。
     * 看日志的人分不清该去修通道，还是根本没配通道。
     */
    @Nested
    @DisplayName("入队那句分不清是通道失败还是没有通道")
    class EnqueueReason {

        private final List<TimelineEvent> recorded = new ArrayList<>();

        private AlertService capturing() {
            @SuppressWarnings("unchecked")
            ObjectProvider<AlertChannel> provider = mock(ObjectProvider.class);
            when(provider.orderedStream()).thenAnswer(invocation -> channels.stream());
            return new AlertService(properties, provider, recorded::add);
        }

        @Test
        @DisplayName("一条通道都没有时仍入队，工程日志却和通道失败写成同一句：没通道要写没有通道")
        void noChannelEnqueueSaysNoChannel() {
            FakeChannel channel = new FakeChannel("qq", "QQ");
            channel.failing = true;
            channels.add(channel);
            AlertService capturing = capturing();

            ch.qos.logback.classic.Logger logger =
                    (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(AlertService.class);
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            logger.addAppender(appender);
            try {
                capturing.alert("login-expired", "登录已失效", "凭据复检未通过");
                channel.available = false;
                capturing.retryPending();
            } finally {
                logger.detachAppender(appender);
                appender.stop();
            }

            List<String> queued = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(message -> message.contains("已入队等待重投"))
                    .toList();
            assertEquals(List.of(
                    "告警 [login-expired] 登录已失效 通道都没发出去, 已入队等待重投, 队列 1 条",
                    "告警 [login-expired] 登录已失效 没有通道, 已入队等待重投, 队列 1 条"),
                    queued,
                    "两种入队要分成两句；得到：" + queued);
            assertEquals(List.of("QQ发不出去：登录已失效"),
                    recorded.stream().map(TimelineEvent::text).toList(),
                    "首投不标，没通道的重投不进时间线；得到：" + recorded);
        }
    }

    /**
     * 告警通道全发不出去时，重投每一趟、每一路都在日志页留一行。
     * 十分钟首页和日志页就被「发不出去」占满，看不出这条告警最后放弃了没有。
     */
    @Nested
    @DisplayName("告警重投把日志页刷满")
    class RetryFloodsTheLog {

        private final List<TimelineEvent> recorded = new ArrayList<>();

        private AlertService capturing() {
            @SuppressWarnings("unchecked")
            ObjectProvider<AlertChannel> provider = mock(ObjectProvider.class);
            when(provider.orderedStream()).thenAnswer(invocation -> channels.stream());
            return new AlertService(properties, provider, recorded::add);
        }

        @Test
        @DisplayName("三路都发不出去、重投走到上限：时间线里这条告警只有首投几条加放弃一条，不是每趟每路一条")
        void threeChannelsFailUntilGiveUpLeavesOneGiveUpLine() {
            properties.getAlert().setRetryMaxAttempts(3);
            channels.add(new FakeChannel("qq", "QQ"));
            channels.add(new FakeChannel("webhook", "Webhook"));
            channels.add(new FakeChannel("mail", "邮件"));
            for (AlertChannel channel : channels) {
                ((FakeChannel) channel).failing = true;
            }

            AlertService capturing = capturing();
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            ch.qos.logback.classic.Logger logger =
                    (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(AlertService.class);
            logger.addAppender(appender);
            try {
                capturing.alert("login-expired", "登录已失效", "凭据复检未通过");
                capturing.retryPending();
                capturing.retryPending();
                capturing.retryPending();
            } finally {
                logger.detachAppender(appender);
                appender.stop();
            }

            List<String> texts = recorded.stream().map(TimelineEvent::text).toList();
            String giveUp = texts.isEmpty() ? "" : texts.get(texts.size() - 1);
            System.out.println("放弃那条：" + giveUp);
            assertEquals(List.of(
                    "QQ发不出去：登录已失效",
                    "Webhook发不出去：登录已失效",
                    "邮件发不出去：登录已失效",
                    "重投 3 次仍没发出去，不再重投：登录已失效"),
                    texts,
                    "首投一路一条，放弃按告警一条，重投失败不进时间线；得到：" + texts);
            assertEquals(null, recorded.get(3).channel(), "放弃按告警记，不挂在某一路上");
            assertEquals(0, capturing.pendingCount(), "到上限应放弃，不再占着队列");
            assertEquals(1, appender.list.stream()
                            .filter(event -> event.getFormattedMessage().contains("重投 3 次仍失败, 放弃"))
                            .count(),
                    "放弃那句工程日志仍只写一次；得到：" + appender.list);
        }

        @Test
        @DisplayName("重投中途有一路送到：那一条「已报出（重投第 N 次）」照记")
        void recoveredChannelOnRetryStillSaysWhichAttempt() {
            channels.add(new FakeChannel("qq", "QQ"));
            channels.add(new FakeChannel("webhook", "Webhook"));
            channels.add(new FakeChannel("mail", "邮件"));
            for (AlertChannel channel : channels) {
                ((FakeChannel) channel).failing = true;
            }

            AlertService capturing = capturing();
            capturing.alert("login-expired", "登录已失效", "凭据复检未通过");
            ((FakeChannel) channels.get(0)).failing = false;
            capturing.retryPending();

            List<String> texts = recorded.stream().map(TimelineEvent::text).toList();
            assertEquals(List.of(
                    "QQ发不出去：登录已失效",
                    "Webhook发不出去：登录已失效",
                    "邮件发不出去：登录已失效",
                    "QQ已报出（重投第 1 次）：登录已失效"),
                    texts,
                    "送到的那一路标明重投第几次；没送到的重投不进时间线；得到：" + texts);
            assertEquals(0, capturing.pendingCount(), "有一路送到了，不再重投");
        }
    }
}
