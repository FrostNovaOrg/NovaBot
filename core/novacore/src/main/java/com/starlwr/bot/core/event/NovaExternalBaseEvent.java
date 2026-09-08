package com.starlwr.bot.core.event;

import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * NovaBot 外部事件基类，由外部来源触发，需外界按需处理的事件
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class NovaExternalBaseEvent extends NovaBaseEvent {
    /**
     * 直播平台
     */
    private String platform;

    /**
     * 主播信息
     */
    private LiveStreamerInfo source;

    public NovaExternalBaseEvent(String platform, LiveStreamerInfo source) {
        this.platform = platform;
        this.source = source;
    }

    public NovaExternalBaseEvent(String platform, LiveStreamerInfo source, Instant instant) {
        super(instant);
        this.platform = platform;
        this.source = source;
    }

    public NovaExternalBaseEvent(LivePlatform platform, LiveStreamerInfo source) {
        this.platform = platform.id();
        this.source = source;
    }

    public NovaExternalBaseEvent(LivePlatform platform, LiveStreamerInfo source, Instant instant) {
        super(instant);
        this.platform = platform.id();
        this.source = source;
    }
}
