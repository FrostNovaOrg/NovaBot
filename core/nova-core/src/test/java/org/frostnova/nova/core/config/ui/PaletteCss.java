package org.frostnova.nova.core.config.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 按浏览器的算法，从 product-ui.css 算出某套配色、某种明暗下每个变量最后取到的值
 * <p>
 * 色表不是「一块一套值」的平铺写法：配色块只写 {@code --l-x}／{@code --d-x}，
 * {@code --x} 由取用那一块按明暗开关挑一个（见 product-ui.css 文件头）。逐块抄值去量的话，
 * 量到的是「写了什么」而不是「页面上用的是什么」——取用那一块写错一行，逐块量照样全绿。
 * 所以这里照浏览器那样走一遍：挑出在这个根元素上生效的规则，按权重与先后叠起来，再展开 {@code var()}。
 * <p>
 * 只认色表里用得到的那几种选择器（{@code :root}、带 {@code data-theme}／{@code data-palette} 的属性、
 * {@code :not(...)}）与 {@code @media (prefers-color-scheme:dark)}；不挂在 {@code :root} 上的规则管不到根元素，跳过。
 * 挂在 {@code :root} 上却认不出的写法直接抛：
 * 色表改了结构而这里还按老结构算，比报错更糟。
 */
final class PaletteCss {
    /** 三套配色，与 theme.js、报告图配色的 id 一致；第一套是没选过时的默认 */
    static final List<String> PALETTES = List.of("pink-blue", "classic", "warm");

    /** 没选过配色时落到的那一套 */
    static final String DEFAULT_PALETTE = "pink-blue";

    /**
     * 根元素的一种状态
     * @param palette data-palette 的值，null 表示没设
     * @param theme data-theme 的值，null 表示跟随系统
     * @param systemDark 系统是不是深色
     */
    record State(String palette, String theme, boolean systemDark) {
    }

    /** 量对比度时用的四种明暗：浅色、跟随系统的深色、手动深色，以及系统深色下手动选浅色 */
    static Map<String, State> modes(String palette) {
        Map<String, State> out = new LinkedHashMap<>();
        out.put("浅色", new State(palette, null, false));
        out.put("跟随系统的深色", new State(palette, null, true));
        out.put("手动深色", new State(palette, "dark", false));
        out.put("系统深色下手动浅色", new State(palette, "light", true));
        return out;
    }

    private record Rule(String selector, boolean darkMedia, int order, Map<String, String> declarations) {
    }

    private static final Pattern DECLARATION = Pattern.compile(
            "(--[a-z0-9-]+|[a-z-]+)\\s*:\\s*((?:url\\([^)]*\\)|[^;])*);");

    private static final Pattern ATTRIBUTE = Pattern.compile("\\[(data-[a-z]+)=\"([^\"]*)\"\\]");

    private final List<Rule> rules = new ArrayList<>();

    PaletteCss(String css) {
        parse(css.replaceAll("(?s)/\\*.*?\\*/", ""), false);
    }

    private void parse(String css, boolean darkMedia) {
        int at = 0;
        while (true) {
            int open = css.indexOf('{', at);
            if (open < 0) {
                if (!css.substring(at).isBlank()) {
                    throw new IllegalStateException("色表结尾有认不出的东西: " + css.substring(at).strip());
                }
                return;
            }
            String head = css.substring(at, open).strip();
            int close = matching(css, open);
            String body = css.substring(open + 1, close).strip();
            // 块里最后一条声明可以不写分号
            if (!head.startsWith("@") && !body.isEmpty() && !body.endsWith(";")) body += ";";
            if (head.startsWith("@media")) {
                if (darkMedia || !head.replace(" ", "").equals("@media(prefers-color-scheme:dark)")) {
                    throw new IllegalStateException("色表里有认不出的媒体查询: " + head);
                }
                parse(body, true);
            } else {
                Map<String, String> declarations = new LinkedHashMap<>();
                Matcher m = DECLARATION.matcher(body);
                int last = 0;
                while (m.find()) {
                    if (!body.substring(last, m.start()).isBlank()) {
                        throw new IllegalStateException(head + " 里有认不出的声明: " + body.substring(last, m.start()).strip());
                    }
                    declarations.put(m.group(1), m.group(2).strip());
                    last = m.end();
                }
                if (!body.substring(last).isBlank()) {
                    throw new IllegalStateException(head + " 里有没写分号或认不出的声明: " + body.substring(last).strip());
                }
                for (String selector : head.split(",")) {
                    rules.add(new Rule(selector.strip(), darkMedia, rules.size(), declarations));
                }
            }
            at = close + 1;
        }
    }

