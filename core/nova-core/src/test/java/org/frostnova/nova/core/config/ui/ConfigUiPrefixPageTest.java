package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;
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

    /** 地址末尾多一个斜杠时，已登录的使用者打开或刷新控制台会看到 404。 */
    @ParameterizedTest(name = "configured={0}, request={1}")
    @CsvSource(value = {"'', ''", "/novabot, /novabot", "/novabot, /forwarded"})
    @DisplayName("已登录打开 /config/ 会转到控制台，部署前缀取配置值")
    void trailingSlashRedirectsToConsole(String configured, String onRequest) throws Exception {
        var response = ConfigUiPrefixPages.trailingSlash(configured, onRequest, true);
        assertEquals(302, response.getStatus(), "已登录打开末尾带斜杠的控制台地址应跳转，不能是 404");
        assertEquals(configured + "/config", response.getHeader("Location"), "跳转地址必须带配置的部署前缀");
        assertFalse(response.getHeader("Location").contains("#"), "跳转不指定片段，让浏览器保留原来的页面位置");
    }

    /** 没登录时末尾带斜杠也要显示登录卡，不能变成错误页或绕过登录。 */
    @ParameterizedTest(name = "prefix={0}")
    @CsvSource({"''", "/novabot"})
    @DisplayName("未登录打开 /config/ 仍显示同一张登录卡")
    void trailingSlashStillShowsLogin(String prefix) throws Exception {
        var response = ConfigUiPrefixPages.trailingSlash(prefix, prefix, false);
        assertEquals(200, response.getStatus());
        assertEquals(ConfigUiPrefixPages.login(prefix, prefix),
                response.getContentAsString(StandardCharsets.UTF_8));
    }

    /** 色表地址丢了部署前缀时会取不到，整页失去配色；也须先于组件样式加载。 */
    @Test
    @DisplayName("控制台色表带部署前缀，并先于组件样式加载")
    void productPaletteLoadsBeforeComponentsWithPrefix() throws Exception {
        for (String prefix : List.of("/novabot", "")) {
            String html = ConfigUiPrefixPages.index(prefix, prefix);
            String palette = "<link rel=\"stylesheet\" href=\"" + prefix
                    + "/config/assets/product-ui.css\">";
            String components = "<link rel=\"stylesheet\" href=\"" + prefix
                    + "/config/assets/app.css\">";
            assertTrue(html.contains(palette), "色表地址未带部署前缀，整页配色取不到: " + palette);
            assertTrue(html.contains(components) && html.indexOf(palette) < html.indexOf(components),
                    "色表必须排在组件样式之前");
        }
    }

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
