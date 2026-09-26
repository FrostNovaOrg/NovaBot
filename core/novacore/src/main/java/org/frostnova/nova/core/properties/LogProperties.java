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
     * 控制台日志级别。
     */
    @ConfigEffect(ConfigEffect.Effect.RESTART)
    @ConfigLabel("控制台日志级别")
    private Level console;

    /**
     * 文件日志级别。
     */
    @ConfigEffect(ConfigEffect.Effect.RESTART)
    @ConfigLabel("日志文件级别")
    private Level file;

    /**
     * 是否记录事件日志。
     */
    @ConfigEffect(ConfigEffect.Effect.RESTART)
    @ConfigLabel("事件日志")
    private boolean eventLog = false;

    /**
     * 是否记录网络请求日志。
     */
    @ConfigEffect(ConfigEffect.Effect.RESTART)
    @ConfigLabel("网络日志")
    private boolean networkLog = false;

    /**
     * 网络日志的同类去重窗口，单位：秒，设为 0 关闭。打开网络日志后轮询请求一小时上千行，不去重要查的异常就淹在里面；失败的请求不参与抑制。
     */
    // 同类按「请求方法 + 去掉查询串的地址」判定；被抑制的条数攒着，随下一条同类
    // 日志一起报出来。失败一律放行——抑制的目的就是让异常显出来。
    @ConfigEffect(ConfigEffect.Effect.RESTART)
    @ConfigLabel("网络日志 · 去重窗口")
    private int networkLogSuppressWindow = 60;
}
