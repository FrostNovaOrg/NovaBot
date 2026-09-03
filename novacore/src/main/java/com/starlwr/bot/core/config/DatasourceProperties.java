package com.starlwr.bot.core.config;

import lombok.Getter;
import lombok.Setter;

/**
 * 数据源相关配置
 * <p>
 * <b>本类只承载字段与说明，不由 Spring 直接绑定</b>：它是
 * {@code StarBotCoreProperties} 的一节，配置键仍是 {@code starbot.core.datasource.*}，
 * 绑定与装配都在那一侧。与 {@link LiveProperties} 同形。
 * <p>
 * 单独成件是为了让本地 JSON 数据源只依赖这两项，而不必依赖整份配置——
 * 那份配置里绝大部分是控制台、绘图、推送的参数，数据源一项也用不上，
 * 依赖着它就等于把这些一并背在身上。
 */
@Getter
@Setter
public class DatasourceProperties {
    /**
     * JSON 文件路径，仅使用 JSON 数据源时生效
     */
    @ConfigEffect(ConfigEffect.Effect.RESTART)
    private String jsonPath = "datasource.json";

    /**
     * JSON 文件发生变化时是否自动重载，仅使用 JSON 数据源时生效
     */
    @ConfigEffect(ConfigEffect.Effect.RESTART)
    private boolean jsonAutoReload = true;
}
