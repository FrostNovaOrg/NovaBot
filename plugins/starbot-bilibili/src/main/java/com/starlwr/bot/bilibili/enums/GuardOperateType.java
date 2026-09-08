package com.starlwr.bot.bilibili.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 大航海操作类型
 */
@Getter
@AllArgsConstructor
public enum GuardOperateType {
    UNKNOWN(-1, "未知"),
    ACTIVATION(1, "开通"),
    RENEWAL(2, "续费");

    private final int code;

    private final String name;

    /**
     * 根据代码获取大航海操作类型
     * @param code 代码
     * @return 大航海操作类型，未匹配时返回 {@link #UNKNOWN}
     */
    public static GuardOperateType of(int code) {
        for (GuardOperateType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        return UNKNOWN;
    }
}
