package org.frostnova.nova.console.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 推送页主播名：现场去平台查不回时，退回程序手上已有的昵称
 * <p>
 * 平台断网或被风控时打开推送页，一排主播都显示成「uid 数字」，认不出谁是谁；
 * 同一时刻运行状态页与主播页却显示得出名字。夹具读源码树里那一份，不是构建产物里的副本。
 * <p>
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("推送页主播名退路")
class PushNameFallbackTest {
    /**
     * 相对本模块目录；测试在模块目录下跑
     */
    private static final Path FIXTURE = Path.of("src/test/resources/frontend/push-name-fallback-fixture.mjs");

    @Test
    @DisplayName("现场查不回时显示手上的昵称，都没有才显示 uid；添加主播仍只认现场查的")
    void pushPageFallsBackToKnownName() throws IOException, InterruptedException {
        String fromRoot = FrontendFixture.repoRoot().relativize(FIXTURE.toAbsolutePath().normalize()).toString();
        FrontendFixture.run(fromRoot, "推送页主播名退路");
    }
}
