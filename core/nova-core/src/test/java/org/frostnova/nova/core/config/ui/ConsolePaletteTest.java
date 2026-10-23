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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 控制台的三套配色：经典、粉蓝、暖阳，各带浅色与深色
 * <p>
 * 抓的使用者故障，一类一格：
 * <ul>
 *   <li>没选过配色的人（包括老用户升级后）打开控制台或登录页，看到的不是粉蓝；</li>
 *   <li>选了「经典」，颜色和升级前不一样；</li>
 *   <li>登录页和控制台的颜色对不上；</li>
 *   <li>某套配色下正文、边框看不清，或者状态色分不开；</li>
 *   <li>换了配色，下拉框的箭头还是原来的颜色；</li>
 *   <li>深色值写了两份，改了一份另一份没跟上，跟随系统的深色与手动深色颜色不一样。</li>
 * </ul>
 * 值一律经 {@link PaletteCss} 按浏览器的算法现算：量的是页面上真取到的那个值，不是色表里写的那一行。
 * 选了配色之后刷新、换页还在不在，由 {@link PaletteSwitchFrontendTest} 真跑脚本去量。
 */
@DisplayName("控制台三套配色")
class ConsolePaletteTest {

    private static String read(String name) throws IOException {
        return Files.readString(FrontendFixture.frontendDir().resolve(name), StandardCharsets.UTF_8);
    }

    private static PaletteCss palette() throws IOException {
        return new PaletteCss(read("product-ui.css"));
    }

    /** 定稿底色的变量名，与 BASE 里每串值一一对应。写在 BASE 前面：静态字段按先后初始化 */
    private static final List<String> BASE_NAMES = List.of(
            "ground", "surface", "line", "text", "dim", "accent", "accent2", "ok", "warn", "err", "live",
            "cloud3", "c-danmu", "c-gift", "c-sc", "c-blind", "c-guard", "c-view",
            "on-accent", "on-err", "accent-text", "on-status", "faint");

    /**
     * 定稿的底色：粉蓝与暖阳。派生色（淡底、阴影、遮罩、箭头）由 {@link #derive} 照同一算法算
     * <p>
     * 暖阳浅色的警告色比原定的深一点（#7E6500 → #7A6100）：设置页组标题上的「本组设置修改后均需重启生效」
     * 压在两层淡底上，原定那一档只到 4.41:1（见 ConsoleTextContrastTest）
     */
    private static final Map<String, Map<String, Map<String, String>>> BASE = Map.of(
            "pink-blue", Map.of(
                    "light", base("#F8F5FA #FFFFFF #ECE4EE #241B30 #685E76 #CC3775 #1E88D2 #137148 #8A5A00 #C42A16 #E0337E"
                            + " #F29BBF #1E88D2 #CC3775 #E39A1B #1FA866 #7B5BE0 #736A80 #FFFFFF #FFFFFF #B42A66 #FFFFFF #776D85"),
                    "dark", base("#121417 #1A1C21 #2C3038 #E8EAEE #A3A9B4 #FF80B4 #5DB8F5 #3FD490 #F5C04F #FF7A5C #FF6FA9"
                            + " #FFB3D2 #5DB8F5 #FF80B4 #F5C04F #3FD490 #A58CFF #9C99B5 #121417 #121417 #FF80B4 #121417 #8C929E")),
            "warm", Map.of(
                    "light", base("#FAF6F1 #FFFFFF #EDE3D8 #2B2119 #6C5F53 #B4520E #C8742C #1C7040 #7A6100 #C01B3C #A6379E"
                            + " #F2B07A #C75F14 #C0447E #D9A21B #3C9A4F #2B8C9E #7A6E63 #FFFFFF #FFFFFF #A3480A #FFFFFF #796B5E"),
                    "dark", base("#1A1511 #241E18 #3A3128 #F3ECE4 #BDB0A2 #FF9E57 #FFC48A #56CF84 #EFD35A #FF6470 #DE80DC"
                            + " #FFC79E #FF9E57 #F27BAE #EFD35A #56CF84 #55C2D6 #A89C8F #1A1511 #1A1511 #FF9E57 #1A1511 #9C8F82")));

