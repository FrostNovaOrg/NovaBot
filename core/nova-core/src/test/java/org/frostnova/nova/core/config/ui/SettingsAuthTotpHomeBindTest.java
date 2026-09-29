package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 首页「建议绑定」卡里绑上验证器后，设置页开关跟着显示「已启用」
 * <p>
 * 抓的用户故障：上锁后那张卡当场就出、成了最顺的一条路。使用者在卡里绑好验证器，
 * 不重载转去设置页，开关还写着「未绑定」——他以为没绑上；去拨那个开关会被回
 * 「无需绑定验证器」，而登录其实已经要输动态验证码了，两头对不上。
 * <p>
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("首页卡绑上后开关跟着显示已启用")
class SettingsAuthTotpHomeBindTest {
    private static final String FIXTURE = FrontendFixture.fixture("settings-auth-totp-home-bind-fixture.mjs");

    @Test
    @DisplayName("卡里绑上后开关当场与再画一次都是已启用")
    void homeCardBindUpdatesTotpSwitch() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "首页卡绑上后开关跟着显示已启用");
    }
}
