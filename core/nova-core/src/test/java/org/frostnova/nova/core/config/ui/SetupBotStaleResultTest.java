package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 初始设置第 2 步：测试连接还没回来时改了参数，旧结果不得存下改过的参数
 * <p>
 * 这一步测通就自动存下并当场接上。回包前把地址改错、旧的成功结果照样放行的话，
 * 错地址直接进了配置，机器人断线。夹具切出 stepBot 真执行，点、改、回包都真走一遍。
 * <p>
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("初始设置测试回包前改了参数即作废这次结果")
class SetupBotStaleResultTest {
    private static final String FIXTURE = FrontendFixture.fixture("setup-bot-stale-result-fixture.mjs");

    @Test
    @DisplayName("回包前改地址：不发保存、仍算没测通；没改：测通自动存下")
    void staleTestResultIsNotSaved() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "初始设置测试回包前改了参数即作废这次结果");
    }
}
