package com.starlwr.bot.core.event.live.common;

import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.event.live.base.StarBotLiveMessageEvent;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import com.starlwr.bot.core.model.UserInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * 弹幕事件
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class DanmuEvent extends StarBotLiveMessageEvent {
    /**
     * 内容
     */
    private String content;

    /**
     * 去除了表情等内容后的纯文本内容
     */
    private String contentText;

    public DanmuEvent(String platform, LiveStreamerInfo source, UserInfo sender, String content) {
        super(platform, source, sender);
        this.content = content;
        this.contentText = content;
    }

    public DanmuEvent(String platform, LiveStreamerInfo source, UserInfo sender, String content, Instant instant) {
        super(platform, source, sender, instant);
        this.content = content;
        this.contentText = content;
    }

    public DanmuEvent(String platform, LiveStreamerInfo source, UserInfo sender, String content, String contentText) {
        super(platform, source, sender);
        this.content = content;
        this.contentText = contentText;
    }

    public DanmuEvent(String platform, LiveStreamerInfo source, UserInfo sender, String content, String contentText, Instant instant) {
        super(platform, source, sender, instant);
        this.content = content;
        this.contentText = contentText;
    }

    public DanmuEvent(LivePlatform platform, LiveStreamerInfo source, UserInfo sender, String content) {
        super(platform, source, sender);
        this.content = content;
        this.contentText = content;
    }

    public DanmuEvent(LivePlatform platform, LiveStreamerInfo source, UserInfo sender, String content, Instant instant) {
        super(platform, source, sender, instant);
        this.content = content;
        this.contentText = content;
    }

    public DanmuEvent(LivePlatform platform, LiveStreamerInfo source, UserInfo sender, String content, String contentText) {
        super(platform, source, sender);
        this.content = content;
        this.contentText = contentText;
    }

    public DanmuEvent(LivePlatform platform, LiveStreamerInfo source, UserInfo sender, String content, String contentText, Instant instant) {
        super(platform, source, sender, instant);
        this.content = content;
        this.contentText = contentText;
    }
}
