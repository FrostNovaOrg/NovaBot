package org.frostnova.nova.console.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 主播详情的场次列表，用键盘也能打开每一场
 * <p>
 * 用户那头的故障：场次表整行只挂着鼠标点击，行里没有链接，按 Tab 会把全部场次跳过去。
 * 夹具在假 DOM 上真跑主播页的取详情与画场次页签，看每一行里有没有指向这一场的链接；
 * 整行点照旧跳到同一处是对照格。
 * <p>
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("场次行的链接")
class StreamersSessionLinkTest {

    @Test
    @DisplayName("每一行都有一个指向这一场的链接，整行点照旧能用")
    void everySessionRowHasALink() throws IOException, InterruptedException {
        FrontendFixture.runFromModule("src/test/resources/frontend/streamers-session-link-fixture.mjs",
                "场次行的链接");
    }
}
