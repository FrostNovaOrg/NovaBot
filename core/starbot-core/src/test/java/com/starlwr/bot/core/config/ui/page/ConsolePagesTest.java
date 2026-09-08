package com.starlwr.bot.core.config.ui.page;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 控制台页面清单
 * <p>
 * 这张清单来自插件，而插件是第三方代码：标识与文件名都是它自己填的，会原样出现在
 * DOM 的 id 上和取资源的路径里。<b>不合规的那些必须在登记这一步就被丢掉</b>，
 * 而不是等到浏览器里渲染出个奇怪的元素、或者服务端照着一个带 {@code ../} 的名字去翻文件。
 */
@DisplayName("控制台页面清单")
class ConsolePagesTest {
    /**
     * 一个只报数据、不做任何事的注册项
     */
    private record Page(String id, String displayName, String script, int order) implements ConsolePageProvider {
    }

    private static ConsolePageProvider page(String id, String script) {
        return new Page(id, id, script, 100);
    }

    private static List<ConsolePageProvider> list(ConsolePageProvider... items) {
        return new ArrayList<>(Arrays.asList(items));
    }

    /**
     * 显式申报落位的注册项。{@link Page} 不覆盖 {@code slot()}，才能守住缺省仍是设置页那一条
     */
    private record Slotted(String id, String displayName, String script, int order, ConsolePageSlot slot)
            implements ConsolePageProvider {
    }

    /**
     * 申报附属脚本的注册项。{@link Page} 不覆盖 {@code assets()}，才能守住缺省仍是空清单那一条
     */
    private record WithAssets(String id, String displayName, String script, int order, List<String> assets)
            implements ConsolePageProvider {
    }

