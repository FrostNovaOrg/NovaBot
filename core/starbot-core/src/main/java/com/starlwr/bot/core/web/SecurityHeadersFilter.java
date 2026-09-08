package com.starlwr.bot.core.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.NonNull;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * 给所有响应补上安全响应头
 *
 * <h2>为什么要有它</h2>
 * 实测过：本程序的响应<b>一条安全头都没有</b>。
 * 当时打的是控制台读取配置文件原文的那个端点，回的是
 * {@code 200 / Content-Type / Transfer-Encoding / Date}，就这四条——
 * 而它返回的是<b>配置文件原文</b>，界面上写着「口令、令牌与密钥都是明文」。
 * <p>
 * 那个端点后来撤了（控制台不再提供配置文件编辑），但这段理由一个字都不用改：
 * {@code /config} 下仍有回配置项当前值、回连接参数、回推送配置的端点。
 * <b>撤掉一个端点不等于这一类响应不再敏感</b>。
 *
 * <h2>三条头，各防各的</h2>
 * <ol>
 *   <li>{@code X-Content-Type-Options: nosniff}——浏览器不许再去猜类型。
 *       配置原文里若恰好有一段看着像 HTML 的内容，被当作 HTML 渲染就是一次同源脚本执行</li>
 *   <li>{@code X-Frame-Options: SAMEORIGIN}——防跨站点击劫持：
 *       别的站点不能把这个面板嵌进它的页面里，诱导人点下「保存」</li>
 *   <li>{@code Cache-Control: no-store}——见下</li>
 * </ol>
 *
 * <h2>🔴 为什么是 SAMEORIGIN 而不是 DENY</h2>
 * 点击劫持的威胁模型是<b>跨站</b>嵌套，SAMEORIGIN 已经把它挡住了。
 * DENY 相对 SAMEORIGIN 多防的只有<b>同源</b>嵌套——而同源嵌套正是我们自己要用的形态
 * （NapCat WebUI 的同源包装页），<b>用 DENY 等于把自家功能当威胁防</b>。
 * <p>
 * 这一位是有判据钉着的（见 {@code SecurityHeadersFilterTest}）：
 * 日后谁把它「顺手加固」成 DENY，会当场变红并读到这段理由，
 * 而不是等到包装页在生产上白屏才发现。
 *
 * <h2>no-store 为什么按命名空间整段收，而不是挑敏感端点</h2>
 * 挑着收就得维护一张「哪些端点算敏感」的表，而这张表<b>会在加第 40 个端点时过期</b>——
 * 漏掉一个的表现是「什么都正常」，没有任何人会发现。
 * {@code /config} 是控制台的命名空间，{@code /nova} 下面是代签发口令，
 * 整段收的代价只是面板的静态资源不进缓存：那是几十 KB 的本地请求，
 * 换掉的是一整类「浏览器/中间层把配置原文存下来」的可能。
 */
public class SecurityHeadersFilter extends OncePerRequestFilter {
    static final String CONTENT_TYPE_OPTIONS = "X-Content-Type-Options";

    static final String NOSNIFF = "nosniff";

    static final String FRAME_OPTIONS = "X-Frame-Options";

    /**
     * 🔴 <b>不要改成 DENY。</b>理由写在类文档里：DENY 多防的那一项正是我们自己的功能形态
     */
    static final String SAMEORIGIN = "SAMEORIGIN";

    static final String NO_STORE = "no-store";

    /**
     * 响应不许被存下来的命名空间
     * <p>
     * 前缀匹配，且要求下一个字符是 {@code /} 或到头——否则 {@code /configuration} 之类
     * 将来新增的路径会被顺带匹配上，而这种「多收了一个」在功能上完全看不出来
     */
    private final List<String> noStorePrefixes;

    public SecurityHeadersFilter(List<String> noStorePrefixes) {
        this.noStorePrefixes = List.copyOf(noStorePrefixes);
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        // 🔴 必须在 chain 之前写。放到后面的话，凡是在处理链里就把响应提交掉的路径
        // （包括安全过滤器自己回的 401/403）都拿不到这几条头，
        // 而那些恰恰是最需要「别被缓存、别被嵌」的响应
        response.setHeader(CONTENT_TYPE_OPTIONS, NOSNIFF);
        response.setHeader(FRAME_OPTIONS, SAMEORIGIN);

        if (isSensitive(request.getRequestURI())) {
            response.setHeader(HttpHeaders.CACHE_CONTROL, NO_STORE);
        }

        chain.doFilter(request, response);
    }

    /**
     * 判断路径是否落在不许缓存的命名空间里
     * @param path 请求路径
     * @return 是否敏感
     */
    private boolean isSensitive(String path) {
        if (path == null) {
            return false;
        }

        for (String prefix : noStorePrefixes) {
            if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                return true;
            }
        }

        return false;
    }
}
