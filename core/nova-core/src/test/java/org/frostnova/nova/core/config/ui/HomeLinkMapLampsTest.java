package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 首页连接图三个站都带状态灯
 * <p>
 * 只有本机那站有灯时，直播平台或机器人那一侧掉了，图上那一站看不出来，读屏也念不到。
 * 每站的灯照它自己那一段的级别画，站按钮的 aria-label 写着这一站此刻的状态。
 */
@DisplayName("首页连接图三站状态灯")
class HomeLinkMapLampsTest {
    private static final String FIXTURE = FrontendFixture.fixture("home-linkmap-lamps-fixture.mjs");

    @Test
    @DisplayName("平台或机器人掉了，图上那一站的灯跟着变色，读屏念得出各站状态")
    void everyStationCarriesItsOwnLamp() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "首页连接图三站状态灯");
    }
}
