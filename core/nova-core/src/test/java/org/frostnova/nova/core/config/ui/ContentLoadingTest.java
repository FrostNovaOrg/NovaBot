package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ContentLoadingTest {
    @Test
    @DisplayName("换页后数据未到，空内容区有读屏可读的加载占位")
    void emptyCardsHaveLoadingFeedback() throws Exception {
        FrontendFixture.run(java.nio.file.Path.of("src/test/resources/frontend/content-loading-fixture.mjs").toAbsolutePath().toString(), "空卡片加载反馈", "cards");
    }

    @Test
    @DisplayName("取数期间显示占位，成功或失败后收起并保留原提示")
    void pendingRequestsSettle() throws Exception {
        FrontendFixture.run(java.nio.file.Path.of("src/test/resources/frontend/content-loading-fixture.mjs").toAbsolutePath().toString(), "加载占位生命周期", "requests");
    }

    @Test
    @DisplayName("整体取数失败后首页与设置页收起占位，两处摘要显示未获取到")
    void failedLoadClearsBothPages() throws Exception {
        FrontendFixture.run(java.nio.file.Path.of("src/test/resources/frontend/content-loading-fixture.mjs").toAbsolutePath().toString(), "整体取数失败反馈", "load-failure");
    }

    @Test
    @DisplayName("占位延迟显现、跟随配色，减少动态效果时停止流光")
    void loadingAppearance() throws Exception {
        FrontendFixture.run(java.nio.file.Path.of("src/test/resources/frontend/content-loading-fixture.mjs").toAbsolutePath().toString(), "加载占位样式", "appearance");
    }
}
