package org.frostnova.nova.interact.speaker;

import org.frostnova.nova.core.config.ui.page.ConsolePageProvider;
import org.frostnova.nova.core.config.ui.page.ConsolePageSlot;
import org.frostnova.nova.core.plugin.NovaComponent;

/**
 * 连接页上的「哔哩哔哩发言账号」卡
 * <p>
 * 与监听账号那张卡分开立：自己的灯、二维码、昵称、到期时间与退出按钮。
 * 页标识与账号标识取同一个值，控制台靠它把卡与账号配上对。只给管理员看，与监听账号那张卡同一个口径。
 */
@NovaComponent
public class BilibiliSpeakerConsolePageProvider implements ConsolePageProvider {
    @Override
    public String id() {
        return BilibiliSpeakerAccount.ID;
    }

    @Override
    public String displayName() {
        return BilibiliSpeakerAccount.DISPLAY_NAME;
    }

    @Override
    public String script() {
        return "bilibili-speaker.js";
    }

    @Override
    public int order() {
        return 11;
    }

    @Override
    public ConsolePageSlot slot() {
        return ConsolePageSlot.LINKS;
    }
}
