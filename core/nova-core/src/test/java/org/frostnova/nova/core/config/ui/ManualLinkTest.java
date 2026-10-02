package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 侧栏「使用说明」按当前页打开手册对应的那一章
 * <p>
 * 对不上的页去目录。外链必须同时挡住新页拿本窗口、以及把本页地址带出去。
 * <p>
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("使用说明入口")
class ManualLinkTest {
    private static final String FIXTURE = FrontendFixture.fixture("manual-link-fixture.mjs");

    @Test
    @DisplayName("按页算出手册网址，外链带 noopener 与 noreferrer")
    void manualLinkFollowsTheCurrentPage() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "使用说明入口");
    }
}
