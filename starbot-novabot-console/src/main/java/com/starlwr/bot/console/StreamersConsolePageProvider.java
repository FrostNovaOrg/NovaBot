package com.starlwr.bot.console;

import com.starlwr.bot.core.config.ui.page.ConsolePageProvider;
import com.starlwr.bot.core.config.ui.page.ConsolePageSlot;
import com.starlwr.bot.core.plugin.StarBotComponent;

import java.util.List;

/**
 * 主播页：列表、详情、某一场
 * <p>
 * 这一页回答「各位主播播了什么、播得怎么样」。它不是某个直播平台的连接卡，
 * 所以挂在顶级导航，地址 {@code #/streamers}。装上本插件才有这一页；
 * 卸掉之后入口跟着消失，而不是点开一张空页。
 */
@StarBotComponent
public class StreamersConsolePageProvider implements ConsolePageProvider {
    @Override
    public String id() {
        return "streamers";
    }

    @Override
    public String displayName() {
        return "主播";
    }

    @Override
    public String script() {
        return "streamers.js";
    }

    @Override
    public List<String> assets() {
        return List.of("streamers-model.js");
    }

    @Override
    public int order() {
        return 30;
    }

    @Override
    public ConsolePageSlot slot() {
        return ConsolePageSlot.TOP;
    }
}
