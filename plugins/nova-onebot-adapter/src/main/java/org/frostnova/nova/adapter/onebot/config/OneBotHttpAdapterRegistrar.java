package org.frostnova.nova.adapter.onebot.config;

import org.frostnova.nova.adapter.onebot.health.OneBotConnectionState;
import org.frostnova.nova.adapter.onebot.http.OneBotHttpAdapter;
import org.frostnova.nova.adapter.onebot.http.OneBotHttpAdapterProxy;
import org.frostnova.nova.adapter.onebot.http.OneBotResponseErrorHandler;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.frostnova.nova.core.properties.LogProperties;
import org.frostnova.nova.core.util.HttpUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Proxy;

/**
 * OneBot HTTP 服务注册器
 */
@Slf4j
@NovaComponent
public class OneBotHttpAdapterRegistrar {
    @Bean
    public OneBotHttpAdapterProxy oneBotHttpAdapterProxy(@Qualifier("networkThreadPool") ThreadPoolTaskExecutor executor,
            RestTemplate shared, LogProperties logging, OneBotConnectionState state, OneBotAdapterPluginProperties properties) {
        RestTemplate client = new RestTemplate(shared.getRequestFactory());
        client.setMessageConverters(shared.getMessageConverters());
        client.setUriTemplateHandler(shared.getUriTemplateHandler());
        client.setErrorHandler(new OneBotResponseErrorHandler(shared.getErrorHandler()));
        HttpUtil http = new HttpUtil(executor, client, logging);
        return new OneBotHttpAdapterProxy(http, state, properties);
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
