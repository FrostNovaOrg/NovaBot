package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 控制台外观五件：去顶栏、退出挪侧栏底、品牌位居中、字号大一号、界面三钮左缩进
 * <p>
 * 只为说得出用户故障的格立测试，一格一种故障：
 * <ul>
 *   <li>窄屏横条里没有退出入口 → 手机上退不出登录；</li>
 *   <li>窄屏退出块与导航同排 → 手机上导航被挤得点不到；</li>
 *   <li>「界面」三钮与下方设置项左缩进不一致 → 按钮贴边；</li>
 *   <li>点目录的落点让出不该让的位 → 宽屏上组标题停偏；让位量写死 → 换台设备、改一下字号又不准。</li>
 * </ul>
 * 「顶栏没了」「字号变了」不立测试——改后恒绿，抓不到将来的故障。
 */
@DisplayName("控制台外观")
class ConsoleAppearanceTest {

    private static String read(String name) throws IOException {
        return Files.readString(FrontendFixture.frontendDir().resolve(name), StandardCharsets.UTF_8);
    }

    /**
     * 窄屏横条里退出入口看得见、点得到
     * <p>
     * 故障：手机（≤959px）上退不出登录。退出按钮若跟着 .verline／.runbox 一起被
     * {@code display:none} 藏掉，窄屏使用者就没有任何退出入口。
     */
    @Test
    @DisplayName("窄屏横条里退出入口不跟着侧栏附属块一起隐藏")
    void logoutButtonsSurviveNarrowScreen() throws IOException {
        String html = read("index.html");
        String css = read("app.css");

        // 退出按钮必须在侧栏内（不在已撤的 header 里）
        int sideStart = html.indexOf("<aside id=\"side\"");
        int sideEnd = html.indexOf("</aside>", sideStart);
        assertTrue(sideStart >= 0 && sideEnd > sideStart, "index.html 里找不到侧栏 <aside id=\"side\">");
        String side = html.substring(sideStart, sideEnd);
        assertTrue(side.contains("id=\"logout\""), "侧栏里没有「退出登录」按钮（id=\"logout\"）");
        assertTrue(side.contains("id=\"logout-all\""), "侧栏里没有「退出全部设备」按钮（id=\"logout-all\"）");

        // 窄屏媒体查询里不许把退出入口藏掉：只看 #auth-actions 自己那条规则
        String narrow = narrowMediaBlock(css);
        String authInNarrow = cssBlockIn(narrow, "#auth-actions");
        assertTrue(!authInNarrow.contains("display:none"),
                "窄屏样式把 #auth-actions 藏掉了，手机上退不出登录");
        // 退出按钮的容器全局也不能 display:none
        String authRule = cssBlock(css, "#auth-actions");
        assertTrue(!authRule.contains("display:none"),
                "#auth-actions 的样式里有 display:none，窄屏退不出登录");
        // 不许把退出块塞进窄屏会隐藏的块里（.verline／.runbox）
        for (String hiddenOnNarrow : new String[]{".verline", ".runbox"}) {
            String block = cssBlockIn(narrow, hiddenOnNarrow);
            if (block.contains("display:none")) {
                assertTrue(!isNestedInside(html, hiddenOnNarrow, "auth-actions"),
                        "退出块在 " + hiddenOnNarrow + " 里，窄屏被一起藏掉，手机上退不出登录");
            }
        }
    }

    /**
     * 「界面」三钮与下方设置项左缩进一致
     * <p>
     * 故障：设置「界面」组的三个按钮贴着卡片左边，与下方 .field 设置项的 16px 缩进不齐。
     */
    @Test
    @DisplayName("「界面」三钮左缩进与设置项一致")
    void themeChoiceIndentMatchesFields() throws IOException {
        String css = read("app.css");

        String field = cssBlock(css, ".field");
        String theme = cssBlock(css, ".themechoice");

        int fieldLeft = extractPaddingLeft(field);
        int themeLeft = extractPaddingLeft(theme);

        assertTrue(fieldLeft > 0, ".field 没有左内边距: " + field.strip());
        assertTrue(themeLeft == fieldLeft,
                ".themechoice 左缩进 " + themeLeft + "px 与 .field 的 " + fieldLeft + "px 不一致");
    }

