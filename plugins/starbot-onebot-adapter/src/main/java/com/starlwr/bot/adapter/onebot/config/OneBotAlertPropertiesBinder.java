package com.starlwr.bot.adapter.onebot.config;

import com.starlwr.bot.core.plugin.StarBotComponent;
import com.starlwr.bot.core.properties.NovaBotPrefixes;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.bind.BindResult;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 告警目标新旧两套键都认
 * <p>
 * 先按旧键 {@code starbot.core.alert.qq-*} 绑一趟，再按现行键
 * {@code novabot.adapter.onebot.alert} 绑第二趟：第二趟只会写入真的出现在配置里的项，
 * 没写的项原样留着第一趟的值。于是「新键在场时压过旧键、缺的项由旧键补上」落在每一项上。
 * 旧键在场时启动打一条提醒，不在每次发送重读时刷。
 */
@Slf4j
@Configuration
@StarBotComponent
public class OneBotAlertPropertiesBinder {
    /**
     * 现行配置键前缀
     */
    public static final String PREFIX = NovaBotPrefixes.ADAPTER_ALERT;

    private static final String LEGACY_PLATFORM = "starbot.core.alert.qq-platform";

    private static final String LEGACY_TYPE = "starbot.core.alert.qq-type";

    private static final String LEGACY_NUM = "starbot.core.alert.qq-num";

    /**
     * 把新旧两套键落到告警节上
     * @param environment 运行环境
     * @param alert 告警节
     * @return 旧键是否在场
     */
    public static boolean apply(Environment environment, OneBotAdapterPluginProperties.Alert alert) {
        Binder binder = Binder.get(environment);
        boolean legacy = false;

        BindResult<String> platform = binder.bind(LEGACY_PLATFORM, Bindable.of(String.class));
        if (platform.isBound()) {
            alert.setPlatform(platform.get());
            legacy = true;
        }
        BindResult<Integer> type = binder.bind(LEGACY_TYPE, Bindable.of(Integer.class));
        if (type.isBound()) {
            alert.setType(type.get());
            legacy = true;
        }
        BindResult<Long> num = binder.bind(LEGACY_NUM, Bindable.of(Long.class));
        if (num.isBound()) {
            alert.setNum(num.get());
            legacy = true;
        }

        boolean previous = binder.bind(NovaBotPrefixes.ADAPTER_ALERT_LEGACY, Bindable.ofInstance(alert)).isBound();
        boolean current = binder.bind(PREFIX, Bindable.ofInstance(alert)).isBound();
        if (legacy) {
            log.warn("配置项 starbot.core.alert.qq-* 已改名为 {}.*, 旧键仍然有效, 但请尽快改过来{}",
                    PREFIX,
                    (previous || current) ? "。两套键同时存在时以新键为准, 新键未写到的项才取旧键的值" : "");
        }
        return legacy;
    }

    /**
     * 启动时把旧键叠到已绑定的适配器属性上
     * @param environment 运行环境
     * @param properties 适配器属性
     * @return 绑定见证，仅用于让容器调用本方法一次
     */
    @Bean
    public OneBotAlertKeyBinding oneBotAlertKeyBinding(Environment environment,
                                                       OneBotAdapterPluginProperties properties) {
        boolean legacy = apply(environment, properties.getAlert());
        return new OneBotAlertKeyBinding(legacy);
    }

    /**
     * 两趟绑定已经做过的标记
     * @param legacyKeysPresent 旧键是否在场
     */
    public record OneBotAlertKeyBinding(boolean legacyKeysPresent) {
    }
}
