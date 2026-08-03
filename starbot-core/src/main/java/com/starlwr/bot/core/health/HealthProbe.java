package com.starlwr.bot.core.health;

/**
 * 健康探针
 * <p>
 * 各模块实现本接口并注册为 Bean 即可把自身的健康状况汇入统一视图，供配置界面的运行状态页、
 * 健康检查接口与告警共用，无需核心反向依赖各插件。
 * <p>
 * 探针会在界面刷新时被同步调用，因此实现必须是<b>廉价且不阻塞</b>的：只读取已有的内存状态，
 * 不要在其中发起网络请求。需要网络探测的检查应由各模块自行定时执行并缓存结果，探针只负责读取。
 */
public interface HealthProbe {
    /**
     * 探针名称，展示在界面上，例如「哔哩哔哩登录」
     * @return 探针名称
     */
    String name();

    /**
     * 读取当前健康状况
     * @return 健康状况
     */
    HealthStatus check();

    /**
     * 展示顺序，数值小的排在前面
     * @return 展示顺序
     */
    default int order() {
        return 100;
    }

    /**
     * 所属范围，决定该探针出现在配置界面的哪个页签
     * <p>
     * 由探针自己声明而非界面按名称硬编码分类：否则插件新增探针后，界面要跟着改一版才认得它。
     * 总览页始终展示全部探针，不受本项影响。
     * @return 所属范围
     */
    default Scope scope() {
        return Scope.SYSTEM;
    }

    /**
     * 探针所属范围
     */
    enum Scope {
        /**
         * 机器人（QQ 侧）连接相关
         */
        BOT,

        /**
         * 直播平台账号与连接相关
         */
        PLATFORM,

        /**
         * 不属于上述任一侧的系统整体状况
         */
        SYSTEM
    }
}
