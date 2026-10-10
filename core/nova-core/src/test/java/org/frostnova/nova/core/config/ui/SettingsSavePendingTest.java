package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 设置页保存：回包之前又改的那几项，不许被当成已经存上
 * <p>
 * 抓的用户故障：设置页改一项点「保存」，接口还没回来，又改了另一项（或把同一项改成别的值）。
 * 回包一到，那一趟整体重取把草稿整个清零——屏幕显示旧值、改动条归零、再点保存什么也不发，
 * 那几处从没进过配置文件，刷新页面就没了。推送页的保存早已改成「快照对齐到真发出去的那一份」，
 * 设置页这一路一直没跟上。
 * <p>
 * 夹具真走保存那条路（切出 save／load 的函数体来跑，供数方那一套用真 core.js），
 * 保存回包放行的时机攥在夹具手里，凑出「回包之前又改」那个窗口。③④是对照：
 * 改回发出的值算存上、途中没改则草稿清零——对账对过头时它们先红。
 * <p>
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("设置页保存：回包前又改的照旧算没存")
class SettingsSavePendingTest {
    private static final String FIXTURE = FrontendFixture.fixture("settings-save-pending-fixture.mjs");

    @Test
    @DisplayName("回包前改的另几项留在改动条上，已发的那几项清掉")
    void editsMadeWhileSavingStayUnsaved() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "保存途中又改的对账");
    }
}
