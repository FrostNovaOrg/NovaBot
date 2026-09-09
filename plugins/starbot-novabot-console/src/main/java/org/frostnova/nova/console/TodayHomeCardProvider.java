package org.frostnova.nova.console;

import org.frostnova.nova.core.config.ui.page.ConsolePageProvider;
import org.frostnova.nova.core.config.ui.page.ConsolePageSlot;
import org.frostnova.nova.core.plugin.NovaComponent;

import java.util.List;

/**
 * 首页那张「今日」卡：今天推了几条、失败几条、@全体成员 用了几次，以及推送总开关
 * <p>
 * 三个数与那个开关问的都是「推送这件事今天怎么样」——产品形态，不是壳。
 * 装上本插件才有这张卡；卸掉之后首页少一张卡，而不是留一张永远写着「—」的空卡。
 * <p>
 * 「立即自检」不在这张卡上：它问的是这台机器此刻健不健康，归宿主的健康自检那一块。
 */
@NovaComponent
public class TodayHomeCardProvider implements ConsolePageProvider {
    @Override
    public String id() {
        return "today";
    }

    @Override
    public String displayName() {
        return "今日";
    }

    @Override
    public String script() {
        return "today.js";
    }

    @Override
    public List<String> assets() {
        return List.of("today-model.js");
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    public ConsolePageSlot slot() {
        return ConsolePageSlot.HOME_CARD;
    }
}
