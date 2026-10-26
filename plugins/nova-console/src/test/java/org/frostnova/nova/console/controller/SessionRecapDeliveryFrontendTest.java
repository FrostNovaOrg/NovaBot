package org.frostnova.nova.console.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SessionRecapDeliveryFrontendTest {
    @Test @DisplayName("小结状态、恢复入口与逐项确认，避免不知道停发或误点打扰主播")
    void statusRecoveryAndTrialConfirmations() throws Exception {
        FrontendFixture.runFromModule("src/test/resources/frontend/session-recap-delivery-fixture.mjs", "下播小结状态与试发");
    }
}
