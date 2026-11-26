package org.frostnova.nova.interact.auto;

import com.alibaba.fastjson2.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public record KeywordRule(boolean enabled, List<String> keywords, boolean exact, List<String> replies,
                          boolean random, int cooldownSeconds) {
    static final String FEATURE = "keywordReply";
    static final int PRIORITY = 3;
    static final DanmakuTemplate TEXT = new DanmakuTemplate("关键词回复", "");
    private static final Pattern EDGES = Pattern.compile("^[\\p{IsWhite_Space}\\x1C-\\x1F\\uFEFF]+|[\\p{IsWhite_Space}\\x1C-\\x1F\\uFEFF]+$");

    public KeywordRule {
        keywords = keywords.stream().map(KeywordRule::trim).toList();
        checkLines(keywords, "关键词", 20);
        replies = List.copyOf(replies);
        checkLines(replies, "回复", 60);
        replies.forEach(TEXT::override);
        if (cooldownSeconds < 10 || cooldownSeconds > 3600) throw new IllegalArgumentException("冷却应为 10 至 3600 秒");
    }

    private static void checkLines(List<String> lines, String label, int max) {
        if (lines.isEmpty() || lines.size() > 5) throw new IllegalArgumentException(label + "应有 1 至 5 项");
        for (String text : lines) {
            if (DanmakuTemplate.blank(text) || text.codePointCount(0, text.length()) > max) {
                throw new IllegalArgumentException("每项" + label + "应有 1 至 " + max + " 个字");
            }
            if (text.contains("\n") || text.contains("\r")) throw new IllegalArgumentException(label + "只能写一行");
        }
    }

    static String trim(String text) {
        return EDGES.matcher(text).replaceAll("");
    }

    boolean matches(String lowerText) {
        for (String keyword : keywords) {
            String word = keyword.toLowerCase(Locale.ROOT);
            if (exact ? lowerText.equals(word) : lowerText.contains(word)) return true;
        }
        return false;
    }

    static List<KeywordRule> parse(JSONObject room) {
        if (!room.containsKey("keywordRules")) return List.of();
        if (!(room.get("keywordRules") instanceof List<?> rows)) throw new IllegalArgumentException("关键词规则应为列表");
        if (rows.size() > 30) throw new IllegalArgumentException("关键词规则第 31 条超出上限，每位主播最多 30 条");
        var rules = new ArrayList<KeywordRule>();
        for (Object item : rows) {
            if (item instanceof KeywordRule saved) {
                rules.add(saved);
                continue;
            }
            try {
                if (!(item instanceof Map)) throw new IllegalArgumentException("请填写关键词和回复");
                JSONObject row = JSONObject.from(item);
                rules.add(new KeywordRule(RoomSettings.flag(row, "enabled", true), lines(row, "keywords", "关键词"),
                        RoomSettings.flag(row, "exact", false), lines(row, "replies", "回复"),
                        RoomSettings.flag(row, "random", false), RoomSettings.number(row, "cooldownSeconds", 60)));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("关键词规则第 " + (rules.size() + 1) + " 条：" + e.getMessage(), e);
            }
        }
        return List.copyOf(rules);
    }

    private static List<String> lines(JSONObject row, String key, String label) {
        if (!(row.get(key) instanceof List<?> list) || !list.stream().allMatch(String.class::isInstance)) {
            throw new IllegalArgumentException(label + "应为文字列表");
        }
        return list.stream().map(String.class::cast).toList();
    }
}
