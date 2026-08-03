package com.starlwr.bot.adapter.onebot.extension.napcat.config;

import com.starlwr.bot.adapter.onebot.extension.napcat.http.NapcatHttpAdapter;
import com.starlwr.bot.adapter.onebot.extension.napcat.http.NapcatHttpAdapterProxy;
import com.starlwr.bot.core.plugin.StarBotComponent;
import com.starlwr.bot.core.util.HttpUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;

import java.lang.reflect.Proxy;

/**
 * Napcat HTTP 扩展服务注册器
 */
@Slf4j
@StarBotComponent
public class NapcatHttpAdapterRegistrar {
    @Bean
    public NapcatHttpAdapterProxy napcatHttpAdapterProxy(HttpUtil http) {
        return new NapcatHttpAdapterProxy(http);
    }

    @Bean
    public NapcatHttpAdapter napcatHttpAdapter(NapcatHttpAdapterProxy proxy) {
        return (NapcatHttpAdapter) Proxy.newProxyInstance(
                NapcatHttpAdapter.class.getClassLoader(),
                new Class[]{NapcatHttpAdapter.class},
                proxy
        );
    }
}
