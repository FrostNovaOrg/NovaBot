package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 先搜过别的，再从首页点名跳来：目标那一组的行和卡都看得见，搜索框已清空
 * <p>
 * 抓的用户故障：在设置页搜过一个词，回首页点「告警有死角 → 去配」，旧搜索还挂着，
 * 告警那一组的行和卡、整块高级照旧被筛掉，落到一片空白。
 * 点目录只看一组之后也一样：打开高级或从首页跳往另一组时，要能显示目标内容。
 * <p>
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("点名跳来先清旧搜索")
class SettingsNamedJumpTest {
    private static final String FIXTURE = FrontendFixture.fixture("settings-named-jump-fixture.mjs");

    @Test
    @DisplayName("从首页点名跳到告警卡、跳进高级里的一组：旧搜索清掉，目标看得见；保存后的重画与点组目录不清")
    void namedJumpClearsStaleSearch() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "点名跳来清旧搜索");
    }
}
