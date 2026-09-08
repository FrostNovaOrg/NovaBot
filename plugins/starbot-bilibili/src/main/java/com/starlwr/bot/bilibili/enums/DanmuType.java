package com.starlwr.bot.bilibili.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 弹幕类型
 */
@Getter
@AllArgsConstructor
public enum DanmuType {
    UNKNOWN(-1, "未知"),
    NORMAL(0, "普通弹幕"),
    EMOJI(1, "表情弹幕");

    private final int code;

    private final String name;

    /**
     * 根据代码获取弹幕类型
     * @param code 代码
     * @return 弹幕类型，未匹配时返回 {@link #UNKNOWN}
     */
    public static DanmuType of(int code) {
        for (DanmuType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        return UNKNOWN;
    }
}
