package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 告警卡：手写成界面改不了的项不给输入框，只读显示读到的值，并写上锁的说明
 * <p>
 * 抓的用户故障：告警那几项在配置文件里写成引用别处的写法时，卡上仍给输入框。
 * 改了点保存，整批被拒，同批别的改动也一起没存上。
 * 夹具读源码树里那一份，不是构建产物里的副本。切段按花括号配平截到块尾后真执行。
 * <p>
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("告警卡界面不能改的项")
class SettingsAlertLockedViewTest {
    private static final String FIXTURE = FrontendFixture.fixture("settings-alert-locked-fixture.mjs");

    @Test
    @DisplayName("锁住的项没有输入框并写上说明；没锁的仍是输入框；发一条测试仍可点")
    void lockedAlertFieldHasNoInput() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "告警卡界面不能改的项");
    }
}
