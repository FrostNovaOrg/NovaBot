package org.frostnova.nova.console.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StreamerInsightsFrontendTest {
    @Test @DisplayName("范围地址恢复、空日不画柱、切换保留本场刷新")
    void rangeAndDailyChart() throws Exception {
        FrontendFixture.runFromModule("src/test/resources/frontend/streamer-insights-fixture.mjs", "主播范围概况");
    }
}
