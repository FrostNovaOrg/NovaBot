package org.frostnova.nova.console.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 通道规则、命令与本群状态：命令那一块开合记住、命令分类原生折叠、问号说明开得了也关得了
 * <p>
 * 用户那头的毛病：展开的栏目切到别的通道再回来又收起了，连改几处每改一处都得再展开一次；
 * 命令开关要先点「细调」才看得见，还以为整组没有开关；几行长说明摊在段里占掉大半屏。
 * <p>
 * 夹具<b>真跑</b>：假 DOM 之外，画这几块用的是产品码那一份。只查源码里有没有某段字
 * 是不行的——字在、画不出来，照样绿。
 * <p>
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("通道规则与命令：命令开合记住、命令分类折叠、问号说明")
class SessionSettingsFoldFixtureTest {
    /**
     * 夹具按模块内相对位置认，不写死模块目录名——写死目录名的旧写法只减不增
     */
    private static final String FIXTURE =
            "src/test/resources/frontend/session-settings-fold-fixture.mjs";

    @Test
    @DisplayName("切走再回来命令仍摊着，命令分类是 details 且无「细调」，问号说明键盘与点按都开得了")
    void sectionRowsStayOpenAndHelpIsReachable() throws IOException, InterruptedException {
        FrontendFixture.runFromModule(FIXTURE, "本群设置的折叠与问号");
    }
}
