package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 另一个控制台标签切了深浅色，这一个跟着变，而且没保存的设置与焦点都还在
 * <p>
 * 抓的用户故障：两个标签主题对不上；或者跟着变了，却是靠重画设置页，正填着的那一格被清掉。
 * <p>
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("主题跨标签同步")
class ThemeCrossTabTest {
    private static final String FIXTURE = FrontendFixture.fixture("theme-cross-tab-fixture.mjs");

    @Test
    @DisplayName("storage 事件（含 clear）换肤、钮跟着变，输入框的值与焦点不动")
    void otherTabThemeKeepsDrafts() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "主题跨标签同步");
    }
}
