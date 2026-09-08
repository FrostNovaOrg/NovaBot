package com.starlwr.bot.core.alert;

import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.config.ui.AlertReadiness;
import com.starlwr.bot.core.service.NovaMailService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 邮件告警通道
 */
@Component
public class MailAlertChannel implements AlertChannel {
    private final NovaMailService mailService;

    private final StarBotCoreProperties properties;

    private final String smtpHost;

    @Autowired
    public MailAlertChannel(NovaMailService mailService, StarBotCoreProperties properties,
                            @Value("${spring.mail.host:}") String smtpHost) {
        this.mailService = mailService;
        this.properties = properties;
        this.smtpHost = smtpHost;
    }

    @Override
    public String id() {
        return "mail";
    }

    @Override
    public String name() {
        return "邮件";
    }

    @Override
    public boolean isAvailable() {
        return AlertReadiness.mailConfigured(properties.getMail().getDefaultTo(), smtpHost);
    }

    @Override
    public void send(String subject, String content) {
        mailService.sendMail(subject, content);
    }
}
