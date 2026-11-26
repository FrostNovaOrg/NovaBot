package org.frostnova.nova.interact.auto;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public enum ThanksKind {
    GIFT("gift", "礼物答谢", 2, "感谢 {用户} 赠送的 {礼物}", "礼物"),
    MEMBERSHIP("membership", "上舰答谢", 4, "感谢 {用户} 开通{等级}", "等级"),
    SUPER_CHAT("superChat", "醒目留言答谢", 3, "感谢 {用户} 的醒目留言"),
    FOLLOW("follow", "关注答谢", 1, "感谢 {用户} 的关注", "人数"),
    SHARE("share", "分享答谢", 1, "感谢 {用户} 的分享直播间", "人数");

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}]*)}");
    final String key;
    final int priority;
    private final String label;
    private final String defaultTemplate;
    private final Set<String> extraFields;

    ThanksKind(String key, String label, int priority, String defaultTemplate, String... extraFields) {
        this.key = key;
        this.label = label;
        this.priority = priority;
        this.defaultTemplate = defaultTemplate;
        this.extraFields = Set.of(extraFields);
    }

    public static Map<String, String> defaultTemplates() {
        Map<String, String> templates = new HashMap<>();
        for (ThanksKind kind : values()) templates.put(kind.key, kind.defaultTemplate);
        return Map.copyOf(templates);
    }

    void validate(String template) {
        if (template.contains("\n") || template.contains("\r")) throw new IllegalArgumentException(label + "话术只能写一行");
        Matcher fields = PLACEHOLDER.matcher(template);
        while (fields.find()) {
            String field = fields.group(1);
            if (!field.equals("用户") && !field.equals("主播") && !extraFields.contains(field)) {
                throw new IllegalArgumentException(label + "不能使用占位符 " + fields.group());
            }
        }
    }

    String render(ThanksSettings settings, String viewer, String streamer, String detail) {
        String template = settings.templates().getOrDefault(key, defaultTemplate);
        return PLACEHOLDER.matcher(template).replaceAll(match -> Matcher.quoteReplacement(switch (match.group(1)) {
            case "用户" -> viewer;
            case "主播" -> streamer;
            default -> detail;
        }));
    }
}
