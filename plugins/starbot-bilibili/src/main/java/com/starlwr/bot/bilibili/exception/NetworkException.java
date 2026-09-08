package com.starlwr.bot.bilibili.exception;

/**
 * 网络异常，表示请求未能到达哔哩哔哩服务器或响应无法读取
 */
public class NetworkException extends RuntimeException {
    public NetworkException(String message) {
        super(message);
    }

    public NetworkException(String message, Throwable cause) {
        super(message, cause);
    }
}
