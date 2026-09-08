package com.starlwr.bot.core.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 礼物信息
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
public class GiftInfo {
    /**
     * 礼物 ID
     */
    private Long id;

    /**
     * 礼物名称
     */
    private String name;

    /**
     * 礼物单价
     */
    private Double price;

    /**
     * 礼物数量
     */
    private Integer count;

    /**
     * 礼物图片 URL
     */
    private String url;

    public GiftInfo(Long id, String name, Double price, Integer count, String url) {
        this.id = id;
        this.name = name;
        this.price = price;
        this.count = count;
        this.url = url;
    }
}
