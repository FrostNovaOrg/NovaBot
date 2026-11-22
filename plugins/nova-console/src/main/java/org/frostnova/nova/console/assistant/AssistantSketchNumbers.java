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

/** 核对单个画图结果中的数值、日期和时刻。 */
final class AssistantSketchNumbers {
    private static final String HAN_DIGITS = "零一二三四五六七八九〇壹贰叁肆伍陆柒捌玖";
    private static final String HAN_UNITS = "两十百千万亿拾佰仟萬億";
    private static final String DATE_OR_TIME = "(?<![0-9])(?:[0-9]{4}[-/.][0-9]{1,2}[-/.][0-9]{1,2}"
            + "|[0-9]{4}年[0-9]{1,2}月[0-9]{1,2}日|[0-9]{1,2}[-/][0-9]{1,2}"
            + "|[0-9]{1,2}月[0-9]{1,2}日|[0-9]{1,2}:[0-9]{2}(?::[0-9]{2})?)(?![0-9])";
    private static final String ARABIC = "(?:[0-9]{1,3}(?:,[0-9]{3})+(?![0-9])|[0-9]+)(?:\\.[0-9]+)?|\\.[0-9]+";
    private static final Pattern TOKEN = Pattern.compile("(?<time>" + DATE_OR_TIME + ")"
            + "|(?<arabic>(?:(?<![0-9])[-+])?(?:" + ARABIC + ")(?:[eE][-+]?[0-9]+)?)(?<unit>[%‰十百千万亿]?)"
            + "|(?<han>(?:负)?(?:[百千]分之)?[" + HAN_DIGITS + HAN_UNITS + "]+(?:点[" + HAN_DIGITS + "]+)?)");
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
            if (tokens.group("time") != null) datesAndTimes.add(tokens.group());
            else {
                var value = readNumber(tokens);
                if (value != null) {
                    switch (unit(tokens)) {
                        case "%" -> percents.add(value);
                        case "‰" -> permilles.add(value);
                        default -> include(value);
                    }
                }
            }
        }
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
        if (token.group("time") != null) return datesAndTimes.contains(token.group());
        BigDecimal target = readNumber(token);
        if (target == null) return false;
        if (matches(values, target)) return true;
        return switch (unit(token)) {
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

    private static String unit(Matcher token) {
        String han = token.group("han");
        if (han == null) return token.group("unit");
        han = han.startsWith("负") ? han.substring(1) : han;
        if (han.startsWith("百分之")) return "%";
        if (han.startsWith("千分之")) return "‰";
        return "";
    }

    private static BigDecimal readNumber(Matcher token) {
        if (token.group().length() > 100) return null;
        try {
            String arabic = token.group("arabic");
            BigDecimal value;
            if (arabic == null) {
                value = hanNumber(token.group("han"), unit(token));
            } else {
                value = new BigDecimal(arabic.replace(",", ""));
                int scale = switch (token.group("unit")) {
                    case "十" -> 1;
                    case "百" -> 2;
                    case "千" -> 3;
                    case "万" -> 4;
                    case "亿" -> 8;
                    default -> 0;
                };
                value = value.scaleByPowerOfTen(scale);
            }
            return Math.abs((long) value.scale()) <= 308 ? value : null;
        } catch (IllegalArgumentException | ArithmeticException error) {
            return null;
        }
    }

    private static BigDecimal hanNumber(String han, String unit) {
        boolean negative = han.startsWith("负");
        if (negative) han = han.substring(1);
        if (!unit.isEmpty()) han = han.substring(3);
        han = han.replace('两', '二').replace('拾', '十').replace('佰', '百')
                .replace('仟', '千').replace('萬', '万').replace('億', '亿');
        String[] parts = han.split("点");
        BigDecimal result = hanInteger(parts[0]);
        if (parts.length == 2) {
            StringBuilder digits = new StringBuilder("0.");
            for (int i = 0; i < parts[1].length(); i++) digits.append(hanDigit(parts[1].charAt(i)));
            result = result.add(new BigDecimal(digits.toString()));
        }
        return negative ? result.negate() : result;
    }

    private static BigDecimal hanInteger(String text) {
        if (text.isEmpty()) return BigDecimal.ZERO;
        for (char unit : new char[]{'亿', '万', '千', '百', '十'}) {
            int split = text.lastIndexOf(unit);
            if (split >= 0) {
                BigDecimal left = split == 0 ? BigDecimal.ONE : hanInteger(text.substring(0, split));
                int scale = switch (unit) {
                    case '亿' -> 8;
                    case '万' -> 4;
                    case '千' -> 3;
                    case '百' -> 2;
                    default -> 1;
                };
                return left.movePointRight(scale).add(hanInteger(text.substring(split + 1)));
            }
        }
        StringBuilder digits = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) digits.append(hanDigit(text.charAt(i)));
        return new BigDecimal(digits.toString());
    }

    private static int hanDigit(char digit) {
        return HAN_DIGITS.indexOf(digit) % 10;
    }
}
