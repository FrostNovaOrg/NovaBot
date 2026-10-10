package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 登录页的失败提示：读屏会念出来，密码被拒会被标成输错
 * <p>
 * #msg 原先没有念出来的属性，密码被拒、连不上服务器都只发生在「看得见」的层上；
 * 密码框也不带 aria-invalid、错误说明没挂到框上。夹具把 login.html 当文本读进来
 * （照 login-reveal-wiring-fixture 的做法），切出标记函数与提交、通行密钥两支真执行：
 * 被拒标错（动态码在页上时一起标，不替服务端分是哪一格）、连不上不标、
 * 再提交与改走通行密钥时随提示清空一并撤掉。
 * <p>
 * 「由 JUnit 拉起 node、找不到 node 时红而不是跳过」这两条规矩见 {@link FrontendFixture}。
 */
@DisplayName("登录失败提示的读屏可念")
class LoginFailureLiveTest {
    private static final String FIXTURE = FrontendFixture.fixture("login-failure-live-fixture.mjs");

    @Test
    @DisplayName("#msg 会念；密码被拒标错并挂上说明；清空提示时一并撤掉；连不上不标")
    void failureMessageIsAnnouncedAndMarked() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "登录失败提示的读屏可念");
    }
}
