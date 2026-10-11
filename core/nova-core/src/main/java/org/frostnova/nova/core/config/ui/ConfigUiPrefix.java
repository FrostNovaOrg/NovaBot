package org.frostnova.nova.core.config.ui;

import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 部署前缀
 * <p>
 * 前缀就是 {@code server.servlet.context-path}，本程序没有自己的键。服务端的映射和过滤器都相对它挂载，
 * 而 Cookie 的 Path、页面里的地址是写给浏览器看的，必须自己带上它。
 *
 * <h2>只认本程序自己的配置，不认请求</h2>
 * 这里读的是 servlet 上下文自己的 {@link ServletContext#getContextPath()}，不是
 * {@link HttpServletRequest#getContextPath()}：开了 {@code server.forward-headers-strategy} 以后，
 * Spring 的 {@code ForwardedHeaderFilter} 会按 {@code X-Forwarded-Prefix} 改写后者，
 * 用它的话，请求方就能左右写进页面和 Cookie 的值。包装过的请求取 {@code getServletContext()}
 * 时拿到的仍是容器那一份，不经那道改写。
 *
 * <h2>页面里怎么带</h2>
 * 页面模板里留两种占位，按所在位置转义后顶替：
 * <ul>
 *   <li>{@link #ATTR_MARKER}：HTML 属性值里，按属性转义；</li>
 *   <li>{@link #SCRIPT_MARKER}：脚本里，连同两侧引号一起换成一个 JavaScript 字符串字面量。</li>
 * </ul>
 * 没配前缀时两种占位都换成空串，吐出的地址与改前逐字相同。
 */
public final class ConfigUiPrefix {
    /**
     * HTML 属性值里的占位，例如 {@code href="{{nova-prefix}}/config/assets/app.css"}
     */
    public static final String ATTR_MARKER = "{{nova-prefix}}";

    /**
     * 脚本里的占位，例如 {@code const PREFIX = "{{nova-prefix:js}}";}，连同两侧双引号一起顶替
     */
    public static final String SCRIPT_MARKER = "\"{{nova-prefix:js}}\"";

    private ConfigUiPrefix() {
    }

    /**
     * 本程序挂在哪个前缀下，没配时为空串
     * @param context servlet 上下文
     * @return 形如 {@code /novabot}，或空串
     */
    public static String of(ServletContext context) {
        String path = context == null ? null : context.getContextPath();
        return path == null ? "" : path;
    }

    /**
     * 这一趟请求所在的应用挂在哪个前缀下。取的是 servlet 上下文那一份，见类说明
     * @param request 请求
     * @return 形如 {@code /novabot}，或空串
     */
    public static String of(HttpServletRequest request) {
        return of(request.getServletContext());
    }

    /**
     * 控制台在浏览器眼里的根路径，即「前缀 + {@code /config}」，Cookie 的 Path 用它
     * @param request 请求
     * @return 形如 {@code /novabot/config}
     */
    public static String consolePath(HttpServletRequest request) {
        return of(request) + ConfigUiController.BASE_PATH;
    }

    /**
     * 把前缀按位置转义后写进页面
     * @param page 页面模板
     * @param prefix 前缀，取自 {@link #of(ServletContext)}
     * @return 写好的页面
     */
    public static String render(String page, String prefix) {
        return page.replace(SCRIPT_MARKER, scriptString(prefix)).replace(ATTR_MARKER, attribute(prefix));
    }

    /**
     * 按 HTML 属性值转义
     */
    static String attribute(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (char c : value.toCharArray()) {
            switch (c) {
                case '&' -> out.append("&amp;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * 写成一个带双引号的 JavaScript 字符串字面量
     * <p>
     * 尖括号与 {@code &} 也写成 {@code \}u 形式：脚本在 {@code <script>} 里，
     * 一个 {@code </script>} 就能把它提前收掉。
     */
    static String scriptString(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (char c : value.toCharArray()) {
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\'' -> out.append("\\'");
                default -> {
                    if (c < 0x20 || c == '<' || c == '>' || c == '&' || c == '\u2028' || c == '\u2029') {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
