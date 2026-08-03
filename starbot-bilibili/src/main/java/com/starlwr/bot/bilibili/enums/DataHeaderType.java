package com.starlwr.bot.bilibili.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 直播间长连接数据包协议版本，决定负载的编码方式
 */
@Getter
@AllArgsConstructor
public enum DataHeaderType {
    /**
     * 负载为未压缩的 JSON
     */
    RAW_JSON(0),

    /**
     * 负载为心跳或认证结果
     */
    HEARTBEAT(1),

    /**
     * 负载为 brotli 压缩后的一批完整数据包
     */
    BROTLI_JSON(3);

    private final int code;
}
