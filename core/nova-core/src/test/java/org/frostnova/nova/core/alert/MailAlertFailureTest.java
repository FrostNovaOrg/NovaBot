package org.frostnova.nova.core.alert;

import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.service.NovaMailService;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.frostnova.nova.core.timeline.TimelineEvent;
import org.frostnova.nova.core.timeline.TimelineEventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.SocketException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 邮件告警发不出去时的两句实话
 * <p>
 * 抓的用户故障：邮件服务器连不上时，设置页点「发一条测试」界面说「已经发了一条测试告警」，
 * 日志页记「邮件已报出」——发信那一步的异常被吞掉当成了成功。使用者以为配好了，
 * 真出事的那天告警邮件没有发出去，也没有人知道它丢了。
 * <p>
 * 走的是真通道与真发信路径，只有 SMTP 那一头是替身：连不上那种用当场抛异常的发信器量，
 * 「配好了却取不到发信器」那种用真 bean 容器量——手写的替身量不出「装了两个发信器」那一档。
 */
@DisplayName("邮件告警发不出去")
class MailAlertFailureTest {
    private static final String FROM = "robot@example.invalid";

    private static final String DEFAULT_TO = "owner@example.invalid";

    private static final String SMTP_HOST = "smtp.example.invalid";

    private NovaCoreProperties properties;

    private final List<TimelineEvent> timeline = new ArrayList<>();

    private final List<AlertChannel> channels = new ArrayList<>();

    @BeforeEach
    void setUp() {
        properties = new NovaCoreProperties();
        properties.getMail().setDefaultTo(DEFAULT_TO);
    }

    /**
     * 只把信收进列表的发信器替身，绝不连 SMTP
     */
    private static class CapturingMailSender extends JavaMailSenderImpl {
        final List<SimpleMailMessage> plain = new ArrayList<>();

        @Override
        public void send(SimpleMailMessage... messages) {
            plain.addAll(Arrays.asList(messages));
        }
    }

    /**
     * 一发就当场炸的发信器，扮演连不上的邮件服务器。
     * 炸的形照真调用链：doSend 连不上时抛的是带起因与逐封异常表的
     * MailSendException(String, Throwable, Map)，起因和表里都是连不上的那个 MessagingException
     */
    private static class RefusingMailSender extends JavaMailSenderImpl {
        @Override
        public void send(SimpleMailMessage... messages) {
            MessagingException couldNotConnect = new MessagingException(
                    "Couldn't connect to SMTP host: smtp.example.invalid, port: 465",
                    new SocketException("Connection refused"));
            Map<Object, Exception> failedMessages = new LinkedHashMap<>();
            failedMessages.put(new SimpleMailMessage(), couldNotConnect);
            throw new MailSendException("Mail server connection failed", couldNotConnect, failedMessages);
        }
    }

    /**
     * 抛指定异常的发信器，用来量「往外交的那一句失败原因怎么说」
     */
    private static class ThrowingMailSender extends JavaMailSenderImpl {
        private final RuntimeException toThrow;

        ThrowingMailSender(RuntimeException toThrow) {
            this.toThrow = toThrow;
        }

        @Override
        public void send(SimpleMailMessage... messages) {
            throw toThrow;
        }
    }

    /**
     * 用真的 bean 容器造 {@code ObjectProvider}：现码靠 {@code getIfUnique} 判「该用哪一个」，
     * 手写一个替身量不出「装了两个发信器」那一档
     */
    private static ObjectProvider<JavaMailSender> providerOf(JavaMailSender... senders) {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        for (int i = 0; i < senders.length; i++) {
            beanFactory.registerSingleton("mailSender" + i, senders[i]);
        }
        return beanFactory.getBeanProvider(JavaMailSender.class);
    }

    private MailAlertChannel mailChannel(JavaMailSender... senders) {
        NovaMailService mailService = new NovaMailService(providerOf(senders), properties);
        ReflectionTestUtils.setField(mailService, "from", FROM);
        return new MailAlertChannel(mailService, properties, SMTP_HOST);
    }

    private AlertService service() {
        @SuppressWarnings("unchecked")
        ObjectProvider<AlertChannel> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenAnswer(invocation -> channels.stream());
        return new AlertService(properties, provider, timeline::add);
    }

    /**
     * 能收信的别的通道，扮演 Webhook 与 QQ 那两路
     */
    private static class RecordingChannel implements AlertChannel {
        private final String name;
        final List<String> received = new ArrayList<>();

        RecordingChannel(String name) {
            this.name = name;
        }

        @Override
        public String id() {
            return name;
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
            received.add(subject + "\n" + content);
        }
    }

    @Nested
    @DisplayName("发一条测试")
    class TestButton {

        @Test
        @DisplayName("发成功：报发了")
        void delivered() {
            CapturingMailSender sender = new CapturingMailSender();
            channels.add(mailChannel(sender));

            AlertService.TestResult result = service().test("mail");

            assertEquals(AlertService.TestResult.Status.DELIVERED, result.status());
            assertEquals("已经往 邮件 发了一条测试告警，去看看收到没有。", result.message());
            assertEquals(1, sender.plain.size(), "测试必须真发一封，回话才有依据");
        }

