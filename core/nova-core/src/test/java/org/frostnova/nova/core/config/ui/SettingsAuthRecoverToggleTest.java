package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 二次验证开关请求失败时须拨回原档，不得停在刚拨到的那一档
 * <p>
 * 开侧停在「已启用」会让人以为绑好了，下次登录进不来；
 * 关侧停在「已关闭」时二次验证其实还开着。夹具读源码树里那一份，
 * 不是构建产物里的副本。切段按花括号配平截到块尾后真执行。
 * <p>
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("二次验证开关请求失败即拨回原档")
class SettingsAuthRecoverToggleTest {
    private static final String FIXTURE =
            "core/nova-core/src/test/resources/frontend/settings-auth-recover-toggle-fixture.mjs";

    @Test
    @DisplayName("recoverToggle 拨回原档；enrollFlow setup 抛错回关闭档")
    void recoverToggleRevertsAndReports() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "二次验证开关请求失败即拨回原档");
    }
}
