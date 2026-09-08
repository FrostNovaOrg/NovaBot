package com.starlwr.bot.core.config.ui;

import java.util.Map;

/**
 * 配置键改名别名的申报点
 * <p>
 * 各平台插件实现本接口并注册为 Bean，即可把「这项配置以前写在哪个旧位置」
 * 告诉控制台。核心只保管核心自有的改名表（{@code novabot.core.event-stream}
 * 从 {@code starbot.bilibili.event-stream} 迁来那一条），
 * <b>不认识任何一个具体平台的键</b>：哪条现行键对应哪条旧键，全在插件自己那一侧。
 * <p>
 * 存在的意义是把「装了哪些平台」这件事从编译期挪到运行期。键改名发生在插件里时，
 * 核心既不该依赖插件的类，也不该把那些旧键名写进核心的常量。
 */
public interface ConfigurationKeyAliasContributor {
    /**
     * 本插件申报的改名：现行键（或前缀） → 旧键（或前缀）
     * <p>
     * 值可以是完整键，也可以是前缀。解析时取最长匹配：
     * 逐键申报不会把同前缀下未申报的邻键一并吃掉。
     * 用 {@link java.util.LinkedHashMap} 保申报顺序。同一条现行键被两方申报时，
     * 核心在合并时抛 {@link IllegalStateException}，文案与核心自己的表重复登记相同。
     * @return 现行到旧，登记顺序
     */
    Map<String, String> renamed();
}
