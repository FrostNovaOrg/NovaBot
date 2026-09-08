package com.starlwr.bot.adapter.onebot.config;

import com.starlwr.bot.core.config.ui.ConfigurationGroupContributor;
import com.starlwr.bot.core.config.ui.ConfigurationGroups;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * OneBot 适配器申报给设置页的配置分组前缀
 */
@DisplayName("OneBot 配置分组前缀")
class OneBotConfigurationGroupsTest {
    private static final List<String> EXPECTED = List.of(
            "novabot.adapter.onebot.extension.napcat.enable-backup-at-all",
            "novabot.adapter.onebot.alert",
            "novabot.adapter.onebot.napcat",
            "novabot.adapter.onebot.base-url",
            "novabot.adapter.onebot.senders",
            "novabot.adapter.onebot.security",
            "novabot.adapter.onebot.websocket-thread",
            "novabot.adapter.onebot.detect",
            "novabot.adapter.onebot.extension");

    @Test
    @DisplayName("申报九条、合并表最长前缀胜、与核心表撞前缀须抛")
    void contributorDeclaresSevenPrefixesAndMerges() {
        List<String> red = new ArrayList<>();
        OneBotConfigurationGroups contributor = new OneBotConfigurationGroups();

        try {
            Map<String, ConfigurationGroups.Group> declared = contributor.prefixes();
            assertEquals(EXPECTED, List.copyOf(declared.keySet()),
                    "申报须恰 9 条且顺序为 enable-backup-at-all、alert、napcat 再其余六");
            assertEquals(ConfigurationGroups.PUSH,
                    declared.get("novabot.adapter.onebot.extension.napcat.enable-backup-at-all"),
                    "enable-backup-at-all 须落推送");
            assertEquals(ConfigurationGroups.ALERT,
                    declared.get("novabot.adapter.onebot.alert"),
                    "alert 须落告警");
            for (int i = 2; i < EXPECTED.size(); i++) {
                String prefix = EXPECTED.get(i);
                assertEquals(ConfigurationGroups.SERVICE, declared.get(prefix),
                        prefix + " 须落服务");
            }
        } catch (AssertionError e) {
            red.add("①" + e.getMessage());
        }

        try {
            ConfigurationGroups groups = ConfigurationGroups.of(List.of(contributor));
            assertEquals(ConfigurationGroups.SERVICE,
                    groups.groupOf("novabot.adapter.onebot.senders.api-token"),
                    "senders.api-token 须落服务");
            assertEquals(ConfigurationGroups.PUSH,
                    groups.groupOf("novabot.adapter.onebot.extension.napcat.enable-backup-at-all"),
                    "enable-backup-at-all 须落推送（最长前缀胜）");
            assertEquals(ConfigurationGroups.ALERT,
                    groups.groupOf("novabot.adapter.onebot.alert.platform"),
                    "alert.platform 须落告警");
        } catch (AssertionError e) {
            red.add("②" + e.getMessage());
        }

        try {
            Map<String, ConfigurationGroups.Group> clash = new LinkedHashMap<>();
            clash.put("novabot.core.push", ConfigurationGroups.COLLECT);
            ConfigurationGroupContributor duplicate = () -> clash;
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> ConfigurationGroups.of(List.of(contributor, duplicate)),
                    "与核心表重复申报 novabot.core.push 须抛 IllegalStateException");
            assertEquals("配置分组前缀 novabot.core.push 被写了两次: push 与 collect",
                    ex.getMessage());
        } catch (AssertionError e) {
            red.add("③" + e.getMessage());
        }

        if (!red.isEmpty()) {
            fail("红格 " + red.size() + "：" + String.join("；", red));
        }
    }
}
