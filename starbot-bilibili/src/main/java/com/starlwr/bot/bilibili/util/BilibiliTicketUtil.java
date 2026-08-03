package com.starlwr.bot.bilibili.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * 哔哩哔哩 bili_ticket 工具
 * <p>
 * bili_ticket 是部分接口所需的一项风控凭据，通过以固定密钥对时间戳做 HMAC-SHA256 后
 * 请求签发接口换取。密钥与签发方式均为公开信息。
 */
public final class BilibiliTicketUtil {
    private static final String TICKET_API = "https://api.bilibili.com/bapis/bilibili.api.ticket.v1.Ticket/GenWebTicket";

    /**
     * 签发接口使用的密钥标识
     */
    private static final String KEY_ID = "ec02";

    /**
     * 与 key_id 对应的 HMAC 密钥
     */
    private static final String HMAC_KEY = "XgwSnGZ1p";

    private BilibiliTicketUtil() {
    }

    /**
     * 构造 bili_ticket 签发接口地址
     * @param biliJct CSRF 令牌，未登录时可为 null
     * @return 签发接口完整地址
     */
    public static String buildTicketUrl(String biliJct) {
        return buildTicketUrl(biliJct, System.currentTimeMillis() / 1000L);
    }

    /**
     * 构造 bili_ticket 签发接口地址，允许指定时间戳，便于测试
     * @param biliJct CSRF 令牌，未登录时可为 null
     * @param timestamp 时间戳，单位：秒
     * @return 签发接口完整地址
     */
    public static String buildTicketUrl(String biliJct, long timestamp) {
        return TICKET_API
                + "?key_id=" + KEY_ID
                + "&hexsign=" + hmacSha256(HMAC_KEY, "ts" + timestamp)
                + "&context[ts]=" + timestamp
                + "&csrf=" + Optional.ofNullable(biliJct).orElse("");
    }

    /**
     * 计算 HMAC-SHA256
     * @param key 密钥
     * @param message 消息
     * @return 小写十六进制摘要
     */
    static String hmacSha256(String key, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));

            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }

            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("计算 HMAC-SHA256 失败", e);
        }
    }
}
