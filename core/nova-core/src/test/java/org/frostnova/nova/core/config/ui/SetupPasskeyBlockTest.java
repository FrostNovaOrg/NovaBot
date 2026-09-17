package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 初始设置页第 1 步的登记钮：上了锁就进页判能不能登记
 * <p>
 * 浏览器没给通行密钥接口时点下去什么也不发生，地址是 IP 时要点了才知道原因。
 * 夹具切出 setup.js 的 passkeyBlock 与 passkeys.js 的 registerBlockedReason 真执行，量的是行为不是写法。
 * <p>
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("初始设置页登记钮进页即判")
class SetupPasskeyBlockTest {

    @Test
    @DisplayName("上了锁、登记不了就进页置灰并换成原因句；登记得了照旧可点；没上锁不去问")
    void lockedStepTellsWhyBeforeClick() throws IOException, InterruptedException {
        FrontendFixture.run(FrontendFixture.fixture("setup-passkey-block-fixture.mjs"), "初始设置页登记钮进页即判");
    }
}
