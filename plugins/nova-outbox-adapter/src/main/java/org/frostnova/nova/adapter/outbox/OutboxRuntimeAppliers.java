package org.frostnova.nova.adapter.outbox;

import org.frostnova.nova.core.config.ui.RuntimeConfigurationApplierContributor;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.convert.ApplicationConversionService;

import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

@NovaComponent
public class OutboxRuntimeAppliers implements RuntimeConfigurationApplierContributor {
    private static final String ENABLED = OutboxSettings.PREFIX + ".pickup-alert-enabled";
    private static final String MINUTES = OutboxSettings.PREFIX + ".pickup-alert-minutes";

    private final OutboxSettings settings;

    public OutboxRuntimeAppliers() {
        this(new OutboxSettings());
    }

    @Autowired
    public OutboxRuntimeAppliers(OutboxSettings settings) {
        this.settings = settings;
    }

    @Override
    public Map<String, Consumer<String>> appliers() {
        return Map.of();
    }

    @Override
    public BatchApplier batchApplier() {
        return new BatchApplier(Set.of(ENABLED, MINUTES), changes -> {
            Boolean enabled = changes.containsKey(ENABLED) ? enabled(changes.get(ENABLED)) : null;
            Integer minutes = changes.containsKey(MINUTES) ? minutes(changes.get(MINUTES)) : null;
            synchronized (settings) {
                if (minutes != null) settings.setPickupAlertMinutes(minutes);
                if (enabled != null) settings.setPickupAlertEnabled(enabled);
            }
        });
    }

    @Override
    public Map<String, Function<String, String>> valueValidators() {
        return Map.of(ENABLED, value -> validate(value, OutboxRuntimeAppliers::enabled),
                MINUTES, value -> validate(value, OutboxRuntimeAppliers::minutes));
    }

    private static String validate(String value, Function<String, ?> parse) {
        try {
            parse.apply(value);
            return null;
        } catch (IllegalArgumentException invalid) {
            return invalid.getMessage();
        }
    }

    private static boolean enabled(String value) {
        return value != null && convert(value, Boolean.class, "请选择开启或关闭");
    }

    private static int minutes(String value) {
        return value == null ? OutboxSettings.DEFAULT_PICKUP_ALERT_MINUTES
                : OutboxSettings.requirePickupAlertMinutes(convert(value, Integer.class, "请填写 1 至 60 分钟的整数"));
    }

    private static <T> T convert(String value, Class<T> type, String message) {
        T result;
        try {
            result = ApplicationConversionService.getSharedInstance().convert(value.strip(), type);
        } catch (RuntimeException invalid) {
            result = null;
        }
        if (result == null) throw new IllegalArgumentException(message);
        return result;
    }
}
