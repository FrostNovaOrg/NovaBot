package com.starlwr.bot.core.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 备份保留份数写越界时，读出来的应是生效值而不是原文。
 */
@DisplayName("配置备份保留份数上下限")
class BackupKeepBoundsTest {

    @ParameterizedTest(name = "写 {0} 生效 {1}")
    @CsvSource({
            "0, 1",
            "-1, 1",
            "101, 100",
            "500, 100",
    })
    @DisplayName("越界值按 1 到 100 的边界生效")
    void outOfRangeBackupKeepTakesTheBoundary(int written, int effective) {
        NovaCoreProperties.ConfigUi ui = new NovaCoreProperties.ConfigUi();
        ui.setBackupKeep(written);
        assertEquals(effective, ui.getBackupKeep(),
                "backup-keep 写 " + written + " 应按 " + effective + " 生效");
    }
}
