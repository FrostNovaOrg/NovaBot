package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 向导插件步：平台清单晚到时得有人叫它重画
 * <p>
 * 抓的用户故障：首屏那趟整体载入先画向导、后取 /platforms（宿主 mountPages 完才 load）。
 * 清单一到没有任何人叫向导重画——refreshPages 只认插件页，不认向导里的插件步，
 * 「第一位主播」那一步上的「正在载入」一直挂到使用者自己翻一步再翻回来。
 * <p>
 * 夹具两头各量一头：setup.js 的 notePlatformsArrived（停在插件步才画、走完或停在内置步不画、
 * 步号越界不炸）切出来真执行；main.js 的 load 切出来跑一趟，量它取回清单之后叫没叫这一声。
 * <p>
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("向导插件步：平台清单晚到有人叫它重画")
class SetupPlatformsLateTest {
    private static final String FIXTURE = FrontendFixture.fixture("setup-platforms-late-fixture.mjs");

    @Test
    @DisplayName("停在插件步、清单到了重画一次；整体载入取回清单时叫这一声")
    void latePlatformListRepaintsPluginStep() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "平台清单晚到的重画与接线");
    }
}
