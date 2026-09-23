package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 服务端换了一把会话之后，界面接住回包里的新 CSRF 令牌
 * <p>
 * 改密码、开关二次验证办成之后，当前这一把会话被换成新的，旧令牌从那一刻起不再管用。
 * 界面没接住新令牌的话，人看到「已改好」，而之后的每一个写请求都被挡下——
 * 保存设置、再改一次密码，一概「请求校验失败」，只有刷新页面才好。
 * <p>
 * 夹具<b>真跑</b> core.js 的 {@code api}：假 fetch 之外，接令牌那一手与 store 都是产品码那一份。
 * 每格自带反向那一半：没带令牌、空令牌的回包不许冲掉手上那一份，401 照旧整页重载。
 * <p>
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("换会话之后界面接住新令牌")
class SessionRenewalFrontendTest {
    private static final String FIXTURE = FrontendFixture.fixture("session-renewal-fixture.mjs");

    @Test
    @DisplayName("回包带新令牌就换上，下一趟写请求带新令牌；没带、空串、401 都不动手上那一份")
    void apiAdoptsTheRenewedCsrfToken() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "换会话之后界面接住新令牌");
    }
}
