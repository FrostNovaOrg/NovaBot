package org.frostnova.nova.core.properties;

import ch.qos.logback.classic.Level;
import lombok.Getter;
import lombok.Setter;

/**
 * 日志相关配置
 * <p>
 * <b>本类只承载字段与说明，不由 Spring 直接绑定</b>：它是
 * {@code NovaCoreProperties} 的一节，配置键仍是 {@code novabot.core.log.*}，
 * 绑定与装配都在那一侧。与 {@link EventStreamProperties} 同形。
 * <p>
 * 之所以从那个类里拆出来单独成件：网络请求要按 {@code network-log} 决定写不写日志，
 * 而承载这一节的那个类同时还装着控制台、口令、绘图、告警等一整套配置。
 * 只为读一个布尔值就要依赖整份配置，等于把那一整套东西都拖进来——
 * <b>这一节自己是干净的，拖进来的不是。</b>
 */
@Getter
@Setter
public class LogProperties {
    /**
     * 控制台日志级别
     */
    @ConfigEffect(ConfigEffect.Effect.RESTART)
    private Level console;

    /**
     * 文件日志级别
     */
    @ConfigEffect(ConfigEffect.Effect.RESTART)
    private Level file;

    /**
     * 是否记录事件日志
     */
    @ConfigEffect(ConfigEffect.Effect.RESTART)
    private boolean eventLog = false;

    /**
     * 是否记录网络请求日志
     */
    @ConfigEffect(ConfigEffect.Effect.RESTART)
    private boolean networkLog = false;

    /**
     * 网络日志的同类去重抑制窗口，单位：秒，设为 0 关闭抑制
     * <p>
     * 打开 {@code network-log} 之后每个请求写一行，而本程序的请求绝大多数是轮询：
     * 三个房间十秒一轮，一小时上千行几乎一样的记录，**要查的那一条异常正好淹在里面**。
     * 排障日志的用处取决于它读不读得下去。
     * <p>
     * 同类按「请求方法 + 去掉查询串的地址」判定；被抑制的条数攒着，
     * 随下一条同类日志一起报出来。<b>失败一律放行，不参与抑制</b>——
     * 抑制的目的就是让异常显出来。
     */
    @ConfigEffect(ConfigEffect.Effect.RESTART)
    private int networkLogSuppressWindow = 60;
}
