package com.starlwr.bot.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 初始设置五步状态机的行为判据
 * <p>
 * 五步各自走到哪、哪一步放不放行、进度条上画什么、从第几步接着走、
 * 「已替你定好的初始值」那几行写什么——这几件事决定了刚装好那台机器上的第一段路。
 * 它们全是纯函数，因此可以喂值直接跑，而 {@link ConfigUiFrontendTest} 那几格是静态检查，
 * 看得见「有没有写」，看不见「拦得对不对」。
 * <p>
 * 🔴 这几格在真机上一格一格点出来的代价极高：要点出「第一步不许跳」得先有一台没上锁的机器，
 * 要点出「第四步 0 主播不许过」得先把机器人连上再故意不选目标。而放行条件写反了
 * <b>不会有任何报错</b>，只是那一步变成点一下就过——那正是这几格要守的东西。
 * <p>
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("初始设置五步")
class SetupModelTest {
    /**
     * 夹具在仓库里的位置。引用的是源码树里的 setup-model.js，不是构建产物里的副本
     */
    private static final String FIXTURE = "starbot-core/src/test/resources/frontend/setup-model-fixture.mjs";

    @Test
    @DisplayName("放行条件、进度条、落点、完成度与初始值逐格与预期相同")
    void stepMachineBehavesAsSpecified() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "初始设置五步");
    }
}
