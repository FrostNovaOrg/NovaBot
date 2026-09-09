package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 设置页那张「使用协议」卡
 * <p>
 * 撤回同意把整台机器打回未签态：所有人都要重新同意才进得了控制台。这种动作少了二次确认，
 * 一次误点就够了，而误点之后<b>什么也没坏</b>——只是全体被关在门外，看起来像面板出了故障。
 * <p>
 * 夹具真跑撤回那一段（不是只 includes）：答复之前一次请求也不发、确认后恰一次、取消一次也不发。
 * 另外两问是正文只有服务端那一份、三个 agreement 键归这张卡而不再是普通输入框。
 * <p>
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("设置页使用协议卡")
class SettingsAgreementCardTest {
    private static final String FIXTURE =
            "core/nova-core/src/test/resources/frontend/settings-agreement-fixture.mjs";

    @Test
    @DisplayName("撤回同意先弹确认才发请求；正文只此一份；三个协议键不再是普通输入框")
    void revokeAsksFirstAndTheTextHasASingleSource() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "设置页使用协议卡");
    }
}
