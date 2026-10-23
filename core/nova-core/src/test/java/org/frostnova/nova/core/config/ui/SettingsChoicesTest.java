package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 设置页两选一的配置项存进去的值与原来一字不差
 * <p>
 * 抓的用户故障：「扫码登录方式」只认 tv 与 web 两种写法，界面上给的却是自由文本框——
 * 手一滑填出别的字，保存后登录那条路当场走不通。改成两项选择之后，选出来存下的值
 * 必须仍是原来那两个字，不能换成标签或序号。
 * <p>
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("两选一的配置项存下的值与原来一致")
class SettingsChoicesTest {
    private static final String FIXTURE = FrontendFixture.fixture("settings-choices-fixture.mjs");

    @Test
    @DisplayName("两项选择框存 tv／web，与文本框存的一字不差")
    void choiceStoresTheSameValue() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "两选一存值");
    }
}