    private static Map<String, String> base(String values) {
        String[] v = values.split(" ");
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < BASE_NAMES.size(); i++) out.put("--" + BASE_NAMES.get(i), v[i]);
        return out;
    }

    private static String rgba(String hex, String alpha) {
        int v = Integer.parseInt(hex.substring(1), 16);
        return "rgba(" + ((v >> 16) & 0xFF) + "," + ((v >> 8) & 0xFF) + "," + (v & 0xFF) + "," + alpha + ")";
    }

    /** 由底色算出淡底、阴影、遮罩与下拉箭头，与经典那一套当初的算法相同 */
    private static Map<String, String> derive(Map<String, String> base, boolean light) {
        Map<String, String> v = new LinkedHashMap<>(base);
        v.put("--soft", rgba(base.get("--accent"), light ? ".08" : ".14"));
        v.put("--softline", rgba(base.get("--accent"), light ? ".28" : ".38"));
        v.put("--oksoft", rgba(base.get("--ok"), light ? ".10" : ".16"));
        v.put("--warnsoft", rgba(base.get("--warn"), light ? ".10" : ".16"));
        v.put("--errsoft", rgba(base.get("--err"), light ? ".08" : ".16"));
        if (light) {
            v.put("--shadow", "0 1px 2px " + rgba(base.get("--text"), ".04") + ", 0 8px 24px " + rgba(base.get("--text"), ".06"));
            v.put("--overlay", rgba(base.get("--text"), ".32"));
        } else {
            v.put("--shadow", "0 0 0 1px rgba(255,255,255,.05)");
            int g = Integer.parseInt(base.get("--ground").substring(1), 16);
            v.put("--overlay", "rgba(" + Math.round(((g >> 16) & 0xFF) * .55) + "," + Math.round(((g >> 8) & 0xFF) * .55)
                    + "," + Math.round((g & 0xFF) * .55) + ",.58)");
        }
        v.put("--select-chevron", "url(\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='12' height='8' "
                + "viewBox='0 0 12 8'%3E%3Cpath d='M2 2l4 4 4-4' fill='none' stroke='%23" + base.get("--dim").substring(1)
                + "' stroke-width='1.5' stroke-linecap='round' stroke-linejoin='round'/%3E%3C/svg%3E\")");
        return v;
    }

    private static boolean isLight(PaletteCss.State state) {
        return state.theme() == null ? !state.systemDark() : state.theme().equals("light");
    }

    /**
     * 没选过配色（根元素上没有 data-palette，或者是认不出的旧值）：浅深都是粉蓝
     */
    @Test
    @DisplayName("没选过配色时，浅色深色都是粉蓝")
    void unchosenPaletteIsPinkBlue() throws IOException {
        PaletteCss css = palette();
        List<String> bad = new ArrayList<>();
        for (String unset : new String[]{null, "", "no-such-palette"}) {
            for (Map.Entry<String, PaletteCss.State> mode : PaletteCss.modes(unset).entrySet()) {
                Map<String, String> got = css.resolved(mode.getValue());
                Map<String, String> want = derive(BASE.get("pink-blue").get(isLight(mode.getValue()) ? "light" : "dark"),
                        isLight(mode.getValue()));
                want.forEach((name, value) -> {
                    if (!value.equals(got.get(name))) {
                        bad.add("data-palette=" + unset + " " + mode.getKey() + " " + name + " 取到 " + got.get(name)
                                + "，粉蓝是 " + value);
                    }
                });
            }
        }
        assertTrue(bad.isEmpty(), "没选过配色的人看到的不是粉蓝:\n  " + String.join("\n  ", bad));
    }

    /**
     * 粉蓝、暖阳选上之后，每个变量都是定稿那一份：底色照抄，派生色照同一算法
     */
    @Test
    @DisplayName("选了粉蓝或暖阳，取值与定稿相同")
    void chosenPalettesMatchTheirBaseColours() throws IOException {
        PaletteCss css = palette();
        List<String> bad = new ArrayList<>();
        int compared = 0;
        for (String name : List.of("pink-blue", "warm")) {
            for (Map.Entry<String, PaletteCss.State> mode : PaletteCss.modes(name).entrySet()) {
                boolean light = isLight(mode.getValue());
                Map<String, String> got = css.resolved(mode.getValue());
                for (Map.Entry<String, String> want : derive(BASE.get(name).get(light ? "light" : "dark"), light).entrySet()) {
                    compared++;
                    if (!want.getValue().equals(got.get(want.getKey()))) {
                        bad.add(name + " " + mode.getKey() + " " + want.getKey() + " 取到 " + got.get(want.getKey())
                                + "，定稿是 " + want.getValue());
                    }
                }
            }
        }
        assertTrue(compared > 0, "一个值也没比");
        assertTrue(bad.isEmpty(), "配色取值与定稿不同:\n  " + String.join("\n  ", bad));
    }

    /**
     * 选「经典」：每个变量与改动前一样，浅深都一样
     * <p>
     * 改动前那一份冻在 {@code classic-palette-before.css}，同一把算法算两边再逐个比，
     * 两边的变量名单也得一样——少一个就是组件落回默认色，多一个说明经典被加了东西。
     */
    @Test
    @DisplayName("选了经典，每个变量的值与改动前相同")
    void classicIsUnchanged() throws IOException {
        PaletteCss before = new PaletteCss(Files.readString(FrontendFixture.repoRoot()
                .resolve(FrontendFixture.fixture("classic-palette-before.css")), StandardCharsets.UTF_8));
        PaletteCss now = palette();
        List<String> bad = new ArrayList<>();
        int compared = 0;
        for (Map.Entry<String, PaletteCss.State> mode : PaletteCss.modes("classic").entrySet()) {
            PaletteCss.State old = new PaletteCss.State(null, isLight(mode.getValue()) ? null : "dark", false);
            Map<String, String> want = before.resolved(old);
            Map<String, String> got = now.resolved(mode.getValue());
            for (Map.Entry<String, String> e : want.entrySet()) {
                compared++;
                if (e.getValue() == null || !e.getValue().equals(got.get(e.getKey()))) {
                    bad.add(mode.getKey() + " " + e.getKey() + " 现在是 " + got.get(e.getKey()) + "，改动前是 " + e.getValue());
                }
            }
            for (String name : got.keySet()) {
                if (!want.containsKey(name)) bad.add(mode.getKey() + " 多了改动前没有的 " + name);
            }
        }
        System.out.println("经典配色比了 " + compared + " 个值");
        assertTrue(compared >= 4 * 40, "经典配色只比了 " + compared + " 个值");
        assertTrue(bad.isEmpty(), "选了经典，颜色和升级前不一样:\n  " + String.join("\n  ", bad));
    }

    /**
     * 深色只定义一份：跟随系统的深色与手动深色两处只拨明暗开关，不写颜色
     * <p>
     * 两处各写一份值的话，改了一处忘了另一处，跟随系统的人与手动选深色的人看到的就不是同一套颜色。
     */
    @Test
    @DisplayName("深色值只写一份，两种深色取到的颜色相同")
    void darkValuesAreDefinedOnce() throws IOException {
        String css = read("product-ui.css").replaceAll("(?s)/\\*.*?\\*/", "");
        List<String> bad = new ArrayList<>();
        Matcher blocks = Pattern.compile(":root(?::not\\(\\[data-theme=\"light\"\\]\\)|\\[data-theme=\"(?:dark|light)\"\\])\\s*\\{([^}]*)\\}")
                .matcher(css);
        int seen = 0;
        while (blocks.find()) {
            seen++;
            for (String declaration : blocks.group(1).split(";")) {
                String name = declaration.contains(":") ? declaration.substring(0, declaration.indexOf(':')).strip() : "";
                if (name.isEmpty()) continue;
                if (!name.equals("color-scheme") && !name.equals("--light-on") && !name.equals("--dark-on")) {
                    bad.add(blocks.group().substring(0, blocks.group().indexOf('{')) + " 里写了 " + name);
                }
            }
        }
        assertEquals(3, seen, "跟随系统的深色、手动深色、手动浅色三块没找全");

        PaletteCss palette = palette();
        for (String name : PaletteCss.PALETTES) {
            Map<String, String> system = palette.resolved(PaletteCss.modes(name).get("跟随系统的深色"));
            Map<String, String> manual = palette.resolved(PaletteCss.modes(name).get("手动深色"));
            if (!system.equals(manual)) bad.add(name + " 跟随系统的深色与手动深色取到的值不同");
            Map<String, String> light = palette.resolved(PaletteCss.modes(name).get("浅色"));
            Map<String, String> forced = palette.resolved(PaletteCss.modes(name).get("系统深色下手动浅色"));
            if (!light.equals(forced)) bad.add(name + " 系统深色下手动选浅色，取到的不是浅色那一套");
            if (light.equals(system)) bad.add(name + " 浅色与深色取到的值一样，明暗开关没起作用");
        }
        assertTrue(bad.isEmpty(), "深色不止定义了一份，或明暗没切对:\n  " + String.join("\n  ", bad));
    }

    /**
     * 登录页和控制台用同一份配色：登录页不再自带色表，吐页时把 product-ui.css 原样拼进去
     */
    @Test
    @DisplayName("登录页用的是控制台那同一份配色")
    void loginPageUsesConsolePalette() throws IOException {
        String template = read("login.html");
        String style = template.substring(template.indexOf("<style>"), template.indexOf("</style>"))
                .replaceAll("(?s)/\\*.*?\\*/", "");
        Matcher own = Pattern.compile("--[a-z0-9-]+\\s*:|#[0-9a-fA-F]{3,8}\\b|rgba?\\(").matcher(style);
        List<String> found = new ArrayList<>();
        while (own.find()) found.add(own.group());
        assertTrue(found.isEmpty(), "登录页的样式里还自带色表或写死的颜色，与控制台会对不上: " + found);

        String page = ConfigUiLoginPage.html("");
        String product = read("product-ui.css");
        int at = page.indexOf(product);
        assertTrue(at >= 0, "吐出来的登录页里没有原样拼进 product-ui.css，登录页的颜色与控制台不是同一份");
        assertTrue(page.lastIndexOf("<style>", at) > page.lastIndexOf("</style>", at),
                "product-ui.css 拼进来了，却不在 <style> 里，不会生效");
    }

    // ---- 对比度与区分度 ----

    /**
     * (前景, 背景, 下限)。背景写成 "x/y" 表示 x 叠在 y 上
     * <p>
     * 统一样式里那几处也由这张表管着：单选框、勾选框的强调色与键盘焦点框是 --accent
     * （落在 --surface 上要 4.5、--ground 上要 3）。关着的开关那一圈由 {@link ConsoleSwitchStateTest} 量
     */
    private static final String[][] PAIRS = {
            {"text", "ground", "4.5"}, {"text", "surface", "4.5"},
            {"dim", "ground", "4.5"}, {"dim", "surface", "4.5"},
            {"faint", "surface", "4.5"},
            {"accent-text", "ground", "4.5"}, {"accent-text", "surface", "4.5"},
            {"accent-text", "soft/surface", "4.5"},
            {"accent", "surface", "4.5"},
            {"ok", "surface", "4.5"}, {"ok", "oksoft/surface", "4.5"},
            {"warn", "surface", "4.5"}, {"warn", "warnsoft/surface", "4.5"},
            {"err", "surface", "4.5"}, {"err", "errsoft/surface", "4.5"},
            {"on-accent", "accent", "4.5"}, {"on-err", "err", "4.5"},
            {"on-status", "ok", "4.5"}, {"on-status", "warn", "4.5"},
            {"trend-up", "surface", "4.5"}, {"trend-down", "surface", "4.5"},
            {"accent", "ground", "3"}, {"live", "surface", "3"}, {"accent2", "surface", "3"},
    };

    /** 并排出现时必须分得开的状态色 */
    private static final String[][] DISTINCT = {
            {"err", "accent"}, {"err", "live"}, {"err", "warn"}, {"err", "ok"}, {"warn", "ok"},
            {"live", "ok"}, {"live", "warn"}, {"trend-up", "trend-down"},
    };

    /** 涨跌色另要与「错误」「成功」分得开。经典保持升级前的样子（跌就是成功色），不在此列 */
    private static final String[][] TREND_DISTINCT = {
            {"trend-up", "err"}, {"trend-up", "ok"}, {"trend-down", "err"}, {"trend-down", "ok"},
    };

    private static final double DISTINCT_MIN = 20;

    /**
     * 经典深色下「错误」与「直播」两色的色差，升级前就是 16.9，不到 20
     * <p>
     * 经典必须与升级前一模一样（见 {@link #classicIsUnchanged}），这一对因此不能为了过线去改。
     * 只放这一对、只在经典的深色下放，并钉住它此刻的读数：再变差一点就红。
     */
    private static final double CLASSIC_DARK_ERR_LIVE = 16.9;

    private static boolean keptFromClassic(String palette, PaletteCss.State state, String[] pair) {
        return palette.equals("classic") && !isLight(state) && pair[0].equals("err") && pair[1].equals("live");
    }

    private static double[] layered(Map<String, String> values, String spec) {
        String[] layers = spec.split("/");
        double[] under = {255, 255, 255};
        for (int i = layers.length - 1; i >= 0; i--) {
            String value = values.get("--" + layers[i]);
            if (value == null) throw new IllegalStateException("取不到 --" + layers[i]);
            under = PaletteCss.rgb(value, under);
        }
        return under;
    }

    /**
     * 三套配色、浅深两种（跟随系统与手动都算），正文与边框看得清、状态色分得开
     */
    @Test
    @DisplayName("三套配色浅深都过对比度与区分度检查")
    void everyPaletteIsReadableAndDistinct() throws IOException {
        PaletteCss css = palette();
        List<String> bad = new ArrayList<>();
        List<String> read = new ArrayList<>();
        int measured = 0;
        for (String name : PaletteCss.PALETTES) {
            for (Map.Entry<String, PaletteCss.State> mode : PaletteCss.modes(name).entrySet()) {
                Map<String, String> values = css.resolved(mode.getValue());
                String where = name + " " + mode.getKey() + " ";
                for (String[] pair : PAIRS) {
                    double ratio = PaletteCss.contrast(layered(values, pair[0]), layered(values, pair[1]));
                    measured++;
                    String line = where + "--" + pair[0] + " 压在 --" + pair[1] + " 上 " + String.format(Locale.ROOT, "%.2f", ratio);
                    read.add(line);
                    if (ratio < Double.parseDouble(pair[2])) bad.add(line + " < " + pair[2]);
                }
                List<String[]> distinct = new ArrayList<>(List.of(DISTINCT));
                if (!name.equals("classic")) distinct.addAll(List.of(TREND_DISTINCT));
                for (String[] pair : distinct) {
                    double d = PaletteCss.deltaE2000(layered(values, pair[0]), layered(values, pair[1]));
                    measured++;
                    String line = where + "--" + pair[0] + " 与 --" + pair[1] + " 色差 " + String.format(Locale.ROOT, "%.1f", d);
                    read.add(line);
                    if (keptFromClassic(name, mode.getValue(), pair)) {
                        if (d < CLASSIC_DARK_ERR_LIVE - 0.05) bad.add(line + "，比升级前的 " + CLASSIC_DARK_ERR_LIVE + " 还差");
                    } else if (d < DISTINCT_MIN) {
                        bad.add(line + " < " + DISTINCT_MIN);
                    }
                }
            }
        }
        System.out.println("配色读数：\n  " + String.join("\n  ", read));
        assertTrue(measured >= 3 * 4 * (PAIRS.length + DISTINCT.length), "只量了 " + measured + " 个组合");
        assertTrue(bad.isEmpty(), "这些在某套配色下看不清或分不开:\n  " + String.join("\n  ", bad));
    }

    /**
     * 下拉框的箭头跟着配色与明暗变：箭头的颜色就是那一套的次要字色
     */
    @Test
    @DisplayName("换了配色或明暗，下拉框的箭头跟着变色")
    void selectChevronFollowsPalette() throws IOException {
        PaletteCss css = palette();
        List<String> bad = new ArrayList<>();
        Map<String, String> seen = new LinkedHashMap<>();
        for (String name : PaletteCss.PALETTES) {
            for (Map.Entry<String, PaletteCss.State> mode : PaletteCss.modes(name).entrySet()) {
                Map<String, String> values = css.resolved(mode.getValue());
                String chevron = values.get("--select-chevron");
                String dim = values.get("--dim");
                String where = name + " " + mode.getKey();
                if (chevron == null || dim == null) {
                    bad.add(where + " 取不到箭头或次要字色");
                    continue;
                }
                Matcher stroke = Pattern.compile("stroke='%23([0-9A-Fa-f]{6})'").matcher(chevron);
                if (!stroke.find()) {
                    bad.add(where + " 箭头里找不到描边色: " + chevron);
                } else if (!("#" + stroke.group(1)).equalsIgnoreCase(dim)) {
                    bad.add(where + " 箭头是 #" + stroke.group(1) + "，这一套的次要字色是 " + dim);
                }
                seen.put(where, chevron);
            }
        }
        assertFalse(seen.get("pink-blue 浅色").equals(seen.get("classic 浅色")), "粉蓝与经典的箭头一样");
        assertFalse(seen.get("warm 手动深色").equals(seen.get("classic 手动深色")), "暖阳与经典的深色箭头一样");
        assertTrue(bad.isEmpty(), "下拉框箭头没跟着配色变:\n  " + String.join("\n  ", bad));
    }
}
