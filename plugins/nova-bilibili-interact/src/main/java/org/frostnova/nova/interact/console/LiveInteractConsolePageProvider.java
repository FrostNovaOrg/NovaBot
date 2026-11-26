package org.frostnova.nova.interact.console;

import org.frostnova.nova.core.config.ui.ConfigUiOperationProvider;
import org.frostnova.nova.core.config.ui.page.ConsolePageProvider;
import org.frostnova.nova.core.config.ui.page.ConsolePageSlot;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.util.List;

/**
 * 「直播间互动」页：选发弹幕用哪个账号、试发一条
 * <p>
 * 与首页、推送、主播并列的一整页，地址 {@code #/live-interact}，只给管理员看：成员的导航里没有这一项，
 * 这一页调的接口也一律只给管理员。插件挪走，入口跟着消失。
 */
@NovaComponent
@ConditionalOnProperty(name = "novabot.core.config-ui.enabled", havingValue = "true", matchIfMissing = true)
public class LiveInteractConsolePageProvider implements ConsolePageProvider, ConfigUiOperationProvider {
    /**
     * 页标识，也是地址里的那一段
     */
    public static final String ID = "live-interact";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "直播间互动";
    }

    @Override
    public String script() {
        return "live-interact.js";
    }

    @Override
    public int order() {
        return 50;
    }

    @Override
    public ConsolePageSlot slot() {
        return ConsolePageSlot.TOP;
    }

    /**
     * 一个对话气泡
     */
    @Override
    public String icon() {
        return "<path d=\"M2.5 3.5h11v7h-6l-3 2.5v-2.5h-2z\"/>"
                + "<line x1=\"5\" y1=\"6\" x2=\"11\" y2=\"6\"/><line x1=\"5\" y1=\"8.2\" x2=\"9\" y2=\"8.2\"/>";
    }

    @Override
    public List<Operation> operations() {
        return List.of(new Operation("POST", "interact/danmaku/test", "DANMAKU_TEST_SEND", "试发一条弹幕"));
    }
}
