package org.frostnova.nova.console;

import org.frostnova.nova.core.config.ui.page.ConsolePageProvider;
import org.frostnova.nova.core.config.ui.page.ConsolePageSlot;
import org.frostnova.nova.core.plugin.NovaComponent;

import java.util.List;

/**
 * 主播页：列表、详情、某一场
 * <p>
 * 这一页回答「各位主播播了什么、播得怎么样」。它不是某个直播平台的连接卡，
 * 所以挂在顶级导航，地址 {@code #/streamers}。装上本插件才有这一页；
 * 卸掉之后入口跟着消失，而不是点开一张空页。
 */
@NovaComponent
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

    /**
     * 一个人的头肩。这一笔形状是这一页搬出核心之前导航上就在画的那一个，逐字照抄
     */
    @Override
    public String icon() {
        return "<circle cx=\"8\" cy=\"5.2\" r=\"2.6\"/>"
                + "<path d=\"M2.8 14c0-2.9 2.3-4.6 5.2-4.6s5.2 1.7 5.2 4.6\"/>";
    }

    @Override
    public ConsolePageSlot slot() {
        return ConsolePageSlot.TOP;
    }
}
