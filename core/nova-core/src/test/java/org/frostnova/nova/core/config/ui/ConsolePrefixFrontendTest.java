package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 配了部署前缀时，控制台前端的请求打到带前缀的路径上
 * <p>
 * 抓的用户故障：配了 {@code server.servlet.context-path=/novabot}，页面打开了，
 * 可每个接口请求都打到没前缀的 {@code /config/api/...} 上，什么都读不出来、什么都存不进去；
 * 登录成功以后又跳回没前缀的 {@code /config}。
 * <p>
 * 夹具<b>真跑</b> core.js 的 {@code api} 与 login-model.js 的 {@code landing}。
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("配了部署前缀时，前端请求带上前缀")
class ConsolePrefixFrontendTest {
    private static final String FIXTURE = FrontendFixture.fixture("console-prefix-fixture.mjs");

    @Test
    @DisplayName("前缀常量为 /novabot 时 api('/x') 请求 /novabot/config/api/x；没配时与改前相同")
    void apiRequestsCarryPrefix() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "前端请求带上部署前缀");
    }
}
