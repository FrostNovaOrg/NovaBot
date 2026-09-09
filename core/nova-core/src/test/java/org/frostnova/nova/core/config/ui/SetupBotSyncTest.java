package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 重进初始设置时，机器人草稿须回到与盘上一致
 * <p>
 * 切出再回来时 Token 格不得留着没存过的输入，否则既成事实会把先前测通翻回有效，
 * 「下一步」放行、屏幕上的 Token 却从未存过。夹具读源码树里那一份，
 * 不是构建产物里的副本。切段按花括号配平截到块尾后真执行。
 * <p>
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("重进向导时机器人草稿与盘上同步")
class SetupBotSyncTest {
    private static final String FIXTURE =
            "core/nova-core/src/test/resources/frontend/setup-bot-sync-fixture.mjs";

    @Test
    @DisplayName("已配过则回填地址端口并清空 Token；未配过五格不变；openSetup 只调 syncBotDraft")
    void reenteringSetupSyncsBotDraftWithSavedConnection() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "重进向导时机器人草稿与盘上同步");
    }
}
