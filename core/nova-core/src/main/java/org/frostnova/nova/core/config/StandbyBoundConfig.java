package org.frostnova.nova.core.config;

import java.util.Map;

/**
 * Spring 把 application.yml 绑进环境那一刻的文件内容。
 * <p>
 * 记下的是那一刻按启动那一路读出来的键值，不是稍后配置应用器构造时再读的那一份。
 * 两刻之间旧的那份进程还可能往同一份文件里存过设置。
 * 只在这一次启动的容器里留着：环境准备时暂存，容器初始化时取走并清掉，
 * 免得同一次测试进程里后面新建的配置应用器读到上一回的快照。
 */
public final class StandbyBoundConfig {

    private static volatile Map<String, Object> pending;

    private final Map<String, Object> values;

    private StandbyBoundConfig(Map<String, Object> values) {
        this.values = values;
    }

    static void remember(Map<String, Object> values) {
        pending = values == null ? Map.of() : Map.copyOf(values);
    }

    /**
     * 取走这一次启动暂存的快照，并清掉暂存。没有记下时为空。
     * @return 这一次的快照，没有时为 null
     */
    static StandbyBoundConfig take() {
        Map<String, Object> snapshot = pending;
        pending = null;
        return snapshot == null ? null : new StandbyBoundConfig(snapshot);
    }

    /**
     * @return 绑定那一刻的键值
     */
    public Map<String, Object> values() {
        return values;
    }
}
