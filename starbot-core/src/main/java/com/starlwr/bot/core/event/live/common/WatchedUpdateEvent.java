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
 * 看过人数更新事件
 * <p>
 * <b>是本场累计来过多少人，不是此刻有多少人在看。</b>这个数只增不减，
 * 所以它的曲线画出来永远在往上爬；真正有信息量的是它的<b>增长速度</b>——
 * 每分钟新来多少人。把它当作「在线人数」展示会误导人。
 * <p>
 * 平台周期性下发（实测每分钟数次），消费方按「最新值覆盖」处理即可。
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class WatchedUpdateEvent extends StarBotLiveInfoUpdateEvent {
    /**
     * 看过人数，本场累计
     */
    private Integer count;

    /**
     * 平台格式化后的展示文本，如「3.4万人看过」
     * <p>
     * 原样带上而不是自己格式化：平台的缩写口径（几时用「万」、保留几位）随时可能调整，
     * 自己算一份只会和观众在直播间里看到的数字对不上。
     */
    private String text;

    public WatchedUpdateEvent(String platform, LiveStreamerInfo source, Integer count, String text) {
        super(platform, source);
        this.count = count;
        this.text = text;
    }

    public WatchedUpdateEvent(LivePlatform platform, LiveStreamerInfo source, Integer count, String text) {
        super(platform, source);
        this.count = count;
        this.text = text;
    }

    public WatchedUpdateEvent(LivePlatform platform, LiveStreamerInfo source, Integer count, String text, Instant instant) {
        super(platform, source, instant);
        this.count = count;
        this.text = text;
    }
}