    private static int matching(String css, int open) {
        int depth = 0;
        for (int i = open; i < css.length(); i++) {
            if (css.charAt(i) == '{') depth++;
            else if (css.charAt(i) == '}' && --depth == 0) return i;
        }
        throw new IllegalStateException("色表里大括号没配对");
    }

    /** 这条选择器在这个状态的根元素上生不生效 */
    private static boolean matches(String selector, State state) {
        // 不挂在 :root 上的规则（设置页的色样）管不到根元素，算根元素的值时不算它
        if (!selector.startsWith(":root")) return false;
        String rest = selector.substring(":root".length());
        while (!rest.isEmpty()) {
            boolean negated = rest.startsWith(":not(");
            String part = negated ? rest.substring(5) : rest;
            Matcher m = ATTRIBUTE.matcher(part);
            if (!m.lookingAt()) {
                throw new IllegalStateException("色表里有认不出的选择器: " + selector);
            }
            String actual = switch (m.group(1)) {
                case "data-theme" -> state.theme();
                case "data-palette" -> state.palette();
                default -> throw new IllegalStateException("色表里有认不出的属性: " + selector);
            };
            boolean hit = m.group(2).equals(actual);
            if (hit == negated) return false;
            rest = part.substring(m.end());
            if (negated) {
                if (!rest.startsWith(")")) throw new IllegalStateException("色表里有认不出的选择器: " + selector);
                rest = rest.substring(1);
            }
        }
        return true;
    }

    private static int specificity(String selector) {
        // 全是一个伪类加若干属性：每个属性一档，:not() 按括号里的算
        return 1 + (int) selector.chars().filter(c -> c == '[').count();
    }

    /**
     * 这个状态下根元素上声明的原值（var() 还没展开），按权重与先后叠好
     */
    Map<String, String> declared(State state) {
        List<Rule> live = new ArrayList<>();
        for (Rule rule : rules) {
            if (rule.darkMedia() && !state.systemDark()) continue;
            if (matches(rule.selector(), state)) live.add(rule);
        }
        live.sort((a, b) -> specificity(a.selector()) != specificity(b.selector())
                ? Integer.compare(specificity(a.selector()), specificity(b.selector()))
                : Integer.compare(a.order(), b.order()));
        Map<String, String> out = new LinkedHashMap<>();
        for (Rule rule : live) out.putAll(rule.declarations());
        return out;
    }

    /**
     * 这个状态下每个对外的变量最后取到的值：var() 展开、空白收成一个
     * <p>
     * 只收组件会引的那些（{@code --l-}／{@code --d-} 的原料与明暗开关不收）。取不到值的变量也收，值为 null——
     * 浏览器里它就是没定义，组件引它会落回默认色。
     */
    Map<String, String> resolved(State state) {
        Map<String, String> declared = declared(state);
        Map<String, String> out = new LinkedHashMap<>();
        for (String name : declared.keySet()) {
            if (!name.startsWith("--") || name.startsWith("--l-") || name.startsWith("--d-")
                    || name.equals("--light-on") || name.equals("--dark-on")) continue;
            out.put(name, value(declared, name, 0));
        }
        return out;
    }

    /** 某个属性展开后的值；null 表示无效（没定义、写的是 initial、或引用链断了） */
    static String value(Map<String, String> declared, String name, int depth) {
        if (depth > 32) throw new IllegalStateException("变量循环引用: " + name);
        String raw = declared.get(name);
        if (raw == null || raw.equals("initial")) return null;
        String out = substitute(declared, raw, depth);
        return out == null ? null : out.replaceAll("\\s+", " ").strip();
    }

