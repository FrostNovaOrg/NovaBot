package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 危险确认弹层纯模型的行为判据
 * <p>
 * 打开／取消／确认三态、以及结算回调只调一次。它们全是纯函数，因此可以喂值直接跑，
 * 而 {@link ConfigUiFrontendTest} 那几格是静态检查，看得见「有没有写」，看不见「算得对不对」。
 * <p>
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("危险确认弹层判定")
class ConfirmModelTest {
    private static final String FIXTURE = "core/nova-core/src/test/resources/frontend/confirm-model-fixture.mjs";

    @Test
    @DisplayName("打开／取消／确认三态与回调只调一次逐格与预期相同")
    void threeStatesAndCallbackOnce() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "危险确认弹层判定");
    }
}
