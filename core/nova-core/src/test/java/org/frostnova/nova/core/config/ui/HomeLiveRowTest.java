package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HomeLiveRowTest {
    @Test
    @DisplayName("多位在播以紧凑行显示，保留缺口、暂停与空态说明")
    void liveStreamersRenderAsRows() throws Exception {
        FrontendFixture.runFromModule("src/test/resources/frontend/home-live-row-fixture.mjs", "首页当前直播");
    }
}
