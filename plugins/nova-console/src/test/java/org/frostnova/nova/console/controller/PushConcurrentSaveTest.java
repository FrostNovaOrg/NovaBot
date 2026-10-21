package org.frostnova.nova.console.controller;

import org.junit.jupiter.api.Test;
import java.io.IOException;

class PushConcurrentSaveTest {
    @Test
    void concurrentPagesAndConflicts() throws IOException, InterruptedException {
        FrontendFixture.runFromModule("src/test/resources/frontend/push-concurrent-save-fixture.mjs", "按主播保存与冲突提示");
    }
}
