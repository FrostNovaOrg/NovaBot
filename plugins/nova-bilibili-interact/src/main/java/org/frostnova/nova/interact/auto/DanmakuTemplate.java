package org.frostnova.nova.interact.auto;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 直播间互动共用的话术校验、默认值与替换规则。 */
record DanmakuTemplate(String label, String defaultText, Set<String> fields) {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}]*)}");
    private static final Pattern BLANK = Pattern.compile("[\\p{IsWhite_Space}\\x1C-\\x1F\\uFEFF]*");

    DanmakuTemplate(String label, String defaultText, String... extraFields) {
        this(label, defaultText, viewerFields(extraFields));
    }

    private static Set<String> viewerFields(String[] extraFields) {
        var fields = new HashSet<>(Set.of(extraFields));
        fields.add("用户");
        fields.add("主播");
        return Set.copyOf(fields);
    }

    static boolean blank(String text) {
        return text == null || BLANK.matcher(text).matches();
    }

    String override(String text) {
        if (blank(text)) return "";
        if (text.contains("\n") || text.contains("\r")) throw new IllegalArgumentException(label + "话术只能写一行");
        Matcher tokens = PLACEHOLDER.matcher(text);
        while (tokens.find()) {
            String field = tokens.group(1);
            if (!fields.contains(field)) {
                throw new IllegalArgumentException(label + "不能使用占位符 " + tokens.group());
            }
        }
        return text;
    }

    String render(String override, String viewer, String streamer, String detail) {
        return render(override, field -> switch (field) {
            case "用户" -> viewer;
            case "主播" -> streamer;
            default -> detail;
        });
    }

    String render(String override, Function<String, String> fields) {
        String template = override == null || override.isEmpty() ? defaultText : override;
        return PLACEHOLDER.matcher(template).replaceAll(match -> Matcher.quoteReplacement(fields.apply(match.group(1))));
    }
}
