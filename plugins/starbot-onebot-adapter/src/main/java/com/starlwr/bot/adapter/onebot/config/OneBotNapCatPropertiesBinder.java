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
 * NapCat 代登录凭据新旧两套键都认
 * <p>
 * 先按旧键 {@code starbot.core.config-ui.napcat} 绑一趟，再按现行键
 * {@code novabot.adapter.onebot.napcat} 绑第二趟：第二趟只会写入真的出现在配置里的项，
 * 没写的项原样留着第一趟的值。于是「新键在场时压过旧键、缺的项由旧键补上」落在每一项上。
 * 旧键在场时启动打一条提醒，不在每次读取时刷。
 */
@Slf4j
@Configuration
@StarBotComponent
public class OneBotNapCatPropertiesBinder {
    /**
     * 现行配置键前缀
     */
    public static final String PREFIX = NovaBotPrefixes.ADAPTER_NAPCAT;

    /**
     * 改名前的配置键前缀
     */
    public static final String LEGACY_PREFIX = "starbot.core.config-ui.napcat";

    /**
     * 旧位置的 token 明文键，写回哈希时若在场须一并清空
     */
    public static final String LEGACY_TOKEN = LEGACY_PREFIX + ".token";

    /**
     * 把新旧两套键落到代登录节上
     * @param environment 运行环境
     * @param napcat 代登录节
     * @return 绑定见证
     */
    public static OneBotNapCatKeyBinding apply(Environment environment,
                                               OneBotAdapterPluginProperties.NapCat napcat) {
        Binder binder = Binder.get(environment);
        boolean legacy = binder.bind(LEGACY_PREFIX, Bindable.ofInstance(napcat)).isBound();
        boolean previous = binder.bind(NovaBotPrefixes.ADAPTER_NAPCAT_LEGACY, Bindable.ofInstance(napcat)).isBound();
        boolean current = binder.bind(PREFIX, Bindable.ofInstance(napcat)).isBound();

        BindResult<String> legacyToken = binder.bind(LEGACY_TOKEN, Bindable.of(String.class));
        boolean legacyTokenPresent = legacyToken.isBound()
                && legacyToken.get() != null
                && !legacyToken.get().isBlank();

        if (legacy) {
            log.warn("配置项 {}.* 已改名为 {}.*, 旧键仍然有效, 但请尽快改过来{}",
                    LEGACY_PREFIX,
                    PREFIX,
                    (previous || current) ? "。两套键同时存在时以新键为准, 新键未写到的项才取旧键的值" : "");
        }
        return new OneBotNapCatKeyBinding(legacy, legacyTokenPresent);
    }

    /**
     * 启动时把旧键叠到已绑定的适配器属性上
     * @param environment 运行环境
     * @param properties 适配器属性
     * @return 绑定见证，仅用于让容器调用本方法一次
     */
    @Bean
    public OneBotNapCatKeyBinding oneBotNapCatKeyBinding(Environment environment,
                                                         OneBotAdapterPluginProperties properties) {
        return apply(environment, properties.getNapcat());
    }

    /**
     * 两趟绑定已经做过的标记
     * @param legacyKeysPresent 旧键是否在场
     * @param legacyTokenPresent 旧位置是否写着 token 明文
     */
    public record OneBotNapCatKeyBinding(boolean legacyKeysPresent, boolean legacyTokenPresent) {
    }
}
