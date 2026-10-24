package org.frostnova.nova.console.assistant;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.regex.Pattern;

/** 只核对本问已发送的工具数据；代号中的十六进制数字不属于事实陈述。 */
final class AssistantNumbers {
    private static final Pattern ALIAS = Pattern.compile("观众-[0-9a-fA-F]{6}");
    private static final Pattern NUMBER = Pattern.compile("[-+]?(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d+)?(?:[eE][-+]?\\d+)?([%％]?)");
    private static final Pattern DATE_TIME = Pattern.compile(
            "(?<![0-9])(?:[0-9]{4}[-/][0-9]{1,2}[-/][0-9]{1,2}|[0-9]{1,2}[-/][0-9]{1,2}"
                    + "|[0-9]{1,2}月[0-9]{1,2}日|[0-9]{1,2}:[0-9]{2}(?::[0-9]{2})?)(?![0-9])");
    private final List<BigDecimal> known = new ArrayList<>();

    void include(Object value) {
        if (value instanceof Number number) {
            try { known.add(new BigDecimal(number.toString())); } catch (NumberFormatException ignored) { }
        } else if (value instanceof Map<?, ?> map) map.values().forEach(this::include);
        else if (value instanceof Collection<?> list) list.forEach(this::include);
    }

    List<String> unverified(String answer) {
        var result = new LinkedHashSet<String>();
        var matcher = NUMBER.matcher(DATE_TIME.matcher(ALIAS.matcher(answer).replaceAll("")).replaceAll(" "));
        while (matcher.find()) {
            String token = matcher.group();
            boolean percent = !matcher.group(1).isEmpty();
            BigDecimal number = new BigDecimal(token.replace(",", "").replace("%", "").replace("％", ""));
            if (!percent && number.scale() <= 0 && number.compareTo(BigDecimal.ONE) >= 0 && number.compareTo(BigDecimal.TEN) <= 0) continue;
            boolean found = known.stream().anyMatch(value -> equal(number, value)
                    || percent && value.abs().compareTo(BigDecimal.ONE) <= 0 && equal(number, value.movePointRight(2)));
            if (!found) result.add(token);
        }
        return List.copyOf(result);
    }

    private static boolean equal(BigDecimal a, BigDecimal b) {
        return a.compareTo(b) == 0 || a.compareTo(b.setScale(Math.max(0, a.scale()), RoundingMode.HALF_UP)) == 0;
    }
}
