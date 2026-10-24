package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;

class SettingsAssistantCardsTest {
    @Test
    void adminCanConfigureAssistantInCommonSettings() throws IOException, InterruptedException {
        FrontendFixture.runFromModule("src/test/resources/frontend/settings-assistant-cards-fixture.mjs",
                "智能助手设置卡片与开启保存");
    }
}
