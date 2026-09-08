package com.starlwr.bot.bilibili.protocol;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.nio.charset.StandardCharsets;

/**
 * 直播间长连接数据包
 *
 * @param operation 操作类型
 * @param protocolVersion 协议版本，决定负载的编码方式
 * @param body 负载
 */
@Getter
@RequiredArgsConstructor
public class BilibiliPacket {
    private final int operation;

    private final int protocolVersion;

    private final byte[] body;

    /**
     * 将负载按 UTF-8 解析为文本
     * @return 负载文本
     */
    public String getBodyAsText() {
        return new String(body, StandardCharsets.UTF_8);
    }

    /**
     * 将负载解析为一个 32 位有符号整数，用于人气值等以整数形式返回的负载
     * @return 负载对应的整数，长度不足时返回 0
     */
    public int getBodyAsInt() {
        if (body.length < 4) {
            return 0;
        }

        return ((body[0] & 0xFF) << 24) | ((body[1] & 0xFF) << 16) | ((body[2] & 0xFF) << 8) | (body[3] & 0xFF);
    }
}
