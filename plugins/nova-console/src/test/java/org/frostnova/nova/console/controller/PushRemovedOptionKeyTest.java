package org.frostnova.nova.console.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 版式项删掉之后：留着死键的老通道不许因此显示「自定义」
 * <p>
 * 处理器删掉一个版式项后，老通道参数里那个键还在，而运行侧早已静默忽略它。
 * 「默认／自定义」的判定若把处理器已不认识的键也算差异，升级后界面说的与机器人做的
 * 就不是同一件事。夹具另带真改过的键作阳性对照，防「全放行」式认宽。
 * <p>
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("推送页被删版式键")
class PushRemovedOptionKeyTest {
    /**
     * 夹具按模块内相对位置认，不写死模块目录名——写死目录名的旧写法只减不增
     */
    private static final String FIXTURE =
            "src/test/resources/frontend/push-removed-option-key-fixture.mjs";

    @Test
    @DisplayName("死键不算自定义，真改过的照旧算；只读不改配置")
    void removedOptionKeyDoesNotReadAsCustom() throws IOException, InterruptedException {
        FrontendFixture.runFromModule(FIXTURE, "推送页被删版式键");
    }
}
