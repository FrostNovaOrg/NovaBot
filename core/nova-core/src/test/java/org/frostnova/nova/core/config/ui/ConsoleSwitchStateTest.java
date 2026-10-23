package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 开关关着时看得出是关着，开与关分得开
 * <p>
 * 抓的使用者故障：关着的开关看不出来——轨道底色是分隔线那一档，落在卡面、页底上都只有 1.2:1 左右，
 * 外圈描边要是去掉或调得太淡，关着的开关就只剩一颗白点，认不出那里有个开关、也认不出它是关着；
 * 或者开着与关着两种样子差不多，拨了一下看不出变了没有。
 * <p>
 * 量法：从 app.css 里现取 {@code .switch input}（关）与 {@code .switch input:checked}（开）两条规则的
 * 轨道底色与描边色，三套配色、浅色与两种深色下经 {@link PaletteCss} 算出页面上真取到的值，
 * 写成 {@code color-mix(in srgb, …)} 的照浏览器的算法混出来，再按 WCAG 的公式算对比度。门槛：
 * <ul>
 *   <li>关着时外圈描边与它落在的卡面（--surface）、页底（--ground）都要到 {@value #OFF_EDGE_MIN}:1——
 *       分隔线那一档只有 1.2:1 左右，到 2:1 才看得出那里有个开关的轮廓；</li>
 *   <li>开着与关着两种轨道底色之间要到 {@value #STATE_MIN}:1，即 WCAG 1.4.11 对界面部件状态的下限。</li>
 * </ul>
 */
@DisplayName("开关的开与关")
class ConsoleSwitchStateTest {
    private static final double OFF_EDGE_MIN = 2.0;
    private static final double STATE_MIN = 3.0;

    private static final Pattern RING = Pattern.compile("inset\\s+0\\s+0\\s+0\\s+1px\\s+(.+)");

    private static final Pattern MIX = Pattern.compile(
            "color-mix\\(\\s*in\\s+srgb\\s*,\\s*(.+?)\\s+([0-9.]+)%\\s*,\\s*(.+?)\\s*\\)");

    private static final Pattern VAR = Pattern.compile("var\\(--([a-z0-9-]+)\\)");

    private static String read(String name) throws IOException {
        return Files.readString(FrontendFixture.frontendDir().resolve(name), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("三套配色浅深下，关着的开关看得出轮廓，开与关分得开")
    void offSwitchIsVisibleAndDistinctFromOn() throws IOException {
        String css = read("app.css").replaceAll("(?s)/\\*.*?\\*/", "");
        Map<String, String> off = declarations(css, ".switch input");
        Map<String, String> on = declarations(css, ".switch input:checked");
        String offFill = require(off, "background", ".switch input");
        String offRing = ring(require(off, "box-shadow", ".switch input"));
        String onFill = on.getOrDefault("background", offFill);

        PaletteCss palette = new PaletteCss(read("product-ui.css"));
        List<String> bad = new ArrayList<>();
        List<String> read = new ArrayList<>();
        int measured = 0;
        for (String name : PaletteCss.PALETTES) {
            for (Map.Entry<String, PaletteCss.State> mode : PaletteCss.modes(name).entrySet()) {
                if (mode.getKey().equals("系统深色下手动浅色")) continue;
                Map<String, String> values = palette.resolved(mode.getValue());
                String where = name + " " + mode.getKey() + " ";
                double[] surface = color(values, "var(--surface)", null);
                double[] ground = color(values, "var(--ground)", null);
                double[] fill = color(values, offFill, surface);
                double[] edge = offRing == null ? fill : color(values, offRing, fill);
                double[] checked = color(values, onFill, surface);
                for (Map.Entry<String, double[]> under : Map.of("卡面", surface, "页底", ground).entrySet()) {
                    double ratio = PaletteCss.contrast(edge, under.getValue());
                    measured++;
                    String line = where + "关着的外圈压在" + under.getKey() + "上 " + String.format(Locale.ROOT, "%.2f", ratio);
                    read.add(line);
                    if (ratio < OFF_EDGE_MIN) bad.add(line + " < " + OFF_EDGE_MIN);
                }
                double ratio = PaletteCss.contrast(checked, fill);
                measured++;
                String line = where + "开着与关着的轨道 " + String.format(Locale.ROOT, "%.2f", ratio);
                read.add(line);
                if (ratio < STATE_MIN) bad.add(line + " < " + STATE_MIN);
            }
        }
        System.out.println("开关读数（关：底 " + offFill + "，外圈 " + offRing + "；开：底 " + onFill + "）：\n  "
                + String.join("\n  ", read));
        assertTrue(measured >= PaletteCss.PALETTES.size() * 3 * 3, "只量了 " + measured + " 个组合");
        assertTrue(bad.isEmpty(), "这些配色下关着的开关看不出来，或开与关分不开:\n  " + String.join("\n  ", bad));
    }

    /** 某条规则里写的声明；规则按行首原样找，找不到就红 */
    private static Map<String, String> declarations(String css, String selector) {
        Matcher m = Pattern.compile("(?m)^\\s*" + Pattern.quote(selector) + "\\s*\\{([^}]*)\\}").matcher(css);
        assertTrue(m.find(), "app.css 里找不到 " + selector + "，这一格什么也没量");
        Map<String, String> out = new LinkedHashMap<>();
        for (String declaration : m.group(1).split(";")) {
            int colon = declaration.indexOf(':');
            if (colon > 0) out.put(declaration.substring(0, colon).strip(), declaration.substring(colon + 1).strip());
        }
        return out;
    }

    private static String require(Map<String, String> declarations, String name, String selector) {
        String value = declarations.get(name);
        assertTrue(value != null, selector + " 里没有 " + name + "，量不出关着的样子");
        return value;
    }

    /** 描在轨道里面那一圈的颜色；不描时给 null，外缘就是轨道底色本身 */
    private static String ring(String shadow) {
        if (shadow.equals("none")) return null;
        Matcher m = RING.matcher(shadow);
        assertTrue(m.matches(), "认不出关着时的描边写法：" + shadow);
        return m.group(1).strip();
    }

    /**
     * 一个颜色值叠在 under 上之后的 r、g、b：认 var(--x)、#RRGGBB、rgba() 与 transparent，
     * 以及 color-mix(in srgb, A p%, B)——两边按 alpha 预乘后照比例混，与浏览器的算法相同
     */
    private static double[] color(Map<String, String> values, String text, double[] under) {
        double[] rgba = rgba(values, text);
        if (rgba[3] >= 1 || under == null) {
            assertTrue(rgba[3] >= 1, "最底一层是半透明的：" + text);
            return new double[]{rgba[0], rgba[1], rgba[2]};
        }
        double a = rgba[3];
        return new double[]{rgba[0] * a + under[0] * (1 - a), rgba[1] * a + under[1] * (1 - a),
                rgba[2] * a + under[2] * (1 - a)};
    }

    private static double[] rgba(Map<String, String> values, String text) {
        String value = text.strip();
        Matcher var = VAR.matcher(value);
        if (var.matches()) {
            String got = values.get("--" + var.group(1));
            assertTrue(got != null, "色表里取不到 --" + var.group(1));
            return rgba(values, got);
        }
        if (value.equals("transparent")) return new double[]{0, 0, 0, 0};
        Matcher mix = MIX.matcher(value);
        if (mix.matches()) {
            double p = Double.parseDouble(mix.group(2)) / 100;
            double[] a = rgba(values, mix.group(1));
            double[] b = rgba(values, mix.group(3));
            double alpha = a[3] * p + b[3] * (1 - p);
            if (alpha == 0) return new double[]{0, 0, 0, 0};
            double[] out = new double[4];
            for (int i = 0; i < 3; i++) out[i] = (a[i] * a[3] * p + b[i] * b[3] * (1 - p)) / alpha;
            out[3] = alpha;
            return out;
        }
        Matcher hex = Pattern.compile("#([0-9a-fA-F]{6})").matcher(value);
        if (hex.matches()) {
            int v = Integer.parseInt(hex.group(1), 16);
            return new double[]{(v >> 16) & 0xFF, (v >> 8) & 0xFF, v & 0xFF, 1};
        }
        Matcher rgba = Pattern.compile("rgba\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*([0-9.]+)\\s*\\)").matcher(value);
        if (rgba.matches()) {
            return new double[]{Double.parseDouble(rgba.group(1)), Double.parseDouble(rgba.group(2)),
                    Double.parseDouble(rgba.group(3)), Double.parseDouble(rgba.group(4))};
        }
        throw new IllegalStateException("认不出的颜色写法：" + value);
    }
}
