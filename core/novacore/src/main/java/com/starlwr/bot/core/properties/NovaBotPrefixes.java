package com.starlwr.bot.core.properties;

import java.util.List;
import java.util.Map;

/**
 * 配置键产品前缀：现行 {@code novabot.*} 与上一档 {@code starbot.*}
 * <p>
 * 读侧两套都认、现行键在场时压过旧键；写侧只写现行前缀。
 * 上一档搬过位的告警 qq-* 与 NapCat 代登录键见 {@link #RELOCATED}；事件输出仍由绑定器认更早的那一档。
 */
public final class NovaBotPrefixes {
    private NovaBotPrefixes() {
    }

    public static final String CORE = "novabot.core";
    public static final String CORE_LEGACY = "starbot.core";

    public static final String EVENT_STREAM = "novabot.core.event-stream";
    public static final String EVENT_STREAM_LEGACY = "starbot.core.event-stream";

    public static final String BILIBILI = "novabot.bilibili";
    public static final String BILIBILI_LEGACY = "starbot.bilibili";

    public static final String ADAPTER = "novabot.adapter.onebot";
    public static final String ADAPTER_LEGACY = "starbot.adapter.onebot";

    public static final String ADAPTER_ALERT = "novabot.adapter.onebot.alert";
    public static final String ADAPTER_ALERT_LEGACY = "starbot.adapter.onebot.alert";

    public static final String ADAPTER_NAPCAT = "novabot.adapter.onebot.napcat";
    public static final String ADAPTER_NAPCAT_LEGACY = "starbot.adapter.onebot.napcat";

    public static final String NAPCAT_EXT = "novabot.adapter.onebot.extension.napcat";
    public static final String NAPCAT_EXT_LEGACY = "starbot.adapter.onebot.extension.napcat";

    /**
     * 上一档搬过位的键（不是同位换名）：旧完整路径 → 现行完整路径
     */
    public static final Map<String, String> RELOCATED = Map.of(
            "starbot.core.alert.qq-platform", ADAPTER_ALERT + ".platform",
            "starbot.core.alert.qq-type", ADAPTER_ALERT + ".type",
            "starbot.core.alert.qq-num", ADAPTER_ALERT + ".num",
            "starbot.core.config-ui.napcat.token", ADAPTER_NAPCAT + ".token",
            "starbot.core.config-ui.napcat.token-hash", ADAPTER_NAPCAT + ".token-hash",
            "starbot.core.config-ui.napcat.totp-secret", ADAPTER_NAPCAT + ".totp-secret",
            "starbot.core.config-ui.napcat.address", ADAPTER_NAPCAT + ".address");

    /**
     * 现行前缀 → 上一档前缀，最长者在前，用来给启动日志归到「每前缀一行」
     */
    public static final List<Map.Entry<String, String>> CURRENT_TO_LEGACY = List.of(
            Map.entry(NAPCAT_EXT, NAPCAT_EXT_LEGACY),
            Map.entry(ADAPTER_ALERT, ADAPTER_ALERT_LEGACY),
            Map.entry(ADAPTER_NAPCAT, ADAPTER_NAPCAT_LEGACY),
            Map.entry(EVENT_STREAM, EVENT_STREAM_LEGACY),
            Map.entry(ADAPTER, ADAPTER_LEGACY),
            Map.entry(BILIBILI, BILIBILI_LEGACY),
            Map.entry(CORE, CORE_LEGACY));

    /**
     * 把上一档键名换成现行键名；与本表无关的键原样返回
     * @param name 配置项完整路径
     * @return 现行键名
     */
    public static String toCurrent(String name) {
        if (name == null) {
            return null;
        }
        String relocated = RELOCATED.get(name);
        if (relocated != null) {
            return relocated;
        }
        if (name.equals("starbot.core") || name.startsWith("starbot.core.")
                || name.equals("starbot.bilibili") || name.startsWith("starbot.bilibili.")
                || name.equals("starbot.adapter.onebot") || name.startsWith("starbot.adapter.onebot.")) {
            return "novabot" + name.substring("starbot".length());
        }
        return name;
    }
}
