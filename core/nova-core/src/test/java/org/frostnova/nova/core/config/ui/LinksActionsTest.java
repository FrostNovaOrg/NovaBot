package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.Test;

class LinksActionsTest {
    private static final String FIXTURE = FrontendFixture.fixture("links-actions-fixture.mjs");

    @Test
    void testMessageResultsFollowTheirOriginalTargets() throws Exception {
        FrontendFixture.run(FIXTURE, "试发结果归到原目标", "send");
    }

    @Test
    void napCatToggleConfirmsSavesAndRestoresOnFailure() throws Exception {
        FrontendFixture.run(FIXTURE, "NapCat 通道开关即时保存与失败恢复", "channel");
    }
}
