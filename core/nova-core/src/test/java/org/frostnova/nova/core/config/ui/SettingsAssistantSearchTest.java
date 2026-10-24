package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

@DisplayName("搜索智能助手管理卡片")
class SettingsAssistantSearchTest {
    @Test
    @DisplayName("搜索组名时五张卡全部可见")
    void groupSearchShowsAllCards() throws IOException, InterruptedException {
        FrontendFixture.runFromModule("src/test/resources/frontend/settings-assistant-search-fixture.mjs",
                "搜索智能助手管理卡片");
    }
}
