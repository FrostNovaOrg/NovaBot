package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 设置页那几个判定的行为判据
 * <p>
 * 搜索、只看改过、默认值、危险项围栏——这四件事决定了使用者在设置页上看见什么、
 * 以及哪一次改动会先被拦下来问一句。它们全是纯函数，因此可以喂值直接跑，
 * 而 {@link ConfigUiFrontendTest} 那几格是静态检查，看得见「有没有写」，看不见「算得对不对」。
 * <p>
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("设置页判定")
class SettingsModelTest {
    /**
     * 夹具在仓库里的位置。引用的是源码树里的 settings-model.js，不是构建产物里的副本
     */
    private static final String FIXTURE = "core/starbot-core/src/test/resources/frontend/settings-model-fixture.mjs";

    @Test
    @DisplayName("搜索、只看改过、默认值、危险项围栏四组判定逐格与预期相同")
    void pureFunctionsBehaveAsSpecified() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "设置页判定");
    }
}
