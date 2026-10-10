package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 初始设置第 2 步：自动保存还没回来时改了参数，改过的那份不得记成「与盘上一致」
 * <p>
 * 测通后自动保存发出的是那一刻的参数。回包前又改了 Token、回包后却把改过的那份记成已存的话，
 * 重进向导时草稿被盘上的参数盖掉、Token 清空，这一步却显示已接上。
 * 夹具切出 stepBot 与对盘那几个函数真执行，点、改、回包、重进都真走一遍。
 * <p>
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("初始设置自动保存回包前改的参数不算已存")
class SetupBotSaveRaceTest {
    private static final String FIXTURE = FrontendFixture.fixture("setup-bot-save-race-fixture.mjs");

    @Test
    @DisplayName("保存回包前改 Token：不算与盘上一致、重进不被盖掉；没改：照旧算已存")
    void editDuringSaveIsNotRememberedAsSaved() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "初始设置自动保存回包前改的参数不算已存");
    }
}
