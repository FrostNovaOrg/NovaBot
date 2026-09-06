package com.starlwr.bot.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 初始设置第 4 步：改了主播 uid 或平台后，先前找到的那位必须作废
 * <p>
 * 找到 A 再把格子改成 B、不点「找一下」就下一步时，落盘的必须不能还是 A。
 * 夹具读源码树里那一份，不是构建产物里的副本。切段按花括号配平截到块尾后真执行。
 * <p>
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("初始设置改 uid 或平台即作废已找到的主播")
class SetupStreamerDraftTest {
    private static final String FIXTURE =
            "starbot-core/src/test/resources/frontend/setup-streamer-draft-fixture.mjs";

    @Test
    @DisplayName("invalidateStreamer 清空草稿；uid 与平台变更都接到它；streamer 为空时第 4 步拦住")
    void changingUidOrPlatformInvalidatesFoundStreamer() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "初始设置改 uid 或平台即作废已找到的主播");
    }
}
