package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 写进页面的前缀按它所在的位置转义
 * <p>
 * 抓的用户故障：配的前缀里带着引号或尖括号时，原样写进属性会把属性提前收掉，
 * 原样写进脚本会把字符串提前收掉，一个 {@code </script>} 还能把整段脚本收掉——页面打开就是坏的。
 * 属性里按属性转义，脚本里按 JavaScript 字符串转义。
 */
@DisplayName("写进页面的前缀按位置转义")
class ConfigUiPrefixEscapeTest {

    private static final String ODD = "/a\"b'c<d>&e</script>";

    @Test
    @DisplayName("控制台页：属性里的前缀按属性转义")
    void indexEscapesForAttributes() throws Exception {
        String html = ConfigUiPrefixPages.index(ODD, ODD);

        assertTrue(html.contains("<meta name=\"nova-prefix\" content=\"/a&quot;b&#39;c&lt;d&gt;&amp;e&lt;/script&gt;\">"),
                "meta 里的前缀没按属性转义");
        assertTrue(html.contains("src=\"/a&quot;b&#39;c&lt;d&gt;&amp;e&lt;/script&gt;/config/assets/main.js\""),
                "入口脚本地址里的前缀没按属性转义");
        assertFalse(html.contains(ODD), "前缀原样出现在页面里");
    }

    @Test
    @DisplayName("登录页：脚本里的前缀按 JavaScript 字符串转义")
    void loginEscapesForScript() throws Exception {
        String html = ConfigUiPrefixPages.login(ODD, ODD);

        assertTrue(html.contains("const PREFIX = \"/a\\\"b\\'c\\u003cd\\u003e\\u0026e\\u003c/script\\u003e\";"),
                "脚本里的前缀没按 JavaScript 字符串转义");
        assertFalse(html.contains(ODD), "前缀原样出现在页面里");
    }
}
