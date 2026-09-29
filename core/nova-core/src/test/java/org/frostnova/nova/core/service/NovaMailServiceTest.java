package org.frostnova.nova.core.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.frostnova.nova.core.config.NovaCoreProperties;
import jakarta.mail.BodyPart;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
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
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 邮件发送
 *
 * <h2>为什么会有这组判据</h2>
 * 告警走这里出去。它的两种坏法都不响：<b>没配发信服务</b>时该说一句然后放过，
 * 不能把整条告警链拖崩；<b>配好了</b>时发出去的那封得是完整的一封——
 * 收件人、发件人、主题、正文缺一样，收到的人就看不出这是哪台机器在报什么警。
 * <p>
 * 判据全程<b>不连任何真实的 SMTP</b>：发信器是一个只把信收进列表的替身。
 * 一条要连外网才跑得动的判据，会在断网那天变成一条没人相信的红。
 */
@DisplayName("邮件发送")
class NovaMailServiceTest {
    private static final String FROM = "robot@example.invalid";

    private static final String DEFAULT_TO = "owner@example.invalid";

    private static final String SUBJECT = "开播提醒失败";

    private static final String CONTENT = "连不上数据源";

    /** 只把信收进列表的发信器替身，绝不连 SMTP */
    private static class CapturingMailSender extends JavaMailSenderImpl {
        private final List<SimpleMailMessage> plain = new ArrayList<>();

        private final List<MimeMessage> rich = new ArrayList<>();

        @Override
        public void send(SimpleMailMessage... messages) {
            plain.addAll(Arrays.asList(messages));
        }

        @Override
        public void send(MimeMessage... messages) {
            rich.addAll(Arrays.asList(messages));
        }
    }

    /**
     * 发什么都当场炸的发信器，用来量「发不出去时会不会把调用方一起拖下水」。
     * 炸的形照真调用链：doSend 连不上时抛的是带起因与逐封异常表的
     * MailSendException(String, Throwable, Map)，起因和表里都是连不上的那个 MessagingException
     */
    private static class FailingMailSender extends JavaMailSenderImpl {
        @Override
        public void send(SimpleMailMessage... messages) {
            throwConnectRefused();
        }

        @Override
        public void send(MimeMessage... messages) {
            throwConnectRefused();
        }

        private static void throwConnectRefused() {
            MessagingException couldNotConnect = new MessagingException(
                    "Couldn't connect to SMTP host: smtp.example.invalid, port: 465",
                    new SocketException("Connection refused"));
            Map<Object, Exception> failedMessages = new LinkedHashMap<>();
            failedMessages.put(new SimpleMailMessage(), couldNotConnect);
            throw new MailSendException("Mail server connection failed", couldNotConnect, failedMessages);
        }
    }

    /** 抛指定异常的发信器，用来量「往外交的那一句失败原因怎么说」 */
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
     * 用真的 bean 容器造 {@code ObjectProvider}：现码靠 {@code getIfUnique} 判「配没配」，
     * 手写一个替身量不出「装了两个发信器」那一档。
     */
    private static ObjectProvider<JavaMailSender> providerOf(JavaMailSender... senders) {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        for (int i = 0; i < senders.length; i++) {
            beanFactory.registerSingleton("mailSender" + i, senders[i]);
        }
        return beanFactory.getBeanProvider(JavaMailSender.class);
    }

    private static NovaMailService service(String defaultTo, JavaMailSender... senders) {
        NovaCoreProperties properties = new NovaCoreProperties();
        properties.getMail().setDefaultTo(defaultTo);

        NovaMailService service = new NovaMailService(providerOf(senders), properties);
        ReflectionTestUtils.setField(service, "from", FROM);
        return service;
    }

    private static List<String> logsOf(Level level, Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger(NovaMailService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            action.run();
            return appender.list.stream()
                    .filter(event -> event.getLevel() == level)
                    .map(ILoggingEvent::getFormattedMessage)
                    .collect(Collectors.toList());
        } finally {
            logger.detachAppender(appender);
        }
    }

