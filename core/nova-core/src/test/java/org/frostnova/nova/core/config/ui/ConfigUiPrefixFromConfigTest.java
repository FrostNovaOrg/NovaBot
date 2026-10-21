package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 写进页面和 Cookie 的前缀只取本程序的配置，不取请求
 * <p>
 * 抓的用户故障：开了 {@code server.forward-headers-strategy} 以后，有人在请求头里塞
 * {@code X-Forwarded-Prefix}，Spring 按它改写请求上的 context path；拿那一份拼页面和 Cookie 的话，
 * 请求方就能把引号、尖括号写进吐出的页面，或把 Cookie 落到别的路径上。
 * <p>
 * 这里让请求上的 context path 与配置值不同、而且带着引号和尖括号，
 * 吐出的页面与 Cookie 里只许出现配置值。
 */
@DisplayName("前缀只取本程序的配置，不取请求")
class ConfigUiPrefixFromConfigTest {

    private static final String CONFIGURED = "/novabot";

    /**
     * 请求上被改成的那一份。认它的话页面里会出现 evil
     */
    private static final String FORGED = "/evil\"'<script>alert(1)</script>";

    @Test
    @DisplayName("控制台页里只有配置的前缀")
    void indexUsesConfiguredPrefix() throws Exception {
        String html = ConfigUiPrefixPages.index(CONFIGURED, FORGED);

        assertFalse(html.contains("evil"), "控制台页里出现了请求上那一份前缀");
        assertTrue(html.contains("src=\"/novabot/config/assets/" + ConfigUiPrefixPages.ASSET_VERSION + "/main.js\""), "入口脚本地址取的是配置的前缀");
    }

    @Test
    @DisplayName("登录页里只有配置的前缀")
    void loginUsesConfiguredPrefix() throws Exception {
        String html = ConfigUiPrefixPages.login(CONFIGURED, FORGED);

        assertFalse(html.contains("evil"), "登录页里出现了请求上那一份前缀");
        assertTrue(html.contains("\"/novabot\""), "登录页里写进去的是配置的前缀");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("org.frostnova.nova.core.config.ui.ConfigUiPrefixCookieTest#sources")
    @DisplayName("Cookie 的 Path 取配置的前缀")
    void cookieUsesConfiguredPrefix(String what, String cookieName, ConfigUiPrefixCookieTest.Source source)
            throws Exception {
        assertEquals("/novabot/config", ConfigUiPrefixCookieTest.path(source.run(CONFIGURED, FORGED), cookieName),
                what + "：Cookie 的 Path 认了请求上那一份前缀");
    }
}
