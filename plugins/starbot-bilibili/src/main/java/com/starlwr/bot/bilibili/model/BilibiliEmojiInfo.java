package com.starlwr.bot.bilibili.model;

import com.starlwr.bot.core.model.EmojiInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 哔哩哔哩表情信息，在通用表情信息之上补充绘制所需的尺寸与数量
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class BilibiliEmojiInfo extends EmojiInfo {
    /**
     * 表情宽度
     */
    private Integer width;

    /**
     * 表情高度
     */
    private Integer height;

    /**
     * 表情出现次数
     */
    private Integer count;

    public BilibiliEmojiInfo(String id, String name, String url) {
        super(id, name, url);
    }

    public BilibiliEmojiInfo(String id, String name, String url, Integer width, Integer height, Integer count) {
        super(id, name, url);
        this.width = width;
        this.height = height;
        this.count = count;
    }
}