    /**
     * 点目录后组标题停在该停的位置
     * <p>
     * 故障：点设置目录后组标题停的位置偏一段让位量。宽屏上没有任何吸顶的元素，
     * 让位量只要不是 0，落点就偏那么远；窄屏上顶栏与组目录都吸在视口顶上，
     * 要让的只能是它们<b>量出来</b>的高度——写死一个数，换台设备、改一下字号
     * 就又不准（旧顶栏那个 54px 正是这么来的）。所以这里只认三件事实——
     * 窄屏那道门之前的代码里没有减号、门之后的让位量必须量过 DOM、
     * 旧的页头高度不许复活（headerHeight 只能是常数 0、--head-h 不许是正数）。
     * 三件各盯一条会把落点弄偏的写法，其中一条变了这一格就红。
     */
    @Test
    @DisplayName("点目录的落点：宽屏不让位，窄屏只让量出来的高度")
    void scrollOffsetYieldsOnlyMeasuredStickyHeights() throws IOException {
        String css = read("app.css");
        String settings = read("settings.js");
        List<String> bad = new ArrayList<>();

        // 1) 宽屏那条路不许让位：窄屏那道门（matchMedia）之前的代码里出现减号，
        //    减掉的就只可能是让位量——宽屏上没有任何吸顶的元素
        //    （data-grp 这类字符串里的连字符不算，先把字符串与注释剥掉再看）
        int fnStart = settings.indexOf("function focusGroup");
        assertTrue(fnStart >= 0, "settings.js 里找不到 focusGroup，这一格此刻什么也没量");
        String focusBody = codeOnly(fnBody(settings, fnStart));
        assertTrue(focusBody.contains("scrollTo") && focusBody.contains("getBoundingClientRect().top"),
                "focusGroup 不再是「滚到组标题」那条路，这一格的射程要重定: " + focusBody.strip());
        int gate = focusBody.indexOf("matchMedia");
        assertTrue(gate >= 0, "focusGroup 里没有窄屏那道门（matchMedia），"
                + "窄屏上吸顶的顶栏与目录会把滚到的组标题盖住: " + focusBody.strip());
        if (focusBody.substring(0, gate).contains("-")) {
            bad.add("窄屏那道门之前就有减号，宽屏的落点会被让位量偏开: " + focusBody.substring(0, gate).strip());
        }

        // 2) 窄屏让的量必须是量出来的：门之后得量 DOM（offsetHeight）；
        //    落点里减号后面跟着数字即写死的让位量，一处都不许有
        String afterGate = focusBody.substring(gate);
        if (!afterGate.contains("offsetHeight")) {
            bad.add("窄屏的让位量没有量 DOM（offsetHeight），写死的数换个字号、换台设备就不准: " + afterGate.strip());
        }
        if (Pattern.compile("-\\s*\\d").matcher(focusBody).find()) {
            bad.add("落点里有写死的让位量（减号后面是数字），量出来的才跟得住会折行的顶栏: " + focusBody.strip());
        }

        // 3) 旧的页头高度不许复活：量 DOM、读令牌、返回正数都算让位量回来了
        int hStart = settings.indexOf("function headerHeight");
        if (hStart >= 0) {
            String hBody = codeOnly(fnBody(settings, hStart));
            for (String measured : new String[]{"--head-h", "getComputedStyle", "getPropertyValue", "getBoundingClientRect", "querySelector"}) {
                if (hBody.contains(measured)) {
                    bad.add("headerHeight() 还在量「" + measured + "」，顶栏已去不该再产出让位量: " + hBody.strip());
                }
            }
            if (Pattern.compile("return\\s+[1-9]").matcher(hBody).find()) {
                bad.add("headerHeight() 会返回正数，滚动定位就偏这么多: " + hBody.strip());
            }
        }

        // 4) 高度令牌不许再是正数：写回 54px 即红，读到它的每一处都跟着偏
        Matcher token = Pattern.compile("--head-h\\s*:\\s*(\\d+(?:\\.\\d+)?)px").matcher(css);
        while (token.find()) {
            if (Double.parseDouble(token.group(1)) != 0) {
                bad.add("--head-h 仍是 " + token.group(1) + "px，任何一处读它都会把定位偏开");
            }
        }

        assertTrue(bad.isEmpty(), "点目录的落点会让错位:\n  " + String.join("\n  ", bad));
    }

