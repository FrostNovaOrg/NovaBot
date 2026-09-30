package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 首页短条在当天记录超过一页时仍要数到更早的失败
 * <p>
 * 最近一页全是普通记录时，更早的失败与告警如果只从这一页里挑，
 * 首页会像没出过事。当天有多少条就数多少条，短条上仍是最新的几条。
 */
@DisplayName("首页短条数得到更早的失败")
class HomeStripOlderFailuresTest {
    private static final String FIXTURE = FrontendFixture.fixture("home-strip-older-failures-fixture.mjs");

    @Test
    @DisplayName("当天超过一页、失败都在较早那部分：首页仍显示失败，末行等于总数减去已显示的")
    void olderFailuresStillCountOnTheHomeStrip() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "首页短条数得到更早的失败");
    }
}
