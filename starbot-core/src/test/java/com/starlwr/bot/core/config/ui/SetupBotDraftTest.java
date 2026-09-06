package com.starlwr.bot.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 初始设置第 2 步：改了机器人地址、端口或 Token 后，先前测通必须作废
 * <p>
 * 测通后再改格子里的值、不点「测试连接」就下一步时，放行依据必须不能还是旧结论。
 * 夹具读源码树里那一份，不是构建产物里的副本。切段按花括号配平截到块尾后真执行。
 * <p>
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("初始设置改机器人参数即作废旧测通")
class SetupBotDraftTest {
    private static final String FIXTURE =
            "starbot-core/src/test/resources/frontend/setup-bot-draft-fixture.mjs";

    @Test
    @DisplayName("invalidateBot 清 botOk；五格输入都接到它；botOk 为假时第 2 步拦住")
    void changingBotParamsInvalidatesOldTest() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "初始设置改机器人参数即作废旧测通");
    }
}
