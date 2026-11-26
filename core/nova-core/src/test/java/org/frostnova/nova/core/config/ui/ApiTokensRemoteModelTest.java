package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 访问令牌页「从别的机器访问」那几条连法
 * <p>
 * 抓的故障：页面上给的地址与实际不符（端口、部署前缀、内网地址拼错），或者监听地址只限本机时
 * 页面却说内网连得上、名单只有本机时却说来访机器进得来——使用者照着去连，连不上也不知道差在哪一步。
 */
@DisplayName("访问令牌页的连法")
class ApiTokensRemoteModelTest {
    private static final String FIXTURE = FrontendFixture.fixture("api-tokens-remote-fixture.mjs");

    @Test
    @DisplayName("本机、内网、公网、隧道的地址与内网通不通逐格与预期相同")
    void routesAndLanStatusFollowTheRunningValues() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "访问令牌页的连法");
    }
}
