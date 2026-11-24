package org.frostnova.nova.adapter.qqbot;

import org.frostnova.nova.core.plugin.NovaComponent;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

/** 供配置入口保存和查看 QQ 官方机器人凭据。 */
@NovaComponent
public class QqBotCredentials {
    private final QqBotTokenService tokens;

    QqBotCredentials(QqBotTokenService tokens) {
        this.tokens = tokens;
    }

    public void save(String appId, String secret) {
        tokens.save(appId, secret);
    }

    public Status status() {
        var settings = tokens.settings();
        return new Status(settings.configured(), settings.appId(), settings.maskedSecret());
    }

    public long intervalMillis() {
        return tokens.settings().intervalMillis();
    }

    @EventListener(ApplicationReadyEvent.class)
    void onApplicationReady() {
        tokens.verifyConfigured();
    }

    public record Status(boolean configured, String appId, String maskedSecret) {
    }
}
