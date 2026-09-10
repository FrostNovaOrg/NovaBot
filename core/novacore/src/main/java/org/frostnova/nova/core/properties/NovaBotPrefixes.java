package org.frostnova.nova.core.properties;

/**
 * 配置键产品前缀 {@code novabot.*}
 */
public final class NovaBotPrefixes {
    private NovaBotPrefixes() {
    }

    public static final String CORE = "novabot.core";

    public static final String EVENT_STREAM = "novabot.core.event-stream";

    public static final String BILIBILI = "novabot.bilibili";

    public static final String ADAPTER = "novabot.adapter.onebot";

    public static final String ADAPTER_ALERT = "novabot.adapter.onebot.alert";

    public static final String ADAPTER_NAPCAT = "novabot.adapter.onebot.napcat";

    public static final String NAPCAT_EXT = "novabot.adapter.onebot.extension.napcat";
}
