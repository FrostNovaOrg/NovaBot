package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 设置页列表项的默认值与「保存前先确认」
 * <p>
 * 抓的用户故障：推送接口「允许的来源 IP」默认只放行本机两条，设置页却画成空框。用户以为是
 * 「往里加一条」的框，只填一个外部地址就保存；这一项保存即生效，本机上的程序从下一个请求起
 * 就推不进来，而且没有任何提示。
 * <p>
 * 夹具量两件事：行里框、「默认：」那行与「恢复默认」都用字段表给的默认值，没动的项不进改动账；
 * 服务端回「要先问」时，取消不写、确定带着确认再送一次。
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("设置页列表项默认值与保存前确认")
class SettingsListDefaultConfirmTest {
    private static final String FIXTURE = FrontendFixture.fixture("settings-list-default-confirm-fixture.mjs");

    @Test
    @DisplayName("框里画出默认名单；挡掉本机的名单先问，取消不写、确定再送")
    void defaultListShownAndRiskySaveAsksFirst() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "列表默认值与保存前确认");
    }
}
