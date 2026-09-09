package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 底部那条只在「有话说」的时候出现
 * <p>
 * 从前它是常驻页脚：状态栏住在里面，因此哪怕一处没改、一句话没说，屏幕最下面也一直
 * 横着一条空条，还占掉一截高度。现在它出现<b>当且仅当</b>三件事之一成立——本页有未保存的
 * 改动、有保存过等重启的项、状态栏正说着一句话；三条都不成立时整条不占位。
 * <p>
 * 夹具<b>真跑</b> core.js：假 DOM 之外，{@code markDirty}／{@code say} 用的都是产品码那一份。
 * 每格自带反向那一半——只量一个方向的话，把判定改成恒真或恒假各有一半格子照样绿。
 * 「调用点确实问过那份判定、页底那截留白跟着切」两件由 {@link ConfigUiFrontendTest} 钉。
 * <p>
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("底部改动条的显隐")
class ChangeBarVisibilityTest {
    private static final String FIXTURE =
            "core/starbot-core/src/test/resources/frontend/change-bar-visibility-fixture.mjs";

    @Test
    @DisplayName("有未保存的改动、有待重启项、状态栏说着话，三者之一才出条，否则整条不占位")
    void barShowsOnlyWhenItHasSomethingToSay() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "底部改动条的显隐");
    }
}
