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
     * 顶级页落位：合法项保留、撞内置名弃掉、枚举名与前端接线对得上
     * <p>
     * 四问各自记下，末尾一起红：①红就 return 的话，③④还没跑过，接线断了也看不见。
     */
    @Test
    @DisplayName("顶级页：合法项保留、撞内置名弃、slot 字面与接线")
    void topSlotKeepsLegalDropsBuiltinAndWiresFrontend() throws IOException {
        List<String> red = new ArrayList<>();
        String main = Files.readString(
                repoRoot().resolve("starbot-core/src/main/resources/config-ui/main.js"),
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
