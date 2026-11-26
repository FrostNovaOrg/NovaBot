package org.frostnova.nova.interact.auto;

import java.util.HashMap;
import java.util.Map;

public enum ThanksKind {
    GIFT("gift", "礼物答谢", 3, "感谢 {用户} 赠送的 {礼物}", "礼物"),
    MEMBERSHIP("membership", "上舰答谢", 5, "感谢 {用户} 开通{等级}", "等级"),
    SUPER_CHAT("superChat", "醒目留言答谢", 4, "感谢 {用户} 的醒目留言"),
    FOLLOW("follow", "关注答谢", 1, "感谢 {用户} 的关注", "人数"),
    SHARE("share", "分享答谢", 1, "感谢 {用户} 的分享直播间", "人数");

    final String key;
    final int priority;
    final DanmakuTemplate template;

    ThanksKind(String key, String label, int priority, String defaultTemplate, String... extraFields) {
        this.key = key;
        this.priority = priority;
        this.template = new DanmakuTemplate(label, defaultTemplate, extraFields);
    }

    public static Map<String, String> defaultTemplates() {
        Map<String, String> templates = new HashMap<>();
        for (ThanksKind kind : values()) templates.put(kind.key, kind.template.defaultText());
        return Map.copyOf(templates);
    }

    String render(ThanksSettings settings, String viewer, String streamer, String detail) {
        return template.render(settings.templates().get(key), viewer, streamer, detail);
    }
}