    /** 展开一段值里的每个 var()；有一个展开不出又没有后备值，整段无效 */
    private static String substitute(Map<String, String> declared, String text, int depth) {
        StringBuilder b = new StringBuilder();
        int at = 0;
        while (true) {
            int start = text.indexOf("var(", at);
            if (start < 0) {
                b.append(text.substring(at));
                return b.toString();
            }
            b.append(text, at, start);
            int depthParen = 0;
            int end = -1;
            for (int i = start + 3; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '(') depthParen++;
                else if (c == ')' && --depthParen == 0) {
                    end = i;
                    break;
                }
            }
            if (end < 0) throw new IllegalStateException("var() 没收口: " + text);
            String inner = text.substring(start + 4, end);
            int comma = inner.indexOf(',');
            String ref = (comma < 0 ? inner : inner.substring(0, comma)).strip();
            String got = value(declared, ref, depth + 1);
            if (got == null && comma >= 0) {
                got = substitute(declared, inner.substring(comma + 1), depth + 1);
            }
            if (got == null) return null;
            b.append(got);
            at = end + 1;
        }
    }

    /** #RRGGBB 或 rgba() 叠在 under 上之后的 r、g、b（0-255） */
    static double[] rgb(String value, double[] under) {
        Matcher hex = Pattern.compile("#([0-9a-fA-F]{6})").matcher(value.strip());
        if (hex.matches()) {
            int v = Integer.parseInt(hex.group(1), 16);
            return new double[]{(v >> 16) & 0xFF, (v >> 8) & 0xFF, v & 0xFF};
        }
        Matcher rgba = Pattern.compile(
                "rgba\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*([0-9.]+)\\s*\\)").matcher(value.strip());
        if (rgba.matches()) {
            double a = Double.parseDouble(rgba.group(4));
            double[] out = new double[3];
            for (int i = 0; i < 3; i++) {
                out[i] = Math.round(Double.parseDouble(rgba.group(i + 1)) * a + under[i] * (1 - a));
            }
            return out;
        }
        throw new IllegalStateException("不是 #RRGGBB 或 rgba(): " + value);
    }

    /** WCAG 对比度 */
    static double contrast(double[] a, double[] b) {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static double luminance(double[] c) {
        return 0.2126 * linear(c[0]) + 0.7152 * linear(c[1]) + 0.0722 * linear(c[2]);
    }

    private static double linear(double v) {
        double s = v / 255;
        return s <= 0.04045 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
    }

    /** CIEDE2000 色差：两种状态色并排时分不分得开 */
    static double deltaE2000(double[] c1, double[] c2) {
        double[] p = lab(c1);
        double[] q = lab(c2);
        double l1 = p[0], a1 = p[1], b1 = p[2], l2 = q[0], a2 = q[1], b2 = q[2];
        double cm = (Math.hypot(a1, b1) + Math.hypot(a2, b2)) / 2;
        double g = 0.5 * (1 - Math.sqrt(Math.pow(cm, 7) / (Math.pow(cm, 7) + Math.pow(25, 7))));
        double a1p = a1 * (1 + g), a2p = a2 * (1 + g);
        double c1p = Math.hypot(a1p, b1), c2p = Math.hypot(a2p, b2);
        double h1p = (Math.toDegrees(Math.atan2(b1, a1p)) + 360) % 360;
        double h2p = (Math.toDegrees(Math.atan2(b2, a2p)) + 360) % 360;
        double dl = l2 - l1, dc = c2p - c1p;
        double dh = h2p - h1p;
        if (c1p * c2p == 0) dh = 0;
        else if (dh > 180) dh -= 360;
        else if (dh < -180) dh += 360;
        double dH = 2 * Math.sqrt(c1p * c2p) * Math.sin(Math.toRadians(dh / 2));
        double lm = (l1 + l2) / 2, cmp = (c1p + c2p) / 2;
        double hm;
        if (c1p * c2p == 0) hm = h1p + h2p;
        else if (Math.abs(h1p - h2p) <= 180) hm = (h1p + h2p) / 2;
        else if (h1p + h2p < 360) hm = (h1p + h2p + 360) / 2;
        else hm = (h1p + h2p - 360) / 2;
        double t = 1 - 0.17 * Math.cos(Math.toRadians(hm - 30)) + 0.24 * Math.cos(Math.toRadians(2 * hm))
                + 0.32 * Math.cos(Math.toRadians(3 * hm + 6)) - 0.20 * Math.cos(Math.toRadians(4 * hm - 63));
        double dtheta = 30 * Math.exp(-Math.pow((hm - 275) / 25, 2));
        double rc = 2 * Math.sqrt(Math.pow(cmp, 7) / (Math.pow(cmp, 7) + Math.pow(25, 7)));
        double sl = 1 + 0.015 * Math.pow(lm - 50, 2) / Math.sqrt(20 + Math.pow(lm - 50, 2));
        double sc = 1 + 0.045 * cmp;
        double sh = 1 + 0.015 * cmp * t;
        double rt = -Math.sin(Math.toRadians(2 * dtheta)) * rc;
        return Math.sqrt(Math.pow(dl / sl, 2) + Math.pow(dc / sc, 2) + Math.pow(dH / sh, 2)
                + rt * (dc / sc) * (dH / sh));
    }

    private static double[] lab(double[] c) {
        double r = linear(c[0]), g = linear(c[1]), b = linear(c[2]);
        double x = (0.4124 * r + 0.3576 * g + 0.1805 * b) / 0.95047;
        double y = 0.2126 * r + 0.7152 * g + 0.0722 * b;
        double z = (0.0193 * r + 0.1192 * g + 0.9505 * b) / 1.08883;
        double fx = f(x), fy = f(y), fz = f(z);
        return new double[]{116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz)};
    }

    private static double f(double t) {
        return t > 216.0 / 24389 ? Math.cbrt(t) : (24389.0 / 27 * t + 16) / 116;
    }
}
