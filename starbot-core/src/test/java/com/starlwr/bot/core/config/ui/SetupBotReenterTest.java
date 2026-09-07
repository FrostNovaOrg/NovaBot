package com.starlwr.bot.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 重进初始设置时，未保存的机器人改动不得把已作废的测通翻回真
 * <p>
 * 改了参数再离开、回来：先前测通必须仍作废，Token 格仍是编辑值。
 * 没改过再回来：按这台机器已经连上的事实放行。夹具读源码树里那一份，
 * 不是构建产物里的副本。切段按花括号配平截到块尾后真执行。
 * <p>
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("重进向导不把作废的测通翻回真")
class SetupBotReenterTest {
    private static final String FIXTURE =
            "starbot-core/src/test/resources/frontend/setup-bot-reenter-fixture.mjs";

    @Test
    @DisplayName("改参重进 botOk 仍假且 Token 仍是编辑值；未改参采既成事实；旧写法放回即红；timer 注释现行安装目录；syncBotDraft 后 botSynced 与 botSnapshot 同一份")
    void reenteringSetupKeepsInvalidatedBotOkAndTimerPath() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "重进向导不把作废的测通翻回真");
    }
}
