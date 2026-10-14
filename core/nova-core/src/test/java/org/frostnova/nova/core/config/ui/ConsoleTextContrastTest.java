package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 控制台与登录页上状态字、错误字、危险按钮字的对比度
 * <p>
 * 抓的故障：有人改了色表，浅色或暗色主题下「重启生效」「立即生效」这类状态字、
 * 登录错误字、危险按钮上的字、淡底上的选中项与辅助字又看不清了（不到 WCAG 的 4.5:1）；
 * 日志页上今天没有条目的分类小标签，字看不清。
 * <p>
 * 读 product-ui.css 与 login.html 的三块色表（浅色、跟随系统的暗色、手动暗色），字色与底色
 * 都从组件规则里现取 {@code color:var(--x)}、{@code background:var(--y)}，
 * 半透明底按 alpha 叠在下面那层上再算。规则改引了别的色，量的也跟着是新引的那个。
 * 规则里写了 opacity 的算量不出：它把字和底一起淡掉，色表里的字色已不是画出来的那个。
 */
@DisplayName("控制台文字对比度")
class ConsoleTextContrastTest {
    private static final double MIN = 4.5;

    private static final Path UI = Path.of("src/main/resources/config-ui");

    /**
     * 一处要量的字
     * @param what 界面上是哪一处
     * @param selector 画它的那条规则，按行首原样找
     * @param under 规则自己的底下面那几层，从最底一层往上；规则没写底时就是字直接落的底
     */
    private record Spot(String what, String selector, List<String> under) {
    }

    private static final List<Spot> APP = List.of(
            new Spot("设置项「立即生效」", ".badge.now", List.of("surface")),
            new Spot("设置项「重启生效」", ".badge.restart", List.of("surface")),
            new Spot("组标题上「本组设置修改后均需重启生效」", ".pill.warn", List.of("surface", "soft")),
            new Spot("改动过的设置行里的绿色药丸", ".pill.ok", List.of("surface", "soft")),
            new Spot("危险按钮", "button.danger", List.of("surface")),
            new Spot("确认框的危险按钮", ".ask-ok", List.of("surface")),
            new Spot("设置分类选中项", ".grpnav .cur", List.of("ground")),
            new Spot("主题选中项", ".themechoice .pill.cur", List.of("surface")),
            new Spot("日志筛选选中项", ".lgpill[aria-pressed=\"true\"]", List.of("surface")),
            new Spot("日志页今天没有条目的分类小标签", ".lgpill.dim", List.of("surface")),
            new Spot("连接页「前往设置」", ".lc-note .lc-go", List.of("ground")),
            new Spot("错误行的日志辅助字", ".tl-hd .tl-x", List.of("surface", "errsoft")),
            new Spot("警告行的日志辅助字", ".tl-hd .tl-x", List.of("surface", "warnsoft")),
            new Spot("错误行的「在工程日志里看」", ".tl-go", List.of("surface", "errsoft")),
            new Spot("警告行的「在工程日志里看」", ".tl-go", List.of("surface", "warnsoft")),
            new Spot("顶部分页的选中项", "#page-tabs button.on", List.of("surface")),
            new Spot("侧栏「有新版」按钮", ".nv-pill.acc", List.of("ground")),
            new Spot("首次设置向导已完成的步骤徽章", ".su-badge.done", List.of("surface")),
            new Spot("首次设置向导要留意的步骤徽章", ".su-badge.warn", List.of("surface")));

    private static final List<Spot> LOGIN = List.of(
            new Spot("登录错误字", ".msg", List.of("surface")));

    private static final Pattern TOKEN = Pattern.compile("--([a-z0-9-]+)\\s*:\\s*([^;]+);");

    private static final Pattern HEX = Pattern.compile("#([0-9a-fA-F]{6})");

    private static final Pattern RGBA = Pattern.compile(
            "rgba\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*([0-9.]+)\\s*\\)");

    private static final Pattern COLOR = Pattern.compile("(?<![-\\w])color\\s*:\\s*var\\(--([a-z0-9-]+)\\)");

    private static final Pattern OPACITY = Pattern.compile("(?<![-\\w])opacity\\s*:");

    private static final Pattern BACKGROUND = Pattern.compile(
            "(?<![-\\w])background\\s*:\\s*var\\(--([a-z0-9-]+)\\)\\s*[;}]");

    @Test
    @DisplayName("浅色、暗色两套主题下，状态字、错误字、危险按钮字与淡底上的字都到 4.5:1")
    void textStaysReadableInBothThemes() throws IOException {
        List<String> bad = new ArrayList<>();
        List<String> read = new ArrayList<>();
        int measured = measure("app.css", "product-ui.css", APP, bad, read)
                + measure("login.html", "login.html", LOGIN, bad, read);

        System.out.println("对比度读数：\n  " + String.join("\n  ", read));
        assertTrue(measured >= (APP.size() + LOGIN.size()) * 3,
                "该量的组合没量全，只量了 " + measured + " 个:\n" + String.join("\n", bad));
        assertTrue(bad.isEmpty(), "这些字不到 " + MIN + ":1:\n" + String.join("\n", bad));
    }

