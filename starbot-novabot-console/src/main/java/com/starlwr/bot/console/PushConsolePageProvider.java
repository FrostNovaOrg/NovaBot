package com.starlwr.bot.console;

import com.starlwr.bot.core.config.ui.page.ConsolePageProvider;
import com.starlwr.bot.core.config.ui.page.ConsolePageSlot;
import com.starlwr.bot.core.plugin.StarBotComponent;

import java.util.List;

/**
 * 推送页：主播、通道、模板与本群设置
 * <p>
 * 这一页回答「推谁、推到哪、推什么」。它不是某个聊天平台的连接卡，
 * 所以挂在顶级导航，地址 {@code #/push}。装上本插件才有这一页；
 * 卸掉之后入口跟着消失，而不是点开一张空页。
 */
@StarBotComponent
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

    @Override
    public ConsolePageSlot slot() {
        return ConsolePageSlot.TOP;
    }
}
