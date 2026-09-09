package org.frostnova.nova.bilibili.event.dynamic;

import org.frostnova.nova.bilibili.BilibiliPlatform;
import org.frostnova.nova.bilibili.model.Dynamic;
import org.frostnova.nova.core.event.dynamic.NovaBaseDynamicEvent;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * 哔哩哔哩动态更新事件
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class BilibiliDynamicUpdateEvent extends NovaBaseDynamicEvent {
    /**
     * 动态内容
     */
    private Dynamic dynamic;

    /**
     * 动态动作描述，例如「发布了动态」「投稿了视频」「转发了动态」
     */
    private String action;

    /**
     * 动态跳转地址
     */
    private String url;

    public BilibiliDynamicUpdateEvent(LiveStreamerInfo source, Dynamic dynamic, String action, String url) {
        super(BilibiliPlatform.BILIBILI, source);
        this.dynamic = dynamic;
        this.action = action;
        this.url = url;
    }

    public BilibiliDynamicUpdateEvent(LiveStreamerInfo source, Dynamic dynamic, String action, String url, Instant instant) {
        super(BilibiliPlatform.BILIBILI, source, instant);
        this.dynamic = dynamic;
        this.action = action;
        this.url = url;
    }
}
