package org.frostnova.nova.core.config.ui;

import java.util.Map;
import java.util.function.Consumer;

/**
 * 即时生效应用器的申报点
 * <p>
 * 各平台插件实现本接口并注册为 Bean，即可把自己那些「保存后立刻生效」的配置项
 * 登记进核心的落地名单。核心只保管核心自有的那张表，
 * <b>不认识任何一个具体平台的键</b>：哪一项该怎么写回运行中的程序，全在插件自己那一侧。
 * <p>
 * 名单与字段上的 {@code @ConfigEffect} 标注必须一一对应，两边对不上时构建会红。
 * 插件登记的键同样进这本账，否则界面会说「已生效」而保存那一步压根没碰它。
 */
public interface RuntimeConfigurationApplierContributor {
    /**
     * 本插件申报的即时生效项：配置项名 → 把新值落到运行中的程序
     * <p>
     * 同一条键被两方申报、或与核心自有表重复时，核心在合并时抛 {@link IllegalStateException}。
     * @return 键到落地动作，登记顺序
     */
    Map<String, Consumer<String>> appliers();
}
