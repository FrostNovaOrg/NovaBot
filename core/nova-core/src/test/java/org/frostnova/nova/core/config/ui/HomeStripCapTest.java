package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 首页短条遇上几十条失败
 * <p>
 * 告警三路都发不出去时，失败不封顶会把「今天发生了什么」撑满，
 * 别的记录一条都看不见。多出来的要收成一行，点了去日志页。
 */
@DisplayName("首页短条失败封顶")
class HomeStripCapTest {
    private static final String FIXTURE = FrontendFixture.fixture("home-strip-cap-fixture.mjs");

    @Test
    @DisplayName("首页短条遇上二十条失败：只显示到封顶，外加「还有几条」那一行，数字对得上")
    void twentyFailuresCollapseToACappedStrip() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "首页短条遇上二十条失败");
    }
}
