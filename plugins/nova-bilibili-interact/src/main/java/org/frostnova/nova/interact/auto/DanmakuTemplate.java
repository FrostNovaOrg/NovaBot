package org.frostnova.nova.interact.auto;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 答谢和欢迎共用的话术校验、默认值与替换规则。 */
record DanmakuTemplate(String label, String defaultText, Set<String> extraFields) {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}]*)}");
    private static final Pattern BLANK = Pattern.compile("[\\p{IsWhite_Space}\\x1C-\\x1F\\uFEFF]*");

    DanmakuTemplate(String label, String defaultText, String... extraFields) {
        this(label, defaultText, Set.of(extraFields));
    }

    static boolean blank(String text) {
        return text == null || BLANK.matcher(text).matches();
    }

    String override(String text) {
        if (blank(text)) return "";
        if (text.contains("\n") || text.contains("\r")) throw new IllegalArgumentException(label + "话术只能写一行");
        Matcher fields = PLACEHOLDER.matcher(text);
        while (fields.find()) {
            String field = fields.group(1);
            if (!field.equals("用户") && !field.equals("主播") && !extraFields.contains(field)) {
                throw new IllegalArgumentException(label + "不能使用占位符 " + fields.group());
            }
        }
        return text;
    }

    String render(String override, String viewer, String streamer, String detail) {
        String template = override == null || override.isEmpty() ? defaultText : override;
        return PLACEHOLDER.matcher(template).replaceAll(match -> Matcher.quoteReplacement(switch (match.group(1)) {
            case "用户" -> viewer;
            case "主播" -> streamer;
            default -> detail;
        }));
    }
}
