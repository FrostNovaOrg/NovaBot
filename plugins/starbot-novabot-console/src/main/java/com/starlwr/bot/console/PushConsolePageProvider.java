package com.starlwr.bot.console;

import com.starlwr.bot.core.config.ui.page.ConsolePageProvider;
import com.starlwr.bot.core.config.ui.page.ConsolePageSlot;
import com.starlwr.bot.core.plugin.NovaComponent;

import java.util.List;

/**
 * 推送页：主播、通道、模板与本群设置
 * <p>
 * 这一页回答「推谁、推到哪、推什么」。它不是某个聊天平台的连接卡，
 * 所以挂在顶级导航，地址 {@code #/push}。装上本插件才有这一页；
 * 卸掉之后入口跟着消失，而不是点开一张空页。
 */
@NovaComponent
public class PushConsolePageProvider implements ConsolePageProvider {
    @Override
    public String id() {
        return "push";
    }

    @Override
    public String displayName() {
        return "推送";
    }

    @Override
    public String script() {
        return "push.js";
    }

    @Override
    public List<String> assets() {
        return List.of("push-model.js", "sessions.js", "template.js", "template-model.js");
    }

    @Override
    public int order() {
        return 20;
    }

    /**
     * 一只对话气泡。这一笔形状是这一页搬出核心之前导航上就在画的那一只，逐字照抄
     */
    @Override
    public String icon() {
        return "<path d=\"M2 4.2A1.2 1.2 0 0 1 3.2 3h9.6A1.2 1.2 0 0 1 14 4.2v6.1a1.2 1.2 0 0 1-1.2 1.2H6.4"
                + "L3.4 14V11.5H3.2A1.2 1.2 0 0 1 2 10.3z\"/>";
    }

    @Override
    public ConsolePageSlot slot() {
        return ConsolePageSlot.TOP;
    }
}
