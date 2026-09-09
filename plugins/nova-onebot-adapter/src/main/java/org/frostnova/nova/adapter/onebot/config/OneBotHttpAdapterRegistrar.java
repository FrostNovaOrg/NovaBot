package org.frostnova.nova.adapter.onebot.config;

import org.frostnova.nova.adapter.onebot.health.OneBotConnectionState;
import org.frostnova.nova.adapter.onebot.http.OneBotHttpAdapter;
import org.frostnova.nova.adapter.onebot.http.OneBotHttpAdapterProxy;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.frostnova.nova.core.util.HttpUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;

import java.lang.reflect.Proxy;

/**
 * OneBot HTTP 服务注册器
 */
@Slf4j
@NovaComponent
public class OneBotHttpAdapterRegistrar {
    @Bean
    public OneBotHttpAdapterProxy oneBotHttpAdapterProxy(HttpUtil http, OneBotConnectionState state) {
        return new OneBotHttpAdapterProxy(http, state);
    }

    @Bean
    public OneBotHttpAdapter oneBotHttpAdapter(OneBotHttpAdapterProxy proxy) {
        return (OneBotHttpAdapter) Proxy.newProxyInstance(
                OneBotHttpAdapter.class.getClassLoader(),
                new Class[]{OneBotHttpAdapter.class},
                proxy
        );
    }
}
