package org.frostnova.nova.bilibili.enums;

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
    // 但对外的文字只陈述观测：连续多分钟没收到弹幕礼物，看不出是没人说话、画面卡住，还是平台没下发
    RISK(7, "长时间无弹幕礼物");

    private final int code;

    private final String name;
}
