package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 告警卡测试结果在按钮上方，空着不占地方
 * <p>
 * 守的是结果字一长不再把这张卡的按钮顶上去。查卡底里谁先谁后，不量像素。
 * 夹具读源码树里那一份，切段按花括号配平截到块尾后真执行。
 * <p>
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("告警卡测试结果在按钮上方")
class SettingsAlertResultRowTest {
    private static final String FIXTURE = FrontendFixture.fixture("settings-alert-result-row-fixture.mjs");

    @Test
    @DisplayName("结果在按钮上方且卡底按列排；空结果不占行；按钮留在最后一行")
    void resultSitsAboveTheButton() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "告警卡测试结果在按钮上方");
    }
}
