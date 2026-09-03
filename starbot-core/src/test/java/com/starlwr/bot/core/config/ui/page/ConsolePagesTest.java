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
}
