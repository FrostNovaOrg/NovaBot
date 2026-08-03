package com.starlwr.bot.adapter.onebot.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 错误代码枚举
 */
@Getter
@AllArgsConstructor
public enum ResultCode {
    SUCCESS(0, "成功"),
    UNKNOWN(1, "未知异常"),
    API_ERROR(2, "OneBot API 返回错误代码"),
    UNKNOWN_TARGET_TYPE(3, "未知的推送目标类型"),
    EMPTY_MESSAGE(4, "消息内容为空"),
    UNAUTHORIZED(5, "推送接口 Token 校验失败"),
    FORBIDDEN_ADDRESS(6, "来源 IP 不在白名单内"),
    RATE_LIMITED(7, "请求频率超出限制");

    private final int code;
    private final String msg;
}
