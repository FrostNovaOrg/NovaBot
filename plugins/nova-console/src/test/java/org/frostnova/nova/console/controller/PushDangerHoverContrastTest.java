package org.frostnova.nova.console.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 推送页的危险按钮（「删除主播」「移除这个通道」），鼠标放上去时字照样看得清
 * <p>
 * 抓的故障：推送页自己的样式在悬停时把字色改成了错误色，而这颗按钮的底色本来就是错误色
 * （它带着 ghost 与 danger 两个类，宿主的 button.danger 写在 button.ghost 之后，底色取 danger 的），
 * 于是鼠标一放上去，字和底同色，按钮上什么字都看不见。
 * <p>
 * 量法照宿主那一份对比度尺：三套配色各浅深两份从宿主的 product-ui.css 里取（按类路径读，不写模块目录），
 * 不悬停时的字色、底色取宿主的 {@code button.danger}，再按先宿主、后推送页的顺序叠上
 * 所有 {@code .danger:hover} 规则里写到的字色与底色，最后按 WCAG 公式算，三套配色的浅深都要到 4.5:1。
 * <p>
 * 色表的结构见 product-ui.css 文件头：配色块里浅色写 {@code --l-x}、深色写 {@code --d-x}，
 * {@code --x} 由开头那块按明暗开关挑一个。这里直接读配色块的两份原料，
 * 并核对开头那块确实是按这个写法挑的——挑法改了而这里还照旧读，量的就不是页面上的颜色了。
 */
@DisplayName("推送页危险按钮悬停时的字")
class PushDangerHoverContrastTest {
    private static final double MIN = 4.5;

    private static final Path PUSH = Path.of("src", "main", "resources", "config-ui-pages", "push.js");

    private static final Pattern HEX = Pattern.compile("#([0-9a-fA-F]{6})");

    private static final Pattern COLOR = Pattern.compile("(?<![-\\w])color\\s*:\\s*var\\(--([a-z0-9-]+)\\)");

    private static final Pattern BACKGROUND = Pattern.compile(
            "(?<![-\\w])background(?:-color)?\\s*:\\s*var\\(--([a-z0-9-]+)\\)\\s*[;}]");

    private static final Pattern HOVER_RULE = Pattern.compile("(?m)^\\s*([^{}\\n]*\\.danger:hover[^{}\\n]*)\\{([^}]*)\\}");

    @Test
    @DisplayName("三套配色的浅色、暗色下，悬停时危险按钮的字到 4.5:1")
    void dangerButtonTextStaysReadableOnHover() throws IOException {
        String app = hostCss("app.css");
        String push = Files.readString(PUSH, StandardCharsets.UTF_8);

        Matcher base = Pattern.compile("(?m)^\\s*button\\.danger\\s*\\{([^}]*)\\}").matcher(app);
        assertTrue(base.find(), "宿主的 app.css 里找不到 button.danger");
        String fg = first(COLOR, base.group(1));
        String bg = first(BACKGROUND, base.group(1) + ";");
        assertNotNull(fg, "button.danger 没有 color:var(--x)");
        assertNotNull(bg, "button.danger 没有 background:var(--x)");

        List<String> rules = new ArrayList<>();
        for (String css : List.of(app, push)) {
            Matcher hover = HOVER_RULE.matcher(css);
            while (hover.find()) {
                rules.add(hover.group(1).trim());
                String color = first(COLOR, hover.group(2));
                String back = first(BACKGROUND, hover.group(2) + ";");
                if (color != null) {
                    fg = color;
                }
                if (back != null) {
                    bg = back;
                }
            }
        }

        String palette = hostCss("product-ui.css");
        for (String name : List.of(fg, bg)) {
            assertTrue(palette.contains("--" + name + ":var(--dark-on,var(--l-" + name + ")) var(--light-on,var(--d-" + name + "));"),
                    "product-ui.css 里 --" + name + " 不是按明暗开关从 --l-／--d- 两份里挑的，这里的读法对不上了");
        }
        Map<String, Map<String, String>> themes = themes(palette);
        assertEquals(6, themes.size(), "三套配色的浅深没找全：" + themes.keySet());
        List<String> read = new ArrayList<>();
        List<String> bad = new ArrayList<>();
        for (Map.Entry<String, Map<String, String>> theme : themes.entrySet()) {
            double ratio = ratio(color(theme.getValue(), fg), color(theme.getValue(), bg));
            String line = theme.getKey() + " 悬停时 --" + fg + " 压在 --" + bg + " 上 "
                    + String.format(Locale.ROOT, "%.3f", ratio);
            read.add(line);
            if (ratio < MIN) {
                bad.add(line);
            }
        }
        System.out.println("推送页危险按钮悬停（叠过的规则 " + rules + "）：\n  " + String.join("\n  ", read));
        assertTrue(bad.isEmpty(), "这些不到 " + MIN + ":1:\n" + String.join("\n", bad));
    }

    private static String hostCss(String name) throws IOException {
        try (InputStream in = PushDangerHoverContrastTest.class.getResourceAsStream("/config-ui/" + name)) {
            assertNotNull(in, "类路径上没有宿主的 config-ui/" + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String first(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    /** 配色块的选择器：默认那一套挂在 :root 上，其余挂在 :root[data-palette="…"] 上（后面可能还跟着色样的选择器） */
    private static final Pattern PALETTE_BLOCK = Pattern.compile(
            "(?m)^:root(?:\\[data-palette=\"([a-z-]+)\"\\])?(?:,[^{\\n]*)?\\{([^}]*)\\}");

    private static final Pattern RAW = Pattern.compile("--([ld])-([a-z0-9-]+)\\s*:\\s*([^;]+);");

    /** 每套配色的浅色、深色两份：从配色块的 --l-x、--d-x 读出 x 的值 */
    private static Map<String, Map<String, String>> themes(String text) {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        Matcher block = PALETTE_BLOCK.matcher(text.replaceAll("(?s)/\\*.*?\\*/", ""));
        while (block.find()) {
            if (!block.group(2).contains("--l-")) {
                continue;
            }
            String name = block.group(1) == null ? "默认（粉蓝）" : block.group(1);
            Map<String, String> light = new LinkedHashMap<>();
            Map<String, String> dark = new LinkedHashMap<>();
            Matcher m = RAW.matcher(block.group(2));
            while (m.find()) {
                (m.group(1).equals("l") ? light : dark).put(m.group(2), m.group(3).trim());
            }
            out.put(name + " 浅色", light);
            out.put(name + " 深色", dark);
        }
        return out;
    }

    private static double[] color(Map<String, String> theme, String name) {
        String value = theme.get(name);
        assertNotNull(value, "色表里没有 --" + name);
        Matcher hex = HEX.matcher(value);
        assertTrue(hex.matches(), "--" + name + " 不是 #RRGGBB：" + value);
        int v = Integer.parseInt(hex.group(1), 16);
        return new double[]{(v >> 16) & 0xFF, (v >> 8) & 0xFF, v & 0xFF};
    }

    private static double ratio(double[] fg, double[] bg) {
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
