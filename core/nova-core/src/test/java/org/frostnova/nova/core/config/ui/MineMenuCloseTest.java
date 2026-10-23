package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 手机顶栏「我的」的收起
 * <p>
 * 点开以后焦点还在按钮上，这时按 Esc 也得收起来；面板里按 Esc、再点按钮、点别处照旧都能收。
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("「我的」菜单收起")
class MineMenuCloseTest {
    /**
     * 夹具在仓库里的位置。切的是源码树里的 personal-menu.js，不是构建产物里的副本
     */
    private static final String FIXTURE = FrontendFixture.fixture("mine-menu-close-fixture.mjs");

    @Test
    @DisplayName("按钮上按 Esc、面板里按 Esc、再点按钮、点别处都能收起")
    void escapeClosesFromToggleAndPanel() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "「我的」菜单收起");
    }
}