    private Path repoRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve("build.sh")) && Files.exists(current.resolve("pom.xml"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("未能定位仓库根目录");
    }

    @Test
    @DisplayName("空清单就是空的，不替谁兜底")
    void emptyStaysEmpty() {
        assertTrue(ConsolePages.valid(List.of()).isEmpty());
    }

    @Test
    @DisplayName("合法项按顺序值排，同值按标识排")
    void sortedByOrderThenId() {
        List<ConsolePageProvider> sorted = ConsolePages.valid(list(
                new Page("zulu", "Z", "zulu.js", 100),
                new Page("alpha", "A", "alpha.js", 100),
                new Page("first", "F", "first.js", 10)));

        assertEquals(List.of("first", "alpha", "zulu"), sorted.stream().map(ConsolePageProvider::id).toList());
    }

    @Test
    @DisplayName("标识不合规的一律不登记")
    void illegalIdsAreDropped() {
        List<ConsolePageProvider> bad = list(
                page("../evil", "a.js"),
                page("Upper", "b.js"),
                page("has space", "c.js"),
                page("", "d.js"),
                page(null, "e.js"),
                page("9lead", "f.js"),
                page("a".repeat(64), "g.js"));

        assertTrue(ConsolePages.valid(bad).isEmpty(), "以上标识都不该被登记: " + bad);
    }

    @Test
    @DisplayName("脚本名不合规的一律不登记，路径穿越连门都进不来")
    void illegalScriptsAreDropped() {
        List<ConsolePageProvider> bad = list(
                page("a", "../../application.yml"),
                page("b", "config-ui/main.js"),
                page("c", "page.css"),
                page("d", "page.js.map"),
                page("e", "page"),
                page("f", ""),
                page("g", null));

        assertTrue(ConsolePages.valid(bad).isEmpty(), "以上脚本名都不该被登记: " + bad);
    }

    /**
     * 与核心自带界面文件同名的页面脚本，一律不予登记
     * <p>
     * 这条判据来自一次真事：某个界面文件已从核心源码树删除、改由插件提供，而上一次构建
     * 留在 target/classes 里的旧文件照旧进了包。取资源那一侧是「核心里有就用核心的，
     * 没有才回落到插件」，于是旧文件<b>静默压过</b>插件那一份——请求成功、长度正常，
     * 内容却来自一个源码里根本不存在的版本。
     * <p>
     * <b>同名就是两份判法。</b>无论静默选哪一方，选中的那一方都看不出来自己压过了另一方：
     * 选核心，就是上面那件事；选插件，则核心自己的 {@code core.js} 一类公共文件会被插件顶掉，
     * 而其余每张页面都 {@code import} 它，坏的是全部页面而不是这一页。<b>拒绝登记</b>是唯一
     * 说得出口的处置：这一页不出现，日志里有一行点名说它为什么不出现，两份文件都还在原处。
     */
    @Test
    @DisplayName("脚本名与核心自带界面资源撞名的，拒绝登记，不静默偏向任何一方")
    void scriptsCollidingWithCoreAssetsAreRejected() {
        List<ConsolePageProvider> providers = list(
                page("shadow", "core.js"),
                page("shadow-main", "main.js"),
                page("ok", "somewhere-else.js"));

        assertEquals(List.of("ok"), ConsolePages.valid(providers).stream().map(ConsolePageProvider::id).toList(),
                "撞名的两页都不该登记，不撞名的那页照常在");
        assertEquals(Optional.empty(), ConsolePages.byScript(providers, "core.js"),
                "撞名的页按脚本名也取不到，否则 /assets 那条路仍会走到它");
        assertEquals(Optional.empty(), ConsolePages.byScript(providers, "main.js"));
    }

    @Test
    @DisplayName("显示名为空的不登记：页签条上会多出一个点不着的空档")
    void blankDisplayNameIsDropped() {
        assertTrue(ConsolePages.valid(list(new Page("x", " ", "x.js", 100))).isEmpty());
    }

    @Test
    @DisplayName("标识重复时只认先登记的那个")
    void duplicateIdKeepsTheFirst() {
        List<ConsolePageProvider> kept = ConsolePages.valid(list(
                new Page("dup", "先来的", "one.js", 100),
                new Page("dup", "后来的", "two.js", 100)));

        assertEquals(1, kept.size());
        assertEquals("先来的", kept.get(0).displayName());
    }

    /**
     * 落位缺省是设置页，已有的实现一个字都不用改
     * <p>
     * 这一条守的是升级路径：{@code slot()} 是后加的默认方法，一个在它之前写好的插件
     * 不会实现它。缺省要是给了「连接页」，那些插件的页会在升级到这一版之后
     * <b>悄悄挪到另一页上</b>——功能一件不少，只是使用者再也找不到它。
     */
    @Test
    @DisplayName("没申报落位的页落在设置页，不会被悄悄挪到别处")
    void slotDefaultsToSettings() {
        assertEquals(ConsolePageSlot.SETTINGS, new Page("a", "甲", "a.js", 100).slot());
    }

    /**
     * 没申报图标的页缺省是空串，已有的实现一个字都不用改
     * <p>
     * 空串在界面那一侧的意思是「画中性缺省图标」。缺省要是给了 {@code null}，
     * 那些在它之前写好的插件会在升级到这一版之后<b>连页都登记不上</b>——
     * 取不到图标这一条会把它们逐个丢掉，而它们一个字都没改过。
     */
    @Test
    @DisplayName("没申报图标的页 icon() 是空串，不是 null")
    void iconDefaultsToEmptyString() {
        assertEquals("", new Page("a", "甲", "a.js", 100).icon());
    }

    /**
     * 申报图标的注册项。{@link Page} 不覆盖 {@code icon()}，才能守住缺省仍是空串那一条
     */
    private record WithIcon(String id, String displayName, String script, int order, String icon)
            implements ConsolePageProvider {
    }

    private static String icon(String shape) {
        return ConsolePages.icon(new WithIcon("demo", "演示", "demo.js", 100, shape));
    }

    /**
     * 图标白名单：几何形状放行，其余整条退成空串
     * <p>
     * 这一串会拼进界面上那个 {@code <svg>} 壳里，而它出自插件——因此这里是关口。
     * 放行的写法与不放行的写法一起量：只列不放行的那些，一道恒假的关口同样全绿，
     * 而它的表现是<b>所有插件的图标都画不出来</b>。
     */
    @Test
    @DisplayName("图标白名单：几何形状放行，脚本、事件属性、别的元素整条退成空串")
    void iconWhitelistKeepsShapesAndDropsTheRest() {
        List<String> red = new ArrayList<>();

        try {
            assertEquals("<circle cx=\"8\" cy=\"5.2\" r=\"2.6\"/>", icon("<circle cx=\"8\" cy=\"5.2\" r=\"2.6\"/>"),
                    "一笔形状应原样放行");
            assertEquals("<circle cx=\"8\" cy=\"8\" r=\"4\"/><path d=\"M2 8h12\"/>",
                    icon("<circle cx=\"8\" cy=\"8\" r=\"4\"/><path d=\"M2 8h12\"/>"),
                    "拼在一起的两笔也应放行，真图标就是这个形状");
            assertEquals("<line x1=\"2\" y1=\"2\" x2=\"14\" y2=\"14\"/>",
                    icon("  <line x1=\"2\" y1=\"2\" x2=\"14\" y2=\"14\"/>  "),
                    "首尾空白去掉即可，属性名里带数字的不许因此落到白名单外");
            assertEquals("<rect x=\"2\" y=\"2\" width=\"12\" height=\"12\" rx=\"2\"/>",
                    icon("<rect x=\"2\" y=\"2\" width=\"12\" height=\"12\" rx=\"2\"/>"));
        } catch (Throwable t) {
            red.add("① " + t.getMessage());
        }

        try {
            assertEquals("", icon("<script>alert(1)</script>"), "脚本不放行");
            assertEquals("", icon("<path d=\"M2 2h12\" onload=\"boom()\"/>"), "事件属性不放行");
            assertEquals("", icon("<foreignObject width=\"16\" height=\"16\"><b>x</b></foreignObject>"),
                    "另一棵文档树不放行");
            assertEquals("", icon("<image href=\"http://example.invalid/x.png\"/>"), "外链不放行");
            assertEquals("", icon("<path d=\"M2 2h12\" style=\"stroke-width:9\"/>"),
                    "style 不放行，否则插件能画出比别条粗的笔画");
        } catch (Throwable t) {
            red.add("② " + t.getMessage());
        }

        try {
            assertEquals("", icon("<path d=\"M2 2h12\">"), "没闭合的形状元素不放行");
            assertEquals("", icon("<circle cx=\"8\" cy=\"8\" r=\"4\"/><script>boom()</script>"),
                    "合规形状后面跟着的东西不许搭车过关");
            assertEquals("", icon("<circle cx=\"8\" cy=\"8\" r=\"4\"/>x<path d=\"M2 8h12\"/>"),
                    "两笔之间夹着的东西不许被跳过去");
        } catch (Throwable t) {
            red.add("③ " + t.getMessage());
        }

        try {
            assertEquals("", icon(""), "没给图标就是空串");
            assertEquals("", icon("   "), "只有空白也是空串");
            assertEquals("", icon(null), "给了 null 也不该抛");
            assertEquals("", ConsolePages.icon(new Exploding("icon")), "问都问不出来时退成空串");
        } catch (Throwable t) {
            red.add("④ " + t.getMessage());
        }

        if (!red.isEmpty()) {
            fail(red.size() + " 问红：" + String.join("；", red));
        }
    }

    /**
     * 图标不合规只丢图标，不丢这一页
     * <p>
     * 「图标写坏了所以整页从导航上消失」比没有图标更坏：少一页看得见，为什么少的看不见。
     * 登记那一步因此完全不看图标——{@code icon()} 抛异常的那一页也照常在清单里。
     */
    @Test
    @DisplayName("图标不合规或取不到，这一页照常登记")
    void badIconNeverUnregistersThePage() {
        List<String> red = new ArrayList<>();

        try {
            assertEquals(List.of("demo"), ConsolePages.valid(list(
                            new WithIcon("demo", "演示", "demo.js", 100, "<script>alert(1)</script>")))
                            .stream().map(ConsolePageProvider::id).toList(),
                    "图标不合规的页应当照常登记");
        } catch (Throwable t) {
            red.add("① " + t.getMessage());
        }

        try {
            assertEquals(List.of("boom-icon"), ConsolePages.valid(list(new Exploding("icon")))
                            .stream().map(ConsolePageProvider::id).toList(),
                    "icon() 抛异常的页应当照常登记");
        } catch (Throwable t) {
            red.add("② " + t.getMessage());
        }

        if (!red.isEmpty()) {
            fail(red.size() + " 问红：" + String.join("；", red));
        }
    }

    /**
     * 顶级页落位：合法项保留、撞内置名弃掉、枚举名与前端接线对得上
     * <p>
     * 四问各自记下，末尾一起红：①红就 return 的话，③④还没跑过，接线断了也看不见。
     */
    @Test
    @DisplayName("顶级页：合法项保留、撞内置名弃、slot 字面与接线")
    void topSlotKeepsLegalDropsBuiltinAndWiresFrontend() throws IOException {
        List<String> red = new ArrayList<>();
        String main = Files.readString(
                repoRoot().resolve("core/starbot-core/src/main/resources/config-ui/main.js"),
                StandardCharsets.UTF_8);

        try {
            List<ConsolePageProvider> kept = ConsolePages.valid(list(
                    new Slotted("demo", "演示", "demo.js", 100, ConsolePageSlot.TOP)));
            assertEquals(List.of("demo"), kept.stream().map(ConsolePageProvider::id).toList(),
                    "slot=TOP 且标识不撞内置页的，应当保留");
        } catch (Throwable t) {
            red.add("① " + t.getMessage());
        }

        try {
            List<ConsolePageProvider> kept = ConsolePages.valid(list(
                    new Slotted("settings", "设置", "settings-plugin.js", 100, ConsolePageSlot.TOP),
                    new Slotted("settings2", "设置二", "settings2.js", 100, ConsolePageSlot.TOP)));
            assertEquals(List.of("settings2"), kept.stream().map(ConsolePageProvider::id).toList(),
                    "slot=TOP 且标识为 settings 的应当弃掉，settings2 是阴性对照应当保留");
        } catch (Throwable t) {
            red.add("② " + t.getMessage());
        }

        try {
            assertEquals("top", ConsolePageSlot.TOP.name().toLowerCase(Locale.ROOT),
                    "TOP 的枚举名小写必须是 top，接口才吐得出 slot=top");
            assertTrue(main.contains("SLOT_TOP = 'top'"),
                    "main.js 应有 SLOT_TOP = 'top'，与服务端 toLowerCase 对齐");
        } catch (Throwable t) {
            red.add("③ " + t.getMessage());
        }

        try {
            assertTrue(main.contains("mountTopPage("), "main.js 应有 mountTopPage(");
            int from = indexOfFunction(main, "mountPages");
            assertTrue(from >= 0, "找不到 mountPages，分派处无从量起");
            int to = nextFunction(main, from);
            String body = main.substring(from, to);
            assertTrue(body.contains("SLOT_TOP"),
                    "mountPages 分派处应引用 SLOT_TOP");
        } catch (Throwable t) {
            red.add("④ " + t.getMessage());
        }

        if (!red.isEmpty()) {
            fail(red.size() + " 问红：" + String.join("；", red));
        }
    }

    /**
     * 首页卡落位：合法项保留、枚举名与前端常量对齐、分派与「高级」折页各守各的
     * <p>
     * 四问各自记下，末尾一起红：①红就 return 的话，③④还没跑过，接线断了也看不见。
     */
    @Test
    @DisplayName("首页卡：合法项保留、slot 字面与接线、高级折页只数设置页")
    void homeCardSlotKeepsLegalAndWiresFrontend() throws IOException {
        List<String> red = new ArrayList<>();
        String main = Files.readString(
                repoRoot().resolve("core/starbot-core/src/main/resources/config-ui/main.js"),
                StandardCharsets.UTF_8);

        try {
            List<ConsolePageProvider> kept = ConsolePages.valid(list(
                    new Slotted("card1", "卡片一", "card1.js", 100, ConsolePageSlot.HOME_CARD)));
            assertEquals(List.of("card1"), kept.stream().map(ConsolePageProvider::id).toList(),
                    "slot=HOME_CARD 且标识合规的，应当保留");
        } catch (Throwable t) {
            red.add("① " + t.getMessage());
        }

        try {
            assertEquals("home_card", ConsolePageSlot.HOME_CARD.name().toLowerCase(Locale.ROOT),
                    "HOME_CARD 的枚举名小写必须是 home_card，接口才吐得出 slot=home_card");
            assertTrue(main.contains("SLOT_HOME_CARD = 'home_card'"),
                    "main.js 应有 SLOT_HOME_CARD = 'home_card'，与服务端 toLowerCase 对齐");
        } catch (Throwable t) {
            red.add("② " + t.getMessage());
        }

        try {
            assertTrue(main.contains("mountHomeCard("), "main.js 应有 mountHomeCard(");
            int from = indexOfFunction(main, "mountPages");
            assertTrue(from >= 0, "找不到 mountPages，分派处无从量起");
            int to = nextFunction(main, from);
            String body = main.substring(from, to);
            assertTrue(body.contains("SLOT_HOME_CARD"),
                    "mountPages 分派处应引用 SLOT_HOME_CARD");
        } catch (Throwable t) {
            red.add("③ " + t.getMessage());
        }

        try {
            int adv = main.indexOf("$('#plugin-adv')");
            assertTrue(adv >= 0, "找不到高级折页显隐");
            int lineStart = main.lastIndexOf('\n', adv) + 1;
            int lineEnd = main.indexOf('\n', adv);
            if (lineEnd < 0) {
                lineEnd = main.length();
            }
            String line = main.substring(lineStart, lineEnd);
            assertTrue(line.contains("SLOT_SETTINGS"),
                    "高级折页显隐应只数 SLOT_SETTINGS: " + line);
            assertTrue(!line.contains("SLOT_HOME_CARD"),
                    "高级折页显隐不应含 SLOT_HOME_CARD: " + line);
        } catch (Throwable t) {
            red.add("④ " + t.getMessage());
        }

        if (!red.isEmpty()) {
            fail(red.size() + " 问红：" + String.join("；", red));
        }
    }

    /**
     * 向导步骤落位：标识与内置步骤键撞车则弃掉
     * <p>
     * 插件步的标识会原样成为步骤表上的 key。撞上 lock／bot／account／test
     * 就会盖住内置那一步，和顶级页撞内置页名是同一形。
     * <p>
     * 🔴 {@code streamer} 是这里的第二个阴性对照，且它是<b>点名要留下</b>的那个：
     * 「第一位主播，推到哪」已随控制台插件走，用的正是这个键。闭集里再留着它的话，
     * 那一步会在登记时被静默弃掉，而屏幕上的表现是向导少了一步、日志里只有一行 warn。
     */
    @Test
    @DisplayName("向导步骤：撞内置步骤名弃掉，streamer 与 danmu 都留下")
    void setupStepSlotDropsBuiltinStepIds() {
        List<ConsolePageProvider> kept = ConsolePages.valid(list(
                new Slotted("account", "登录", "account-step.js", 50, ConsolePageSlot.SETUP_STEP),
                new Slotted("streamer", "主播", "setup-streamer.js", 40, ConsolePageSlot.SETUP_STEP),
                new Slotted("danmu", "弹幕", "danmu.js", 50, ConsolePageSlot.SETUP_STEP)));
        assertEquals(List.of("streamer", "danmu"), kept.stream().map(ConsolePageProvider::id).toList(),
                "slot=SETUP_STEP 且标识为 account 的应当弃掉；streamer 与 danmu 是阴性对照应当保留");
    }

    /**
     * 向导步骤落位：合法项保留、枚举名小写与 /api/pages 的 slot 串对齐
     * <p>
     * 两问各自记下，末尾一起红：①红就 return 的话，②还没跑过，接口字面漂了也看不见。
     * 本笔不改 ConfigUiController：slot 串由枚举名 {@code toLowerCase} 现算。
     */
    @Test
    @DisplayName("向导步骤：合法项保留、slot 字面为 setup_step")
    void setupStepSlotKeepsLegalAndWiresPagesApi() throws IOException {
        List<String> red = new ArrayList<>();
        String controller = Files.readString(
                repoRoot().resolve("core/starbot-core/src/main/java/com/starlwr/bot/core/config/ui/ConfigUiController.java"),
                StandardCharsets.UTF_8);

        try {
            List<ConsolePageProvider> kept = ConsolePages.valid(list(
                    new Slotted("danmu", "弹幕", "danmu.js", 50, ConsolePageSlot.SETUP_STEP)));
            assertEquals(List.of("danmu"), kept.stream().map(ConsolePageProvider::id).toList(),
                    "slot=SETUP_STEP 且标识合规、不撞内置步骤的，应当保留");
        } catch (Throwable t) {
            red.add("① " + t.getMessage());
        }

        try {
            assertEquals("setup_step", ConsolePageSlot.SETUP_STEP.name().toLowerCase(Locale.ROOT),
                    "SETUP_STEP 的枚举名小写必须是 setup_step，接口才吐得出 slot=setup_step");
            assertTrue(controller.contains("page.slot().name().toLowerCase(Locale.ROOT)"),
                    "/api/pages 应按枚举名小写吐 slot");
        } catch (Throwable t) {
            red.add("② " + t.getMessage());
        }

        if (!red.isEmpty()) {
            fail(red.size() + " 问红：" + String.join("；", red));
        }
    }

    private static int indexOfFunction(String text, String name) {
        int async = text.indexOf("async function " + name + "(");
        if (async >= 0) {
            return async;
        }
        return text.indexOf("function " + name + "(");
    }

    private static int nextFunction(String text, int from) {
        int next = text.indexOf("\nfunction ", from + 1);
        int nextAsync = text.indexOf("\nasync function ", from + 1);
        int to = text.length();
        if (next >= 0) {
            to = Math.min(to, next);
        }
        if (nextAsync >= 0) {
            to = Math.min(to, nextAsync);
        }
        return to;
    }

    @Test
    @DisplayName("整理出来的清单改不动")
    void resultIsImmutable() {
        List<ConsolePageProvider> kept = ConsolePages.valid(list(page("a", "a.js")));
        assertThrows(UnsupportedOperationException.class, () -> kept.add(page("b", "b.js")));
    }

    @Test
    @DisplayName("按脚本名找得到登记过的，找不到没登记的")
    void scriptLookupOnlyMatchesRegistered() {
        List<ConsolePageProvider> providers = list(page("a", "a.js"), page("b", "b.js"));

        assertEquals("a", ConsolePages.byScript(providers, "a.js").map(ConsolePageProvider::id).orElse(null));
        assertEquals("b", ConsolePages.byScript(providers, "b.js").map(ConsolePageProvider::id).orElse(null));
        assertEquals(Optional.empty(), ConsolePages.byScript(providers, "main.js"));
        assertEquals(Optional.empty(), ConsolePages.byScript(providers, "a.js "));
        assertEquals(Optional.empty(), ConsolePages.byScript(providers, null));
    }

    @Test
    @DisplayName("没通过登记的脚本，按名字也取不到")
    void scriptLookupSkipsRejectedProviders() {
        List<ConsolePageProvider> providers = list(page("Upper", "shady.js"));

        assertEquals(Optional.empty(), ConsolePages.byScript(providers, "shady.js"));
    }

    /**
     * 附属脚本按登记名端出
     * <p>
     * 三问各自记下，末尾一起红：①红就 return 的话，③还没跑过，byScript 没扩也看不见。
     */
    @Test
    @DisplayName("assets 可按名端出、byScript 找得到")
    void extraAssetsAreServedByName() {
        List<String> red = new ArrayList<>();
        ConsolePageProvider demo = new WithAssets("demo", "演示", "demo.js", 100,
                List.of("demo-model.js", "demo-extra.js"));
        List<ConsolePageProvider> providers = list(demo);

        try {
            assertEquals(List.of(), new Page("x", "甲", "x.js", 100).assets(),
                    "没报附属脚本的页，assets() 应为空清单");
        } catch (Throwable t) {
            red.add("① " + t.getMessage());
        }

        try {
            assertEquals(List.of("demo"), ConsolePages.valid(providers).stream()
                            .map(ConsolePageProvider::id).toList(),
                    "只报合规附属脚本的页应当保留");
        } catch (Throwable t) {
            red.add("② " + t.getMessage());
        }

        try {
            assertEquals("demo", ConsolePages.byScript(providers, "demo.js")
                    .map(ConsolePageProvider::id).orElse(null), "主脚本仍按名找得到");
            assertEquals("demo", ConsolePages.byScript(providers, "demo-model.js")
                    .map(ConsolePageProvider::id).orElse(null),
                    "附属脚本应按名找得到，否则 /assets 端不出");
            assertEquals("demo", ConsolePages.byScript(providers, "demo-extra.js")
                    .map(ConsolePageProvider::id).orElse(null));
            assertEquals(Optional.empty(), ConsolePages.byScript(providers, "other.js"),
                    "没报的名字仍找不到");
        } catch (Throwable t) {
            red.add("③ " + t.getMessage());
        }

        if (!red.isEmpty()) {
            fail(red.size() + " 问红：" + String.join("；", red));
        }
    }

    /**
     * 附属脚本越界、撞核心同名、撞其他插件，整页拒
     * <p>
     * 三问各自记下，末尾一起红：①红就 return 的话，②③还没跑过，撞名仍端得出也看不见。
     */
    @Test
    @DisplayName("assets 越界名／撞核心同名拒")
    void extraAssetsOutOfBoundsOrCollidingAreRejected() {
        List<String> red = new ArrayList<>();

        try {
            List<ConsolePageProvider> bad = list(
                    new WithAssets("a", "甲", "a.js", 100, List.of("../evil.js")),
                    new WithAssets("b", "乙", "b.js", 100, List.of("foo/bar.js")),
                    new WithAssets("c", "丙", "c.js", 100, List.of("page.css")),
                    new WithAssets("d", "丁", "d.js", 100, List.of("ok.js")));
            assertEquals(List.of("d"), ConsolePages.valid(bad).stream()
                            .map(ConsolePageProvider::id).toList(),
                    "越界名的页应整页拒，合规附属脚本的页保留");
            assertEquals(Optional.empty(), ConsolePages.byScript(bad, "../evil.js"));
            assertEquals("d", ConsolePages.byScript(bad, "ok.js").map(ConsolePageProvider::id).orElse(null));
        } catch (Throwable t) {
            red.add("① " + t.getMessage());
        }

        try {
            List<ConsolePageProvider> collide = list(
                    new WithAssets("shadow", "影", "shadow.js", 100, List.of("core.js")),
                    new WithAssets("ok", "好", "somewhere-else.js", 100, List.of()));
            assertEquals(List.of("ok"), ConsolePages.valid(collide).stream()
                            .map(ConsolePageProvider::id).toList(),
                    "附属脚本撞核心同名应整页拒，不撞的那页照常在");
            assertEquals(Optional.empty(), ConsolePages.byScript(collide, "core.js"),
                    "撞核心同名的附属脚本按名也取不到");
            assertEquals(Optional.empty(), ConsolePages.byScript(collide, "shadow.js"),
                    "附属脚本撞核心时整页拒，主脚本也取不到");
        } catch (Throwable t) {
            red.add("② " + t.getMessage());
        }

        try {
            List<ConsolePageProvider> clash = list(
                    page("alpha", "alpha.js"),
                    new WithAssets("beta", "乙", "beta.js", 100, List.of("alpha.js")),
                    new WithAssets("gamma", "丙", "gamma.js", 100, List.of("extra.js")),
                    new WithAssets("delta", "丁", "delta.js", 100, List.of("extra.js")));
            assertEquals(List.of("alpha", "gamma"), ConsolePages.valid(clash).stream()
                            .map(ConsolePageProvider::id).toList(),
                    "附属脚本撞其他插件的主脚本或附属脚本应整页拒");
            assertEquals("alpha", ConsolePages.byScript(clash, "alpha.js")
                    .map(ConsolePageProvider::id).orElse(null));
            assertEquals(Optional.empty(), ConsolePages.byScript(clash, "beta.js"));
            assertEquals("gamma", ConsolePages.byScript(clash, "extra.js")
                    .map(ConsolePageProvider::id).orElse(null));
            assertEquals(Optional.empty(), ConsolePages.byScript(clash, "delta.js"));
        } catch (Throwable t) {
            red.add("③ " + t.getMessage());
        }

        if (!red.isEmpty()) {
            fail(red.size() + " 问红：" + String.join("；", red));
        }
    }

    /**
     * 会抛异常的注册项
     * <p>
     * 插件是第三方代码，这几个方法里可以是任何东西——读配置、拼字符串、甚至查一次数据库。
     * 它抛出来的那一下，不该由整张清单来承担。
     */
    private record Exploding(String where) implements ConsolePageProvider {
        @Override
        public String id() {
            if ("id".equals(where)) {
                throw new IllegalStateException("插件的 id() 抛了异常");
            }
            return "boom-" + where;
        }

        @Override
        public String displayName() {
            if ("displayName".equals(where)) {
                throw new IllegalStateException("插件的 displayName() 抛了异常");
            }
            return "会抛异常的页";
        }

        @Override
        public String script() {
            if ("script".equals(where)) {
                throw new IllegalStateException("插件的 script() 抛了异常");
            }
            return "boom-" + where + ".js";
        }

        @Override
        public int order() {
            if ("order".equals(where)) {
                throw new IllegalStateException("插件的 order() 抛了异常");
            }
            return 100;
        }

        @Override
        public String icon() {
            if ("icon".equals(where)) {
                throw new IllegalStateException("插件的 icon() 抛了异常");
            }
            return "";
        }

        @Override
        public ConsolePageSlot slot() {
            if ("slot".equals(where)) {
                throw new IllegalStateException("插件的 slot() 抛了异常");
            }
            return ConsolePageSlot.SETTINGS;
        }
    }

    /**
     * 一个坏插件只坏它自己那一页
     * <p>
     * 清单里的各项来自互不相干的插件。<b>其中一个填错或抛了异常，控制台该少的只是那一个页签</b>，
     * 而不是整条清单一起消失——那样使用者看到的是一个一个平台页都没有的控制台，
     * 只会以为插件全都没装上，而真正出问题的那一个反倒无从辨认。
     */
    @Test
    @DisplayName("坏的注册项只丢它自己，其余照常入清单")
    void oneBadProviderDoesNotTakeDownTheRest() {
        List<ConsolePageProvider> kept = ConsolePages.valid(list(
                new Page("alpha", "甲", "alpha.js", 10),
                new Exploding("id"),
                new Exploding("displayName"),
                new Exploding("script"),
                new Exploding("order"),
                new Exploding("slot"),
                page("Upper", "shady.js"),
                page("bad-script", "../../application.yml"),
                null,
                new Page("zulu", "乙", "zulu.js", 20)));

        assertEquals(List.of("alpha", "zulu"), kept.stream().map(ConsolePageProvider::id).toList(),
                "坏项该被逐个跳过，好的那两页照常在清单里");
    }

    @Test
    @DisplayName("清单里有会抛异常的项时，按脚本名照样找得到好的那一页")
    void scriptLookupSurvivesExplodingProviders() {
        List<ConsolePageProvider> providers = list(
                new Exploding("script"),
                page("alpha", "alpha.js"),
                new Exploding("id"));

        assertEquals("alpha", ConsolePages.byScript(providers, "alpha.js").map(ConsolePageProvider::id).orElse(null));
        assertEquals(Optional.empty(), ConsolePages.byScript(providers, "boom-id.js"));
    }
}
