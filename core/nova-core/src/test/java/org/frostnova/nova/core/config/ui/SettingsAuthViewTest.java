package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 二次验证开关当场回灌签发只读口令表单用的那一位
 * <p>
 * 开关 settle 若只改卡片自己的状态、不写 {@code store.totpRequired}，
 * 签发表单仍按进页时那一份画：刚绑上会少验证码栏，刚关掉会多要一格。
 * 夹具读源码树里那一份，不是构建产物里的副本。切段按花括号配平截到块尾。
 * <p>
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("二次验证开关回灌签发表单验证码栏")
class SettingsAuthViewTest {
    private static final String FIXTURE =
            "core/nova-core/src/test/resources/frontend/settings-auth-fixture.mjs";

    @Test
    @DisplayName("settle 回灌 store.totpRequired；签发表单读这一位")
    void settleSyncsTotpRequiredForIssueForm() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "二次验证开关回灌签发表单验证码栏");
    }
}
