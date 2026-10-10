package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 确认弹层：开着的时候 Tab 只在弹层里打转
 * <p>
 * 弹层标了 aria-modal，读屏那一侧守得住；键盘上的 Tab 浏览器却照旧放行——
 * 不拦的话，只按键盘的人在弹层里按一下 Tab，焦点就跑到弹层后面的导航上，
 * 得整页绕一圈才回得来。带输入栏那一形（输入栏、取消、确认）与
 * 长文那一形（只有关闭；藏起来的取消不算进圈）都要首尾相接。
 * <p>
 * 夹具在假 DOM 上<b>真跑</b> confirm.js，pressKey 驱动 keydown 且能带 Shift。
 * <p>
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("确认弹层内循环焦点")
class ConfirmFocusCycleTest {
    /**
     * 夹具按模块内相对位置认（{@link FrontendFixture#runFromModule}），不写死模块目录名
     */
    private static final String FIXTURE = "src/test/resources/frontend/confirm-focus-cycle-fixture.mjs";

    @Test
    @DisplayName("弹层开着时 Tab／Shift+Tab 首尾循环，焦点不出弹层；关掉后不再拦")
    void tabStaysInsideTheOpenDialog() throws IOException, InterruptedException {
        FrontendFixture.runFromModule(FIXTURE, "确认弹层内循环焦点");
    }
}
