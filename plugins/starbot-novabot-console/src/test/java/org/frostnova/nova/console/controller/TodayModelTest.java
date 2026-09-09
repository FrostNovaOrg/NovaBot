package org.frostnova.nova.console.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 首页「今日」卡那几组判定的行为判据
 * <p>
 * 三个数格该写什么、「—」与 0 分不分得开、不限额那一档画不画分母、明细怎么排怎么截、
 * 群名前面加不加平台前缀——全是纯函数，因此可以喂回包直接跑。
 * <p>
 * 三组是重头：
 * <ul>
 *   <li><b>「—」与 0</b>。0 看起来像今天一次都没用，而「—」说的是这台机器此刻无从谈起
 *   （没有机器人、或者额度接口没回来）。</li>
 *   <li><b>不限额不画分母</b>。上限 0 或负数在配额服务里都是「不限」，
 *   画成 3/0 会让人以为今天已经用完了。</li>
 *   <li><b>群名前缀按全量明细判</b>。只按显示的前 5 行判的话，第 6 个群来自另一个平台时，
 *   屏幕上那五行「群 11」分不出是谁家的。</li>
 * </ul>
 * <p>
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("首页今日卡判定")
class TodayModelTest {
    /**
     * 夹具在仓库里的位置。引用的是源码树里的 today-model.js，不是构建产物里的副本
     */
    private static final String FIXTURE = "tools/today-model-check.mjs";

    @Test
    @DisplayName("三个数格、额度三态、明细排序与截断、平台前缀与开合 HTML 逐格与预期相同")
    void pureFunctionsBehaveAsSpecified() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "首页今日卡判定");
    }
}
