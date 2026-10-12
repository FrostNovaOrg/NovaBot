package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 静音时段两格用时间框，只交出 HH:mm 或空串；配置文件里认不出的旧值说一声
 * <p>
 * 抓的用户故障：手敲的 8:00 存进去服务端认不出、整段静音不生效；或者换成时间框后，
 * 文件里早就存着的 8:00 显示成空，使用者以为本来就没设。
 * <p>
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("静音时段用时间框")
class SettingsQuietTimeTest {
    private static final String FIXTURE = FrontendFixture.fixture("settings-quiet-time-fixture.mjs");

    @Test
    @DisplayName("时间框只交 HH:mm 或空串，旧值 8:00 显示认不出的提示")
    void quietHoursSubmitHourMinute() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "静音时段时间框");
    }
}
