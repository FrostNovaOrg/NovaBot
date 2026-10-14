package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 设置页「高级选项」开关在组目录最底下
 * <p>
 * 抓的用户故障：点了目录底下的「高级选项」没反应，或者页上有两个同 id 的开关、点的那个不管用；
 * 高级收着时搜到高级里的项，目录里「高级」那一段却还收着。
 * <p>
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("高级选项开关在设置目录底部")
class SettingsAdvancedToggleTest {
    private static final String FIXTURE = FrontendFixture.fixture("settings-advanced-toggle-fixture.mjs");

    @Test
    @DisplayName("页上只有一个 show-advanced，在目录最后；点它高级区与目录高级段一起开合，aria-expanded 跟着变")
    void toggleAtBottomOfNav() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "目录底部的高级选项开关", "toggle");
    }

    @Test
    @DisplayName("高级收着时搜到只在高级里的项：高级区与目录里的高级段一起摆出来")
    void searchRevealsAdvancedNav() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "搜到高级项时目录高级段", "search");
    }
}
