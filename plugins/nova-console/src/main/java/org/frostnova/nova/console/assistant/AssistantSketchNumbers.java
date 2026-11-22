package org.frostnova.nova.console.assistant;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.util.HashSet;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 核对单个画图结果中用阿拉伯数字写的数值、日期和时刻。 */
final class AssistantSketchNumbers {
    private static final String DATE_OR_TIME = "(?<![0-9])(?:[0-9]{4}[-/.][0-9]{1,2}[-/.][0-9]{1,2}"
            + "|[0-9]{4}年[0-9]{1,2}月[0-9]{1,2}日|[0-9]{1,2}-[0-9]{1,2}"
            + "|[0-9]{1,2}月[0-9]{1,2}日|(?<clock>[0-9]{1,2}:[0-9]{2}(?::[0-9]{2})?))(?![0-9])";
    private static final String ARABIC = "(?:[0-9]{1,3}(?:,[0-9]{3})+(?![0-9])|[0-9]+)(?:\\.[0-9]+)?|\\.[0-9]+";
    private static final Pattern TOKEN = Pattern.compile("(?<time>" + DATE_OR_TIME + ")"
            + "|(?<arabic>(?:(?<![0-9])[-+])?(?:" + ARABIC + ")(?:[eE][-+]?[0-9]+)?)(?<unit>[%‰十百千万亿]?)");
    private static final Pattern ALIAS = Pattern.compile("观众-[0-9a-fA-F]{6}");
    private final NavigableSet<BigDecimal> values = new TreeSet<>();
    private final NavigableSet<BigDecimal> percents = new TreeSet<>();
    private final NavigableSet<BigDecimal> permilles = new TreeSet<>();
    private final Set<String> datesAndTimes = new HashSet<>();

    void include(BigDecimal value) {
        values.add(value);
        if (value.abs().compareTo(BigDecimal.ONE) <= 0) {
            percents.add(value.movePointRight(2));
            permilles.add(value.movePointRight(3));
        }
    }

    void includeText(String text) {
        var tokens = tokens(text);
        while (tokens.find()) {
            if (tokens.group("time") != null) datesAndTimes.add(dateOrTimeKey(tokens));
            else {
                var value = readNumber(tokens);
                if (value != null) {
                    switch (tokens.group("unit")) {
                        case "%" -> percents.add(value);
                        case "‰" -> permilles.add(value);
                        default -> include(value);
                    }
                }
            }
        }
    }

    static boolean isNumberOrTime(String text) {
        return TOKEN.matcher(text.strip()).matches();
    }

    void verify(String text) {
        var tokens = tokens(text);
        while (tokens.find()) {
            if (!covered(tokens)) {
                String token = AssistantChartRequest.clean(tokens.group(), 40);
                throw new AssistantChartRequest.Invalid("手绘数字 " + token + " 在引用的查数结果里对不上");
            }
        }
    }

    private boolean covered(Matcher token) {
        if (token.group("time") != null) return datesAndTimes.contains(dateOrTimeKey(token));
        BigDecimal target = readNumber(token);
        if (target == null) return false;
        if (matches(values, target)) return true;
        return switch (token.group("unit")) {
            case "%" -> matches(percents, target);
            case "‰" -> matches(permilles, target);
            default -> false;
        };
    }

    private static boolean matches(NavigableSet<BigDecimal> known, BigDecimal target) {
        if (known.contains(target)) return true;
        // 四舍五入是单调的，只需检查这个数左右最近的两个结果。
        int scale = Math.max(0, target.scale());
        return roundsTo(known.floor(target), target, scale) || roundsTo(known.ceiling(target), target, scale);
    }

    private static boolean roundsTo(BigDecimal value, BigDecimal target, int scale) {
        return value != null && value.setScale(scale, RoundingMode.HALF_UP).compareTo(target) == 0;
    }

    private static Matcher tokens(String text) {
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).replace('−', '-');
        StringBuilder plain = new StringBuilder(normalized.length());
        normalized.codePoints().forEach(cp -> {
            int digit = Character.digit(cp, 10);
            if (digit >= 0) plain.append((char) ('0' + digit));
            else plain.appendCodePoint(cp);
        });
        return TOKEN.matcher(ALIAS.matcher(plain).replaceAll(" "));
    }

    private static String dateOrTimeKey(Matcher token) {
        String time = token.group("time");
        return token.group("clock") != null && time.startsWith("0") && time.charAt(1) != ':'
                ? time.substring(1) : time;
    }

    private static BigDecimal readNumber(Matcher token) {
        if (token.group().length() > 100) return null;
        try {
            BigDecimal value = new BigDecimal(token.group("arabic").replace(",", ""));
            int scale = switch (token.group("unit")) {
                case "十" -> 1;
                case "百" -> 2;
                case "千" -> 3;
                case "万" -> 4;
                case "亿" -> 8;
                default -> 0;
            };
            value = value.scaleByPowerOfTen(scale);
            return Math.abs((long) value.scale()) <= 308 ? value : null;
        } catch (IllegalArgumentException | ArithmeticException error) {
            return null;
        }
    }
}
