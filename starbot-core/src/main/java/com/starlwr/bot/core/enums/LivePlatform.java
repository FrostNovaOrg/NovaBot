package com.starlwr.bot.core.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 直播平台
 */
@Getter
@AllArgsConstructor
public enum LivePlatform {
    BILIBILI("bilibili"),
    DOU_YIN("抖音"),
    KUAI_SHOU("快手"),
    DOU_YU("斗鱼"),
    HU_YA("虎牙"),
    XIAO_HONG_SHU("小红书"),
    CC("CC"),
    YY("YY");

    private final String name;
}
