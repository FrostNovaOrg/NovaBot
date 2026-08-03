package com.starlwr.bot.adapter.onebot.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.starlwr.bot.core.enums.PushTargetType;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 消息
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
public class MessageDTO {
    /**
     * 推送平台
     */
    private String platform;

    /**
     * 推送目标类型
     */
    private PushTargetType type;

    /**
     * 账号或群号，根据推送目标类型而定
     */
    private Long num;

    /**
     * 可包含占位符的消息内容
     */
    private String content;

    /**
     * StarBot 内部消息创建顺序号
     */
    private Long sequence;

    /**
     * 创建时间戳
     */
    @JsonProperty("create_time")
    private Long createTime;
}
