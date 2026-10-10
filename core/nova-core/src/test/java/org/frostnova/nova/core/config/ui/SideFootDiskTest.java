package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 侧栏底部两枚按钮与运行栏「磁盘剩余」那一行
 * <p>
 * 界面上「去哪儿找说明书、去哪儿找源码」原来只是两行小字，其中源码那行连地址都
 * 点不动；磁盘快满时运行一栏更是一片正常色。本组用例把两头钉住：按钮的壳与属性
 * 在 index.html／app.css，变色与说明那一层真跑 overview.js 的 renderDiskRow。
 * 后端那一头（回包里给不给、给什么档）由 {@link RuntimeDiskSpaceTest} 钉。
 * <p>
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("侧栏底部与磁盘剩余")
class SideFootDiskTest {
    /**
     * 夹具按模块内相对位置认（{@link FrontendFixture#runFromModule}），不写死模块目录名
     */
    private static final String FIXTURE = "src/test/resources/frontend/side-foot-disk-fixture.mjs";

    @Test
    @DisplayName("两枚按钮齐整、源码地址只有一份；磁盘快满时那一行变黄变红并带说明")
    void sideFootButtonsAndDiskRow() throws IOException, InterruptedException {
        FrontendFixture.runFromModule(FIXTURE, "侧栏底部与磁盘剩余");
    }
}
