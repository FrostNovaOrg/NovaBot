package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 高级收起时，搜到的、从别处点名跳来的项照样找得到；告警卡默认收起，点名或搜到时展开
 * <p>
 * 抓的用户故障：搜到的高级项收着、看起来和没搜到一样；从首页「告警有死角 → 去配」跳来，
 * 落到一排收着的卡上，看起来像点了没反应。
 * <p>
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("高级收起时照样找得到")
class SettingsAdvancedRevealTest {
    private static final String FIXTURE = FrontendFixture.fixture("settings-advanced-reveal-fixture.mjs");

    @Test
    @DisplayName("搜到高级项就摆出来，点名跳进高级先摆出来，card=alert 展开告警卡")
    void collapsedAdvancedStillReachable() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "高级与告警卡开合");
    }
}
