package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 主播页那几组判定的行为判据
 * <p>
 * 地址栏与三块子视图的往返、状态四档的措辞与闭集覆盖、七天折线的取值与几何、
 * 两种缺口分开列、快照裸键换人话、分页与报告图三态——全是纯函数，因此可以喂值直接跑，
 * 而 {@link ConfigUiFrontendTest} 那几格是静态检查，看得见「有没有写」，看不见「算得对不对」。
 * <p>
 * 程序停机和直播间断线可有重叠，页面分开显示两种缺口。
 */
@DisplayName("主播页判定")
class StreamersModelTest {
    private static final String FIXTURE = FrontendFixture.fixture("streamers-model-fixture.mjs");

    @Test
    @DisplayName("地址栏往返、状态四档、折线几何、缺口分列逐格与预期相同")
    void pureFunctionsBehaveAsSpecified() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "主播页判定");
    }
}
