package com.starlwr.bot.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 首页视图模型的行为判据
 * <p>
 * 八档运行态、今日第三格三态、明细排序与截断、Webhook 待办四态——都是纯函数，
 * 因此可以喂回包直接跑。{@link ConfigUiFrontendTest} 那几格看得见「有没有写」，
 * 看不见「算得对不对」。
 */
@DisplayName("首页判定")
class HomeModelTest {
    private static final String FIXTURE = "tools/home-model-check.mjs";

    @Test
    @DisplayName("八档、今日格三态、明细截断、Webhook 待办四态逐格与预期相同")
    void pureFunctionsBehaveAsSpecified() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "首页判定");
    }
}
