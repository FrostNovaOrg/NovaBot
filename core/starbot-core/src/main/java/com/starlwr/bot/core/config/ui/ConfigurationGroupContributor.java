package com.starlwr.bot.core.config.ui;

import java.util.Map;

/**
 * 设置页分组前缀的申报点
 * <p>
 * 各平台插件实现本接口并注册为 Bean，即可把自己的配置键前缀登记进设置页的分组表。
 * 核心只保管核心自有前缀（{@code novabot.core.*}／{@code spring.*}），
 * <b>不认识任何一个具体平台</b>：哪个前缀落哪一组，全在插件自己那一侧。
 * <p>
 * 存在的意义是把「装了哪些平台」这件事从编译期挪到运行期。此前平台前缀写死在核心的分组表里，
 * 于是核心即使一个平台插件都没装，也背着那些平台的配置键名。
 */
public interface ConfigurationGroupContributor {
    /**
     * 本插件申报的配置键前缀 → 组
     * <p>
     * 用 {@link java.util.LinkedHashMap} 保申报顺序。同一条前缀被两方申报时，
     * 核心在合并时抛 {@link IllegalStateException}，文案与核心自己的前缀表重复登记相同。
     * @return 前缀到组，登记顺序
     */
    Map<String, ConfigurationGroups.Group> prefixes();
}
