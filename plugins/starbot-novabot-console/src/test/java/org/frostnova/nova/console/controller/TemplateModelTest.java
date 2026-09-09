package org.frostnova.nova.console.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 模板编辑器那几组判定的行为判据
 * <p>
 * 一整串模板与一张张卡之间的换算、块的增删排序、「块不可嵌套」、@ 那一档三选一、
 * 以及「改默认会影响到谁」。它们全是纯函数，因此可以喂值直接跑，而
 * {@link ConfigUiFrontendTest} 那几格是静态检查，看得见「有没有写」，看不见「算得对不对」。
 * <p>
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("模板编辑器判定")
class TemplateModelTest {
    /**
     * 夹具在仓库里的位置。引用的是源码树里的 template-model.js，不是构建产物里的副本
     */
    private static final String FIXTURE = "plugins/starbot-novabot-console/src/test/resources/frontend/template-model-fixture.mjs";

    @Test
    @DisplayName("块表换算、排序、不可嵌套、@ 三档与默认影响面逐格与预期相同")
    void pureFunctionsBehaveAsSpecified() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "模板编辑器判定");
    }
}
