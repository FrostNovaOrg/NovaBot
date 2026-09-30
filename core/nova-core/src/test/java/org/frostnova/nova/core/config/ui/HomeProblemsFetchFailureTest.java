package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 首页另取失败与告警那一趟出错时，首页仍要画出来
 * <p>
 * 那一趟取不到时，短条退回从时间线这一页里数。原来三趟出错仍让整页载入失败。
 */
@DisplayName("首页另取失败出错时仍画首页")
class HomeProblemsFetchFailureTest {
    private static final String FIXTURE = FrontendFixture.fixture("home-problems-fetch-failure-fixture.mjs");

    @Test
    @DisplayName("失败与告警那一趟回包不是数据：首页照常，短条按这一页里的失败来数")
    void problemsFetchFailureStillDrawsThePage() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "首页另取失败出错时仍画首页");
    }
}
