package com.starlwr.bot.core.properties;

import lombok.Getter;
import lombok.Setter;

/**
 * 直播相关配置
 * <p>
 * <b>本类只承载字段与说明，不由 Spring 直接绑定</b>：它是
 * {@code StarBotCoreProperties} 的一节，配置键仍是 {@code novabot.core.live.*}，
 * 绑定与装配都在那一侧。与 {@link EventStreamProperties} 同形。
 * <p>
 * ⚠️ <b>这一节的四项并不同源</b>：断线重连的判定间隔是事件本身的语义，
 * 而另外三项讲的是直播数据往哪儿存、多久存一次。四项写在同一个键前缀下是既成事实，
 * <b>拆开就要改键</b>，而改键会让所有既有部署的这几行悄悄失效，
 * 因此这里整节一起留着，不按来源再切一刀。
 */
@Getter
@Setter
public class LiveProperties {
    /**
     * 是否持久化直播数据至文件，仅使用默认直播数据服务时生效
     */
    @ConfigEffect(ConfigEffect.Effect.RESTART)
    private boolean saveLiveData = true;

    /**
     * 直播数据文件路径，仅使用默认直播数据服务时生效
     */
    @ConfigEffect(ConfigEffect.Effect.RESTART)
    private String liveDataPath = "data.json";

    /**
     * 自动保存直播数据间隔，单位：秒，仅使用默认直播数据服务时生效
     */
    @ConfigEffect(ConfigEffect.Effect.RESTART)
    private int autoSaveLiveDataInterval = 300;

    /**
     * 判定主播断线重连（下播后短时间内重新开播）的时间间隔，断线重连不会重置直播数据，单位：秒
     */
    @ConfigEffect(ConfigEffect.Effect.RESTART)
    private int reconnectInterval = 300;

    /**
     * 每场直播明细数据（曲线、排行、词频、弹幕原文）的保留天数，0 表示永久保留
     */
    @ConfigEffect(ConfigEffect.Effect.RESTART)
    private int detailRetentionDays = 0;
}
