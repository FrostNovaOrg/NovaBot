package org.frostnova.nova.console.controller;

import org.junit.jupiter.api.Test;

class StreamerSelfFrontendTest {
    @Test void friendMarkPersistsAndGroupCannotSelectIt() throws Exception {
        FrontendFixture.runFromModule("src/test/resources/frontend/push-streamer-self-fixture.mjs", "主播本人标记");
    }
}
