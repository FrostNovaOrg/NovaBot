package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 设置页开关值跟随已保存值与草稿，以及这份脚本取件的规矩
 * <p>
 * 取件按唯一后缀找：不跟软链、子目录有 .git 不下去、读不了的目录判红，
 * 都由脚本末尾几格在临时目录里现造现量。整测里拉起一遍，不只靠构建脚本里那一步。
 */
@DisplayName("设置页开关值")
class SettingsValuesCheckTest {
    private static final String FIXTURE = "tools/settings-values-check.mjs";

    @Test
    @DisplayName("开关随已保存值与草稿重画；取件不跟软链、不进带 .git 的子目录、读不了的目录判红、临时目录删净")
    void switchFollowsValuesAndSweepRulesHold() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "设置页开关值");
    }
}
