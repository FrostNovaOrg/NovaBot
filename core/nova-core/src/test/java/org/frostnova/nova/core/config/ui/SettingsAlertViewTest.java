package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 邮件告警卡选预设后药丸与端口认法
 * <p>
 * 选一个邮箱预设只改输入框的 value，不触发 input；药丸若只听 input，
 * 收件已经填好时界面仍写「未配置」。认预设若只比服务器名，文件里端口是 587
 * 也会被认成 465 那一档，自定义栏藏起来，端口改不着。
 * 夹具读源码树里那一份，不是构建产物里的副本。切段按花括号配平截到块尾后真执行。
 * <p>
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("邮件告警预设药丸与端口认法")
class SettingsAlertViewTest {
    private static final String FIXTURE = FrontendFixture.fixture("settings-alert-fixture.mjs");

    @Test
    @DisplayName("applyMail 真执行刷药丸；切回自定义不刷；认预设要比端口")
    void applyMailRefreshesPillAndPresetMatchesPort() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "邮件告警预设药丸与端口认法");
    }
}
