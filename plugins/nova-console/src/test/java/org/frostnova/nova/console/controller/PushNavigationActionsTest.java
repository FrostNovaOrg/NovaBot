package org.frostnova.nova.console.controller;

import org.junit.jupiter.api.Test;

import java.io.IOException;

class PushNavigationActionsTest {
    @Test
    void defaultTemplateActionsChannelPickerAndNavigationRemainUsable() throws IOException, InterruptedException {
        FrontendFixture.runFromModule("src/test/resources/frontend/push-navigation-actions-fixture.mjs",
                "默认模板操作与推送导航");
    }
}