    /**
     * 窄屏退出块不与导航抢横条那一行
     * <p>
     * 故障：手机上导航被挤得点不到。退出钮与品牌、导航同排时，两枚按钮按内容宽
     * 占住右端，四条导航只剩几十像素宽，横滚也点不准。量法：按 390px 视口、
     * 从样式现读字号与内边距，估导航那一行还剩多宽，与各条导航的自然宽比。
     * 文字宽按「汉字＝字号、拉丁≈0.62×字号」上估——估宽不估窄，放得下才算放下。
     */
    @Test
    @DisplayName("窄屏 390 宽下导航不被退出块挤掉")
    void narrowExitRowDoesNotSqueezeNav() throws IOException {
        String html = read("index.html");
        String css = read("app.css");
        String narrow = narrowMediaBlock(css);
        assertTrue(!narrow.isBlank(), "app.css 里没有 ≤959px 那段窄屏样式，这一格此刻什么也没量");

        String side = require(cssBlockIn(narrow, "#side"), "窄屏 #side");
        String navNarrow = require(cssBlockIn(narrow, ".nav"), "窄屏 .nav");
        String navA = require(cssBlockIn(narrow, ".nav a"), "窄屏 .nav a");
        String brandNarrow = require(cssBlockIn(narrow, ".brand"), "窄屏 .brand");
        String auth = require(cssBlockIn(narrow, "#auth-actions"), "窄屏 #auth-actions");
        String authBtn = require(cssBlockIn(narrow, "#auth-actions button"), "窄屏 #auth-actions button");
        String brandBase = require(cssBlock(css, ".brand"), ".brand");
        String markBase = require(cssBlock(css, ".brand-mark"), ".brand-mark");
        String nameBase = require(cssBlock(css, ".brand-name"), ".brand-name");
        String navBase = require(cssBlock(css, ".nav"), ".nav");
        String navABase = require(cssBlock(css, ".nav a"), ".nav a");
        String authBase = require(cssBlock(css, "#auth-actions"), "#auth-actions");
        String authBtnBase = require(cssBlock(css, "#auth-actions button"), "#auth-actions button");

        int inner = 390 - pick(padSide(side, 3), 0) - pick(padSide(side, 1), 0);
        int colGap = pick(gap(side, true), gap(side, false));

        // 品牌位：图标 ＋ 与名字的间隙 ＋ 名字 ＋ 右内边距
        int brandW = requirePx(px(markBase, "width"), ".brand-mark 宽")
                + pick(gap(brandNarrow, true), gap(brandBase, true))
                + textW(brandName(html), requirePx(px(nameBase, "font-size"), ".brand-name 字号"))
                + pick(padSide(brandNarrow, 1), padSide(brandBase, 1));

        // 导航各条自然宽：字宽 ＋ 左右内边距，条与条之间一个 gap
        List<String> navLabels = navLabels(html);
        assertTrue(navLabels.size() >= 2, "index.html 里数不出导航条目，分母是空的");
        int navFs = pick(px(navA, "font-size"), requirePx(px(navABase, "font-size"), ".nav a 字号"));
        int navPad = pick(padSide(navA, 3), padSide(navABase, 3)) + pick(padSide(navA, 1), padSide(navABase, 1));
        int navGap = pick(gap(navNarrow, true), gap(navBase, true));
        int navW = (navLabels.size() - 1) * navGap;
        for (String label : navLabels) navW += textW(label, navFs) + navPad;

        // 退出两钮自然宽：字宽 ＋ 左右内边距 ＋ 边框
        List<String> authLabels = authLabels(html);
        assertTrue(authLabels.size() >= 1, "index.html 里数不出退出按钮，分母是空的");
        int authFs = pick(px(authBtn, "font-size"), requirePx(px(authBtnBase, "font-size"), "退出钮字号"));
        int authPad = pick(padSide(authBtn, 3), padSide(authBtnBase, 3)) + pick(padSide(authBtn, 1), padSide(authBtnBase, 1));
        int authGap = pick(gap(auth, true), gap(authBase, true));
        int border = borderPx(authBtnBase) * 2;
        int authW = Math.max(0, authLabels.size() - 1) * authGap;
        for (String label : authLabels) authW += textW(label, authFs) + authPad + border;

        boolean sameRow = !authOnOwnRow(side, auth);
        int navAvail = inner - brandW - colGap - (sameRow ? authW + colGap : 0);

        List<String> bad = new ArrayList<>();
        if (navW > navAvail) {
            bad.add("导航要 " + navW + "px，横条一行里只剩 " + navAvail + "px"
                    + (sameRow ? "（退出钮 " + authW + "px 同排抢位）" : "")
                    + "——手机上导航被挤得点不到");
        }
        if (authW > inner) {
            bad.add("两个退出钮 " + authW + "px 超出 390 宽可有的 " + inner + "px，放不下就滚出屏外");
        }
        assertTrue(bad.isEmpty(), "窄屏 390px 下横条放不下:\n  " + String.join("\n  ", bad));
    }

