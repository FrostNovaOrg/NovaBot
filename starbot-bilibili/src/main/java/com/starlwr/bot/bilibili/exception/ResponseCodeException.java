package com.starlwr.bot.bilibili.exception;

import lombok.Getter;

/**
 * 响应错误代码异常，表示服务器返回了非 0 的业务错误代码
 */
@Getter
public class ResponseCodeException extends RuntimeException {
    /**
     * 业务错误代码
     */
    private final int code;

    public ResponseCodeException(int code, String message) {
        super("接口返回错误代码 " + code + ": " + message);
        this.code = code;
    }
}
