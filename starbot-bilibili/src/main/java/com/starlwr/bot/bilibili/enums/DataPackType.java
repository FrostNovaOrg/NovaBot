package com.starlwr.bot.bilibili.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 直播间长连接数据包操作类型
 */
@Getter
@AllArgsConstructor
public enum DataPackType {
    /**
     * 客户端发送的心跳包
     */
    HEARTBEAT(2),

    /**
     * 服务端返回的心跳响应，负载为直播间人气值
     */
    HEARTBEAT_RESPONSE(3),

    /**
     * 服务端推送的通知，即弹幕、礼物等业务消息
     */
    NOTICE(5),

    /**
     * 客户端发送的认证包
     */
    VERIFY(7),

    /**
     * 服务端返回的认证结果
     */
    VERIFY_SUCCESS_RESPONSE(8);

    private final int code;
}
