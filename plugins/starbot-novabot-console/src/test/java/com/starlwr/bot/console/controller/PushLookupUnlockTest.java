package com.starlwr.bot.console.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 添加主播抽屉：查询失败或主播已在列表后，「找一下」须能再点
 * <p>
 * uid 查不到或已经在名单里时若提前返回、不把按钮解开，
 * 只能关掉抽屉重开才能再查。夹具读源码树里那一份，不是构建产物里的副本。
 * 切段按花括号配平截到块尾后真执行。
 * <p>
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("添加主播查询失败后按钮解锁")
class PushLookupUnlockTest {
    private static final String FIXTURE =
            "plugins/starbot-novabot-console/src/test/resources/frontend/push-lookup-unlock-fixture.mjs";

    @Test
    @DisplayName("查不到或已在列表后按钮解开；抛错同样解开并报查询失败")
    void lookupFailureUnlocksButton() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "添加主播查询失败后按钮解锁");
    }
}
