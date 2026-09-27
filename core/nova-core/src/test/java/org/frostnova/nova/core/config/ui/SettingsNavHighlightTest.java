package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 设置页导航高亮：整页高度变了要重判
 * <p>
 * 抓的用户故障：设置页上方提示条出现或收起（页底那块变高变矮一个样），页面没滚动，
 * 左边导航还亮着先前那一组——「已经滚到最底就亮最后一组」那条判定读的是整页高度，
 * 整页高度变了却不重判，亮着的那一组是按旧高度算出来的。
 * 夹具读源码树里那一份，不是构建产物里的副本。切段按花括号配平截到块尾后真执行。
 * <p>
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("设置页导航高亮：整页高度变了要重判")
class SettingsNavHighlightTest {
    /**
     * 夹具在仓库里的位置。引用的是源码树里的 settings.js，不是构建产物里的副本
     */
    private static final String FIXTURE = FrontendFixture.fixture("settings-nav-highlight-fixture.mjs");

    @Test
    @DisplayName("#groups 以外的内容变高、页面没滚动，高亮跟着重判；拆页时观察器断得干净")
    void pageHeightChangeRejudgesHighlight() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "设置页导航高亮");
    }
}
