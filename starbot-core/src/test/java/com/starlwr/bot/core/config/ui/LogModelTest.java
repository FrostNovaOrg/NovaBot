package com.starlwr.bot.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 日志页那几组判定的行为判据
 * <p>
 * 地址栏与筛选状态的往返、查询串换名、日期导航、工程日志分行与筛——四件事全是纯函数，
 * 因此可以喂值直接跑，而 {@link ConfigUiFrontendTest} 那几格是静态检查，
 * 看得见「有没有写」，看不见「算得对不对」。
 * <p>
 * 往返那一组是本页判据的重头：「筛完的画面能贴给别人」是这一页的承诺，
 * 而写得进地址栏与从地址栏还原得回来是两件事。只成立一半时，
 * 贴过去的地址打开是一张<b>没筛过</b>的页——它与筛过的长得一模一样。
 */
@DisplayName("日志页判定")
class LogModelTest {
    private static final String FIXTURE = "starbot-core/src/test/resources/frontend/log-model-fixture.mjs";

    @Test
    @DisplayName("地址栏往返、查询串、日期导航、工程日志分行与筛逐格与预期相同")
    void pureFunctionsBehaveAsSpecified() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "日志页判定");
    }
}
