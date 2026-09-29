package org.frostnova.nova.core.service;

import org.frostnova.nova.core.config.NovaCoreProperties;
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
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

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
public class NovaMailService {
    @Value("${spring.mail.username:}")
    private String from;

    private final ObjectProvider<JavaMailSender> mailSenderProvider;

    private final NovaCoreProperties properties;

    @Autowired
    public NovaMailService(ObjectProvider<JavaMailSender> mailSenderProvider, NovaCoreProperties properties) {
        this.mailSenderProvider = mailSenderProvider;
        this.properties = properties;
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
            message.setFrom(from);
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
            helper.setFrom(from);
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

        JavaMailSender mailSender = mailSenderProvider.getIfUnique();
        if (mailSender == null) {
            // 没配发信服务与装了多个发信服务在这里都取不出唯一那一个，两句分开说，
            // 使用者照着回话就知道往哪一栏去查
            throw new IllegalStateException(mailSenderProvider.stream().findAny().isEmpty()
                    ? "发信服务没有配置好"
                    : "装了多个发信服务，认不出该用哪一个");
        }

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
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
            // 发信被顶回：只有顶在收件地址上的才说收件地址；其余（判垃圾信、配额满、
            // 发件被拒这类）说服务器拒收了这封信并带上服务器原话，让人照原话去查——
            // 一律说成收件地址，会把人引去改一个没有错的收件地址，改了也没用
            if (cause instanceof SendFailedException sendFailed) {
                if (recipientRejected(sendFailed)) {
                    return "收件邮箱地址被服务器拒收";
                }
                return serverRefusalInPlainWords(sendFailed);
            }
            if (cause instanceof MessagingException
                    || cause instanceof ConnectException
                    || cause instanceof UnknownHostException) {
                return "连不上邮件服务器";
            }
            if (cause instanceof MailSendException) {
                for (Exception perMessage : ((MailSendException) cause).getMessageExceptions()) {
                    if (perMessage == null) {
                        // 表里混着 null 就跳过这一封：null 不是任何一封的失败原因
                        continue;
                    }
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
     * 服务器顶回单个收件人时，逐封表里那封的异常是 SendFailedException「Invalid Addresses」，
     * 服务器那句话挂在它的 next 上（SMTPAddressFailedException）——那张无效地址名单反而是空的，
     * 只认名单或只认表值本身都认不出它。所以沿 next 链逐层认：哪一层是收件那一步的
     * SMTPAddressFailedException，或哪一层带着非空的无效地址名单，都算顶在收件地址上。
     */
    private static boolean recipientRejected(SendFailedException failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable link = failure; link != null && seen.add(link); link = nextExceptionOf(link)) {
            if (link instanceof SMTPAddressFailedException) {
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
     * 收件地址以外的一切顶回，都说服务器拒收了这封信，并带上服务器原话（回码与那一句）
     * <p>
     * 判垃圾信（收完信 DATA 末回 554）、配额满（发件那步回 550 5.4.5）、发件被拒，
     * 原话都在异常自己的话里，next 链上更深那几层往往是同一句——取最深一句非空的话，
     * 那是离服务器顶回那一步最近的一句。哪一层都没话时也照实说服务器拒收了这封信：
     * 这是认得出的事，落到英文兜底或说成连不上都比它差。
     */
    private static String serverRefusalInPlainWords(SendFailedException refusal) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        String deepestSaid = "";
        for (Throwable link = refusal; link != null && seen.add(link); link = nextExceptionOf(link)) {
            String said = messageOf(link);
            if (!said.isBlank()) {
                deepestSaid = said;
            }
        }
        return deepestSaid.isEmpty()
                ? "服务器拒收了这封信"
                : "服务器拒收了这封信（服务器原话：" + deepestSaid + "）";
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
        JavaMailSender sender = mailSenderProvider.getIfUnique();
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
