package org.frostnova.nova.console.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 推送页「＋ 添加主播」的面板，只用键盘也能用
 * <p>
 * 用户那头的故障：点开面板焦点没进去，打不了字；Esc 关不掉；Tab 走到面板后面那一页上；
 * 关掉以后焦点丢了。夹具在假 DOM 上真跑推送页建页、开关面板与圈 Tab 那几段产品码。
 * <p>
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("添加主播面板的键盘操作")
class PushDrawerKeyboardTest {

    @Test
    @DisplayName("打开即可打字，Esc 能关，Tab 圈在面板里，关掉或加完主播后焦点回到按钮")
    void addStreamerDrawerWorksFromKeyboard() throws IOException, InterruptedException {
        FrontendFixture.runFromModule("src/test/resources/frontend/push-drawer-keyboard-fixture.mjs",
                "添加主播面板的键盘操作");
    }
}
