package com.starlwr.bot.core.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 表情信息
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
public class EmojiInfo {
    /**
     * 表情 ID
     */
    private String id;

    /**
     * 表情名称
     */
    private String name;

    /**
     * 表情图片 URL
     */
    private String url;

    public EmojiInfo(String id, String name, String url) {
        this.id = id;
        this.name = name;
        this.url = url;
    }
}
