package com.starlwr.bot.bilibili.event.live;

import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.event.live.common.FreeGiftEvent;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import com.starlwr.bot.core.model.GiftInfo;
import com.starlwr.bot.core.model.UserInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * 哔哩哔哩免费礼物事件
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class BilibiliFreeGiftEvent extends FreeGiftEvent {
    public BilibiliFreeGiftEvent(LiveStreamerInfo source, UserInfo sender, GiftInfo giftInfo) {
        super(LivePlatform.BILIBILI, source, sender, giftInfo);
    }

    public BilibiliFreeGiftEvent(LiveStreamerInfo source, UserInfo sender, GiftInfo giftInfo, Instant instant) {
        super(LivePlatform.BILIBILI, source, sender, giftInfo, instant);
    }
}