        @Test
        @DisplayName("连不上邮件服务器：报失败并带原因，不许说发了")
        void connectionRefusedReportsFailure() {
            channels.add(mailChannel(new RefusingMailSender()));

            AlertService.TestResult result = service().test("mail");

            assertEquals(AlertService.TestResult.Status.FAILED, result.status(),
                    "连不上却说发了，使用者会以为这一路配好了");
            assertEquals("邮件 这一路发不出去：连不上邮件服务器", result.message());
        }

        /**
         * 授权码过期是最常见的一种发不出去。回话说「连不上」会把人引去查服务器地址和端口，
         * 真正要改的授权码没人去动——认证失败要说认证失败。
         * 夹具照真调用链：connect 时认证失败，doSend 把它翻译成
         * MailAuthenticationException(String, Throwable)，起因链上是服务器拒绝登录的
         * AuthenticationFailedException。
         */
        @Test
        @DisplayName("授权码不对：说账号或授权码，不说连不上")
        void authFailureInPlainWords() {
            channels.add(mailChannel(new ThrowingMailSender(new MailAuthenticationException(
                    "Authentication failed",
                    new AuthenticationFailedException("535 Error: authentication failed")))));

            AlertService.TestResult result = service().test("mail");

            assertEquals(AlertService.TestResult.Status.FAILED, result.status(),
                    "服务器没认这个身份也是发不出去，回话不许说发了");
            assertEquals("邮件 这一路发不出去：邮箱账号或授权码不对，服务器拒绝了登录", result.message());
        }

        /**
         * 收件邮箱填错或被对方服务器拒收时，服务器在收件那一步就把信顶回来。
         * 这一格钉的是：它要说收件地址，而不是把人引去查网络的「连不上」。
         * 夹具照真调用链：doSend 逐封发送被 550 顶回时，这封的异常收进逐封异常表，
         * 末尾以 MailSendException(Map) 抛出——不带起因，只带表。
         */
        @Test
        @DisplayName("收件地址被拒收：说收件地址，不说连不上")
        void rejectedRecipientInPlainWords() throws Exception {
            Map<Object, Exception> failedMessages = new LinkedHashMap<>();
            failedMessages.put(new SimpleMailMessage(),
                    new SMTPAddressFailedException(new InternetAddress(DEFAULT_TO),
                            "RCPT TO", 550,
                            "550 5.1.1 <owner@example.invalid>: Recipient address rejected"));
            channels.add(mailChannel(new ThrowingMailSender(new MailSendException(failedMessages))));

            AlertService.TestResult result = service().test("mail");

            assertEquals(AlertService.TestResult.Status.FAILED, result.status(),
                    "服务器拒收也是发不出去，回话不许说发了");
            assertEquals("邮件 这一路发不出去：收件邮箱地址被服务器拒收", result.message());
        }

        @Test
        @DisplayName("配好了、取到的发信器却不唯一：报失败，不许说发了")
        void ambiguousSenderReportsFailure() {
            MailAlertChannel channel = mailChannel(new CapturingMailSender(), new CapturingMailSender());
            assertTrue(channel.isAvailable(), "前置：设置页按「配好了」显示，点测试走真发送");
            channels.add(channel);

            AlertService.TestResult result = service().test("mail");

            assertEquals(AlertService.TestResult.Status.FAILED, result.status(),
                    "这一路看起来配好了，实际一封也发不出去，回话不许说发了");
            assertEquals("邮件 这一路发不出去：装了多个发信服务，认不出该用哪一个", result.message());
        }
    }

    @Nested
    @DisplayName("真告警路")
    class Delivery {

        @Test
        @DisplayName("邮件发不出去记「发不出去」，另外两路照发、照记「已报出」")
        void mailFailureRecordedWhileOthersDeliver() {
            channels.add(mailChannel(new RefusingMailSender()));
            RecordingChannel webhook = new RecordingChannel("Webhook");
            RecordingChannel qq = new RecordingChannel("QQ");
            channels.add(webhook);
            channels.add(qq);

            AlertService alertService = service();
            alertService.alert("link.lost", "机器人掉线", "请重新扫码");

            TimelineEvent mailEvent = timeline.stream()
                    .filter(event -> event.type() == TimelineEventType.ALERT_FAILED)
                    .filter(event -> "邮件".equals(event.channel()))
                    .findFirst().orElse(null);
            assertNotNull(mailEvent, "邮件这一路发不出去，时间线上必须有它的「发不出去」: " + timeline);
            assertTrue(mailEvent.text().contains("邮件发不出去"), mailEvent.text());

            assertEquals(2, timeline.stream()
                            .filter(event -> event.type() == TimelineEventType.ALERT_SENT)
                            .count(),
                    "另两路各记一条「已报出」: " + timeline);
            assertTrue(timeline.stream()
                    .filter(event -> event.type() == TimelineEventType.ALERT_SENT)
                    .allMatch(event -> event.text().contains("已报出") && event.channel() != null),
                    "记的是各自的通道名: " + timeline);

            assertEquals(1, webhook.received.size(), "Webhook 照发");
            assertEquals(1, qq.received.size(), "QQ 照发");
            assertEquals(0, alertService.pendingCount(),
                    "邮件失败而别的路送到了，人已经收到，不许重投");
        }
    }
}
