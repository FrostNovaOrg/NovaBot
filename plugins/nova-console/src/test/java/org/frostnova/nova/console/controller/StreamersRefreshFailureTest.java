package org.frostnova.nova.console.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 主播详情后台刷新失败时只说一句短话
 * <p>
 * 页面上的数据还在，网络抖一下就在页脚冒一行红字、拼上原话，读起来像整页坏了。
 * 夹具在假 DOM 上真跑主播页：详情与正在直播卡各成功一次后再失败，
 * 量屏幕上那句话、它的颜色、已画出的数据还在不在。
 * <p>
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("主播详情后台刷新失败的提示")
class StreamersRefreshFailureTest {

    @Test
    @DisplayName("后台刷新失败只说「刷新失败，稍后自动重试」，不带原话、不标红、数据照留")
    void backgroundRefreshFailureIsShort() throws Exception {
        FrontendFixture.runFromModule("src/test/resources/frontend/streamers-refresh-failure-fixture.mjs",
                "主播详情后台刷新失败的提示");
    }
}
