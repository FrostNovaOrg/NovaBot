package org.frostnova.nova.adapter.qqbot;

import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.health.HealthProbe;
import org.frostnova.nova.core.health.HealthStatus;
import org.frostnova.nova.core.plugin.NovaComponent;

import java.util.LinkedHashSet;
import java.util.Optional;

@NovaComponent
public class QqBotHealthProbe implements HealthProbe {
    private final QqBotTokenService tokens;
    private final QqBotGateway gateway;
    private final QqBotDirectory directory;
    private final Optional<AbstractDataSource> dataSource;

    QqBotHealthProbe(QqBotTokenService tokens, QqBotGateway gateway, QqBotDirectory directory,
                     Optional<AbstractDataSource> dataSource) {
        this.tokens = tokens;
        this.gateway = gateway;
        this.directory = directory;
        this.dataSource = dataSource;
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
    public String card() {
        return QqBotConsolePageProvider.ID;
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
        HealthStatus connection = gateway.health();
        if (connection.level() == HealthStatus.Level.DOWN || dataSource.isEmpty()) return connection;
        var groups = new LinkedHashSet<Long>();
        for (var user : dataSource.get().getAllUsers()) {
            if (Boolean.FALSE.equals(user.getEnabled())) continue;
            for (var target : user.getTargets()) {
                if (QqBotPluginAutoConfiguration.PLATFORM.equals(target.getPlatform())
                        && target.getType() == PushTargetType.GROUP && target.getNum() != null
                        && !Boolean.FALSE.equals(target.getEnabled())) {
                    groups.add(target.getNum());
                }
            }
        }
        if (groups.isEmpty()) return connection;
        var disabled = directory.disabledPushGroups(groups);
        if (disabled.isEmpty()) return connection;
        String summary = "这些群的群主还没打开主动发言，推送发不进去：" + String.join("、", disabled);
        String advice = QqBotSender.SWITCH_PATH;
        if (connection.level() == HealthStatus.Level.DEGRADED) {
            summary = connection.summary() + "；" + summary;
            advice = connection.advice() + "；" + advice;
        }
        return new HealthStatus(HealthStatus.Level.DEGRADED, summary, advice, connection.reason());
    }

    QqBotGateway.ConnectionState connectionState() {
        return gateway.connectionState();
    }
}
