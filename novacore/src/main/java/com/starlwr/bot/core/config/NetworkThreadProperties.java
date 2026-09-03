package com.starlwr.bot.core.config;

import lombok.Getter;
import lombok.Setter;

/**
 * 网络线程池相关配置
 * <p>
 * <b>本类只承载字段与说明，不由 Spring 直接绑定</b>：它是
 * {@code StarBotCoreProperties} 的一节，配置键仍是 {@code starbot.core.network-thread.*}，
 * 绑定与装配都在那一侧。与 {@link EventStreamProperties} 同形。
 */
@Getter
@Setter
public class NetworkThreadProperties {
    /**
     * 线程池核心线程数
     */
    private int corePoolSize = 4;

    /**
     * 线程池最大线程数
     */
    private int maxPoolSize = 24;

    /**
     * 线程池任务队列容量
     */
    private int queueCapacity = 64;

    /**
     * 非核心线程存活时间，单位：秒
     */
    private int keepAliveSeconds = 60;
}
