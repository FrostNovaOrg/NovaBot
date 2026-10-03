package org.frostnova.nova.core.protocol;

/**
 * 候命门之后的两档相位。
 * <p>
 * 候命门本身取 {@link Integer#MIN_VALUE}，并且不依赖任何别的组件。
 * 核配置紧挨在它后面，写盘与起线程再晚一档。两档都是负数，早于默认相位，
 * 也早于端口绑定和就绪。放在协议包里，是因为事件输出端点与配置壳都要同一对数。
 */
public final class StandbyPhases {

    /**
     * 过门后先核配置，赶在本进程自己改配置文件之前
     */
    public static final int RECHECK = Integer.MIN_VALUE + 1;

    /**
     * 写盘、起线程。开关关着时同一次启动里照样走到，只是比建对象晚
     */
    public static final int AFTER_GATE = Integer.MIN_VALUE + 2;

    private StandbyPhases() {
    }
}
