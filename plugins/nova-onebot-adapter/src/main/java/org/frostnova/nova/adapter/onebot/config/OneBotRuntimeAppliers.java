package org.frostnova.nova.adapter.onebot.config;

import org.frostnova.nova.adapter.onebot.service.OneBotConnectionManager;
import org.frostnova.nova.core.config.ui.RuntimeConfigurationApplierContributor;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.frostnova.nova.core.properties.NovaBotPrefixes;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.convert.ApplicationConversionService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * OneBot 适配器申报的即时生效应用器：告警目标三项写回运行中的适配器属性
 * <p>
 * 推送接口安全三项与慢调用阈值走 {@link #batchApplier()}：读取方都是用到时现读——
 * {@code PushApiSecurityFilter} 每个请求开头取一份 {@link OneBotAdapterPluginProperties.Security#requestPolicy()}，
 * {@code OneBotHealthProbe} 每次判慢现读阈值。一批先全部读懂、再在安全设置那把锁下一起写回，
 * 一个请求拿到的不会是半新半旧的一组，保存返回之后进来的请求全按新值办。
 * <p>
 * 长连接存活检测的两项（是否检测、静默超时）不在这里：它们在连上那一刻读一次，
 * 已连着的连接不会看见新值，仍要重启。
 */
@NovaComponent
public class OneBotRuntimeAppliers implements RuntimeConfigurationApplierContributor {
    private final OneBotAdapterPluginProperties properties;

    private final ObjectProvider<OneBotConnectionManager> connections;

    public OneBotRuntimeAppliers() {
        this(new OneBotAdapterPluginProperties());
    }

    public OneBotRuntimeAppliers(OneBotAdapterPluginProperties properties) {
        this(properties, null);
    }

    @Autowired
    public OneBotRuntimeAppliers(OneBotAdapterPluginProperties properties, ObjectProvider<OneBotConnectionManager> connections) {
        this.connections = connections;
        this.properties = properties;
    }

    @Override
    public Map<String, Consumer<String>> appliers() {
        Map<String, Consumer<String>> appliers = new LinkedHashMap<>();
        appliers.put("novabot.adapter.onebot.alert.platform",
                value -> properties.getAlert().setPlatform(value));
        appliers.put("novabot.adapter.onebot.alert.type",
                value -> properties.getAlert().setType(Integer.parseInt(value.trim())));
        appliers.put("novabot.adapter.onebot.alert.num",
                value -> properties.getAlert().setNum(
                        value == null || value.isBlank() ? null : Long.parseLong(value.trim())));
        appliers.put(OneBotAdapterPluginProperties.NapCat.ENABLED_KEY, value -> {
            boolean enabled = value.isBlank() || Boolean.parseBoolean(value);
            if (connections == null) properties.getNapcat().setEnabled(enabled);
            else connections.getObject().setChannelEnabled(enabled);
        });
        return appliers;
    }

    public static final String TRUST_PROXY_KEY = "novabot.adapter.onebot.security.trust-proxy";
    public static final String AUDIT_LOG_KEY = "novabot.adapter.onebot.security.audit-log";
    public static final String RATE_LIMIT_KEY = "novabot.adapter.onebot.security.rate-limit.enabled";
    public static final String SLOW_THRESHOLD_KEY = "novabot.adapter.onebot.detect.slow-threshold-millis";

    /**
     * 推送接口安全三项与慢调用阈值，键到它的名字
     */
    private static final Map<String, String> BATCH_LABELS = batchLabels();

    private static Map<String, String> batchLabels() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(TRUST_PROXY_KEY, "推送接口 · 信任反向代理");
        labels.put(AUDIT_LOG_KEY, "推送接口 · 鉴权失败审计日志");
        labels.put(RATE_LIMIT_KEY, "推送接口 · 频率限制");
        labels.put(SLOW_THRESHOLD_KEY, "OneBot 探测 · 慢调用阈值");
        return labels;
    }

    /**
     * @return 走成批写回的配置项全名
     */
    public static List<String> batchKeys() {
        return List.copyOf(BATCH_LABELS.keySet());
    }

    @Override
    public BatchApplier batchApplier() {
        return new BatchApplier(Set.copyOf(BATCH_LABELS.keySet()), this::applyBatch);
    }

    @Override
    public Map<String, Function<String, String>> valueValidators() {
        Map<String, Function<String, String>> validators = new LinkedHashMap<>();
        validators.put(OneBotAdapterPluginProperties.NapCat.ENABLED_KEY, value ->
                "true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value) ? null : "请选择启用或停用");
        BATCH_LABELS.forEach((key, label) -> validators.put(key, value -> {
            try {
                parse(key, value);
                return null;
            } catch (IllegalArgumentException invalid) {
                return key + "（" + label + "）：" + invalid.getMessage();
            }
        }));
        return validators;
    }

    /**
     * 先把这一批全部读懂，有一项读不懂就抛出、一项也不写；再在安全设置那把锁下一起写回。
     * 删键（null）回到默认值。
     */
    private void applyBatch(Map<String, String> changes) {
        OneBotAdapterPluginProperties defaults = new OneBotAdapterPluginProperties();
        OneBotAdapterPluginProperties.Security security = properties.getSecurity();
        boolean trustProxy = (Boolean) parsedOr(changes, TRUST_PROXY_KEY, security.isTrustProxy(),
                defaults.getSecurity().isTrustProxy());
        boolean auditLog = (Boolean) parsedOr(changes, AUDIT_LOG_KEY, security.isAuditLog(),
                defaults.getSecurity().isAuditLog());
        boolean rateLimited = (Boolean) parsedOr(changes, RATE_LIMIT_KEY, security.getRateLimit().isEnabled(),
                defaults.getSecurity().getRateLimit().isEnabled());
        int slowThreshold = (Integer) parsedOr(changes, SLOW_THRESHOLD_KEY,
                properties.getDetect().getSlowThresholdMillis(), defaults.getDetect().getSlowThresholdMillis());
        // 读方 Security#requestPolicy 在同一把锁下一次取三项
        synchronized (security) {
            security.setTrustProxy(trustProxy);
            security.setAuditLog(auditLog);
            security.getRateLimit().setEnabled(rateLimited);
        }
        properties.getDetect().setSlowThresholdMillis(slowThreshold);
    }

    private static Object parsedOr(Map<String, String> changes, String key, Object current, Object fallback) {
        if (!changes.containsKey(key)) {
            return current;
        }
        String value = changes.get(key);
        return value == null ? fallback : parse(key, value);
    }

    private static Object parse(String key, String value) {
        Class<?> type = SLOW_THRESHOLD_KEY.equals(key) ? Integer.class : Boolean.class;
        Object result;
        try {
            result = ApplicationConversionService.getSharedInstance().convert(value, type);
        } catch (RuntimeException e) {
            result = null;
        }
        if (result == null) {
            throw new IllegalArgumentException(type == Boolean.class ? "请选择开启或关闭" : "请填写整数");
        }
        return result;
    }

    @Override
    public Map<String, String> appliedElsewhere() {
        Map<String, String> elsewhere = new LinkedHashMap<>();
        elsewhere.put(NovaBotPrefixes.ADAPTER + ".senders",
                "/api/setup/bot 保存时经 BotConnectionTester#apply 当场重建连接");
        return elsewhere;
    }
}
