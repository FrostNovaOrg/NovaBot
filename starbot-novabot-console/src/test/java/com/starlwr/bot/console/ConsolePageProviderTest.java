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

    @Test
    @DisplayName("TOP 槽登记 push、script＋四 assets 在 classpath 可读")
    void topSlotRegistersPushAndAssetsAreOnClasspath() {
        List<String> red = new ArrayList<>();
        PushConsolePageProvider page = new PushConsolePageProvider();

        try {
            assertEquals("push", page.id(), "页标识必须是 push，地址才是 #/push");
            assertEquals(ConsolePageSlot.TOP, page.slot(), "推送页挂顶级槽，与首页／主播并列");
            assertEquals("推送", page.displayName(), "导航上显示「推送」");
            List<ConsolePageProvider> kept = ConsolePages.valid(List.of(page));
            assertEquals(List.of("push"), kept.stream().map(ConsolePageProvider::id).toList(),
                    "内置页闭集不得再拦 push，否则这一页登记不上");
        } catch (Throwable t) {
            red.add("① " + t.getMessage());
        }

        try {
            assertEquals("push.js", page.script(), "主脚本必须是 push.js");
            assertEquals(List.of("push-model.js", "sessions.js", "template.js", "template-model.js"),
                    page.assets(),
                    "附属脚本必须登记 push-model／sessions／template／template-model，否则 import 解析到 404");
        } catch (Throwable t) {
            red.add("② " + t.getMessage());
        }

        try {
            assertReadable("config-ui-pages/push.js");
            assertReadable("config-ui-pages/push-model.js");
            assertReadable("config-ui-pages/sessions.js");
            assertReadable("config-ui-pages/template.js");
            assertReadable("config-ui-pages/template-model.js");
        } catch (Throwable t) {
            red.add("③ " + t.getMessage());
        }

        if (!red.isEmpty()) {
            fail(red.size() + " 问红：" + String.join("；", red));
        }
    }

    /**
     * 向导「主播」步与首页「今日」卡：两处落位、两个脚本
     * <p>
     * 两件一起量，因为它们是同一件事的两面：产品形态从宿主搬进插件之后，
     * <b>宿主那边删干净了、这边没登记上</b>的表现是屏幕上少一块，而不是任何一处报错——
     * 向导少一步就直接跳到「发一条试试」，首页少一张卡就只是空了一截。
     * <p>
     * 落位各自要紧：向导步走 {@code SETUP_STEP}（宿主的向导页自己去取），
     * 首页卡走 {@code HOME_CARD}（挂在探针卡后面）。填成缺省的 {@code SETTINGS} 的话，
     * 两块都会跑到设置页「高级」折页里去，而那里根本不该有它们。
     */
    @Test
    @DisplayName("SETUP_STEP 槽登记主播步、HOME_CARD 槽登记今日卡，两个 script 在 classpath 可读")
    void setupStepAndHomeCardAreRegistered() {
        List<String> red = new ArrayList<>();

        try {
            ConsolePageProvider step = new SetupStreamerStepProvider();
            assertEquals("streamer", step.id(), "向导步的标识必须是 streamer，步骤表上的 key 就是它");
            assertEquals(ConsolePageSlot.SETUP_STEP, step.slot(), "主播步挂向导步槽");
            assertEquals("setup-streamer.js", step.script(), "主脚本必须是 setup-streamer.js");
            assertEquals(List.of("streamer"),
                    ConsolePages.valid(List.of(step)).stream().map(ConsolePageProvider::id).toList(),
                    "内置向导步闭集不得再拦 streamer，否则这一步登记不上");
        } catch (Throwable t) {
            red.add("① " + t);
        }

        try {
            ConsolePageProvider card = new TodayHomeCardProvider();
            assertEquals("today", card.id(), "首页卡的标识必须是 today，它会原样成为那张卡的 DOM id");
            assertEquals(ConsolePageSlot.HOME_CARD, card.slot(), "今日卡挂首页卡槽");
            assertEquals("today.js", card.script(), "主脚本必须是 today.js");
            assertEquals(List.of("today-model.js"), card.assets(),
                    "附属脚本必须登记 today-model.js，否则 import 解析到 404");
        } catch (Throwable t) {
            red.add("② " + t);
        }

        try {
            assertReadable("config-ui-pages/setup-streamer.js");
            assertReadable("config-ui-pages/today.js");
            assertReadable("config-ui-pages/today-model.js");
        } catch (Throwable t) {
            red.add("③ " + t);
        }

        if (!red.isEmpty()) {
            fail(red.size() + " 问红：" + String.join("；", red));
        }
    }

    /**
     * 两条导航入口的图标，与搬家前画的那两笔逐字相同
     * <p>
     * 这两串的出处不是这两个类，而是<b>它们还写在核心界面文件里的时候</b>：
     * 推送与主播两页搬进插件之后，建入口那一处只写文字，于是侧栏上这两条自搬家之日起
     * 就是光秃秃的——而其余四条都有图标。补回来时照抄原文，不另画。
     * <p>
     * 因此这一格钉的是一件源码本身答不出的事：<b>画的还是不是原来那两笔</b>。
     * 换了图案不会有任何报错，只是这两条入口从此与谁都对不上号。
     * 外壳（{@code viewBox}／{@code stroke-width} 那些）不在这里，它由核心统一套。
     */
    @Test
    @DisplayName("推送与主播两条入口的图标与搬家前的原文逐字相同")
    void topPageIconsMatchTheDrawingsFromBeforeTheMove() {
        List<String> red = new ArrayList<>();

        try {
            assertEquals("<path d=\"M2 4.2A1.2 1.2 0 0 1 3.2 3h9.6A1.2 1.2 0 0 1 14 4.2v6.1a1.2 1.2 0 0 1-1.2 1.2"
                            + "H6.4L3.4 14V11.5H3.2A1.2 1.2 0 0 1 2 10.3z\"/>",
                    new PushConsolePageProvider().icon(), "推送那只对话气泡");
        } catch (Throwable t) {
            red.add("① " + t.getMessage());
        }

        try {
            assertEquals("<circle cx=\"8\" cy=\"5.2\" r=\"2.6\"/>"
                            + "<path d=\"M2.8 14c0-2.9 2.3-4.6 5.2-4.6s5.2 1.7 5.2 4.6\"/>",
                    new StreamersConsolePageProvider().icon(), "主播那个头肩");
        } catch (Throwable t) {
            red.add("② " + t.getMessage());
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
