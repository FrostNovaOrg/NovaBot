package com.starlwr.bot.bilibili.model;

import com.starlwr.bot.bilibili.enums.DanmuType;
import com.starlwr.bot.core.model.UserInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 弹幕
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
public class Danmu {
    /**
     * 弹幕类型
     */
    private DanmuType type = DanmuType.NORMAL;

    /**
     * 发送者
     */
    private BilibiliUserInfo sender;

    /**
     * 被回复的用户，仅在弹幕为回复时存在
     */
    private UserInfo reply;

    /**
     * 弹幕原始内容，表情弹幕时为表情标识
     */
    private String content;

    /**
     * 弹幕的纯文本内容
     */
    private String contentText;

    /**
     * 弹幕中包含的表情
     */
    private List<BilibiliEmojiInfo> emojis = new ArrayList<>();

    /**
     * 弹幕发送时间
     */
    private Instant timestamp;
}
