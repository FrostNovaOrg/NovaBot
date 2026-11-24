package org.frostnova.nova.adapter.qqbot;

import org.frostnova.nova.core.health.HealthProbe;
import org.frostnova.nova.core.health.HealthStatus;
import org.frostnova.nova.core.plugin.NovaComponent;

@NovaComponent
public class QqBotHealthProbe implements HealthProbe {
    private final QqBotTokenService tokens;
    private final QqBotGateway gateway;

    QqBotHealthProbe(QqBotTokenService tokens, QqBotGateway gateway) {
        this.tokens = tokens;
        this.gateway = gateway;
    }

    @Override
    public String name() {
        return QqBotPluginAutoConfiguration.DISPLAY_NAME;
    }

    @Override
    public Scope scope() {
        return Scope.BOT;
    }

    @Override
    public int order() {
        return 21;
    }

    @Override
    public boolean active() {
        var settings = tokens.settings();
        return settings.configured() || settings.unreadable();
    }

    @Override
    public HealthStatus check() {
        return gateway.health();
    }
}
