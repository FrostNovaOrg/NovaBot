package com.starlwr.bot.bilibili.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 直播间长连接服务器地址
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class ConnectAddress {
    /**
     * 服务器主机名
     */
    private String host;

    /**
     * TCP 端口
     */
    private int port;

    /**
     * WSS 端口
     */
    private int wssPort;

    /**
     * WS 端口
     */
    private int wsPort;

    /**
     * 构造 WSS 连接地址
     * @return WSS 连接地址
     */
    public String toWebSocketUrl() {
        return "wss://" + host + ":" + wssPort + "/sub";
    }
}
