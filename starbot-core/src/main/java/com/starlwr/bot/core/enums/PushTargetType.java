package com.starlwr.bot.core.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 推送目标类型
 */
@Getter
@AllArgsConstructor
public enum PushTargetType {
    FRIEND(0, "好友"),
    GROUP(1, "群"),
    UNKNOWN(-1, "未知");

    private final int code;
    private final String str;

    public static PushTargetType of(int code) {
        for (PushTargetType pushTargetType : PushTargetType.values()) {
            if (pushTargetType.code == code) {
                return pushTargetType;
            }
        }

        return UNKNOWN;
    }
}
