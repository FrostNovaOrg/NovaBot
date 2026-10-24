package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

@DisplayName("取消启用智能助手")
class SettingsAssistantCancelTest {
    @Test
    @DisplayName("取消后保持关闭且不提交确认")
    void cancelKeepsAssistantDisabled() throws IOException, InterruptedException {
        FrontendFixture.run(FrontendFixture.fixture("settings-assistant-cancel-fixture.mjs"), "取消启用智能助手");
    }
}
