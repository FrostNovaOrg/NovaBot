package com.starlwr.bot.core.config;

import lombok.Getter;
import lombok.Setter;

/**
 * 网络相关配置
 * <p>
 * <b>本类只承载字段与说明，不由 Spring 直接绑定</b>：它是
 * {@code StarBotCoreProperties} 的一节，配置键仍是 {@code starbot.core.network.*}，
 * 绑定与装配都在那一侧。与 {@link EventStreamProperties} 同形。
 * <p>
 * 拆成单件的理由与 {@link LogProperties} 相同：HTTP 客户端只需要这两个超时值，
 * 不需要认得控制台口令与绘图字体。
 */
@Getter
@Setter
public class NetworkProperties {
    /**
     * 网络请求连接超时时间，单位：秒
     */
    @ConfigEffect(ConfigEffect.Effect.RESTART)
    private int connectTimeout = 10;

    /**
     * 网络请求读取超时时间，单位：秒
     */
    @ConfigEffect(ConfigEffect.Effect.RESTART)
    private int readTimeout = 60;
}
