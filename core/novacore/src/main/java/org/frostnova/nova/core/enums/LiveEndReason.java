package org.frostnova.nova.core.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 一场直播的结束原因
 * <p>
 * <b>这是数据正确性问题，不是锦上添花。</b>一场被平台切断的直播和一场正常结束的直播，
 * 时长、营收、互动全都不可比——把两者混在同一张趋势图里，主播看到的是
 * 「这天怎么突然掉了一半」，而真实原因是那场根本没播完。
 * <p>
 * 判据有两个来源，互不重叠：
 * <ul>
 *     <li>{@code CUT_OFF} / {@code ROOM_LOCK} 来自直播间长连接自身下发的指令，
 *         不依赖任何需要额外签名或轮询的接口</li>
 *     <li>{@code UNCLOSED} 来自我们自己——它意味着这一场我们没看到结尾，
 *         与平台怎么处置这个直播间无关</li>
 * </ul>
 */
@Getter
@AllArgsConstructor
public enum LiveEndReason {
    /**
     * 主播主动下播。缺少任何干预记录时的默认值
     */
    NORMAL("主动下播"),

    /**
     * 被平台切断直播流
     */
    CUT_OFF("被平台切断"),

    /**
     * 直播间被封禁
     */
    ROOM_LOCK("直播间被封禁"),

    /**
     * 未闭合：程序在这一场进行中崩溃或被强杀，等重启后才发现它已经结束了
     * <p>
     * <b>结束时刻是我们最后一次落盘的时刻，不是主播真正下播的时刻</b>，
     * 所以时长只是个下界，崩溃到重启之间到达的消息也不在统计里。
     * 这类场次的时长与各项指标<b>都不可用于趋势对比</b>。
     */
    UNCLOSED("异常中断未闭合");

    private final String description;
}
