package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配了部署前缀时，吐出的页面里的地址都带前缀
 * <p>
 * 抓的用户故障：配了 {@code server.servlet.context-path=/novabot}，控制台页面打开一片空白，
 * 样式和脚本都 404；登录页点登录没反应。页面里写死的 {@code /config/...} 打到了没前缀的路径上。
 * <p>
 * 每张页都量两次：配了前缀时每个本站地址都带它、不剩一处裸的 {@code /config}；
 * 没配时吐出的地址与改前逐字相同。NapCat 引导页在适配器模块的 {@code NapCatBootstrapPrefixTest}。
 */
@DisplayName("配了部署前缀时，页面里的地址带上前缀")
class ConfigUiPrefixPageTest {

    @Test
    @DisplayName("控制台页：属性上的本站地址一律以前缀起头，不剩裸的 /config")
    void indexAddressesCarryPrefix() throws Exception {
        String html = ConfigUiPrefixPages.index("/novabot", "/novabot");

        List<String> addresses = ConfigUiPrefixPages.siteAttributes(html);
        assertTrue(addresses.size() >= 4, "前提：量到了页面上的样式、图标与脚本地址，实得 " + addresses);
        for (String address : addresses) {
            assertTrue(address.startsWith("/novabot/") || address.equals("/novabot"),
                    "控制台页里的这个地址没带前缀，配了前缀时它取不到: " + address);
        }
        assertEquals(List.of(), ConfigUiPrefixPages.bareAddresses(html), "控制台页里还剩写死的地址");
        assertTrue(html.contains("href=\"/novabot/config/assets/app.css\""), "样式表地址带前缀");
        assertTrue(html.contains("src=\"/novabot/config/assets/main.js\""), "入口脚本地址带前缀");
    }

    @Test
    @DisplayName("控制台页：没配前缀时地址与改前逐字相同")
    void indexUnchangedWithoutPrefix() throws Exception {
        String html = ConfigUiPrefixPages.index("", "");

        assertTrue(html.contains("<link rel=\"icon\" href=\"/config/assets/icon.svg\" type=\"image/svg+xml\">"));
        assertTrue(html.contains("<link rel=\"stylesheet\" href=\"/config/assets/app.css\">"));
        assertTrue(html.contains("<img src=\"/config/assets/icon.svg\" alt=\"NovaBot\">"));
        assertTrue(html.contains("<script type=\"module\" src=\"/config/assets/main.js\"></script>"));
        assertFalse(html.contains("{{nova-prefix"), "没配前缀时也不许留下没顶替的占位");
    }

    @Test
    @DisplayName("登录页：脚本里不剩裸的 /config，前缀按脚本字符串写进去")
    void loginAddressesCarryPrefix() throws Exception {
        String html = ConfigUiPrefixPages.login("/novabot", "/novabot");

        assertEquals(List.of(), ConfigUiPrefixPages.bareAddresses(html),
                "登录页里还剩写死的地址，配了前缀时登录、通行密钥、协议那几条接口都打不到");
        assertTrue(html.contains("\"/novabot\""), "登录页里要有那一份写进去的前缀");
    }

    @Test
    @DisplayName("登录页：没配前缀时写进去的是空串，不留占位")
    void loginWithoutPrefix() throws Exception {
        String html = ConfigUiPrefixPages.login("", "");

        assertFalse(html.contains("{{nova-prefix"), "没配前缀时也不许留下没顶替的占位");
        assertEquals(List.of(), ConfigUiPrefixPages.bareAddresses(html));
    }
}