    /** 从函数声明处取函数体（左花括号起到配对的右花括号） */
    private static String fnBody(String js, int fnStart) {
        int open = js.indexOf('{', fnStart);
        if (open < 0) return "";
        int depth = 0;
        for (int i = open; i < js.length(); i++) {
            char c = js.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return js.substring(open, i + 1);
            }
        }
        return "";
    }

    /** 剥掉注释与字符串字面量，只留代码本体（字符串里的连字符不算减号） */
    private static String codeOnly(String js) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < js.length()) {
            char c = js.charAt(i);
            if (c == '/' && i + 1 < js.length() && js.charAt(i + 1) == '/') {
                while (i < js.length() && js.charAt(i) != '\n') i++;
            } else if (c == '/' && i + 1 < js.length() && js.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < js.length() && !(js.charAt(i) == '*' && js.charAt(i + 1) == '/')) i++;
                i = Math.min(i + 2, js.length());
            } else if (c == '\'' || c == '"' || c == '`') {
                i++;
                while (i < js.length() && js.charAt(i) != c) {
                    if (js.charAt(i) == '\\') i++;
                    i++;
                }
                i++;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** 取一段样式规则；取不到就红（空射程不判绿） */
    private static String require(String block, String what) {
        assertTrue(!block.isBlank(), "找不到「" + what + "」的样式规则，这一格此刻什么也没量");
        return block;
    }

    private static int requirePx(int value, String what) {
        assertTrue(value >= 0, "取不到「" + what + "」的像素值，这一格估不出宽度");
        return value;
    }

    /** 窄屏那条优先，没有就用全局那条 */
    private static int pick(int narrow, int base) {
        return narrow >= 0 ? narrow : base;
    }

    /** 取一段 CSS 里某属性的像素数；没有该属性返回 -1 */
    private static int px(String block, String prop) {
        Matcher m = Pattern.compile("(?<![\\w-])" + Pattern.quote(prop) + "\\s*:\\s*(\\d+(?:\\.\\d+)?)px").matcher(block);
        return m.find() ? (int) Math.round(Double.parseDouble(m.group(1))) : -1;
    }

    /** 边框宽（单边）；没有写死像素边框返回 0 */
    private static int borderPx(String block) {
        Matcher m = Pattern.compile("(?<![\\w-])border\\s*:\\s*(\\d+(?:\\.\\d+)?)px").matcher(block);
        return m.find() ? (int) Math.round(Double.parseDouble(m.group(1))) : 0;
    }

    /** padding 某一边的像素（0上 1右 2下 3左）：先看长写，再拆简写；取不到返回 -1 */
    private static int padSide(String block, int side) {
        String[] names = {"top", "right", "bottom", "left"};
        Matcher longhand = Pattern.compile("padding-" + names[side] + "\\s*:\\s*(\\d+(?:\\.\\d+)?)px").matcher(block);
        if (longhand.find()) return (int) Math.round(Double.parseDouble(longhand.group(1)));
        Matcher shorthand = Pattern.compile("(?<![\\w-])padding\\s*:\\s*([^;}]+)").matcher(block);
        if (!shorthand.find()) return -1;
        String[] parts = shorthand.group(1).trim().split("\\s+");
        if (parts.length == 1) return raw(parts[0]);
        if (parts.length == 2) return side % 2 == 1 ? raw(parts[1]) : raw(parts[0]);
        if (parts.length == 3) return side == 0 ? raw(parts[0]) : side == 2 ? raw(parts[2]) : raw(parts[1]);
        return raw(parts[side]);
    }

    private static int raw(String token) {
        if (token.equals("0")) return 0;
        Matcher m = Pattern.compile("^(\\d+(?:\\.\\d+)?)px$").matcher(token);
        return m.find() ? (int) Math.round(Double.parseDouble(m.group(1))) : -1;
    }

    /** row/column gap 的像素：简写 1 值＝两向同值，2 值＝row column；取不到返回 -1 */
    private static int gap(String block, boolean column) {
        Matcher longhand = Pattern.compile((column ? "column-gap" : "row-gap") + "\\s*:\\s*(\\d+(?:\\.\\d+)?)px").matcher(block);
        if (longhand.find()) return (int) Math.round(Double.parseDouble(longhand.group(1)));
        Matcher shorthand = Pattern.compile("(?<![\\w-])gap\\s*:\\s*([^;}]+)").matcher(block);
        if (!shorthand.find()) return -1;
        String[] parts = shorthand.group(1).trim().split("\\s+");
        if (parts.length == 1) return raw(parts[0]);
        return column ? raw(parts[1]) : raw(parts[0]);
    }

    /** 退出块是不是独占一行：同排的话它就挤在导航右边 */
    private static boolean authOnOwnRow(String side, String auth) {
        if (side.contains("flex-direction:column") || side.contains("display:grid")) return true;
        return side.contains("flex-wrap:wrap")
                && (auth.contains("flex-basis:100%") || auth.contains("width:100%") || auth.contains("flex:0 0 100%"));
    }

    /** 估一段文字的宽：汉字按 1.0×字号、其余按 0.62×字号（偏宽，放得下才算放下） */
    private static int textW(String text, int fs) {
        double w = 0;
        for (int i = 0; i < text.length(); i++) {
            w += (text.charAt(i) >= 0x2E80 ? 1.0 : 0.62) * fs;
        }
        return (int) Math.ceil(w);
    }

    private static String brandName(String html) {
        Matcher m = Pattern.compile("class=\"brand-name\"[^>]*>([^<]*)<").matcher(html);
        assertTrue(m.find(), "index.html 里找不到品牌名");
        return m.group(1).trim();
    }

    private static List<String> navLabels(String html) {
        int start = html.indexOf("<nav class=\"nav\"");
        int end = html.indexOf("</nav>", start);
        assertTrue(start >= 0 && end > start, "index.html 里找不到侧栏导航");
        return textsBetween(html.substring(start, end), "<a", "</a>");
    }

    private static List<String> authLabels(String html) {
        int start = html.indexOf("id=\"auth-actions\"");
        int end = html.indexOf("</div>", start);
        assertTrue(start >= 0 && end > start, "index.html 里找不到退出块");
        return textsBetween(html.substring(start, end), "<button", "</button>");
    }

    /** 取一段标记里每个开闭标签之间的文字（内部子标签剥掉） */
    private static List<String> textsBetween(String block, String openTag, String closeTag) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (true) {
            int a = block.indexOf(openTag, i);
            if (a < 0) break;
            int gt = block.indexOf('>', a);
            int b = gt < 0 ? -1 : block.indexOf(closeTag, gt);
            if (b < 0) break;
            String text = block.substring(gt + 1, b).replaceAll("<[^>]*>", "").trim();
            if (!text.isEmpty()) out.add(text);
            i = b + closeTag.length();
        }
        return out;
    }

    /** 取 ≤959px 那段媒体查询的正文 */
    private static String narrowMediaBlock(String css) {
        int at = css.indexOf("@media (max-width:959px)");
        if (at < 0) return "";
        int open = css.indexOf('{', at);
        int depth = 0;
        for (int i = open; i < css.length(); i++) {
            if (css.charAt(i) == '{') depth++;
            else if (css.charAt(i) == '}') {
                depth--;
                if (depth == 0) return css.substring(open + 1, i);
            }
        }
        return "";
    }

    /** 判断 target 是否嵌套在 class 为 outer 的 div 里（按 div 配对判） */
    private static boolean isNestedInside(String html, String outer, String target) {
        String cls = outer.startsWith(".") ? outer.substring(1) : outer;
        int clsAt = html.indexOf("class=\"" + cls + "\"");
        if (clsAt < 0) clsAt = html.indexOf("class=\"" + cls + " ");
        if (clsAt < 0) return false;
        int tagEnd = html.indexOf('>', clsAt);
        if (tagEnd < 0) return false;
        int depth = 1;
        int i = tagEnd + 1;
        while (i < html.length() && depth > 0) {
            int nextOpen = html.indexOf("<div", i);
            int nextClose = html.indexOf("</div>", i);
            if (nextClose < 0) return false;
            if (nextOpen >= 0 && nextOpen < nextClose) {
                depth++;
                i = nextOpen + 4;
            } else {
                depth--;
                i = nextClose + 6;
            }
        }
        int end = i;
        int targetAt = html.indexOf("id=\"" + target + "\"", clsAt);
        return targetAt > clsAt && targetAt < end;
    }

    /** 取一个选择器块的正文（到匹配的右花括号），在给定文本里搜 */
    private static String cssBlockIn(String text, String selector) {
        int at = text.indexOf(selector + "{");
        if (at < 0) at = text.indexOf(selector + " {");
        if (at < 0) return "";
        int open = text.indexOf('{', at);
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            if (text.charAt(i) == '{') depth++;
            else if (text.charAt(i) == '}') {
                depth--;
                if (depth == 0) return text.substring(open + 1, i);
            }
        }
        return "";
    }

    /** 取一个选择器块的正文（到匹配的右花括号） */
    private static String cssBlock(String css, String selector) {
        int at = css.indexOf(selector + "{");
        if (at < 0) at = css.indexOf(selector + " {");
        if (at < 0) return "";
        int open = css.indexOf('{', at);
        int depth = 0;
        for (int i = open; i < css.length(); i++) {
            if (css.charAt(i) == '{') depth++;
            else if (css.charAt(i) == '}') {
                depth--;
                if (depth == 0) return css.substring(open + 1, i);
            }
        }
        return "";
    }

    /** 从一段 CSS 声明里取 padding-left；没有就从 padding 简写里取左值 */
    private static int extractPaddingLeft(String block) {
        java.util.regex.Matcher pl = java.util.regex.Pattern
                .compile("padding-left\\s*:\\s*(\\d+)px")
                .matcher(block);
        if (pl.find()) return Integer.parseInt(pl.group(1));
        java.util.regex.Matcher p = java.util.regex.Pattern
                .compile("padding\\s*:\\s*(\\d+)px(?:\\s+(\\d+)px)?(?:\\s+(\\d+)px)?(?:\\s+(\\d+)px)?")
                .matcher(block);
        if (p.find()) {
            // padding: T R B L 或 padding: V H 或 padding: ALL
            if (p.group(4) != null) return Integer.parseInt(p.group(4));
            if (p.group(3) != null) return Integer.parseInt(p.group(2));
            if (p.group(2) != null) return Integer.parseInt(p.group(2));
            return Integer.parseInt(p.group(1));
        }
        return 0;
    }
}
