package org.frostnova.nova.adapter.outbox;

import org.frostnova.nova.core.health.HealthProbe;
import org.frostnova.nova.core.health.HealthStatus;
import org.frostnova.nova.core.plugin.NovaComponent;

@NovaComponent
class OutboxPickupHealthProbe implements HealthProbe {
    private final OutboxService outbox;
    private final OutboxSettings settings;

    OutboxPickupHealthProbe(OutboxService outbox, OutboxSettings settings) {
        this.outbox = outbox;
        this.settings = settings;
    }

    @Override public String name() { return "外发箱取件"; }

    @Override public boolean active() { return settings.isPickupAlertEnabled(); }

    @Override public long activationVersion() { return settings.pickupAlertActivation(); }

    @Override public String excludedAlertPlatform() { return OutboxPluginAutoConfiguration.PLATFORM; }

    @Override public HealthStatus check() { return outbox.pickupHealth(); }

    @Override public HealthStatus checkForAlert() { return outbox.refreshPickupHealth(); }
}
