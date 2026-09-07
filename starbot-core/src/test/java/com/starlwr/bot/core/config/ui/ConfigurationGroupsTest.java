package com.starlwr.bot.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 设置页分组表的判据
 * <p>
 * 界面上那八个组的先后<b>就是这张表的先后</b>——前端照着 {@code /schema} 回的顺序渲染，
 * 不自己排。因此「六组的顺序对不对」在这里量得到，不必等到把页面打开。
 * 反过来说，这一格量不到的是「渲染出来的 DOM 真的是这个顺序吗」，那要在跑起来的界面上看。
 */
@DisplayName("设置页分组表")
class ConfigurationGroupsTest {
    @Test
    @DisplayName("常用六组在前，顺序是 推送→告警→命令与权限→采集→报告外观→登录与安全")
    void commonGroupsComeFirstInOrder() {
        List<String> common = new ArrayList<>();
        for (ConfigurationGroups.Group group : ConfigurationGroups.all()) {
            if (!group.advanced()) {
                common.add(group.id());
            }
        }

        assertEquals(List.of("push", "alert", "cmd", "collect", "report", "auth"), common,
                "常用组的顺序变了。它就是界面上从上往下的顺序，改它等于改版式");
    }

    @Test
    @DisplayName("高级两组排在最后，且只有这两组是高级")
    void advancedGroupsComeLast() {
        List<ConfigurationGroups.Group> all = ConfigurationGroups.all();
        List<String> advanced = new ArrayList<>();
        int firstAdvanced = -1;

        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).advanced()) {
                advanced.add(all.get(i).id());
                if (firstAdvanced < 0) {
                    firstAdvanced = i;
                }
            } else if (firstAdvanced >= 0) {
                // 高级组之后又冒出一个常用组，界面上它会被折进页底那张卡里
                throw new AssertionError("常用组 " + all.get(i).id() + " 排在高级组后面，它会被一起折起来");
            }
        }

        assertEquals(List.of("logdbg", "svc"), advanced, "高级组应当只有日志与调试、服务这两组");
        assertEquals(all.size() - 2, firstAdvanced, "高级组没有排在最后");
    }

    @Test
    @DisplayName("组标识两两不同，每组都有标题与说明")
    void everyGroupIsWellFormed() {
        List<String> ids = new ArrayList<>();

        for (ConfigurationGroups.Group group : ConfigurationGroups.all()) {
            assertTrue(group.id().matches("[a-z-]+"), "组标识要是 ASCII 小写，它会进地址与锚点: " + group.id());
            assertTrue(group.title() != null && !group.title().isBlank(), group.id() + " 没有标题");
            assertTrue(group.description() != null && !group.description().isBlank(),
                    group.id() + " 没有说明。组说明答的是「这一组管什么」，缺了它组名就得自己扛这件事");
            assertTrue(!ids.contains(group.id()), "组标识重了: " + group.id());
            ids.add(group.id());
        }

        assertEquals(8, ids.size(), "组是闭集，八个");
    }

    @Test
    @DisplayName("取最长前缀：整段归一组时，其中单独指名的那一项归它自己那一组")
    void longestPrefixWins() {
        Map<String, ConfigurationGroups.Group> contributed = new LinkedHashMap<>();
        contributed.put("starbot.test.foo", ConfigurationGroups.COLLECT);
        contributed.put("starbot.test.foo.logo", ConfigurationGroups.REPORT);
        ConfigurationGroups groups = ConfigurationGroups.of(List.of(() -> contributed));

        // 贡献者的前缀并进核心表之后，最长前缀跨合并表仍成立
        assertEquals(ConfigurationGroups.COLLECT, groups.groupOf("starbot.test.foo.backup"));
        assertEquals(ConfigurationGroups.REPORT, groups.groupOf("starbot.test.foo.logo"));
        assertEquals(ConfigurationGroups.AUTH,
                groups.groupOf("starbot.core.config-ui.auth.password"));
        assertEquals(ConfigurationGroups.SERVICE,
                groups.groupOf("starbot.core.event-stream.enabled"));
    }

    @Test
    @DisplayName("两方申报同一前缀须抛 IllegalStateException")
    void duplicatePrefixAcrossPartiesIsRejected() {
        Map<String, ConfigurationGroups.Group> first = new LinkedHashMap<>();
        first.put("starbot.test.dup", ConfigurationGroups.COLLECT);
        Map<String, ConfigurationGroups.Group> second = new LinkedHashMap<>();
        second.put("starbot.test.dup", ConfigurationGroups.REPORT);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> ConfigurationGroups.of(List.of(() -> first, () -> second)));
        assertEquals("配置分组前缀 starbot.test.dup 被写了两次: collect 与 report", ex.getMessage());
    }

    @Test
    @DisplayName("前缀只在整段边界上命中，不吃掉名字更长的兄弟键")
    void prefixMatchesOnSegmentBoundary() {
        ConfigurationGroups groups = ConfigurationGroups.core();
        assertEquals(ConfigurationGroups.SERVICE, groups.groupOf("starbot.core.network.read-timeout"));
        assertEquals(ConfigurationGroups.SERVICE, groups.groupOf("starbot.core.network-thread.max-pool-size"));
        // 前缀本身也是一个键时照样命中：starbot.core.sender 是叶子，不是一段
        assertEquals(ConfigurationGroups.PUSH, groups.groupOf("starbot.core.sender"));
        // 而它不该把名字以它开头的另一个键一起吃掉
        assertNull(groups.groupOf("starbot.core.senders-extra"),
                "前缀吃掉了名字更长的兄弟键，那一项会被摆进一个跟它无关的组里");
    }

    @Test
    @DisplayName("归不了组的回 null，不悄悄兜进某一组")
    void unmatchedKeyGetsNoGroup() {
        ConfigurationGroups groups = ConfigurationGroups.core();
        assertNull(groups.groupOf("starbot.brandnew.something"),
                "兜底会给此后每一个新配置项签一张免检牌：它安静地待在某个组里，没有任何东西会再提起这件事");
        assertNull(groups.groupOf(null));
        assertNull(groups.groupOf("完全不相干的东西"));
    }

    @Test
    @DisplayName("组的序号就是它在表里的位次")
    void orderMatchesPosition() {
        List<ConfigurationGroups.Group> all = ConfigurationGroups.all();
        for (int i = 0; i < all.size(); i++) {
            assertEquals(i, ConfigurationGroups.orderOf(all.get(i)), all.get(i).id() + " 的序号不对");
        }

        List<String> prefixes = ConfigurationGroups.core().prefixes();
        assertNotNull(prefixes);
        assertTrue(prefixes.size() >= all.size(),
                "前缀比组还少，至少有一组一条前缀都没有");
    }

    @Test
    @DisplayName("核心表零适配器前缀")
    void coreTableHasNoAdapterPrefixes() {
        List<String> prefixes = ConfigurationGroups.core().prefixes();
        List<String> adapter = new ArrayList<>();
        for (String prefix : prefixes) {
            if (prefix.startsWith("starbot.adapter.")) {
                adapter.add(prefix);
            }
        }

        assertTrue(prefixes.contains("starbot.core.push"),
                "阳性锚：核心表须含 starbot.core.push");
        assertTrue(adapter.isEmpty(),
                "核心表不得含适配器前缀，命中 " + adapter.size() + " 条：\n  "
                        + String.join("\n  ", adapter));
    }
}
