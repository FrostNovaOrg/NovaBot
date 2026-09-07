package com.starlwr.bot.console;

import com.starlwr.bot.core.config.ui.page.ConsolePageProvider;
import com.starlwr.bot.core.config.ui.page.ConsolePageSlot;
import com.starlwr.bot.core.config.ui.page.ConsolePages;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 主播页登记：顶级槽、脚本与附属脚本都在 classpath 上
 */
@DisplayName("控制台主播页登记")
class ConsolePageProviderTest {
    @Test
    @DisplayName("TOP 槽登记 streamers、script＋assets 两件在 classpath 可读")
    void topSlotRegistersStreamersAndAssetsAreOnClasspath() {
        List<String> red = new ArrayList<>();
        StreamersConsolePageProvider page = new StreamersConsolePageProvider();

        try {
            assertEquals("streamers", page.id(), "页标识必须是 streamers，地址才是 #/streamers");
            assertEquals(ConsolePageSlot.TOP, page.slot(), "主播页挂顶级槽，与首页／推送并列");
            assertEquals("主播", page.displayName(), "导航上显示「主播」");
            List<ConsolePageProvider> kept = ConsolePages.valid(List.of(page));
            assertEquals(List.of("streamers"), kept.stream().map(ConsolePageProvider::id).toList(),
                    "内置页闭集不得再拦 streamers，否则这一页登记不上");
        } catch (Throwable t) {
            red.add("① " + t.getMessage());
        }

        try {
            assertEquals("streamers.js", page.script(), "主脚本必须是 streamers.js");
            assertEquals(List.of("streamers-model.js"), page.assets(),
                    "附属脚本必须登记 streamers-model.js，否则 import 解析到 404");
        } catch (Throwable t) {
            red.add("② " + t.getMessage());
        }

        try {
            assertReadable("config-ui-pages/streamers.js");
            assertReadable("config-ui-pages/streamers-model.js");
        } catch (Throwable t) {
            red.add("③ " + t.getMessage());
        }

        if (!red.isEmpty()) {
            fail(red.size() + " 问红：" + String.join("；", red));
        }
    }

    private static void assertReadable(String resource) {
        InputStream in = ConsolePageProviderTest.class.getClassLoader().getResourceAsStream(resource);
        assertTrue(in != null, resource + " 在 classpath 上读不到");
        try {
            in.close();
        } catch (Exception ignored) {
            // 读到了就算
        }
    }
}
