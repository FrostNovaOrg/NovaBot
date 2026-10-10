package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 连接页机器人表单：测试连接还没回来时改了参数，旧结果不得放行改过的参数
 * <p>
 * 回包前把地址改错，旧的成功结果一回来就解开保存的话，点下去存的是没测过的错地址。
 * 夹具把 bot.js 整份真执行，点、改、回包都真走一遍。
 * <p>
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("连接页测试回包前改了参数即作废这次结果")
class BotFormStaleResultTest {
    private static final String FIXTURE = FrontendFixture.fixture("bot-form-stale-result-fixture.mjs");

    @Test
    @DisplayName("回包前改地址：保存仍禁用；没改：测通解开保存")
    void staleTestResultDoesNotUnlockSave() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "连接页测试回包前改了参数即作废这次结果");
    }
}
