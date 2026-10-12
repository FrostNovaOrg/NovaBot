package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 侧栏「使用说明」按当前页打开自带手册对应的那一章
 * <p>
 * 手册随包走、从 {@code /config/manual} 发，地址是站内的：配了部署前缀时必须带上，
 * 丢了前缀那一下的表现是打开一个 404。对不上的页去目录。外链必须同时挡住
 * 新页拿本窗口、以及把本页地址带出去。
 * <p>
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("使用说明入口")
class ManualLinkTest {
    private static final String FIXTURE = FrontendFixture.fixture("manual-link-fixture.mjs");

    @Test
    @DisplayName("没配前缀时，手册地址是 /config/manual 下的章，外链带 noopener 与 noreferrer")
    void manualLinkFollowsTheCurrentPage() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "使用说明入口（无前缀）");
    }

    @Test
    @DisplayName("配了部署前缀时，手册地址同样带前缀，不剩丢前缀的 404")
    void manualLinkCarriesDeploymentPrefix() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "使用说明入口（带前缀）", "/novabot");
    }
}
