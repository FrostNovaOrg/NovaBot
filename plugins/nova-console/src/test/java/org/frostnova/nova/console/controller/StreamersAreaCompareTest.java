package org.frostnova.nova.console.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 开播节奏抽屉里的「直播分区对比」表
 * <p>
 * 用户那头的故障：整区都没有收入记录时把未知当 0 写，点分区名下钻时带错场次。
 * 夹具在假 DOM 上真跑主播页的取详情与画开播节奏抽屉，逐格比对表里的写法，
 * 并解析分区链接带的场次与来源名。
 * <p>
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("直播分区对比")
class StreamersAreaCompareTest {

    @Test
    @DisplayName("流水未知写「—」，分区链接带本区场次与分区名")
    void areaCompareCard() throws IOException, InterruptedException {
        FrontendFixture.runFromModule("src/test/resources/frontend/streamers-area-compare-fixture.mjs",
                "直播分区对比");
    }
}
