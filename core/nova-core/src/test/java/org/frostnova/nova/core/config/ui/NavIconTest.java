package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 侧栏入口的图标
 * <p>
 * 内置四条的图标写死在界面文件里，插件带来的顶级页则由脚本在运行期建入口。两者分了岔：
 * 建入口那一处只写文字，于是插件页那几条<b>没有图标</b>——它不报错，只是侧栏上一半的条目
 * 与另一半对不齐。这一格量的正是「建出来的那条入口里有没有图标」。
 * <p>
 * 🔴 元素与属性的白名单不在这一侧，在服务端登记那一处（{@code ConsolePages.icon}）：
 * 图标出自插件，过关口的是那一串本身，不合规的在那里就退成空串、日志里点名。
 * 这里量的是「拿到什么就画成什么样」，两处各设一道的话，前面那道死掉的那天，
 * 后面这道会替它把红藏起来。
 * <p>
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("侧栏入口图标")
class NavIconTest {
    /**
     * 夹具在仓库里的位置。切的是源码树里的 main.js，不是构建产物里的副本
     */
    private static final String FIXTURE = FrontendFixture.fixture("nav-icon-fixture.mjs");

    @Test
    @DisplayName("插件页入口有图标、外壳与内置四条相同、没给形状时画中性缺省图标")
    void topPageEntriesDrawIcons() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "侧栏入口图标");
    }
}
