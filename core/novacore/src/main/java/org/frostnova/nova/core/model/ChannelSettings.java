package org.frostnova.nova.core.model;

import org.frostnova.nova.core.enums.PushTargetType;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 一位主播在一个群或好友上的设置。 */
public final class ChannelSettings {
    public static final Set<String> COMMAND_KEYS = Set.of("开播@我", "取消开播@我", "动态@我", "取消动态@我",
            "开播@名单", "动态@名单", "直播报告", "直播间数据", "直播间总数据", "数据排行榜", "总数据排行榜");

    private ChannelSettings() { }

    public static PushTarget target(PushUser user, String platform, PushTargetType type, Long num) {
        if (user == null || user.getTargets() == null) return null;
        return user.getTargets().stream().filter(t -> Objects.equals(platform, t.getPlatform())
                && type == t.getType() && Objects.equals(num, t.getNum())).findFirst().orElse(null);
    }

    public static boolean disabled(PushTarget target, String key) {
        return COMMAND_KEYS.contains(key) && target != null && target.getDisabledCommands() != null
                && target.getDisabledCommands().contains(key);
    }

    public static boolean allDisabled(PushTarget target, List<String> keys) {
        return !keys.isEmpty() && keys.stream().allMatch(key -> disabled(target, key));
    }
}
