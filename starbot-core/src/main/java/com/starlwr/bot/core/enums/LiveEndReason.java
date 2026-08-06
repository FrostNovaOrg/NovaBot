package com.starlwr.bot.core.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 一场直播的结束原因
 * <p>
 * <b>这是数据正确性问题，不是锦上添花。</b>一场被平台切断的直播和一场正常结束的直播，
 * 时长、营收、互动全都不可比——把两者混在同一张趋势图里，主播看到的是
 * 「这天怎么突然掉了一半」，而真实原因是那场根本没播完。
 * <p>
 * 判据来自直播间长连接自身下发的指令（{@code CUT_OFF} / {@code ROOM_LOCK}），
 * 不依赖任何需要额外签名或轮询的接口。
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
    ROOM_LOCK("直播间被封禁");

    private final String description;
}
