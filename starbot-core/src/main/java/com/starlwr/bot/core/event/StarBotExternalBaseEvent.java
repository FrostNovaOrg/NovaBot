package com.starlwr.bot.core.event;

import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * StarBot 外部事件基类，由外部来源触发，需外界按需处理的事件
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class StarBotExternalBaseEvent extends StarBotBaseEvent {
    /**
     * 直播平台
     */
    private String platform;

    /**
     * 主播信息
     */
    private LiveStreamerInfo source;

    public StarBotExternalBaseEvent(String platform, LiveStreamerInfo source) {
        this.platform = platform;
        this.source = source;
    }

    public StarBotExternalBaseEvent(String platform, LiveStreamerInfo source, Instant instant) {
        super(instant);
        this.platform = platform;
        this.source = source;
    }

    public StarBotExternalBaseEvent(LivePlatform platform, LiveStreamerInfo source) {
        this.platform = platform.getName();
        this.source = source;
    }

    public StarBotExternalBaseEvent(LivePlatform platform, LiveStreamerInfo source, Instant instant) {
        super(instant);
        this.platform = platform.getName();
        this.source = source;
    }
}
