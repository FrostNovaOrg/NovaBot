package org.frostnova.nova.core.alert;

import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.service.NovaMailService;
import jakarta.mail.Address;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.InternetAddress;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.eclipse.angus.mail.smtp.SMTPSenderFailedException;
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
         * 夹具照真发信链：逐封发信被 550 顶回时，逐封异常表里的值不是
         * SMTPAddressFailedException 本身，而是 SendFailedException「Invalid Addresses」，
         * 服务器那句话挂在它的 next 上，那个收件地址记在它的无效地址名单里。
         * 服务器原话照真形以换行结尾（发信库读回话时每行都补一个换行）。
         */
        @Test
        @DisplayName("收件地址被拒收：说收件地址，不说连不上")
        void rejectedRecipientInPlainWords() throws Exception {
            InternetAddress receiver = new InternetAddress(DEFAULT_TO);
            Map<Object, Exception> failedMessages = new LinkedHashMap<>();
            failedMessages.put(new SimpleMailMessage(), new SendFailedException("Invalid Addresses",
                    new SMTPAddressFailedException(receiver, "RCPT TO:<" + DEFAULT_TO + ">", 550,
                            "550 5.1.1 <owner@example.invalid>: Recipient address rejected\n"),
                    new Address[0], new Address[0], new Address[]{receiver}));
            channels.add(mailChannel(new ThrowingMailSender(new MailSendException(failedMessages))));

            AlertService.TestResult result = service().test("mail");

            assertEquals(AlertService.TestResult.Status.FAILED, result.status(),
                    "服务器拒收也是发不出去，回话不许说发了");
            assertEquals("邮件 这一路发不出去：收件邮箱地址被服务器拒收", result.message());
        }

        /**
         * 信被判成垃圾信时，服务器收完信才把整封信顶回来（DATA 末回 554）。
         * 抓的用户故障：这一形与收件地址没有任何关系，说成「收件地址被拒」
         * 会把人引去改一个没有错的收件地址，改了也没用——要带着服务器原话去查发信那头。
         * 夹具照真发信链：表值就是 SMTPSendFailedException，回码与那一句在它自己身上，
         * 那一句以换行结尾。
         * 抓的另一个用户故障：原话尾巴上的换行照搬进回话，界面上右括号前多出一个空格。
         */
        @Test
        @DisplayName("判垃圾信：说服务器拒收并带原话，不说收件地址")
        void spamRejectionCarriesServerWords() throws Exception {
            Map<Object, Exception> failedMessages = new LinkedHashMap<>();
            failedMessages.put(new SimpleMailMessage(), new SMTPSendFailedException(
                    "DATA", 554, "554 5.7.1 Message rejected as spam\n", null,
                    new Address[0], new Address[]{new InternetAddress(DEFAULT_TO)}, new Address[0]));
            channels.add(mailChannel(new ThrowingMailSender(new MailSendException(failedMessages))));

            AlertService.TestResult result = service().test("mail");

            assertEquals(AlertService.TestResult.Status.FAILED, result.status(),
                    "服务器拒收也是发不出去，回话不许说发了");
            assertEquals("邮件 这一路发不出去：服务器拒收了这封信"
                            + "（服务器原话：554 5.7.1 Message rejected as spam）",
                    result.message());
        }

        /**
         * 发件邮箱当天发信数到了上限时，发件那一步就被顶回来（MAIL FROM 回 550 5.4.5）。
         * 抓的用户故障：这也不是收件地址的错，等第二天配额回来或换发件邮箱才有用——
         * 回话同样要带服务器原话，而不是「收件地址被拒」。
         * 夹具照真发信链：表值是 SMTPSendFailedException，next 上挂发件那一步的
         * SMTPSenderFailedException，两层是同一句服务器原话。
         */
        @Test
        @DisplayName("发信配额满：说服务器拒收并带原话，不说收件地址")
        void quotaExhaustionCarriesServerWords() throws Exception {
            Map<Object, Exception> failedMessages = new LinkedHashMap<>();
            failedMessages.put(new SimpleMailMessage(), new SMTPSendFailedException(
                    "MAIL FROM", 550, "550 5.4.5 Daily sending quota exceeded\n",
                    new SMTPSenderFailedException(new InternetAddress(FROM),
                            "MAIL FROM", 550, "550 5.4.5 Daily sending quota exceeded\n"),
                    new Address[0], new Address[]{new InternetAddress(DEFAULT_TO)}, new Address[0]));
            channels.add(mailChannel(new ThrowingMailSender(new MailSendException(failedMessages))));

            AlertService.TestResult result = service().test("mail");

            assertEquals(AlertService.TestResult.Status.FAILED, result.status(),
                    "服务器拒收也是发不出去，回话不许说发了");
            assertEquals("邮件 这一路发不出去：服务器拒收了这封信"
                            + "（服务器原话：550 5.4.5 Daily sending quota exceeded）",
                    result.message());
        }

        /**
         * 服务器常把一句长话分几行回（前几行是「550-」开头，末行是「550 」开头），
         * 发信库把这几行用换行连成一串交出来。
         * 抓的用户故障：几行原样带进回话，界面上挤成一长串，每行开头的回码重复出现，
         * 读起来像几句不相干的话。要并成一句：回码只留一次，各行的话接着说下去。
         */
        @Test
        @DisplayName("服务器分三行回话：并成一句，回码只留一次")
        void multiLineRefusalOnOneLine() throws Exception {
            String said = "550-5.4.5 Daily user sending limit exceeded. For more information on\n"
                    + "550-5.4.5 sending limits go to\n"
                    + "550 5.4.5  https://help.example.invalid/sending-limits\n";
            Map<Object, Exception> failedMessages = new LinkedHashMap<>();
            failedMessages.put(new SimpleMailMessage(), new SMTPSendFailedException(
                    "MAIL FROM", 550, said,
                    new SMTPSenderFailedException(new InternetAddress(FROM), "MAIL FROM", 550, said),
                    new Address[0], new Address[]{new InternetAddress(DEFAULT_TO)}, new Address[0]));
            channels.add(mailChannel(new ThrowingMailSender(new MailSendException(failedMessages))));

            AlertService.TestResult result = service().test("mail");

            assertEquals("邮件 这一路发不出去：服务器拒收了这封信（服务器原话：550 5.4.5 Daily user sending"
                            + " limit exceeded. For more information on sending limits go to"
                            + " https://help.example.invalid/sending-limits）",
                    result.message());
        }

        /**
         * 有的服务器把整段说明塞进一句回话里，几百个字。
         * 抓的用户故障：整句原样带进回话，设置页的提示和日志页那一条被撑满一屏，
         * 前面那句「服务器拒收了这封信」反倒找不见了。要截短，并说明截过。
         */
        @Test
        @DisplayName("服务器原话过长：截短并说明截过")
        void overlongRefusalIsCut() throws Exception {
            String said = "554 5.7.1 "
                    + "The message was rejected because it matched a local content policy rule. ".repeat(7);
            Map<Object, Exception> failedMessages = new LinkedHashMap<>();
            failedMessages.put(new SimpleMailMessage(), new SMTPSendFailedException(
                    "DATA", 554, said + "\n", null,
                    new Address[0], new Address[]{new InternetAddress(DEFAULT_TO)}, new Address[0]));
            channels.add(mailChannel(new ThrowingMailSender(new MailSendException(failedMessages))));

            AlertService.TestResult result = service().test("mail");

            assertTrue(said.length() > 480, "前置：原话要足够长: " + said.length());
            assertEquals("邮件 这一路发不出去：服务器拒收了这封信（服务器原话："
                            + said.substring(0, 200).strip() + "……（原话过长，后面略去））",
                    result.message());
        }

        /**
         * 收件那一步回 4xx（灰名单、对方收件箱暂时满了）是暂时的，地址本身没有错。
         * 抓的用户故障：说成「收件邮箱地址被服务器拒收」，使用者会去改一个没有错的收件地址。
         * 要说服务器暂时拒收，并带上原话。
         * 夹具照真发信链：4xx 时那个收件地址记在「有效但没发出」名单里，无效地址名单是空的，
         * 服务器那句话挂在 next 上的 SMTPAddressFailedException 里，回码 451。
         * 灰名单的原话里常带「Recipient address rejected」，照实带出，前半句仍说暂时拒收。
         */
        @Test
        @DisplayName("收件那一步暂时拒收：说暂时拒收并带原话，不说收件地址被拒")
        void temporaryRecipientRefusalIsNotAddressRejection() throws Exception {
            InternetAddress receiver = new InternetAddress(DEFAULT_TO);
            Map<Object, Exception> failedMessages = new LinkedHashMap<>();
            failedMessages.put(new SimpleMailMessage(), new SendFailedException("Invalid Addresses",
                    new SMTPAddressFailedException(receiver, "RCPT TO:<" + DEFAULT_TO + ">", 451,
                            "451 4.7.1 <owner@example.invalid>: Recipient address rejected:"
                                    + " Greylisted, please try again later\n"),
                    new Address[0], new Address[]{receiver}, new Address[0]));
            channels.add(mailChannel(new ThrowingMailSender(new MailSendException(failedMessages))));

            AlertService.TestResult result = service().test("mail");

            assertEquals(AlertService.TestResult.Status.FAILED, result.status());
            assertEquals("邮件 这一路发不出去：服务器暂时拒收了这封信（服务器原话：451 4.7.1 <owner@example.invalid>:"
                            + " Recipient address rejected: Greylisted, please try again later）",
                    result.message());
        }

        /**
         * 对方邮箱满了时，服务器在收件那一步回 552（「552 5.2.2 …: Mailbox full」）。
         * 抓的用户故障：界面说「收件邮箱地址被服务器拒收」，使用者去改一个没有错的收件地址，
         * 改了也没用。地址本身没有错，要说服务器暂时拒收，并带上原话，让人看出是对方邮箱满了。
         * 收件那一步回的 552 按暂时失败对待（发信库把这个地址记在「有效但没发出」名单里，
         * 无效地址名单是空的），夹具照这条真链。
         */
        @Test
        @DisplayName("收件那一步回552对方邮箱满：说暂时拒收并带原话，不说收件地址被拒")
        void mailboxFullAtRecipientIsNotAddressRejection() throws Exception {
            InternetAddress receiver = new InternetAddress(DEFAULT_TO);
            Map<Object, Exception> failedMessages = new LinkedHashMap<>();
            failedMessages.put(new SimpleMailMessage(), new SendFailedException("Invalid Addresses",
                    new SMTPAddressFailedException(receiver, "RCPT TO:<" + DEFAULT_TO + ">", 552,
                            "552 5.2.2 <owner@example.invalid>: Mailbox full\n"),
                    new Address[0], new Address[]{receiver}, new Address[0]));
            channels.add(mailChannel(new ThrowingMailSender(new MailSendException(failedMessages))));

            AlertService.TestResult result = service().test("mail");

            assertEquals(AlertService.TestResult.Status.FAILED, result.status());
            assertEquals("邮件 这一路发不出去：服务器暂时拒收了这封信"
                            + "（服务器原话：552 5.2.2 <owner@example.invalid>: Mailbox full）",
                    result.message());
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
