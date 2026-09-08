package com.starlwr.bot.bilibili.console;

import com.starlwr.bot.bilibili.BilibiliPlatform;
import com.starlwr.bot.core.config.ui.page.ConsolePageProvider;
import com.starlwr.bot.core.config.ui.page.ConsolePageSlot;
import com.starlwr.bot.core.plugin.NovaComponent;

/**
 * 哔哩哔哩的控制台页面
 * <p>
 * 页签、页面内容与页面里调的那几个接口，从此都随本插件走：装了这个插件才有「哔哩哔哩」页，
 * 把插件挪走页签就跟着消失。此前它写死在核心的界面文件里，于是没装插件的实例也立着这个入口，
 * 点开是一张空页——而使用者无从判断是插件没装还是它坏了。
 * <p>
 * 落位是连接页上的一张卡：这一页上做的事是扫码登录与查看登录态，
 * 也就是「这台机器与外面怎么连」，与机器人连接、外部面板口令是同一类事。
 */
@NovaComponent
public class BilibiliConsolePageProvider implements ConsolePageProvider {
    @Override
    public String id() {
        return BilibiliPlatform.BILIBILI.id();
    }

    @Override
    public String displayName() {
        return "哔哩哔哩";
    }

    @Override
    public String script() {
        return "bilibili.js";
    }

    @Override
    public ConsolePageSlot slot() {
        return ConsolePageSlot.LINKS;
    }
}
