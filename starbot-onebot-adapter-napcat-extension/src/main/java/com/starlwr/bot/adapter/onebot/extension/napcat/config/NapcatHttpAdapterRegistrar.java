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
 * 拆成两个 bean 而不是一个：处理器管「一次请求怎么打」、代理只管「按哪个接口打」，
 * 前者因此能脱开动态代理单独跑判据。
 */
@StarBotComponent
public class NapcatHttpAdapterRegistrar {
    /**
     * 真正去打请求的那一半
     * <p>
     * 它单独成一个 bean，是为了能被直接拿在手里构造：动态代理只认接口，
     * 而这一半是普通类，脱开代理也立得住判据
     * @param http 核心提供的 HTTP 工具
     * @return 处理器，同时也是下面那个代理的 {@link java.lang.reflect.InvocationHandler}
     */
    @Bean
    public NapcatHttpAdapterProxy napcatHttpAdapterProxy(HttpUtil http) {
        return new NapcatHttpAdapterProxy(http);
    }

    /**
     * 注入到别处去用的那一半：一个没有实现类的 {@link NapcatHttpAdapter}
     * <p>
     * 接口上每一支方法都没有方法体，调用一律落到上面那个处理器身上，由它照注解上的地址打出去；
     * 因此新增一支接口只要在接口里加一个标了注解的方法，这里不必动
     * @param proxy 上面那个处理器
     * @return 可直接注入的 NapCat 扩展接口
     */
    @Bean
    public NapcatHttpAdapter napcatHttpAdapter(NapcatHttpAdapterProxy proxy) {
        return (NapcatHttpAdapter) Proxy.newProxyInstance(
                NapcatHttpAdapter.class.getClassLoader(),
                new Class[]{NapcatHttpAdapter.class},
                proxy);
    }
}
