package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 告警卡：锁住的栏与预设同时碰上时的两处缺口，以及已有的两道锁门
 * <p>
 * 首屏若只按现值认预设，锁住的栏连同说明会被收进预设里，打开设置页看不见。
 * 切预设时若只跳过锁住的栏、别的栏照改，下拉写着新预设，存下来的却是半套。
 * 锁住的值恰好就是预设要的，应当照常换。
 * 锁住的项经写入不进改动账；收件人下拉里有锁时不给下拉，免得一次改掉锁住的键。
 * 夹具读源码树里那一份，不是构建产物里的副本。切段按花括号配平截到块尾后真执行。
 * <p>
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("告警卡锁格与预设")
class SettingsAlertPresetLockTest {
    private static final String FIXTURE = FrontendFixture.fixture("settings-alert-preset-lock-fixture.mjs");

    @Test
    @DisplayName("首屏：Webhook 有锁住的栏就摊开，锁和说明看得到")
    void webhookLockedSectionStaysOpenOnFirstPaint() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "首屏 Webhook 有锁就摊开", "open-webhook");
    }

    @Test
    @DisplayName("首屏：邮件有锁住的栏就摊开，锁和说明看得到")
    void mailLockedSectionStaysOpenOnFirstPaint() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "首屏邮件有锁就摊开", "open-mail");
    }

    @Test
    @DisplayName("Webhook 预设要改锁住的栏时整次不换，下拉退回，改动账是空的")
    void webhookPresetThatWouldChangeLockedFieldIsRefused() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "Webhook 预设要改锁栏时整次不换", "reject-webhook");
    }

    @Test
    @DisplayName("邮件预设要改锁住的栏时整次不换，下拉退回，改动账是空的")
    void mailPresetThatWouldChangeLockedFieldIsRefused() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "邮件预设要改锁栏时整次不换", "reject-mail");
    }

    @Test
    @DisplayName("锁住的值恰好就是预设要的，预设照常换，只改没锁的栏")
    void presetAppliesWhenLockedValueAlreadyMatches() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "锁着的值恰好符合预设时照常换", "match");
    }

    @Test
    @DisplayName("锁住的项经写入也不进改动账")
    void lockedKeyDoesNotEnterDirtyViaSetValue() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "锁住的项经写入不进改动账", "setvalue");
    }

    @Test
    @DisplayName("收件人三个键里有一个锁着时不给下拉")
    void recipientDropdownHiddenWhenAnyKeyLocked() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "收件人有锁时不给下拉", "recipient");
    }
}
