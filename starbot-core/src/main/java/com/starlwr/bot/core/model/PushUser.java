package com.starlwr.bot.core.model;

import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.event.dynamic.StarBotBaseDynamicEvent;
import com.starlwr.bot.core.event.live.StarBotBaseLiveEvent;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 推送用户
 */
@Getter
@Setter
public class PushUser {
    /**
     * UID
     */
    private Long uid;

    /**
     * 昵称，非必填，会自动获取
     */
    private String uname;

    /**
     * 房间号，非必填，会自动获取
     */
    private Long roomId;

    /**
     * 头像，非必填，会自动获取
     */
    private String face;

    /**
     * 直播平台，请优先从 {@link LivePlatform} 中获取，若不存在可使用自定义字符串
     */
    private String platform;

    /**
     * 是否启用
     */
    private Boolean enabled;

    /**
     * 关联的推送目标
     */
    private List<PushTarget> targets = new ArrayList<>();

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PushUser pushUser)) return false;
        return Objects.equals(uid, pushUser.uid) && Objects.equals(platform, pushUser.platform);
    }

    @Override
    public int hashCode() {
        return Objects.hash(uid, platform);
    }

    @Override
    public String toString() {
        return "PushUser(" + "uid=" + uid + ", uname=" + uname + ", roomId=" + roomId + ", face=" + face + ", platform=" + platform + ", enabled=" + enabled + ", targets=" + targets + ")";
    }

    /**
     * 检查当前推送用户是否与另一个推送用户完全相同
     * @param other 另一个推送用户
     * @return 是否完全相同
     */
    public boolean same(PushUser other) {
        if (other.targets.size() != targets.size()) return false;
        for (int i = 0; i < targets.size(); i++) {
            if (!targets.get(i).same(other.targets.get(i))) {
                return false;
            }
        }
        return Objects.equals(uid, other.uid) && Objects.equals(uname, other.uname) && Objects.equals(roomId, other.roomId) && Objects.equals(face, other.face) && Objects.equals(platform, other.platform);
    }

    /**
     * 获取兼容未开通直播间的房间号字符串
     * @return 兼容未开通直播间的房间号字符串
     */
    public String getRoomIdString() {
        return roomId == null ? "未开通" : roomId.toString();
    }

    /**
     * 检查推送用户是否监听直播事件
     * @return 是否监听直播事件
     */
    public boolean hasEnabledLiveEvent() {
        return targets.stream()
                .map(PushTarget::getMessages)
                .flatMap(List::stream)
                .map(PushMessage::getEventClass)
                .anyMatch(StarBotBaseLiveEvent.class::isAssignableFrom);
    }

    /**
     * 检查推送用户是否监听动态更新事件
     * @return 是否监听动态更新事件
     */
    public boolean hasEnabledDynamicEvent() {
        return targets.stream()
                .map(PushTarget::getMessages)
                .flatMap(List::stream)
                .map(PushMessage::getEventClass)
                .anyMatch(StarBotBaseDynamicEvent.class::isAssignableFrom);
    }
}
