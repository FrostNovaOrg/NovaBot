package com.starlwr.bot.core.event.live.common;

import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.event.live.base.StarBotLiveInfoUpdateEvent;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * 高能用户数更新事件
 * <p>
 * 高能榜只收<b>有过消费的观众</b>，因此这个数远小于实际观看人数，
 * 更接近「有多少人真的掏了钱」。它会随时间涨落，和只增不减的
 * {@link WatchedUpdateEvent} 不是一回事。
 * <p>
 * 沿用平台的原始命名而不另造一个中性词：这个概念本就没有跨平台的通用说法，
 * 换个名字只会让对着协议排查的人多绕一圈。
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class OnlineRankCountUpdateEvent extends StarBotLiveInfoUpdateEvent {
    /**
     * 高能用户数
     */
    private Integer count;

    /**
     * 在线高能用户数
     * <p>
     * 平台分了两个字段，实测取值始终与 {@link #count} 相同。两个都带上是为了
     * 万一哪天语义分叉时不必改事件结构；取不到时为空。
     */
    private Integer onlineCount;

    /**
     * 平台格式化后的展示文本，如「1万+」
     */
    private String text;

    public OnlineRankCountUpdateEvent(String platform, LiveStreamerInfo source, Integer count, Integer onlineCount, String text) {
        super(platform, source);
        this.count = count;
        this.onlineCount = onlineCount;
        this.text = text;
    }

    public OnlineRankCountUpdateEvent(LivePlatform platform, LiveStreamerInfo source, Integer count, Integer onlineCount, String text) {
        super(platform, source);
        this.count = count;
        this.onlineCount = onlineCount;
        this.text = text;
    }

    public OnlineRankCountUpdateEvent(LivePlatform platform, LiveStreamerInfo source, Integer count, Integer onlineCount, String text, Instant instant) {
        super(platform, source, instant);
        this.count = count;
        this.onlineCount = onlineCount;
        this.text = text;
    }
}
