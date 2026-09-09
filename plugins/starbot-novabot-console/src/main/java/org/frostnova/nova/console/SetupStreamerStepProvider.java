package org.frostnova.nova.console;

import org.frostnova.nova.core.config.ui.page.ConsolePageProvider;
import org.frostnova.nova.core.config.ui.page.ConsolePageSlot;
import org.frostnova.nova.core.plugin.NovaComponent;

import java.util.List;

/**
 * 初始设置向导里的「第一位主播，推到哪」这一步
 * <p>
 * 查一位主播、挑几个推送目标、把这两样写进数据源——三件都是产品形态，不是壳。
 * 装上本插件才有这一步；卸掉之后向导少一步，而不是留一步点进去查不到任何东西。
 * <p>
 * 标识就是步骤表上的 key，因此必须是 {@code streamer}：首页那条待办、进度条上的记号
 * 与「从第几步接着走」都按这个键认。
 */
@NovaComponent
public class SetupStreamerStepProvider implements ConsolePageProvider {
    @Override
    public String id() {
        return "streamer";
    }

    @Override
    public String displayName() {
        return "第一位主播，推到哪";
    }

    @Override
    public String script() {
        return "setup-streamer.js";
    }

    @Override
    public List<String> assets() {
        return List.of();
    }

    @Override
    public int order() {
        return 40;
    }

    @Override
    public ConsolePageSlot slot() {
        return ConsolePageSlot.SETUP_STEP;
    }
}
