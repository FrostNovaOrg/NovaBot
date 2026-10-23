package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 设置页收起的组里，搜索与筛选仍能找到那一项
 * <p>
 * 抓的用户故障：组默认收起以后，搜出来的命中项就在收着的组里，屏幕上只有一排组名——
 * 搜到了却看不见。筛着东西的时候有命中的组要先放出来，筛完再按原来收放复原。
 * <p>
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("收起的组里搜得到也看得见")
class SettingsGroupFoldTest {
    private static final String FIXTURE = FrontendFixture.fixture("settings-group-fold-fixture.mjs");

    @Test
    @DisplayName("搜索与筛选放出行里的命中项，筛完回到原来的收放")
    void searchFindsCollapsedItems() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "收起的组与搜索");
    }
}
