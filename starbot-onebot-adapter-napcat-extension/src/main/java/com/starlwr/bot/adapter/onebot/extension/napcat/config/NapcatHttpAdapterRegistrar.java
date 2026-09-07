package com.starlwr.bot.adapter.onebot.extension.napcat.config;

import com.starlwr.bot.adapter.onebot.extension.napcat.http.NapcatHttpAdapter;
import com.starlwr.bot.adapter.onebot.extension.napcat.http.NapcatHttpAdapterProxy;
import com.starlwr.bot.core.plugin.StarBotComponent;
import com.starlwr.bot.core.util.HttpUtil;
import org.springframework.context.annotation.Bean;

import java.lang.reflect.Proxy;

/**
 * 把只有声明的 NapCat 扩展接口装成一个真能打出请求去的实例
 * <p>
 * 拆成两个 bean 而不是一个：处理器管「一次请求怎么打」、代理只管「按哪个接口打」，前者因此能脱开动态代理单独跑判据。
 */
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
                proxy);
    }
}
