package org.frostnova.nova.core.service;

import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.SettingsAppliedListener;
import org.frostnova.nova.core.lang.StringUtil;
import jakarta.mail.Address;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.MimeMessage;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.mail.autoconfigure.MailProperties;
import org.springframework.core.env.Environment;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * NovaBot 邮件服务
 * <p>
 * 发信分两条路，失败时各自的交代不同：
 * <ul>
 *     <li>{@link #sendMail} 与 {@link #sendMimeMail} 什么都不往外抛——邮件是旁路，
 *     把调用它的那条业务路掀翻了，报的就不再是原来那件事；</li>
 *     <li>{@link #sendAlertMail} 发不出去就抛——告警的调用方要如实知道发出去了没有，
 *     吞掉失败会把「连不上邮件服务器」报成「告警已送出」。</li>
 * </ul>
 * <p>
 * 发信服务用 {@link ObjectProvider} 取而不是直接注入：邮件是可选功能，
 * 没配 {@code spring.mail.*} 时容器里压根没有这个 bean，直接注入会让整个程序起不来。
 */
@Slf4j
@Service
public class NovaMailService implements SettingsAppliedListener {
    /**
     * 回话里带的服务器原话最多留这么多字，再长就截掉后面
     * <p>
     * 这句会显示在设置页的提示和日志页那一条里，几百字的一整段会把前面那句「拒收了」挤得找不见；
     * 全文跟着起因链进工程日志，截掉的不会丢。
     */
    private static final int SERVER_WORDS_LIMIT = 200;

    /** 服务器回话的一行：三位回码、「-」（后面还有行）或空格，可带「5.7.1」这类扩展状态码 */
    private static final Pattern REPLY_LINE =
            Pattern.compile("(\\d{3})[ -](?:(\\d\\.\\d{1,3}\\.\\d{1,3})(?:\\s+|$))?(.*)");

    /**
     * 设置页上能改的那四项发件设置
     */
    static final String HOST = "spring.mail.host";
    static final String PORT = "spring.mail.port";
    static final String USERNAME = "spring.mail.username";
    static final String PASSWORD = "spring.mail.password";

    @Value("${spring.mail.username:}")
    private String from;

    private final ObjectProvider<JavaMailSender> mailSenderProvider;

    private final NovaCoreProperties properties;

    /**
     * 启动时的环境：启动时没配服务器（容器里没有发信器）、后来在设置页填上的，
     * 端口、加密这些照启动时那一路从这里读
     */
    private final Environment environment;

    /**
     * 设置页上改过发件设置之后按新值建的发信器，没改过时为 null，照旧用容器里启动时建好的那一个
     * <p>
     * 整个换掉而不是在原来那个上改字段：发信线程拿到的要么是旧的一整套、要么是新的一整套，
     * 不会是新服务器配旧口令。
     */
    private volatile Rebuilt rebuilt;

    /**
     * 按新值建好的一套
     * @param sender 发信器，服务器留空时为 null（即不用邮件告警）
     * @param from 发件人
     * @param host 服务器
     * @param settings 建它用的全部设置，下一次只改一项时以此为底
     */
    private record Rebuilt(JavaMailSenderImpl sender, String from, String host, Smtp settings) {
    }

    /**
     * 建一个发信器要的全部设置
     * <p>
     * 覆写了 {@code toString}：记录类自带的那个会把口令原样印出来，这里只印服务器与端口。
     */
    private record Smtp(String host, Integer port, String username, String password, String protocol,
                        String encoding, Properties javaMail) {
        @Override
        public String toString() {
            return "Smtp[host=" + host + ", port=" + port + "]";
        }
    }

    public NovaMailService(ObjectProvider<JavaMailSender> mailSenderProvider, NovaCoreProperties properties) {
        this(mailSenderProvider, properties, null);
    }

    @Autowired
    public NovaMailService(ObjectProvider<JavaMailSender> mailSenderProvider, NovaCoreProperties properties,
                           Environment environment) {
        this.mailSenderProvider = mailSenderProvider;
        this.properties = properties;
        this.environment = environment;
    }

    @Override
    public Set<String> keys() {
        return Set.of(HOST, PORT, USERNAME, PASSWORD);
    }

    /**
     * 设置页上改了发件设置：按新值建一个发信器换上，下一封信就用它
     * <p>
     * 没改的那几项沿用此刻在用的；启动时没配服务器、容器里没有发信器的，按启动时的环境补齐端口与加密。
     * 服务器留空等于不用邮件告警，发信时照「没配发信服务」说。
     * <b>口令不进日志</b>：这里只记服务器与端口。
     * @param changes 本次改了的发件设置，null 表示删掉了那一项
     */
    @Override
    public synchronized void applied(Map<String, String> changes) {
        Rebuilt last = rebuilt;
        Smtp base = last != null ? last.settings() : startupSettings();
        Smtp next = new Smtp(
                changes.containsKey(HOST) ? blankToNull(changes.get(HOST)) : base.host(),
                changes.containsKey(PORT) ? portOf(changes.get(PORT)) : base.port(),
                changes.containsKey(USERNAME) ? blankToNull(changes.get(USERNAME)) : base.username(),
                changes.containsKey(PASSWORD) ? changes.get(PASSWORD) : base.password(),
                base.protocol(), base.encoding(), base.javaMail());
        JavaMailSenderImpl sender = next.host() == null ? null : build(next);
        rebuilt = new Rebuilt(sender, next.username() == null ? "" : next.username(), next.host(), next);
        if (sender == null) {
            log.info("邮件发件服务器已清空, 邮件告警停用");
        } else {
            log.info("邮件发件服务已按新设置重建: 服务器 {}, 端口 {}", next.host(),
                    next.port() == null ? "默认" : next.port());
        }
    }

    /**
     * 此刻在用的服务器：设置页上改过的话是改过之后的，没改过为 null（由调用方用启动时那一份）
     * @return 服务器，没改过为 null；改成留空时为空串
     */
    public String currentHost() {
        Rebuilt last = rebuilt;
        if (last == null) {
            return null;
        }
        return last.host() == null ? "" : last.host();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static Integer portOf(String value) {
        return value == null || value.isBlank() ? null : Integer.valueOf(value.strip());
    }

    /**
     * 此刻在用的那一套的全部设置：容器里启动时建好的发信器，或启动时的环境
     */
    private Smtp startupSettings() {
        if (mailSenderProvider.getIfUnique() instanceof JavaMailSenderImpl started) {
            Properties javaMail = new Properties();
            javaMail.putAll(started.getJavaMailProperties());
            return new Smtp(started.getHost(), started.getPort() < 0 ? null : started.getPort(),
                    started.getUsername(), started.getPassword(), started.getProtocol(),
                    started.getDefaultEncoding(), javaMail);
        }
        MailProperties bound = environment == null ? new MailProperties()
                : Binder.get(environment).bind("spring.mail", MailProperties.class).orElseGet(MailProperties::new);
        Properties javaMail = new Properties();
        javaMail.putAll(bound.getProperties());
        String protocol = bound.getProtocol() == null ? "smtp" : bound.getProtocol();
        if (bound.getSsl().isEnabled()) {
            // 与框架启动时建发信器的那一路同一个写法
            javaMail.setProperty("mail." + protocol + ".ssl.enable", "true");
        }
        return new Smtp(bound.getHost(), bound.getPort(), bound.getUsername(), bound.getPassword(), protocol,
                bound.getDefaultEncoding() == null ? null : bound.getDefaultEncoding().name(), javaMail);
    }

    private static JavaMailSenderImpl build(Smtp smtp) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(smtp.host());
        if (smtp.port() != null) {
            sender.setPort(smtp.port());
        }
        sender.setUsername(smtp.username());
        sender.setPassword(smtp.password());
        if (smtp.protocol() != null) {
            sender.setProtocol(smtp.protocol());
        }
        if (smtp.encoding() != null) {
            sender.setDefaultEncoding(smtp.encoding());
        }
        Properties javaMail = new Properties();
        javaMail.putAll(smtp.javaMail());
        sender.setJavaMailProperties(javaMail);
        return sender;
    }

    /**
     * 此刻该用的发信器：设置页上改过就用改过之后建的那一个
     * @return 发信器；没配，或容器里装了不止一个时为 null
     */
    private JavaMailSender currentSender() {
        Rebuilt last = rebuilt;
        return last != null ? last.sender() : mailSenderProvider.getIfUnique();
    }

    /**
     * 此刻的发件人
     */
    private String sender() {
        Rebuilt last = rebuilt;
        return last != null ? last.from() : from;
    }

    /**
     * 发送纯文本邮件到默认收件邮箱
     * @param subject 主题
     * @param content 内容
     */
    public void sendMail(String subject, String content) {
        sendMail(getDefaultReceiver(), subject, content);
    }

    /**
     * 发送纯文本邮件
     * @param receiver 收件邮箱
     * @param subject 主题
     * @param content 内容
     */
    public void sendMail(String receiver, String subject, String content) {
        JavaMailSender mailSender = usableSender(receiver, subject, content);
        if (mailSender == null) {
            return;
        }

        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(sender());
            message.setTo(receiver);
            message.setSubject(subject);
            message.setText(content);

            mailSender.send(message);
            log.info("纯文本邮件发送成功, 主题: {}", subject);
        } catch (Exception e) {
            log.error("纯文本邮件发送失败, 主题: {}, 内容: {}", subject, content, e);
        }
    }

    /**
     * 发送富文本邮件到默认收件邮箱
     * @param subject 主题
     * @param content 内容
     */
    public void sendMimeMail(String subject, String content) {
        sendMimeMail(getDefaultReceiver(), subject, content);
    }

    /**
     * 发送富文本邮件
     * @param receiver 收件邮箱
     * @param subject 主题
     * @param content 内容
     */
    public void sendMimeMail(String receiver, String subject, String content) {
        JavaMailSender mailSender = usableSender(receiver, subject, content);
        if (mailSender == null) {
            return;
        }

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true);
            helper.setFrom(sender());
            helper.setTo(receiver);
            helper.setSubject(subject);
            // 正文按 HTML 发：告警正文本来就带标记，当成纯文本发出去，收信的人看到的是一串尖括号
            helper.setText(content, true);

            mailSender.send(message);
            log.info("富文本邮件发送成功, 主题: {}", subject);
        } catch (Exception e) {
            log.error("富文本邮件发送失败, 主题: {}, 内容: {}", subject, content, e);
        }
    }

    /**
     * 发送告警邮件，发不出去时把原因抛给调用方
     * <p>
     * 「发一条测试」与真告警走这一条路：它们要如实知道发出去了没有——发不出去也吞掉的话，
     * 邮件服务器连不上时界面仍会说「已经发了一条测试告警」，日志页会记「已报出」，
     * 没有人知道那条告警其实丢了。普通发信仍走 {@link #sendMail(String, String)}，
     * 那里失败只留一笔日志。
     *
     * @param subject 主题
     * @param content 内容
     */
    public void sendAlertMail(String subject, String content) {
        String receiver = getDefaultReceiver();
        if (StringUtil.isBlank(receiver)) {
            throw new IllegalStateException("没有收件邮箱，这封信不知道该发给谁");
        }

        JavaMailSender mailSender = currentSender();
        if (mailSender == null) {
            // 没配发信服务与装了多个发信服务在这里都取不出唯一那一个，两句分开说，
            // 使用者照着回话就知道往哪一栏去查
            throw new IllegalStateException(rebuilt != null || mailSenderProvider.stream().findAny().isEmpty()
                    ? "发信服务没有配置好"
                    : "装了多个发信服务，认不出该用哪一个");
        }

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(sender());
        message.setTo(receiver);
        message.setSubject(subject);
        message.setText(content);

        try {
            mailSender.send(message);
        } catch (Exception e) {
            // 抛出去的只说原因，「发不出去」那句由告警服务拼——它会带上通道名，
            // 这里再带一遍就成了「发不出去：邮件发不出去：…」
            throw new IllegalStateException(reasonOf(e), e);
        }
        log.info("纯文本邮件发送成功, 主题: {}", subject);
    }

    /**
     * 往外交的那一句失败原因
     * <p>
     * 认得出的写成一句人话，而且各说各的——认证失败、收件地址被拒、服务器顶回、连不上
     * 要去的栏不一样，说成同一句会把人引去查不该查的地方；认不出的带上最深一层异常自己说的话。
     * 原文不丢——它作为起因链跟着异常走，工程日志里查得到全文。
     */
    private static String reasonOf(Exception failure) {
        String recognized = recognizeAlongCauseChain(failure,
                Collections.newSetFromMap(new IdentityHashMap<>()));
        if (recognized != null) {
            return recognized;
        }
        // 起因链可能成环（A 的起因是 B、B 的起因又是 A）：走过的每一层按对象身份记下
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable deepest = failure;
        for (Throwable cause = failure; cause != null && seen.add(cause); cause = cause.getCause()) {
            deepest = cause;
        }
        String message = messageOf(deepest);
        return message.isBlank()
                ? "发送失败（" + deepest.getClass().getSimpleName() + "）"
                : message;
    }

    /**
     * 沿起因链认一句人话，一层都认不出时返回 null
     * <p>
     * 逐封发信时，哪几封没发出去记在 MailSendException 的逐封异常表里——连不上那种
     * 走带起因的构造，起因链上就认得出；被服务器顶回那种只带表不带起因，只认外层的话，
     * 界面拿到的是发信库自己拼的英文「Failed messages: …」。所以碰到带逐封异常表的
     * MailSendException，按表里的异常逐封再认（认法与外层各支相同），
     * 表里有多封时取第一封认得出的。
     * <p>
     * {@code seen} 是整条认路的防环集，跟着递归一路传下去，而不是每层各建一个新的：
     * 表内异常的起因可能指回外层（外层先构造、表后指回就是一个环），每层新建的话
     * 跨过表的那段环没人防，一层都认不出时会一路递归到栈溢出。
     */
    private static String recognizeAlongCauseChain(Throwable failure, Set<Throwable> seen) {
        for (Throwable cause = failure; cause != null && seen.add(cause); cause = cause.getCause()) {
            // 认证失败：发信库抛 AuthenticationFailedException，发信器会把它再包一层
            // MailAuthenticationException——两层都认，包在哪一层都拦得住
            if (cause instanceof AuthenticationFailedException
                    || cause instanceof MailAuthenticationException) {
                return "邮箱账号或授权码不对，服务器拒绝了登录";
            }
            // 发信被顶回：只有顶在收件地址上的才说收件地址；收件那一步暂时拒收（灰名单、
            // 对方收件箱暂时满了、对方邮箱满了）说暂时拒收；其余（判垃圾信、配额满、发件被拒这类）
            // 说服务器拒收了这封信。后两种都带上服务器原话，让人照原话去查——
            // 一律说成收件地址，会把人引去改一个没有错的收件地址，改了也没用
            if (cause instanceof SendFailedException sendFailed) {
                if (recipientRejected(sendFailed)) {
                    return "收件邮箱地址被服务器拒收";
                }
                if (recipientDeferred(sendFailed)) {
                    return serverRefusalInPlainWords("服务器暂时拒收了这封信", sendFailed);
                }
                return serverRefusalInPlainWords("服务器拒收了这封信", sendFailed);
            }
            if (cause instanceof MessagingException
                    || cause instanceof ConnectException
                    || cause instanceof UnknownHostException) {
                return "连不上邮件服务器";
            }
            if (cause instanceof MailSendException) {
                for (Exception perMessage : ((MailSendException) cause).getMessageExceptions()) {
                    String perRecognized = recognizeAlongCauseChain(perMessage, seen);
                    if (perRecognized != null) {
                        return perRecognized;
                    }
                }
            }
        }
        return null;
    }

    /**
     * 这一次顶回是不是顶在收件地址上
     * <p>
     * 服务器在收件那一步顶回时，逐封表里那封的异常是 SendFailedException「Invalid Addresses」，
     * 服务器那句话挂在它的 next 上（SMTPAddressFailedException，带着回码）。发信库按回码分：
     * 4xx 与 552 记在「有效但没发出」名单里，无效地址名单是空的——那是暂时拒收，
     * 地址本身没有错，不算在这里（收件那一步回 552 多半是对方邮箱满或一封信收件人太多；
     * 收件人太多那种，SMTP 规范说应按暂时失败处理；邮箱满也归暂时，与发信库的分法一致）；
     * 其余 5xx 记在无效地址名单里。
     * 所以沿 next 链逐层认：哪一层是收件那一步回的、不算暂时的 SMTPAddressFailedException，
     * 或哪一层带着非空的无效地址名单，都算顶在收件地址上。
     */
    private static boolean recipientRejected(SendFailedException failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable link = failure; link != null && seen.add(link); link = nextExceptionOf(link)) {
            if (link instanceof SMTPAddressFailedException addressFailed
                    && !deferredAtRecipient(addressFailed.getReturnCode())) {
                return true;
            }
            if (link instanceof SendFailedException sendFailed) {
                Address[] invalid = sendFailed.getInvalidAddresses();
                if (invalid != null && invalid.length > 0) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 收件那一步是不是暂时拒收（回 4xx 的灰名单、对方收件箱暂时满了，或回 552 的对方邮箱满）
     */
    private static boolean recipientDeferred(SendFailedException failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable link = failure; link != null && seen.add(link); link = nextExceptionOf(link)) {
            if (link instanceof SMTPAddressFailedException addressFailed
                    && deferredAtRecipient(addressFailed.getReturnCode())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 收件那一步回的这个码算不算暂时拒收：与发信库记进「有效但没发出」名单的回码一致
     */
    private static boolean deferredAtRecipient(int returnCode) {
        return (returnCode >= 400 && returnCode <= 499) || returnCode == 552;
    }

    /**
     * 顶回不在收件地址上时，说服务器拒收了这封信，并带上服务器原话（回码与那一句）
     * <p>
     * 判垃圾信（收完信 DATA 末回 554）、配额满（发件那步回 550 5.4.5）、发件被拒、
     * 收件那步暂时拒收，原话都在异常自己的话里，next 链上更深那几层往往是同一句——
     * 取最深一句非空的话，那是离服务器顶回那一步最近的一句。哪一层都没话时也照实说拒收了：
     * 这是认得出的事，落到英文兜底或说成连不上都比它差。
     */
    private static String serverRefusalInPlainWords(String refused, SendFailedException refusal) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        String deepestSaid = "";
        for (Throwable link = refusal; link != null && seen.add(link); link = nextExceptionOf(link)) {
            String said = messageOf(link);
            if (!said.isBlank()) {
                deepestSaid = said;
            }
        }
        String words = serverWordsOnOneLine(deepestSaid);
        return words.isEmpty() ? refused : refused + "（服务器原话：" + words + "）";
    }

    /**
     * 把服务器原话理成一行
     * <p>
     * 发信库读回话时每行后面都补一个换行，服务器分几行回的（「550-…」「550-…」「550 …」）
     * 就用换行连成一串交出来。换行照搬进界面会折成空格，几行挤成一长串、回码重复出现。
     * 所以：回码和扩展状态码只留第一行的那一份，后面各行与它相同的去掉，各行的话用空格接起来；
     * 连着的空白并成一个；过长就截短并注明。
     */
    private static String serverWordsOnOneLine(String said) {
        StringBuilder merged = new StringBuilder();
        String firstStatus = null;
        boolean first = true;
        for (String line : said.strip().split("\\R")) {
            String words = line.strip();
            Matcher reply = REPLY_LINE.matcher(words);
            if (reply.matches()) {
                String status = reply.group(2);
                words = reply.group(3).strip();
                if (first) {
                    firstStatus = status;
                    words = reply.group(1) + (status == null ? "" : " " + status)
                            + (words.isEmpty() ? "" : " " + words);
                } else if (status != null && !status.equals(firstStatus)) {
                    words = status + (words.isEmpty() ? "" : " " + words);
                }
            }
            first = false;
            if (!words.isEmpty()) {
                if (!merged.isEmpty()) {
                    merged.append(' ');
                }
                merged.append(words);
            }
        }
        String oneLine = merged.toString().replaceAll("\\s+", " ");
        if (oneLine.codePointCount(0, oneLine.length()) <= SERVER_WORDS_LIMIT) {
            return oneLine;
        }
        return oneLine.substring(0, oneLine.offsetByCodePoints(0, SERVER_WORDS_LIMIT)).strip()
                + "……（原话过长，后面略去）";
    }

    /**
     * 同一封信各步的异常挂在 next 上，不在起因链上：jakarta.mail 把每一步被顶回的
     * 命令异常用 nextException 串成一串，认顶在哪一步就得沿它走
     */
    private static Exception nextExceptionOf(Throwable link) {
        return link instanceof MessagingException messaging ? messaging.getNextException() : null;
    }

    /**
     * 取一层异常自己说的话，取不到就当没有这句
     * <p>
     * 不能直接调 {@code getMessage}：逐封异常表里混着 null 时，发信库替外层拼
     * 「Failed messages」那一句会对 null 调 toString，这一取自己就先炸了——
     * 兜底与带原话的回话都从这里取话，不许把那记空指针抛给「发一条测试」的调用方。
     */
    private static String messageOf(Throwable failure) {
        try {
            String message = failure.getMessage();
            return message == null ? "" : message;
        } catch (RuntimeException e) {
            return "";
        }
    }

    /**
     * 取出这一封信能用的发信器，取不到就顺手把「为什么发不出去」写进日志
     * <p>
     * 两句提示都把主题和内容一起写出来：发不出去的那封信在别处没有第二份留底，
     * 日志里不带上，这条告警就彻底没了。收件人先于发信服务检查——
     * 两样都没配时，「没填收件人」是更常见也更好改的那一个。
     *
     * @return 发不出去时返回 null
     */
    private JavaMailSender usableSender(String receiver, String subject, String content) {
        if (StringUtil.isBlank(receiver)) {
            log.warn("未配置默认邮件接收地址, 无法发送邮件, 主题: {}, 内容: {}", subject, content);
            return null;
        }

        // 容器里装了两个发信器时这里也答 null：认不出该用哪一个，与没配同等对待
        JavaMailSender sender = currentSender();
        if (sender == null) {
            log.warn("未配置邮件发送服务, 无法向 {} 发送邮件, 主题: {}, 内容: {}", receiver, subject, content);
        }

        return sender;
    }

    /**
     * 获取默认收件邮箱
     * @return 默认收件邮箱
     */
    private String getDefaultReceiver() {
        return properties.getMail().getDefaultTo();
    }
}
