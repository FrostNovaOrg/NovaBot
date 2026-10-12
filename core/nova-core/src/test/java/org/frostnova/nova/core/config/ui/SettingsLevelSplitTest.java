package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 设置页常用／高级按服务端给每一项标的级别分
 * <p>
 * 抓的用户故障：插件新加的设置项落错了地方——该进高级的挤在常用里，或常用的整组掉进高级。
 * 界面上没有另一张写着键名的名单，分法只认 schema 的 level 与组上的 advanced。
 * <p>
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("设置页常用／高级按服务端级别分")
class SettingsLevelSplitTest {
    private static final String FIXTURE = FrontendFixture.fixture("settings-level-split-fixture.mjs");

    @Test
    @DisplayName("ADVANCED 进高级、BASIC／COMMON 留常用、advanced 组整组进高级")
    void splitFollowsServerLevel() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "常用高级分组");
    }
}
