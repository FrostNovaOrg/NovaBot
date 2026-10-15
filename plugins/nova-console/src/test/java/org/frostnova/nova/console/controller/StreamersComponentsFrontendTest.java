package org.frostnova.nova.console.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

class StreamersComponentsFrontendTest {
    @Test
    @DisplayName("图表缺测与日期选择、响应尺寸、日历状态及抽屉焦点")
    void componentsRenderAndRelease() throws Exception {
        FrontendFixture.runFromModule("src/test/resources/frontend/streamers-components-fixture.mjs", "主播展示组件");
    }

    @Test
    @DisplayName("图表标签不重叠、按钮禁用样式与长日历滚动")
    void componentsInBrowser() throws Exception {
        assumeTrue(System.getenv("NOVABOT_CHROME_BINARY") != null,
                "未配置 NOVABOT_CHROME_BINARY，跳过需要浏览器布局与计算样式的检查");
        FrontendFixture.runFromModule("src/test/resources/frontend/streamers-components-browser-fixture.mjs", "主播组件浏览器");
    }
}
