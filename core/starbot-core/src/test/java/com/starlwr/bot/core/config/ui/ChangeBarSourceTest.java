package com.starlwr.bot.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 底部改动条的供数方：核心不认识任何一份具体的产品状态
 * <p>
 * 改动条从前直接读 {@code store} 上那三项推送状态，开机那一趟也直接去取 {@code /datasource}。
 * 而推送页随控制台插件走，插件是可卸的——卸掉之后核心仍会去取那份文件、仍按一份取不到的东西
 * 算改动数，而屏幕上<b>不会有任何异常</b>：改动条照样显示 0，出问题的那句话
 * （「datasource.json 不是合法 JSON」）却会在一台根本没有推送页的机器上弹出来。
 * <p>
 * 三条静态那半截一律<b>先各自报一个非零分母再比</b>：只 diff 的话，「本来就没有」与
 * 「没变化」是同一个读数。两条真跑那半截里，⑤ 是 ④ 的阳性对照——少了它，
 * 把整套 SPI 删干净这一格照样绿。
 * <p>
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("改动条的供数方")
class ChangeBarSourceTest {
    private static final String FIXTURE =
            "core/starbot-core/src/test/resources/frontend/change-bar-source-fixture.mjs";

    @Test
    @DisplayName("核心界面里没有推送状态；无供数方时什么都不数，登记一位后照它报的数写字")
    void changeBarOnlyKnowsItsSources() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "改动条的供数方");
    }
}
