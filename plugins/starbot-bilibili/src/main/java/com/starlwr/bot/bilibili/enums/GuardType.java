package com.starlwr.bot.bilibili.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 大航海类型
 */
@Getter
@AllArgsConstructor
public enum GuardType {
    UNKNOWN(-1, "未知"),
    Governor(1, "总督"),
    Commander(2, "提督"),
    Captain(3, "舰长");

    private final int code;

    private final String name;

    /**
     * 根据代码获取大航海类型
     * @param code 代码
     * @return 大航海类型，未匹配时返回 {@link #UNKNOWN}
     */
    public static GuardType of(int code) {
        for (GuardType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        return UNKNOWN;
    }
}