    private static int measure(String file, String paletteFile, List<Spot> spots,
                               List<String> bad, List<String> read) throws IOException {
        String text = Files.readString(UI.resolve(file), StandardCharsets.UTF_8);
        Map<String, Map<String, String>> themes = themes(paletteFile, Files.readString(UI.resolve(paletteFile), StandardCharsets.UTF_8), bad);
        int measured = 0;
        for (Spot spot : spots) {
            String body = rule(text, spot.selector());
            if (body == null) {
                bad.add(file + " 找不到规则 " + spot.selector() + "（" + spot.what() + "）");
                continue;
            }
            if (OPACITY.matcher(body).find()) {
                bad.add(file + " " + spot.selector() + " 用 opacity 变淡，字和底一起淡掉，量不出字色（" + spot.what() + "）");
                continue;
            }
            Matcher fg = COLOR.matcher(body);
            if (!fg.find()) {
                bad.add(file + " " + spot.selector() + " 没有 color:var(--x)（" + spot.what() + "）");
                continue;
            }
            List<String> layers = new ArrayList<>(spot.under());
            Matcher bg = BACKGROUND.matcher(body + ";");
            if (bg.find()) {
                layers.add(bg.group(1));
            }
            for (Map.Entry<String, Map<String, String>> theme : themes.entrySet()) {
                String where = file + " " + theme.getKey() + " " + spot.what() + "（" + spot.selector() + "）："
                        + "--" + fg.group(1) + " 叠在 " + String.join(" + ", layers.stream().map(l -> "--" + l).toList()) + " 上";
                try {
                    double ratio = ratio(color(theme.getValue(), fg.group(1)), backdrop(theme.getValue(), layers));
                    measured++;
                    String line = where + " " + String.format(Locale.ROOT, "%.3f", ratio);
                    read.add(line);
                    if (ratio < MIN) {
                        bad.add(line);
                    }
                } catch (IllegalStateException e) {
                    bad.add(where + " 量不出：" + e.getMessage());
                }
            }
        }
        return measured;
    }

    /**
     * 三块色表。暗色两块只重定义一部分时，没写的那几样照浅色那块继承
     */
    private static Map<String, Map<String, String>> themes(String file, String text, List<String> bad) {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        int lightAt = text.indexOf(":root{");
        int media = text.indexOf("@media (prefers-color-scheme:dark)");
        int systemAt = media < 0 ? -1 : text.indexOf(":root:not([data-theme=\"light\"]){", media);
        int manualAt = text.indexOf(":root[data-theme=\"dark\"]{");
        if (lightAt < 0 || systemAt < 0 || manualAt < 0) {
            bad.add(file + " 三块色表没找全：浅色 " + lightAt + "，跟随系统的暗色 " + systemAt + "，手动暗色 " + manualAt);
        }
        Map<String, String> light = tokens(block(text, lightAt));
        Map<String, String> systemDark = new LinkedHashMap<>(light);
        systemDark.putAll(tokens(block(text, systemAt)));
        Map<String, String> manualDark = new LinkedHashMap<>(light);
        manualDark.putAll(tokens(block(text, manualAt)));
        out.put("浅色", light);
        out.put("跟随系统的暗色", systemDark);
        out.put("手动暗色", manualDark);
        return out;
    }

    private static String block(String text, int at) {
        if (at < 0) {
            return "";
        }
        int open = text.indexOf('{', at);
        int close = text.indexOf('}', open);
        return text.substring(open + 1, close);
    }

    private static Map<String, String> tokens(String block) {
        Map<String, String> out = new LinkedHashMap<>();
        // 去掉注释：注释里的「--paper 永远是白的」不是定义
        Matcher m = TOKEN.matcher(block.replaceAll("(?s)/\\*.*?\\*/", ""));
        while (m.find()) {
            out.put(m.group(1), m.group(2).trim());
        }
        return out;
    }

    private static String rule(String text, String selector) {
        Matcher m = Pattern.compile("(?m)^\\s*" + Pattern.quote(selector) + "\\s*\\{([^}]*)\\}").matcher(text);
        return m.find() ? m.group(1) : null;
    }

    /** r、g、b 为 0-255，a 为 0-1 */
    private static double[] color(Map<String, String> theme, String name) {
        String value = theme.get(name);
        if (value == null) {
            throw new IllegalStateException("色表里没有 --" + name);
        }
        Matcher hex = HEX.matcher(value);
        if (hex.matches()) {
            int v = Integer.parseInt(hex.group(1), 16);
            return new double[]{(v >> 16) & 0xFF, (v >> 8) & 0xFF, v & 0xFF, 1};
        }
        Matcher rgba = RGBA.matcher(value);
        if (rgba.matches()) {
            return new double[]{Double.parseDouble(rgba.group(1)), Double.parseDouble(rgba.group(2)),
                    Double.parseDouble(rgba.group(3)), Double.parseDouble(rgba.group(4))};
        }
        throw new IllegalStateException("--" + name + " 不是 #RRGGBB 或 rgba()：" + value);
    }

    /** 从最底一层往上叠：半透明的那层按 alpha 盖在下面已叠好的颜色上 */
    private static double[] backdrop(Map<String, String> theme, List<String> layers) {
        double[] out = null;
        for (String layer : layers) {
            double[] c = color(theme, layer);
            if (out == null) {
                if (c[3] < 1) {
                    throw new IllegalStateException("最底一层 --" + layer + " 是半透明的，下面没有东西可叠");
                }
                out = c;
            } else {
                out = over(c, out);
            }
        }
        return out;
    }

    private static double[] over(double[] top, double[] below) {
        double a = top[3];
        return new double[]{top[0] * a + below[0] * (1 - a), top[1] * a + below[1] * (1 - a),
                top[2] * a + below[2] * (1 - a), 1};
    }

    private static double ratio(double[] fg, double[] bg) {
        if (fg[3] < 1) {
            fg = over(fg, bg);
        }
        double a = luminance(fg);
        double b = luminance(bg);
        return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
    }

    private static double luminance(double[] c) {
        return 0.2126 * channel(c[0]) + 0.7152 * channel(c[1]) + 0.0722 * channel(c[2]);
    }

    private static double channel(double v) {
        double s = v / 255;
        return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
    }
}
