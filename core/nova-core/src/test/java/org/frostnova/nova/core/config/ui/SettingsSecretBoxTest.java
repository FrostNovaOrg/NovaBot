package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 服务端判成机密的项，界面照旧给密码框；路径换等宽字也不改这一条
 * <p>
 * 抓的用户故障：前端把服务端标了机密的字段改成不机密，口令、令牌在控制台上明文摆着。
 * <p>
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("机密项照旧密码框")
class SettingsSecretBoxTest {
    private static final String FIXTURE = FrontendFixture.fixture("settings-secret-box-fixture.mjs");

    @Test
    @DisplayName("sensitive 的字段照旧用密码框，标了 PATH 的不机密项用等宽字")
    void sensitiveStaysPassword() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "机密项密码框");
    }
}
