package com.starlwr.bot.core.alert;

import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.service.StarBotMailService;
import com.starlwr.bot.core.util.StringUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 邮件告警通道
 */
@Component
public class MailAlertChannel implements AlertChannel {
    private final StarBotMailService mailService;

    private final StarBotCoreProperties properties;

    @Autowired
    public MailAlertChannel(StarBotMailService mailService, StarBotCoreProperties properties) {
        this.mailService = mailService;
        this.properties = properties;
    }

    @Override
    public String name() {
        return "邮件";
    }

    @Override
    public boolean isAvailable() {
        return StringUtil.isNotBlank(properties.getMail().getDefaultTo());
    }

    @Override
    public void send(String subject, String content) {
        mailService.sendMail(subject, content);
    }
}
