package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 进首页仍转初始设置
 * <p>
 * 五步一件都还没做、正停在首页、没点过「稍后再说」时，才把地址改成 {@code #/setup}。
 * 同意协议会写出配置文件，按文件在不在判的话，刚装好的机器会停在空首页。
 * <p>
 * 夹具切出 {@code considerSetupRedirect} 后真执行四态，不是只 includes。
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」见 {@link FrontendFixture}。
 */
@DisplayName("进首页转初始设置")
class SetupRedirectTest {
    private static final String FIXTURE = FrontendFixture.fixture("setup-redirect-fixture.mjs");

    @Test
    @DisplayName("该开向导且正停在首页且没点稍后再说才转 #/setup，其余 hash 不动")
    void considerSetupRedirectTurnsHomeToSetup() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "进首页转初始设置");
    }
}
