package org.frostnova.nova.console.assistant;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

/** 只核对本问已发送的工具数据；代号中的十六进制数字不属于事实陈述。 */
final class AssistantNumbers {
    private static final Pattern ALIAS = Pattern.compile("观众-[0-9a-fA-F]{6}");
    private static final Pattern NUMBER = Pattern.compile("(?:(?<![0-9])[-+])?(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d+)?(?:[eE][-+]?\\d+)?([%％]?)");
    private static final Pattern DATE_TIME = Pattern.compile(
            "(?<![0-9])(?:[0-9]{4}[-/.][0-9]{1,2}[-/.][0-9]{1,2}"
                    + "|[0-9]{4}年(?:[0-9]{1,2}月(?:[0-9]{1,2}日)?)?|[0-9]{1,2}[-/][0-9]{1,2}"
                    + "|[0-9]{1,2}月[0-9]{1,2}日|[0-9]{1,2}:[0-9]{2}(?::[0-9]{2})?)(?![0-9])");
    private static final Pattern PERCENT_CONTEXT = Pattern.compile(
            "([。.,，；;！？!?\\r\\n])"
                    + "|(下降|降低|降幅|减少|下滑|回落|下跌|少了|缩水|跌|降)"
                    + "|增长|增加|增幅|上升|提升|上涨|涨幅|多了|涨");
    private static final Pattern DURATION_UNIT = Pattern.compile("\\h*(?:(个?(?:小时|钟头))|分钟)");
    private static final BigDecimal SECONDS_PER_HOUR = BigDecimal.valueOf(3600);
    private static final BigDecimal SECONDS_PER_MINUTE = BigDecimal.valueOf(60);
    private final List<BigDecimal> known = new ArrayList<>();
    private final List<BigDecimal> knownSeconds = new ArrayList<>();

    void include(Object value) {
        include(null, value);
    }

    private void include(Object field, Object value) {
        if (value instanceof Number number) {
            try {
                var decimal = new BigDecimal(number.toString());
                known.add(decimal);
                if (field instanceof String name && (name.endsWith("Seconds") || name.endsWith("_seconds")
                        || name.equalsIgnoreCase("seconds"))) knownSeconds.add(decimal);
            } catch (NumberFormatException ignored) { }
        } else if (value instanceof Map<?, ?> map) {
            List<?> columns = map.get("columns") instanceof List<?> names ? names : List.of();
            map.forEach((key, child) -> {
                if ("rows".equals(key) && child instanceof Collection<?> rows) {
                    rows.forEach(row -> includeRow(columns, row));
                } else if ("totals".equals(key)) {
                    includeRow(columns, child);
                } else {
                    include(key, child);
                }
            });
        } else if (value instanceof Collection<?> list) {
            list.forEach(this::include);
        }
    }

    private void includeRow(List<?> columns, Object value) {
        if (value instanceof List<?> row) {
            for (int i = 0; i < row.size(); i++) {
                include(i < columns.size() ? columns.get(i) : null, row.get(i));
            }
        } else {
            include(value);
        }
    }

    List<String> unverified(String answer) {
        var result = new LinkedHashSet<String>();
        String text = DATE_TIME.matcher(withoutAliases(answer)).replaceAll(" ");
        var matcher = NUMBER.matcher(text);
        var context = PERCENT_CONTEXT.matcher(text);
        int direction = 0;
        int previousEnd = 0;
        while (matcher.find()) {
            // 只扫描数字之间的文字，数字内的千位逗号和小数点不划分句子。
            context.region(previousEnd, matcher.start());
            while (context.find()) {
                if (context.group(1) != null) direction = 0;
                else direction = context.group(2) != null ? -1 : 1;
            }
            previousEnd = matcher.end();

            String token = matcher.group();
            boolean percent = !matcher.group(1).isEmpty();
            BigDecimal number = new BigDecimal(token.replace(",", "").replace("%", "").replace("％", ""));
            if (!percent && number.scale() <= 0 && number.compareTo(BigDecimal.ONE) >= 0 && number.compareTo(BigDecimal.TEN) <= 0) continue;
            int expectedDirection = direction;
            boolean signed = token.startsWith("-") || token.startsWith("+");
            boolean found = percent
                    ? known.stream().anyMatch(value -> matchesPercent(number, value, signed, expectedDirection))
                    : known.stream().anyMatch(value -> equal(number, value))
                            || matchesDuration(number, text, matcher.end());
            if (!found) result.add(token);
        }
        return List.copyOf(result);
    }

    static List<String> allTokens(String answer) {
        return NUMBER.matcher(withoutAliases(answer)).results().map(MatchResult::group).toList();
    }

    private static String withoutAliases(String answer) {
        return ALIAS.matcher(answer).replaceAll("");
    }

    private static boolean matchesPercent(BigDecimal number, BigDecimal value, boolean signed, int direction) {
        if (!signed && direction != 0 && value.signum() != 0 && value.signum() != direction) return false;
        BigDecimal candidate = signed ? value : value.abs();
        return equal(number, candidate)
                || (value.abs().compareTo(BigDecimal.ONE) <= 0 && equal(number, candidate.movePointRight(2)));
    }

    private boolean matchesDuration(BigDecimal number, String text, int numberEnd) {
        var unit = DURATION_UNIT.matcher(text).region(numberEnd, text.length());
        if (!unit.lookingAt()) return false;
        BigDecimal divisor = unit.group(1) != null ? SECONDS_PER_HOUR : SECONDS_PER_MINUTE;
        int scale = Math.max(0, number.scale());
        return knownSeconds.stream().anyMatch(seconds ->
                number.compareTo(seconds.divide(divisor, scale, RoundingMode.HALF_UP)) == 0);
    }

    private static boolean equal(BigDecimal a, BigDecimal b) {
        return a.compareTo(b) == 0 || a.compareTo(b.setScale(Math.max(0, a.scale()), RoundingMode.HALF_UP)) == 0;
    }
}
