package org.frostnova.nova.console.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 主播列表与场次列表的链接，以及概况中的本场数据刷新
 * <p>
 * 用户那头的故障：场次表整行只挂着鼠标点击，行里没有链接，按 Tab 会把全部场次跳过去。
 * 夹具在假 DOM 上真跑主播页的取详情与画场次页签，看每一行里有没有指向这一场的链接；
 * 整行点照旧跳到同一处是对照格。
 * <p>
 * 本场刷新还验证失败保留数值、两种数据时刻，以及切页和隐藏时停止刷新。
 * <p>
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("主播页链接与本场刷新")
class StreamersSessionLinkTest {

    @Test
    @DisplayName("列表整行链接、场次行链接与本场刷新失败保留数据")
    void everySessionRowHasALink() throws IOException, InterruptedException {
        FrontendFixture.runFromModule("src/test/resources/frontend/streamers-session-link-fixture.mjs",
                "主播页链接与本场刷新");
    }
}
