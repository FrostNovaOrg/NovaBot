package org.frostnova.nova.adapter.qqbot;

import org.frostnova.nova.core.plugin.NovaComponent;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

/** 供配置入口保存和查看 QQ 官方机器人凭据。 */
@NovaComponent
public class QqBotCredentials {
    private final QqBotTokenService tokens;
    private final QqBotGateway gateway;
    private final QqBotSender sender;

    QqBotCredentials(QqBotTokenService tokens, QqBotGateway gateway, QqBotSender sender) {
        this.tokens = tokens;
        this.gateway = gateway;
        this.sender = sender;
    }

    public synchronized void save(String appId, String secret) {
        tokens.save(appId, secret);
        gateway.credentialsChanged();
        sender.updateRegistration();
        verifyAndStart();
    }

    public synchronized void clear() {
        tokens.clear();
        gateway.credentialsChanged();
        sender.updateRegistration();
    }

    public Status status() {
        var settings = tokens.settings();
        return new Status(settings.configured(), settings.appId(), settings.maskedSecret());
    }

    public long intervalMillis() {
        return tokens.settings().intervalMillis();
    }

    public synchronized void setIntervalMillis(long intervalMillis) {
        tokens.setIntervalMillis(intervalMillis);
        sender.updateRegistration();
    }

    @EventListener(ApplicationReadyEvent.class)
    synchronized void onApplicationReady() {
        sender.updateRegistration();
        verifyAndStart();
    }

    private void verifyAndStart() {
        tokens.verifyConfigured();
        gateway.start();
    }

    public record Status(boolean configured, String appId, String maskedSecret) {
    }
}
