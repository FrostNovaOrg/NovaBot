package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * 读屏标签按字段说名词
 * <p>
 * 显示／隐藏按钮的读屏标签是四个调用点共用的：签发口令页的字段是刚签发的只读口令，
 * 设置页机密行是访问令牌、二次验证密钥与各插件密钥，登录页与改密码三栏才是登录密码。
 * 标签一律念「密码」的话，读屏用户在签发口令页会听到与页面自称对不上的词，
 * 在设置页会把访问令牌听成密码——而屏幕上什么也看不出来。
 * <p>
 * {@link PasswordRevealModelTest} 量的是显隐翻面算得对不对，这一格量的是
 * <b>念出来的名词对不对</b>，以及四个调用点有没有各传各的名词。
 */
@DisplayName("读屏标签按字段说名词")
class PasswordRevealLabelTest {
    private static final String FIXTURE = FrontendFixture.fixture("password-reveal-label-fixture.mjs");

    @Test
    @DisplayName("读屏用户在签发口令页听到「显示口令」，在登录页听到「显示密码」，在设置页机密行听到的是该行的名字")
    void screenReaderSaysTheFieldsOwnNoun() throws IOException, InterruptedException {
        FrontendFixture.run(FIXTURE, "读屏标签按字段说名词");
    }
}
