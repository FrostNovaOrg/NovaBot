package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 设置引导：试发那一步写「有试发记录」，只在服务端说过有记录的时候
 * <p>
 * /api/setup/state 回包里缺 testSent 时，进度条照前几步推断末步打勾；
 * 勾底下若写着「有试发记录」，屏幕就断言了一件谁也没记下过的事。
 * 夹具真跑 openSetup，按缺字段、false、true 三份回包读试发那一格。
 */
@DisplayName("设置引导试发那一步的记号")
class SetupTestSentMarkTest {
    private static final String FIXTURE = FrontendFixture.fixture("setup-test-sent-mark-fixture.mjs");

    @Test
    @DisplayName("回包缺 testSent 时不写「有试发记录」，false 不打勾，true 才写")
    void testStepClaimsARecordOnlyWhenOneExists() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "设置引导试发那一步的记号");
    }
}