    private static List<String> partTypes(Object content) throws Exception {
        List<String> types = new ArrayList<>();
        if (content instanceof Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart part = multipart.getBodyPart(i);
                types.add(part.getContentType());
                types.addAll(partTypes(part.getContent()));
            }
        }
        return types;
    }

    @Nested
    @DisplayName("纯文本邮件")
    class PlainText {
        @Test
        @DisplayName("发出去的信四样齐全")
        void sendsACompleteMessage() {
            CapturingMailSender sender = new CapturingMailSender();
            service(DEFAULT_TO, sender).sendMail(SUBJECT, CONTENT);

            assertEquals(1, sender.plain.size(), "应当正好发一封");
            SimpleMailMessage message = sender.plain.get(0);
            assertEquals(FROM, message.getFrom(), "发件人取自配置项 spring.mail.username");
            assertEquals(List.of(DEFAULT_TO), List.of(message.getTo()));
            assertEquals(SUBJECT, message.getSubject());
            assertEquals(CONTENT, message.getText());
        }

        @Test
        @DisplayName("指定收件人时以指定的为准")
        void explicitReceiverWins() {
            CapturingMailSender sender = new CapturingMailSender();
            service(DEFAULT_TO, sender).sendMail("someone@example.invalid", SUBJECT, CONTENT);

            assertEquals(List.of("someone@example.invalid"), List.of(sender.plain.get(0).getTo()));
        }

        @Test
        @DisplayName("发信失败时只记一笔，不把调用方拖下水")
        void swallowsSendFailure() {
            NovaMailService service = service(DEFAULT_TO, new FailingMailSender());

            List<String> errors = logsOf(Level.ERROR, () ->
                    assertDoesNotThrow(() -> service.sendMail(SUBJECT, CONTENT)));

            assertEquals(1, errors.size(), "发不出去要留一笔: " + errors);
            assertTrue(errors.get(0).contains(SUBJECT), "留的这一笔要说清是哪封信: " + errors.get(0));
        }
    }

    @Nested
    @DisplayName("富文本邮件")
    class Rich {
        @Test
        @DisplayName("发出去的信四样齐全")
        void sendsACompleteMessage() throws Exception {
            CapturingMailSender sender = new CapturingMailSender();
            service(DEFAULT_TO, sender).sendMimeMail(SUBJECT, "<b>" + CONTENT + "</b>");

            assertEquals(1, sender.rich.size(), "应当正好发一封");
            MimeMessage message = sender.rich.get(0);
            assertEquals(FROM, message.getFrom()[0].toString());
            assertEquals(DEFAULT_TO, message.getAllRecipients()[0].toString());
            assertEquals(SUBJECT, message.getSubject());
        }

        /**
         * 🔴 正文必须是 HTML。写成纯文本发出去不会报错，收到的人看到的是一串尖括号——
         * 而告警邮件的正文本来就带标记。
         */
        @Test
        @DisplayName("正文是 HTML，不是被当成纯文本发出去")
        void bodyIsHtml() throws Exception {
            CapturingMailSender sender = new CapturingMailSender();
            service(DEFAULT_TO, sender).sendMimeMail(SUBJECT, "<b>" + CONTENT + "</b>");

            // 内容类型这个头是在 saveChanges 那一刻才落到信上的，真发的时候由发信器来做；
            // 不先落一遍，量到的是 MimeBodyPart 的默认值 text/plain
            MimeMessage message = sender.rich.get(0);
            message.saveChanges();

            List<String> types = partTypes(message.getContent());

            assertTrue(types.stream().anyMatch(type -> type.toLowerCase().startsWith("text/html")),
                    "找不到 HTML 正文: " + types);
        }

        @Test
        @DisplayName("指定收件人时以指定的为准")
        void explicitReceiverWins() throws Exception {
            CapturingMailSender sender = new CapturingMailSender();
            service(DEFAULT_TO, sender).sendMimeMail("someone@example.invalid", SUBJECT, CONTENT);

            assertEquals("someone@example.invalid", sender.rich.get(0).getAllRecipients()[0].toString());
        }

        @Test
        @DisplayName("发信失败时只记一笔，不把调用方拖下水")
        void swallowsSendFailure() {
            NovaMailService service = service(DEFAULT_TO, new FailingMailSender());

            List<String> errors = logsOf(Level.ERROR, () ->
                    assertDoesNotThrow(() -> service.sendMimeMail(SUBJECT, CONTENT)));

            assertEquals(1, errors.size(), "发不出去要留一笔: " + errors);
            assertTrue(errors.get(0).contains(SUBJECT), "留的这一笔要说清是哪封信: " + errors.get(0));
        }
    }

    /**
     * 告警那一条发信路：发不出去要抛，调用方才能如实说「没发出去、为什么」——
     * 吞掉的话，连不上邮件服务器也会被报成「告警已送出」。
     */
    @Nested
    @DisplayName("告警邮件")
    class AlertMail {

        @Test
        @DisplayName("发出去的信四样齐全")
        void sendsACompleteMessage() {
            CapturingMailSender sender = new CapturingMailSender();
            service(DEFAULT_TO, sender).sendAlertMail(SUBJECT, CONTENT);

            assertEquals(1, sender.plain.size(), "应当正好发一封");
            SimpleMailMessage message = sender.plain.get(0);
            assertEquals(FROM, message.getFrom(), "发件人取自配置项 spring.mail.username");
            assertEquals(List.of(DEFAULT_TO), List.of(message.getTo()));
            assertEquals(SUBJECT, message.getSubject());
            assertEquals(CONTENT, message.getText());
        }

        @Test
        @DisplayName("发信失败时把原因抛给调用方，原文留在起因链里")
        void throwsOnSendFailure() {
            NovaMailService service = service(DEFAULT_TO, new FailingMailSender());

            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> service.sendAlertMail(SUBJECT, CONTENT));

            assertEquals("连不上邮件服务器", failure.getMessage(),
                    "抛出去的只说原因，「发不出去」那句由告警服务拼");
            assertNotNull(failure.getCause(), "原文要跟着异常走，工程日志里才查得到全文");
        }

        @Test
        @DisplayName("连不上那种失败收口成一句人话")
        void connectionRefusalInPlainWords() {
            // 夹具照真调用链：doSend 连不上时抛 MailSendException(String, Throwable, Map)，
            // 起因和逐封异常表里都是连不上的那个 MessagingException
            MessagingException couldNotConnect = new MessagingException(
                    "Couldn't connect to SMTP host: smtp.example.invalid, port: 465",
                    new SocketException("Connection refused"));
            Map<Object, Exception> failedMessages = new LinkedHashMap<>();
            failedMessages.put(new SimpleMailMessage(), couldNotConnect);
            MailSendException wrapped = new MailSendException("Mail server connection failed",
                    couldNotConnect, failedMessages);
            NovaMailService service = service(DEFAULT_TO, new ThrowingMailSender(wrapped));

            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> service.sendAlertMail(SUBJECT, CONTENT));

            assertEquals("连不上邮件服务器", failure.getMessage());
        }

        /**
         * 逐封异常表里混着 null 时，认不出原因也不能炸：发信库拼「Failed messages」
         * 那句时会对表里的 null 调 toString，取话那一步自己就先炸了——
         * 兜底取不到话就按没有话交代，不许把空指针抛给「发一条测试」的调用方。
         */
        @Test
        @DisplayName("逐封异常表里混着空值：跳过它，照常交代一句")
        void nullTableEntryIsSkipped() {
            Map<Object, Exception> failedMessages = new LinkedHashMap<>();
            failedMessages.put(new SimpleMailMessage(), null);
            NovaMailService service = service(DEFAULT_TO,
                    new ThrowingMailSender(new MailSendException(failedMessages)));

            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> service.sendAlertMail(SUBJECT, CONTENT));

            assertEquals("发送失败（MailSendException）", failure.getMessage());
        }

        /**
         * 表内异常的起因指回外层那封的异常时，起因链成环。防环集要跟着递归一路传下去，
         * 每层各建一个新的，跨层的环就防不住——认不出时也不许栈溢出，照常落回兜底那句。
         * 表值用 RuntimeException 造环：jakarta 的 MessagingException(String) 在构造里就把
         * 起因定成「没有」，initCause 想指回外层都指不了。
         */
        @Test
        @DisplayName("表内异常的起因指回外层：不成环栈溢出，照常交代一句")
        void cycleThroughTableDoesNotOverflow() {
            RuntimeException odd = new RuntimeException("odd");
            Map<Object, Exception> failedMessages = new LinkedHashMap<>();
            failedMessages.put(new SimpleMailMessage(), odd);
            MailSendException outer = new MailSendException(failedMessages);
            odd.initCause(outer);
            NovaMailService service = service(DEFAULT_TO, new ThrowingMailSender(outer));

            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> service.sendAlertMail(SUBJECT, CONTENT));

            assertEquals("Failed messages: java.lang.RuntimeException: odd", failure.getMessage());
        }

        @Test
        @DisplayName("没配发信服务：抛，不装作发过")
        void missingSenderThrows() {
            NovaMailService service = service(DEFAULT_TO);

            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> service.sendAlertMail(SUBJECT, CONTENT));

            assertEquals("发信服务没有配置好", failure.getMessage());
        }

        @Test
        @DisplayName("装了两个发信器：抛，不装作发过")
        void ambiguousSenderThrows() {
            NovaMailService service = service(DEFAULT_TO,
                    new CapturingMailSender(), new CapturingMailSender());

            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> service.sendAlertMail(SUBJECT, CONTENT));

            assertEquals("装了多个发信服务，认不出该用哪一个", failure.getMessage());
        }

        @Test
        @DisplayName("没有收件邮箱：抛，不装作发过")
        void missingReceiverThrows() {
            NovaMailService service = service(null, new CapturingMailSender());

            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> service.sendAlertMail(SUBJECT, CONTENT));

            assertEquals("没有收件邮箱，这封信不知道该发给谁", failure.getMessage());
        }
    }

    @Nested
    @DisplayName("没配好的时候")
    class NotConfigured {
        @Test
        @DisplayName("没配收件人：说一句，一封不发")
        void missingReceiverWarnsAndSendsNothing() {
            CapturingMailSender sender = new CapturingMailSender();
            NovaMailService service = service(null, sender);

            List<String> warnings = logsOf(Level.WARN, () -> service.sendMail(SUBJECT, CONTENT));

            assertEquals(1, warnings.size(), "应当正好说一句: " + warnings);
            assertTrue(warnings.get(0).contains("未配置默认邮件接收地址"), warnings.get(0));
            assertEquals(0, sender.plain.size(), "没有收件人就不该发");
        }

        @Test
        @DisplayName("收件人填的是空白也算没配")
        void blankReceiverCountsAsMissing() {
            CapturingMailSender sender = new CapturingMailSender();
            service("   ", sender).sendMail(SUBJECT, CONTENT);

            assertEquals(0, sender.plain.size());
        }

        @Test
        @DisplayName("没配发信服务：说一句，不抛")
        void missingSenderWarnsAndDoesNotThrow() {
            NovaMailService service = service(DEFAULT_TO);

            List<String> warnings = logsOf(Level.WARN, () ->
                    assertDoesNotThrow(() -> service.sendMail(SUBJECT, CONTENT)));

            assertEquals(1, warnings.size(), "应当正好说一句: " + warnings);
            assertTrue(warnings.get(0).contains("未配置邮件发送服务"), warnings.get(0));
            assertTrue(warnings.get(0).contains(DEFAULT_TO), "要说清是发给谁的那封没发出去: " + warnings.get(0));
        }

        /**
         * 装了两个发信器时 {@code getIfUnique} 也答 null，现码把这一档与「没配」同等对待。
         * 钉住是因为它看起来像「配好了」，实际一封都发不出去。
         */
        @Test
        @DisplayName("装了两个发信器时按没配处理")
        void ambiguousSenderCountsAsMissing() {
            CapturingMailSender first = new CapturingMailSender();
            CapturingMailSender second = new CapturingMailSender();
            NovaMailService service = service(DEFAULT_TO, first, second);

            List<String> warnings = logsOf(Level.WARN, () -> service.sendMail(SUBJECT, CONTENT));

            assertEquals(1, warnings.size(), "应当正好说一句: " + warnings);
            assertTrue(warnings.get(0).contains("未配置邮件发送服务"), warnings.get(0));
            assertEquals(0, first.plain.size() + second.plain.size());
        }

        /**
         * 两样都没配时，先报的是收件人——检查顺序决定了使用者先看到哪一句，
         * 而「没填收件人」是两者里更常见也更好改的那一个。
         */
        @Test
        @DisplayName("两样都没配时先报收件人")
        void receiverIsCheckedFirst() {
            NovaMailService service = service(null);

            List<String> warnings = logsOf(Level.WARN, () -> service.sendMail(SUBJECT, CONTENT));

            assertEquals(1, warnings.size(), "两样都缺也只说一句: " + warnings);
            assertTrue(warnings.get(0).contains("未配置默认邮件接收地址"), warnings.get(0));
        }

        @Test
        @DisplayName("富文本那一路的把关一模一样")
        void richMailIsGuardedTheSameWay() {
            CapturingMailSender sender = new CapturingMailSender();

            service(null, sender).sendMimeMail(SUBJECT, CONTENT);
            assertEquals(0, sender.rich.size());

            assertDoesNotThrow(() -> service(DEFAULT_TO).sendMimeMail(SUBJECT, CONTENT));
        }
    }
}
