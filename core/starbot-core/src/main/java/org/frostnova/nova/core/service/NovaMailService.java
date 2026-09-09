package org.frostnova.nova.core.service;

import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.lang.StringUtil;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

/**
 * NovaBot 邮件服务
 * <p>
 * 告警走这里出去，所以这里<b>什么都不往外抛</b>：没配好、发不出去，都只留一笔日志。
 * 一条报警的路自己把调用它的那条路掀翻，报的就不再是原来那件事了。
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
