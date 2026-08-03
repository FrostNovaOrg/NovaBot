package com.starlwr.bot.core.service;

import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.util.StringUtil;
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
 * StarBot 邮件服务
 */
@Slf4j
@Service
public class StarBotMailService {
    @Value("${spring.mail.username:}")
    private String from;

    private final ObjectProvider<JavaMailSender> mailSenderProvider;

    private final StarBotCoreProperties properties;

    @Autowired
    public StarBotMailService(ObjectProvider<JavaMailSender> mailSenderProvider, StarBotCoreProperties properties) {
        this.mailSenderProvider = mailSenderProvider;
        this.properties = properties;
    }

    /**
     * 获取邮件发送器
     * @return 邮件发送器
     */
    private JavaMailSender getMailSender() {
        return mailSenderProvider.getIfUnique();
    }

    /**
     * 获取默认收件邮箱
     * @return 默认收件邮箱
     */
    private String getDefaultReceiver() {
        return properties.getMail().getDefaultTo();
    }

    /**
     * 判断邮件是否可发送
     * @param receiver 收件邮箱
     * @param subject 主题
     * @param content 内容
     * @return 邮件是否可发送
     */
    private boolean canSend(String receiver, String subject, String content) {
        if (StringUtil.isBlank(receiver)) {
            log.warn("未配置默认邮件接收地址, 无法发送邮件, 主题: {}, 内容: {}", subject, content);
            return false;
        }

        JavaMailSender sender = getMailSender();
        if (sender == null) {
            log.warn("未配置邮件发送服务, 无法向 {} 发送邮件, 主题: {}, 内容: {}", receiver, subject, content);
            return false;
        }

        return true;
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
        if (!canSend(receiver, subject, content)) {
            return;
        }

        try {
            JavaMailSender mailSender = getMailSender();

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
        if (!canSend(receiver, subject, content)) {
            return;
        }

        try {
            JavaMailSender mailSender = getMailSender();

            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true);
            helper.setFrom(from);
            helper.setTo(receiver);
            helper.setSubject(subject);
            helper.setText(content, true);

            mailSender.send(message);
            log.info("富文本邮件发送成功, 主题: {}", subject);
        } catch (Exception e) {
            log.error("富文本邮件发送失败, 主题: {}, 内容: {}", subject, content, e);
        }
    }
}
