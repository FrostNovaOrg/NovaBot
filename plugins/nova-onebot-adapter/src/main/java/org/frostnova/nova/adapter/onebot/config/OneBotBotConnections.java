package org.frostnova.nova.adapter.onebot.config;

import org.frostnova.nova.core.config.ui.BotConnectionContributor;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.frostnova.nova.core.properties.NovaBotPrefixes;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * OneBot 适配器申报的连接列表位置，以及列表里留空即未配的令牌键
 */
@NovaComponent
public class OneBotBotConnections implements BotConnectionContributor {
    @Override
    public String connectionListKey() {
        return NovaBotPrefixes.ADAPTER + ".senders";
    }

    @Override
    public Set<String> blankMeansAbsentKeys() {
        Set<String> keys = new LinkedHashSet<>();
        String list = connectionListKey();
        keys.add(list + ".one-bot-http-token");
        keys.add(list + ".one-bot-websocket-token");
        keys.add(list + ".api-token");
        return keys;
    }
}
