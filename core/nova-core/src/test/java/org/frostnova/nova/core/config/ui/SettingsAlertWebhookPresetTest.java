package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * Webhook 预设来回切不把没配的提交方式记成改动
 * <p>
 * 文件里没写提交方式时，屏上显示 POST，三栏能凑成「Server 酱」。
 * 切到 Bark 再切回来，三栏回到原值，底部改动应为 0。
 * 切到 Bark 停住，变了的栏照旧记改动。文件里已经写过的，仍按文件里那一位比。
 * 夹具读源码树里那一份，切段按花括号配平截到块尾后真执行。
 * <p>
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("Webhook 预设来回切不记假改动")
class SettingsAlertWebhookPresetTest {
    private static final String FIXTURE = FrontendFixture.fixture("settings-alert-webhook-preset-fixture.mjs");

    @Test
    @DisplayName("没配提交方式时切 Bark 再切回改动为 0；停在 Bark 上照记；已配的照旧")
    void unsetMethodRoundTripIsNotDirty() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "Webhook 预设来回切不记假改动");
    }
}
