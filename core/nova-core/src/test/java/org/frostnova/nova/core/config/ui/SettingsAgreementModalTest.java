package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 设置页「使用协议」改成点按钮在弹窗里看全文
 * <p>
 * 从前整篇协议直接铺在设置页上，要翻过它才看得到下面的设置。
 * 改成按钮点开之后还有两件容易弄丢的事：弹层里要真能读到全文（取不到得说明），
 * 以及 Esc／关闭钮／遮罩都关得掉、关完焦点回到按钮。
 * <p>
 * 夹具<b>真跑</b>：agreementCard 切段注入依赖后执行，弹层走 confirm.js 那份 showDoc。
 * 只 includes 一段字样是不行的——全文照样铺着、「查看全文」只是个摆设，字样也都在。
 * <p>
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("设置页使用协议弹窗")
class SettingsAgreementModalTest {
    private static final String FIXTURE = FrontendFixture.fixture("settings-agreement-modal-fixture.mjs");

    @Test
    @DisplayName("全文不平铺；弹层可读可关，取不到正文会说明")
    void agreementOpensInADialogAndClosesCleanly() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "设置页使用协议弹窗");
    }
}
