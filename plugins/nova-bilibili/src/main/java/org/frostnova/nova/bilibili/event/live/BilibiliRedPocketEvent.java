package org.frostnova.nova.bilibili.event.live;

import org.frostnova.nova.bilibili.BilibiliPlatform;
import org.frostnova.nova.core.event.live.common.RedPocketEvent;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import org.frostnova.nova.core.model.UserInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * 哔哩哔哩红包事件
 * <p>
 * 对应 {@code POPULARITY_RED_POCKET_START} 与 {@code POPULARITY_RED_POCKET_V2_START}。
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class BilibiliRedPocketEvent extends RedPocketEvent {
    public BilibiliRedPocketEvent(LiveStreamerInfo source, UserInfo sender) {
        super(BilibiliPlatform.BILIBILI, source, sender);
    }

    public BilibiliRedPocketEvent(LiveStreamerInfo source, UserInfo sender, Instant instant) {
        super(BilibiliPlatform.BILIBILI, source, sender, instant);
    }
}
