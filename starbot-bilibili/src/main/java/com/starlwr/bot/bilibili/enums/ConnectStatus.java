package com.starlwr.bot.bilibili.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 直播间连接状态
 */
@Getter
@AllArgsConstructor
public enum ConnectStatus {
    INIT(0, "初始化"),
    CONNECTING(1, "连接中"),
    CONNECTED(2, "已连接"),
    CLOSING(3, "断开连接中"),
    CLOSED(4, "已断开"),
    TIMEOUT(5, "心跳响应超时"),
    ERROR(6, "错误"),
    // 枚举名沿用 RISK（它进了持久化与对外约定，改名的代价与收益不成比例），
    // 但对外的文字只陈述观测：检测器看到的是「业务消息不来了」，看不出成因
    RISK(7, "业务消息断流");

    private final int code;

    private final String name;
}
