package org.frostnova.nova.console.controller;

import org.junit.jupiter.api.Test;
import java.io.IOException;

class ChannelSettingsFrontendTest {
    @Test
    void editsOnlyCurrentChannelAndRejectsStaleDrafts() throws IOException, InterruptedException {
        FrontendFixture.runFromModule("src/test/resources/frontend/channel-settings-fixture.mjs", "通道设置保存与冲突");
    }
}
