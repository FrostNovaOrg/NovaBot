package com.starlwr.bot.core.timeline;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 时间线事件的大类
 * <p>
 * 与 {@link TimelineEventType} 是两层：类型答的是「这一条是什么事」，大类答的是「这一条归谁管」。
 * 分两层是因为筛选那一端——屏幕上一排药丸，一类一枚，点一下就把整块事情筛出来；
 * 让使用者从十几二十个类型里挑，等于要求他先学会这套类型表才用得了这一页。
 * <p>
 * <b>声明顺序就是药丸的顺序</b>：界面不另排一遍。另排的那张表漏一项、多一项都不会报错，
 * 而两处顺序不一致的表现是「同一台机器换个页面进来，药丸的位置变了」。
 * <p>
 * ⚠️ <b>只进不退</b>：这些名字会出现在地址栏里（{@code #/log?cat=PUSH}）。
 * 改名等于让已经贴出去、收藏起来的地址落回一张没筛过的页，而那与筛过的长得一样。
 */
public enum TimelineCategory {
    /**
     * 推送：发出去、没发出去、被丢弃的那些
     */
    PUSH("推送"),

    /**
     * 直播：开播、下播、断流与场次
     * <p>
     * 断流与场次那几类记事由报告与数据那一侧记，记进来之前药丸上只有开播与下播
     * （见 {@link #inUse(Collection)}）。
     */
    LIVE("直播"),

    /**
     * 连接：与直播平台、与机器人那一头的连接，以及登录态
     */
    LINK("连接"),

    /**
     * 命令：群里与私聊里那些指令
     */
    COMMAND("命令"),

    /**
     * 告警：往外报的那几路
     */
    ALERT("告警"),

    /**
     * 设置：配置被改动
     */
    SETTINGS("设置"),

    /**
     * 系统：启动、停止、清理这类程序自己的事
     */
    SYSTEM("系统");

    private final String description;

    TimelineCategory(String description) {
        this.description = description;
    }

    /**
     * 中文说明，供界面展示与筛选
     * @return 中文说明
     */
    public String getDescription() {
        return description;
    }

    /**
     * 这台机器认得的类型里，有事件归属的那些大类，按声明顺序
     * @return 大类
     */
    public static List<TimelineCategory> inUse() {
        return inUse(Arrays.asList(TimelineEventType.values()));
    }

    /**
     * 给定的这些类型落在哪几个大类上，按声明顺序、不重复
     * <p>
     * 空的大类<b>不出现在清单里</b>：一枚点下去必然空空如也的药丸，比没有那枚药丸更糟——
     * 使用者会把它读成「这台机器没发生过这类事」，而实际上是这一类还没有任何东西往里记。
     * @param types 类型
     * @return 大类
     */
    public static List<TimelineCategory> inUse(Collection<TimelineEventType> types) {
        Set<TimelineCategory> found = EnumSet.noneOf(TimelineCategory.class);
        for (TimelineEventType type : types) {
            if (type != null && type.getCategory() != null) {
                found.add(type.getCategory());
            }
        }
        // EnumSet 按声明顺序迭代，因此这里不必再排一次
        return new ArrayList<>(found);
    }

    /**
     * 按名称解析，认不出时返回 {@code null}
     * <p>
     * 不抛异常，与 {@link TimelineEventType#parse} 同法：调用方要能分辨「没筛」与
     * 「筛了个认不出的」，后者必须当场说出来——当成没筛的话，屏幕上是一大堆记录，
     * 看起来像是筛选没生效。
     * @param name 大类名，不区分大小写
     * @return 大类，认不出时为 {@code null}
     */
    public static TimelineCategory parse(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }

        for (TimelineCategory category : values()) {
            if (category.name().equalsIgnoreCase(name.trim())) {
                return category;
            }
        }
        return null;
    }
}
