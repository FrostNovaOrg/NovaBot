package org.frostnova.nova.core.alert;

import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.AlertReadiness;
import org.frostnova.nova.core.service.NovaMailService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 邮件告警通道
 */
@Component
public class MailAlertChannel implements AlertChannel {
    private final NovaMailService mailService;

    private final NovaCoreProperties properties;

    private final String smtpHost;

    @Autowired
    public MailAlertChannel(NovaMailService mailService, NovaCoreProperties properties,
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
