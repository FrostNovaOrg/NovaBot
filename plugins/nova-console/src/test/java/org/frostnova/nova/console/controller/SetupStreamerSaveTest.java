package org.frostnova.nova.console.controller;

import org.junit.jupiter.api.Test;
import java.io.IOException;

class SetupStreamerSaveTest {
    @Test
    void addingStreamerPreservesDraftsAndConcurrentSaves() throws IOException, InterruptedException {
        FrontendFixture.runFromModule("src/test/resources/frontend/setup-streamer-save-fixture.mjs", "向导按主播保存");
    }
}
