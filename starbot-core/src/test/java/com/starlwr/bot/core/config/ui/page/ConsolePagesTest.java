package com.starlwr.bot.core.config.ui.page;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
