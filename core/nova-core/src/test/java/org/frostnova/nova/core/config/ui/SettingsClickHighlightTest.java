package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 窄屏点目录里的某一组，高亮要对准那一组
 * <p>
 * 抓的用户故障：窄屏（手机、平板或放大页面）上点设置目录里的某一组，高亮停在上一组。
 * 点目录滚到位时组标题正好落在吸顶的顶栏与组目录下面，而「哪一组算当前」让出的是 0，
 * 上一组的尾巴还压在吸顶栏底下却被当成看得见，于是亮了上一组。手动滚到同一个位置也一样。
 * <p>
 * node 夹具没有排版：窄屏断点、顶栏与目录的实测高度、各组的位置全按桩上的数走；
 * 点完之后的落点按 focusGroup 的口径摆，量的是真 headerHeight 与真 watchCurrentGroup。
 * 吸顶那半是纯样式叠层，合进以后由真浏览器量，不在本夹具。
 * <p>
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("窄屏点目录后高亮对准那一组")
class SettingsClickHighlightTest {
    private static final String FIXTURE = FrontendFixture.fixture("settings-click-highlight-fixture.mjs");

    @Test
    @DisplayName("窄屏点每一组都亮那一组；滚到底亮最后一组照旧；宽屏上不让位")
    void clickHighlightsTheClickedGroup() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "点目录的高亮");
    }
}
