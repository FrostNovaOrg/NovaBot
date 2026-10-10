package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 连接页机器人表单：五个输入框各有自己的名字
 * <p>
 * 表单里五个 label 原先没有接上输入框（无 for、也没包住）：点标签定位不到框，
 * 读屏念不出框的名字；两个 Token 框的 placeholder 又一字不差，
 * 听读屏的人分不出此刻停在哪个框上。夹具切段真执行 botFormHtml，
 * 对每个前缀各画一份，逐一对照 label[for] 与输入框 id（id 一个不许改，取值处按 id 找）。
 * <p>
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("连接页机器人表单的输入框名字")
class BotFormLabelsTest {
    /**
     * 夹具按模块内相对位置认（{@link FrontendFixture#runFromModule}），不写死模块目录名
     */
    private static final String FIXTURE = "src/test/resources/frontend/bot-form-labels-fixture.mjs";

    @Test
    @DisplayName("五个输入框各有 label[for] 对上 id，标签文字互不相同，id 不改")
    void eachBotInputHasItsOwnLabel() throws IOException, InterruptedException {
        FrontendFixture.runFromModule(FIXTURE, "连接页机器人表单的输入框名字");
    }
}
