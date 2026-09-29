package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 二次验证开关说的是「登录要不要输码」，不是配置里那一位
 * <p>
 * 抓的用户故障：上了锁、配置要开二次验证、还没绑验证器时，设置页的开关说「已启用」——
 * 使用者以为登录已经要输动态验证码，其实只要口令；这时拨关也拨不下去。
 * 真开着的判法是「开着且已绑」，没绑那一档画成关着并写明「未绑定」。
 * <p>
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("二次验证开关按绑没绑说实话")
class SettingsAuthTotpSwitchTest {
    private static final String FIXTURE = FrontendFixture.fixture("settings-auth-totp-switch-fixture.mjs");

    @Test
    @DisplayName("没绑验证器时不说已启用；绑上了才亮")
    void totpSwitchTellsWhetherLoginNeedsCode() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "二次验证开关按绑没绑说实话");
    }
}
