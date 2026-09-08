package com.starlwr.bot.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 只读口令页判法的行为判据
 * <p>
 * 失败体怎么翻成人话、吊销之后要不要重取清单，这两件在 {@code tokens.js} 里是纯函数，
 * 因此可以喂值直接跑。静态检查看得见「有没有写」，看不见「写没写对」——
 * 比如把写盘失败那句人话吞成 HTTP 500，恰是这一组要逮的退步。
 * <p>
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("只读口令页判定")
class TokensModelTest {
    private static final String FIXTURE = "core/starbot-core/src/test/resources/frontend/tokens-model-fixture.mjs";

    @Test
    @DisplayName("失败体翻译与吊销后动作逐格与预期相同")
    void explainAndRevokeOutcome() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "只读口令页判定");
    }
}
