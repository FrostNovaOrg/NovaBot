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
 * 直播间标题或分区变更事件
 * <p>
 * 平台在直播中途改标题、换分区时下发。<b>下发是无条件的</b>——即使改动前后完全一样
 * 也照发不误，因此消费方必须自行判重，否则会把「主播点开设置又原样保存」记成一次变更。
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class RoomInfoChangeEvent extends StarBotLiveInfoUpdateEvent {
    /**
     * 变更后的直播间标题
     */
    private String title;

    /**
     * 变更后的父分区名，如「娱乐」
     */
    private String parentAreaName;

    /**
     * 变更后的子分区名，如「视频聊天」
     */
    private String areaName;

    public RoomInfoChangeEvent(String platform, LiveStreamerInfo source, String title, String parentAreaName, String areaName) {
        super(platform, source);
        this.title = title;
        this.parentAreaName = parentAreaName;
        this.areaName = areaName;
    }

    public RoomInfoChangeEvent(LivePlatform platform, LiveStreamerInfo source, String title, String parentAreaName, String areaName) {
        super(platform, source);
        this.title = title;
        this.parentAreaName = parentAreaName;
        this.areaName = areaName;
    }

    public RoomInfoChangeEvent(LivePlatform platform, LiveStreamerInfo source, String title, String parentAreaName, String areaName, Instant instant) {
        super(platform, source, instant);
        this.title = title;
        this.parentAreaName = parentAreaName;
        this.areaName = areaName;
    }

    /**
     * 分区的完整描述
     * @return 形如「娱乐 · 视频聊天」，两级都取不到时为空字符串
     */
    public String fullAreaName() {
        boolean hasParent = parentAreaName != null && !parentAreaName.isBlank();
        boolean hasChild = areaName != null && !areaName.isBlank();

        if (hasParent && hasChild) {
            return parentAreaName + " · " + areaName;
        }
        if (hasParent) {
            return parentAreaName;
        }
        return hasChild ? areaName : "";
    }
}
