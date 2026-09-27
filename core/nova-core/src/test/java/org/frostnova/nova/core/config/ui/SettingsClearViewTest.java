package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 设置页清除钮：清除态重画不丢，撤回一次即恢复，保存后键被删
 * <p>
 * 抓的用户故障：点了「清除」、还没保存时页面一重画，框里又显示遮点、钮变回「清除」，
 * 「保存后清除」不见了——看着像没动，可一保存已存的值照样被删，要撤回得连点两下。
 * 夹具读源码树里那一份，不是构建产物里的副本。切段按花括号配平截到块尾后真执行。
 * <p>
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("设置页清除钮：清除态重画不丢")
class SettingsClearViewTest {
    private static final String FIXTURE = FrontendFixture.fixture("settings-clear-fixture.mjs");

    @Test
    @DisplayName("重画后仍是清除态；撤回一次即恢复；保存后键被删")
    void clearStateSurvivesRepaint() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "设置页清除钮");
    }
}
