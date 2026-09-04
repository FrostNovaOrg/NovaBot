package com.starlwr.bot.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 登录页锁定接线、机密按钮样式、加主播空输入句与眼睛收拢
 * <p>
 * 这几件事漏写页面上照样能用：锁定期仍能揭开口令、两处「显示」按钮大小不一、
 * 空着按下去的那句与格子上的提示各写各的、设置页机密行和签发口令页还各写一份切换。
 * 夹具读源码树里那几份，不是构建产物里的副本。
 * <p>
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("登录页眼睛接线与加主播空输入句")
class LoginRevealWiringTest {
    private static final String FIXTURE =
            "starbot-core/src/test/resources/frontend/login-reveal-wiring-fixture.mjs";

    @Test
    @DisplayName("锁定禁眼睛、两份 .secret 数字相同、空输入句与 placeholder 同一常量、眼睛落点按源码计数")
    void lockEyeSecretCopyAndSixSites() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "登录页眼睛接线与加主播空输入句");
    }
}
