package com.starlwr.bot.bilibili.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * 接口签名所需的凭据集合
 * <p>
 * 其中 bili_ticket 有有效期，WBI 密钥每日轮换，均需按需刷新。
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class WebSign {
    /**
     * bili_ticket
     */
    private String ticket;

    /**
     * bili_ticket 过期时间戳，单位：秒
     */
    private Integer ticketExpires;

    /**
     * WBI 签名所需的 img_key
     */
    private String imgKey;

    /**
     * WBI 签名所需的 sub_key
     */
    private String subKey;

    /**
     * 判断签名凭据是否仍然有效
     * @return 是否仍然有效
     */
    public boolean isValid() {
        if (ticket == null || ticket.isBlank() || imgKey == null || imgKey.isBlank() || subKey == null || subKey.isBlank()) {
            return false;
        }

        // 提前一小时视为过期，避免在临界点上使用即将失效的凭据
        return ticketExpires != null && ticketExpires - 3600 > Instant.now().getEpochSecond();
    }

    /**
     * 刻意不输出凭据内容，防止在日志中泄露
     * @return 脱敏后的描述
     */
    @Override
    public String toString() {
        return "WebSign(有效期至 " + ticketExpires + ", 内容已脱敏)";
    }
}
