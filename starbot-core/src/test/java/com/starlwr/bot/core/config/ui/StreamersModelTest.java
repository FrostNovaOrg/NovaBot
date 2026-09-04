package com.starlwr.bot.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 主播页那几组判定的行为判据
 * <p>
 * 地址栏与三块子视图的往返、状态四档的措辞与闭集覆盖、七天折线的取值与几何、人气峰三态、
 * 两种缺口分开列、快照裸键换人话、分页与报告图三态——全是纯函数，因此可以喂值直接跑，
 * 而 {@link ConfigUiFrontendTest} 那几格是静态检查，看得见「有没有写」，看不见「算得对不对」。
 * <p>
 * 其中三组是本页判据的重头：
 * <ul>
 *   <li><b>人气峰的「不知道」与「知道它是 0」</b>。只分两态的话，早于「峰值入归档」的场次
 *   会整批显示成「人气峰 0」——而那是一句假话，且它与真的 0 在屏幕上长得一模一样。</li>
 *   <li><b>两种采集缺口分开列</b>。服务端特意分两个字段给出来（程序停机与这个直播间断线
 *   必然重叠），界面上一相加就是重复计数，而算出来的数看起来完全正常。</li>
 *   <li><b>累计数据那条小横条的条件</b>。写成取反的话，接口还没回来那一瞬间也算「没开」，
 *   屏幕上会先闪一条说这台机器不行的话，随后又自己消失。</li>
 * </ul>
 */
@DisplayName("主播页判定")
class StreamersModelTest {
    private static final String FIXTURE = "starbot-core/src/test/resources/frontend/streamers-model-fixture.mjs";

    @Test
    @DisplayName("地址栏往返、状态四档、折线几何、人气峰三态、缺口分列逐格与预期相同")
    void pureFunctionsBehaveAsSpecified() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "主播页判定");
    }
}
