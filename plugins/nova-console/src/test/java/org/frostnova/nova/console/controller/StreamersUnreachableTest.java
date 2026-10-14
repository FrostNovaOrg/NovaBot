package org.frostnova.nova.console.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 连不上服务器时，主播页的出错提示是中文
 * <p>
 * 请求没到服务器时浏览器只抛一个 TypeError，原话是英文（Failed to fetch 之类），
 * 原样拼进提示就是「刷新失败：Failed to fetch」。夹具在假 DOM 上真跑主播页，量正在直播卡、
 * 报告图与宿主 api() 三处；服务器真回了带 message 的错误时照常显示那句话。
 * <p>
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("连不上服务器时出错提示是中文")
class StreamersUnreachableTest {

    @Test
    @DisplayName("连不上服务器时主播页不露英文报错，服务器回的 message 照常显示")
    void unreachableServerIsSaidInChinese() throws Exception {
        FrontendFixture.runFromModule("src/test/resources/frontend/streamers-unreachable-fixture.mjs",
                "连不上服务器时出错提示是中文");
    }
}
