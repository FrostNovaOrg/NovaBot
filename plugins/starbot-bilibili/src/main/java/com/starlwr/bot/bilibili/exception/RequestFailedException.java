package com.starlwr.bot.bilibili.exception;

/**
 * 请求失败异常，表示重试耗尽后仍未取得有效响应
 */
public class RequestFailedException extends RuntimeException {
    public RequestFailedException(String message) {
        super(message);
    }

    public RequestFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
