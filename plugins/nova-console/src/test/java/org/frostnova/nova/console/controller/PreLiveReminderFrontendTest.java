package org.frostnova.nova.console.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PreLiveReminderFrontendTest {
    @Test @DisplayName("提醒状态、逐项确认和请求期间禁用，避免误判停发和重复试发")
    void reminderStatusAndTrial() throws Exception {
        FrontendFixture.runFromModule("src/test/resources/frontend/pre-live-reminder-fixture.mjs", "开播前提醒状态与试发");
    }
}
