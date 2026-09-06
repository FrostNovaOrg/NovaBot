package com.starlwr.bot.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 连接页机器人表单：改了地址、端口或 Token 后，先前测通必须作废
 * <p>
 * 测通后再改格子里的值、不点「测试连接」就保存时，写进文件的必须不能还是没测过的参数。
 * 测试请求抛错时也不得留着上次的可保存。夹具读源码树里那一份，不是构建产物里的副本。
 * 切段按花括号配平截到块尾后真执行。
 * <p>
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("连接页改机器人参数即锁保存")
class BotFormRetestTest {
    private static final String FIXTURE =
            "starbot-core/src/test/resources/frontend/bot-form-retest-fixture.mjs";

    @Test
    @DisplayName("invalidateBotForm 锁保存；五格 input 接到它；测试出错也回锁")
    void changingBotParamsRelocksSave() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "连接页改机器人参数即锁保存");
    }
}
