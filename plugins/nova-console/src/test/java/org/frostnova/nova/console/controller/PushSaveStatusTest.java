package org.frostnova.nova.console.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 推送页按「保存」之后，状态栏要说得出这一趟到底存成了什么
 * <p>
 * 从前写死一句「已保存」：服务端那句「重启后生效」「配置将自动重新加载」被丢掉，
 * 使用者不知道要不要重启；两段都存了时也只剩一句。反过来，本群设置一处没改、
 * 推送配置存失败时，提示里却写着「本群设置已存」——那一段根本没发过请求。
 * <p>
 * 夹具<b>真跑</b>：假 DOM 与假 fetch 之外，{@code save} 用的是产品码那一份。
 * 只 includes 一段字样是不行的——写死「已保存」照样含「已保存」三个字。
 * <p>
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("推送页保存提示")
class PushSaveStatusTest {
    private static final String FIXTURE =
            "plugins/nova-console" + "/src/test/resources/frontend/push-save-status-fixture.mjs";

    @Test
    @DisplayName("成功写服务端那句、两句并列；本群没动时不提本群设置")
    void saveStatusTellsWhatActuallyLanded() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "推送页保存提示");
    }
}
