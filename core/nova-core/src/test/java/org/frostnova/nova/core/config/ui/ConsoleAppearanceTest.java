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
 * 控制台外观：去顶栏、退出挪侧栏底（窄屏收进「我的」）、品牌位居中、界面三钮左缩进
 * <p>
 * 只为说得出用户故障的格立测试，一格一种故障：
 * <ul>
 *   <li>窄屏横条里没有退出入口、或「我的」打不开 → 手机上退不出登录；</li>
 *   <li>窄屏 Logo 与「我的」挤不下一行、或退出块占了横条里的行 → 手机上导航被挤得点不到、正文被推下去；</li>
 *   <li>「界面」三钮与下方设置项左缩进不一致 → 按钮贴边；</li>
 *   <li>点目录的落点让出不该让的位 → 宽屏上组标题停偏；让位量写死 → 换台设备、改一下字号又不准；</li>
 *   <li>落点与高亮各量各的吸顶高度 → 窄屏上点某一组亮的是上一组。</li>
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

        // 窄屏上退出块收在「我的」里：那颗按钮得在、得指着装退出块的面板，
        // 面板摊开时得真显示出来，且点那颗按钮真会摊开（脚本接上了）
        assertTrue(side.contains("id=\"mine-toggle\"") && side.contains("aria-controls=\"mine-panel\""),
                "侧栏里没有指向 #mine-panel 的「我的」按钮，手机上找不到退出入口");
        assertTrue(isNestedInside(html, ".mine-panel", "auth-actions"),
                "退出块不在 #mine-panel 里，「我的」摊开也看不到它");
        assertTrue(!cssBlockIn(narrow, "#mine-toggle").contains("display:none"),
                "窄屏样式把「我的」藏掉了，手机上退不出登录");
        assertTrue(cssBlockIn(narrow, ".mine-panel.open").contains("display:flex"),
                "窄屏上「我的」摊开（.mine-panel.open）时面板没有显示出来");
        String menu = read("personal-menu.js");
        assertTrue(menu.contains("export function bindMineMenu") && menu.contains("classList.toggle('open'"),
                "personal-menu.js 里没有给面板挂 open 的 bindMineMenu，点「我的」不会摊开");
        assertTrue(read("main.js").contains("bindMineMenu($('#mine-toggle'), $('#mine-panel'))"),
                "main.js 没有把「我的」接上 bindMineMenu");
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
     * 就又不准（旧顶栏那个 54px 正是这么来的）。落点与「哪一组算当前」还得取
     * 同一个数：各量各的，窄屏上点某一组亮的是上一组。所以这里只认四件事实——
     * 量高度只有 headerHeight 一处（focusGroup 取它，不自个儿量）、
     * 窄屏那道门之前的代码里没有减号、门之后的让位量必须量过 DOM、
     * 旧的页头高度不许复活（不许量 --head-h、不许返回写死的正数，--head-h 不许是正数）。
     * 四件各盯一条会把落点弄偏的写法，其中一条变了这一格就红。
     */
    @Test
    @DisplayName("点目录的落点：宽屏不让位，窄屏只让量出来的高度")
    void scrollOffsetYieldsOnlyMeasuredStickyHeights() throws IOException {
        String css = read("app.css");
        String settings = read("settings.js");
        List<String> bad = new ArrayList<>();

        // 0) 量高度只有 headerHeight 一处：落点与高亮都取它。focusGroup 里再量一份
        //    （offsetHeight）或再守一道断点（matchMedia／max-width），又是两处口径
        int fnStart = settings.indexOf("function focusGroup");
        assertTrue(fnStart >= 0, "settings.js 里找不到 focusGroup，这一格此刻什么也没量");
        String focusRaw = fnBody(settings, fnStart);
        String focusBody = codeOnly(focusRaw);
        assertTrue(focusBody.contains("scrollTo") && focusBody.contains("getBoundingClientRect().top"),
                "focusGroup 不再是「滚到组标题」那条路，这一格的射程要重定: " + focusBody.strip());
        if (!focusBody.contains("headerHeight")) {
            bad.add("focusGroup 没有取 headerHeight 量出来的数，落点与高亮又是两处口径: " + focusBody.strip());
        }
        if (focusRaw.contains("offsetHeight") || focusRaw.contains("matchMedia") || focusRaw.contains("max-width")) {
            bad.add("focusGroup 里自个儿量高度／守断点了，量高度只该有 headerHeight 一处: " + focusRaw.strip());
        }

        // 1) 宽屏那条路不许让位：窄屏那道门（matchMedia）之前的代码里出现减号，
        //    减掉的就只可能是让位量——宽屏上没有任何吸顶的元素
        //    （data-grp 这类字符串里的连字符不算，先把字符串与注释剥掉再看）
        int hStart = settings.indexOf("function headerHeight");
        assertTrue(hStart >= 0, "settings.js 里找不到 headerHeight，这一格此刻什么也没量");
        String hBody = codeOnly(fnBody(settings, hStart));
        int gate = hBody.indexOf("matchMedia");
        assertTrue(gate >= 0, "headerHeight 里没有窄屏那道门（matchMedia），"
                + "宽屏上会让出一段、组标题停偏: " + hBody.strip());
        if (hBody.substring(0, gate).contains("-")) {
            bad.add("窄屏那道门之前就有减号，宽屏的落点会被让位量偏开: " + hBody.substring(0, gate).strip());
        }

        // 2) 窄屏让的量必须是量出来的：门之后得量 DOM（offsetHeight）；
        //    落点里减号后面跟着数字即写死的让位量，一处都不许有
        String afterGate = hBody.substring(gate);
        if (!afterGate.contains("offsetHeight")) {
            bad.add("窄屏的让位量没有量 DOM（offsetHeight），写死的数换个字号、换台设备就不准: " + afterGate.strip());
        }
        if (Pattern.compile("-\\s*\\d").matcher(hBody).find()
                || Pattern.compile("-\\s*\\d").matcher(focusBody).find()) {
            bad.add("落点里有写死的让位量（减号后面是数字），量出来的才跟得住会折行的顶栏: " + hBody.strip());
        }

        // 3) 旧的页头高度不许复活：量 --head-h、读令牌、返回写死的正数都算让位量写死了
        for (String measured : new String[]{"--head-h", "getComputedStyle", "getPropertyValue", "getBoundingClientRect", "querySelector"}) {
            if (hBody.contains(measured)) {
                bad.add("headerHeight() 还在量「" + measured + "」，让位量只能量吸顶那两块的实测高度: " + hBody.strip());
            }
        }
        if (Pattern.compile("return\\s+[1-9]").matcher(hBody).find()) {
            bad.add("headerHeight() 返回写死的正数，换台设备、改一下字号就不准: " + hBody.strip());
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
     * 窄屏顶部：第一行 Logo 与「我的」，导航独占第二行，退出块不占横条里的行
     * <p>
     * 故障：手机上每页顶部要占一大截才到正文，或导航被挤得点不到。使用说明、账号、退出全部设备
     * 各占一行时，正文要往下推一百五十像素；退出钮与导航同排时，导航只剩几十像素宽。
     * 量法：按 390px 视口、从样式现读字号（字号档位从 product-ui.css 解出来）与内边距，
     * 估第一行 Logo 加「我的」放不放得下；导航那一行按独占整行估；退出块所在的面板须是浮出来的
     * （position:absolute），不占横条里的行。文字宽按「汉字＝字号、拉丁≈0.62×字号」上估——估宽不估窄。
     */
    @Test
    @DisplayName("窄屏 390 宽下 Logo 与「我的」同一行，导航独占一行，退出块不占行")
    void narrowTopBarKeepsLogoAndMineOnOneRow() throws IOException {
        String html = read("index.html");
        String css = read("app.css");
        String tokens = read("product-ui.css");
        String narrow = narrowMediaBlock(css);
        assertTrue(!narrow.isBlank(), "app.css 里没有 ≤959px 那段窄屏样式，这一格此刻什么也没量");

        String side = require(cssBlockIn(narrow, "#side"), "窄屏 #side");
        String navNarrow = require(cssBlockIn(narrow, ".nav"), "窄屏 .nav");
        String navA = require(cssBlockIn(narrow, ".nav a"), "窄屏 .nav a");
        String brandNarrow = require(cssBlockIn(narrow, ".brand"), "窄屏 .brand");
        String toggleNarrow = require(cssBlockIn(narrow, "#mine-toggle"), "窄屏 #mine-toggle");
        String panelNarrow = require(cssBlockIn(narrow, ".mine-panel"), "窄屏 .mine-panel");
        String brandBase = require(cssBlock(css, ".brand"), ".brand");
        String markBase = require(cssBlock(css, ".brand-mark"), ".brand-mark");
        String nameBase = require(cssBlock(css, ".brand-name"), ".brand-name");
        String navBase = require(cssBlock(css, ".nav"), ".nav");
        String navABase = require(cssBlock(css, ".nav a"), ".nav a");
        String buttonBase = require(cssBlock(css, ":is(button,a):is(.primary,.ghost,.danger),.nv-btn,.nv-btn2"), "按钮基样");
        String small = require(cssBlock(css, ":is(button,a).sm"), "小号按钮");

        int inner = 390 - pick(padSide(side, 3), 0) - pick(padSide(side, 1), 0);
        int colGap = pick(gap(side, true), gap(side, false));

        // 品牌位：图标 ＋ 与名字的间隙 ＋ 名字 ＋ 右内边距
        int brandW = requirePx(px(markBase, "width", tokens), ".brand-mark 宽")
                + pick(gap(brandNarrow, true), gap(brandBase, true))
                + textW(brandName(html), requirePx(px(nameBase, "font-size", tokens), ".brand-name 字号"))
                + pick(padSide(brandNarrow, 1), padSide(brandBase, 1));

        // 「我的」：小号次按钮，字宽 ＋ 左右内边距 ＋ 边框
        Matcher label = Pattern.compile("id=\"mine-toggle\"[^>]*>([^<]*)<").matcher(html);
        assertTrue(label.find(), "index.html 里找不到「我的」按钮");
        int toggleFs = requirePx(px(small, "font-size", tokens), "小号按钮字号");
        int toggleW = textW(label.group(1).trim(), toggleFs) + padSide(small, 3) + padSide(small, 1)
                + borderPx(cssBlock(css, ":is(button,a).ghost,.nv-btn2")) * 2;
        assertTrue(padSide(small, 3) >= 0 && padSide(small, 1) >= 0, "取不到小号按钮的左右内边距: " + small.strip());
        assertTrue(!buttonBase.isBlank(), "按钮基样是空的");

        List<String> bad = new ArrayList<>();
        if (!toggleNarrow.contains("margin-left:auto")) {
            bad.add("窄屏「我的」没有推到 Logo 那一行的右端（margin-left:auto）");
        }
        if (brandW + colGap + toggleW > inner) {
            bad.add("Logo " + brandW + "px 加「我的」" + toggleW + "px 超出 390 宽可有的 " + inner + "px，挤不下一行");
        }

        // 导航独占一行：放得下就不横滚，放不下也只在自己那一行里横滚
        if (!(navNarrow.contains("flex:1 1 100%") || navNarrow.contains("flex-basis:100%"))) {
            bad.add("窄屏导航不是独占一行（flex-basis 100%），会和 Logo、「我的」抢同一行");
        }
        List<String> navLabels = navLabels(html);
        assertTrue(navLabels.size() >= 2, "index.html 里数不出导航条目，分母是空的");
        int navFs = pick(px(navA, "font-size", tokens), requirePx(px(navABase, "font-size", tokens), ".nav a 字号"));
        int navPad = pick(padSide(navA, 3), padSide(navABase, 3)) + pick(padSide(navA, 1), padSide(navABase, 1));
        int navGap = pick(gap(navNarrow, true), gap(navBase, true));
        int navW = (navLabels.size() - 1) * navGap;
        for (String one : navLabels) navW += textW(one, navFs) + navPad;
        if (navW > inner) {
            bad.add("导航要 " + navW + "px，独占一行也只有 " + inner + "px——手机上导航被挤得点不到");
        }

        // 退出块所在的面板浮在横条下面，不占行
        if (!panelNarrow.contains("position:absolute")) {
            bad.add("窄屏「我的」面板不是浮出来的（position:absolute），摊开时会把横条撑高、把正文推下去");
        }
        assertTrue(bad.isEmpty(), "窄屏 390px 下顶部排不下:\n  " + String.join("\n  ", bad));
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

    /**
     * 取一段 CSS 里某属性的像素数；写的是 var(--x) 时到 product-ui.css 里解出那个变量的像素数
     * （字号一律引档位变量）；没有该属性、或变量解不出像素时返回 -1
     */
    private static int px(String block, String prop, String tokens) {
        Matcher m = Pattern.compile("(?<![\\w-])" + Pattern.quote(prop) + "\\s*:\\s*(?:(\\d+(?:\\.\\d+)?)px|var\\((--[\\w-]+)\\))").matcher(block);
        if (!m.find()) return -1;
        if (m.group(1) != null) return (int) Math.round(Double.parseDouble(m.group(1)));
        Matcher t = Pattern.compile("(?<![\\w-])" + Pattern.quote(m.group(2)) + "\\s*:\\s*(\\d+(?:\\.\\d+)?)px").matcher(tokens);
        return t.find() ? (int) Math.round(Double.parseDouble(t.group(1))) : -1;
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
