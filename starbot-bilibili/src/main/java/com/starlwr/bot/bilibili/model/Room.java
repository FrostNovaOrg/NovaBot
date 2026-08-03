package com.starlwr.bot.bilibili.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 直播间信息
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class Room {
    /**
     * 直播状态，1 为直播中
     */
    private Integer liveStatus;

    /**
     * 开播时间戳，单位：秒
     */
    private Long liveStartTime;

    /**
     * 直播间标题
     */
    private String title;

    /**
     * 直播间封面地址
     */
    private String cover;

    /**
     * 判断是否正在直播
     * @return 是否正在直播
     */
    public boolean isLiving() {
        return liveStatus != null && liveStatus == 1;
    }
}
