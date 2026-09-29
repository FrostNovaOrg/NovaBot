package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 使用协议那一行的时间到分钟、不带 T
 * <p>
 * 与同一屏通行密钥表同一种写法。夹具值不带时区尾，只认写法，
 * 不随跑测试那台机器的时区变。读不懂的原值照原样显示。
 * 夹具读源码树里那一份，切段按花括号配平截到块尾后真执行。
 * <p>
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("使用协议时间到分钟")
class SettingsAgreementTimeTest {
    private static final String FIXTURE = FrontendFixture.fixture("settings-agreement-time-fixture.mjs");

    @Test
    @DisplayName("机器格式换成到分钟且不带 T；读不懂的原值照原样")
    void agreementTimeIsReadableToTheMinute() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "使用协议时间到分钟");
    }
}
