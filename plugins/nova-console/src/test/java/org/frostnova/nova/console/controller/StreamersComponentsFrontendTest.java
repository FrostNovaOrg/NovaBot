package org.frostnova.nova.console.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StreamersComponentsFrontendTest {
    @Test
    @DisplayName("图表缺测与日期选择、响应尺寸、日历状态及抽屉焦点")
    void componentsRenderAndRelease() throws Exception {
        FrontendFixture.runFromModule("src/test/resources/frontend/streamers-components-fixture.mjs", "主播展示组件");
    }
}
