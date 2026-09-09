package org.frostnova.nova.core.config.ui;

import java.util.Set;

/**
 * 机器人连接列表的申报点
 * <p>
 * 各平台插件实现本接口并注册为 Bean，即可告诉控制台两件事：连接列表在配置树的哪个位置，
 * 以及列表里哪几个键留空等于「没配」。核心<b>不认识任何一个具体平台的键</b>：
 * 没有申报时连接列表键为空，按「无连接列表」走。
 * <p>
 * 两件事放在同一只接口里（留空键用 default 方法，不必报的适配器走空集），
 * 因为它们都关于「这一条连接列表怎么落盘」，不该再起第二套注册机制。
 */
public interface BotConnectionContributor {
    /**
     * 本插件的机器人连接列表在配置树里的路径
     * <p>
     * 没有适配器时核心拿不到路径，保存连接那一步按「无连接列表」处理，不写文件。
     * @return 列表的完整路径；没有列表时不要实现本接口
     */
    String connectionListKey();

    /**
     * 本插件申报的「留空即未配」键
     * <p>
     * 核心自有表只留 Redis 地址。连接令牌这类键由本方法申报：标量写口按完整路径认，
     * 对象列表渲染与列表元素字段按最后一段认。默认空集——只报路径、不报令牌键的适配器走这一支。
     * @return 完整路径，登记顺序
     */
    default Set<String> blankMeansAbsentKeys() {
        return Set.of();
    }
}
