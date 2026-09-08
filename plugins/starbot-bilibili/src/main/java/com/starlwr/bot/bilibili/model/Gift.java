package com.starlwr.bot.bilibili.model;

import lombok.AllArgsConstructor;
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
@AllArgsConstructor
public class Gift {
    /**
     * 礼物 ID
     */
    private Long id;

    /**
     * 礼物名称
     */
    private String name;

    /**
     * 礼物单价，单位：元
     */
    private Double price;

    /**
     * 礼物图片地址
     */
    private String url;
}
