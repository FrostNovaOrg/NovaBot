package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 设置页：手写成界面改不了的项只读显示读到的值，并说明去配置文件里改
 * <p>
 * 抓的用户故障：值写成带换行的块标量，设置页照常摆一个单行框，一改一存多行就被压成一行，
 * 界面上一个字也没说这一项不该在这里改。
 * 夹具读源码树里那一份，不是构建产物里的副本。切段按花括号配平截到块尾后真执行。
 * <p>
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("设置页界面不能改的项")
class SettingsLockedViewTest {
    private static final String FIXTURE = FrontendFixture.fixture("settings-locked-fixture.mjs");

    @Test
    @DisplayName("标了不能改的只读显示值并附说明；没标的、回包里没这一栏的照常是输入框")
    void lockedFieldIsReadOnly() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "设置页界面不能改的项");
    }
}
