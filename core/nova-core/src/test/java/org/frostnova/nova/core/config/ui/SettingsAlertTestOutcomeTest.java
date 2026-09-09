package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 告警「发一条测试」的结果里须连带保留未保存提醒
 * <p>
 * 发送用的是服务端此刻已保存的配置。有改动时先写出提醒，
 * 成功／失败／异常若再整段覆盖同一格，使用者只看见「已发出」，
 * 会以为刚填的地址已经被试过。夹具读源码树里那一份，不是构建产物里的副本。
 * 切段按花括号配平截到块尾后真执行。
 * <p>
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("告警测试结果保留未保存提醒")
class SettingsAlertTestOutcomeTest {
    private static final String FIXTURE =
            "core/nova-core/src/test/resources/frontend/settings-alert-test-outcome-fixture.mjs";

    @Test
    @DisplayName("testOutcomeText 拼接提醒；sendTest 成功与异常都经它；空 note 恰原文")
    void testOutcomeKeepsUnsavedNote() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "告警测试结果保留未保存提醒");
    }
}
