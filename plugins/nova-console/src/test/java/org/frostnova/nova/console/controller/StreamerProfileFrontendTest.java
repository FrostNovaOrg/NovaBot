package org.frostnova.nova.console.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StreamerProfileFrontendTest {
    @Test @DisplayName("档案只读显示全部设置与时间，错误和切换主播不会露出旧档案")
    void profileIsReadablePrivateAndReadOnly() throws Exception {
        FrontendFixture.runFromModule("src/test/resources/frontend/streamers-profile-fixture.mjs", "主播档案");
    }
}
