package com.starlwr.bot.core.web;

import com.starlwr.bot.core.config.ui.ConfigUiRegistrar;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 安全响应头（审计点 5 与 M2）
 *
 * <h2>这把尺子量的是响应头本身</h2>
 * 「有没有配过滤器」与「响应上真的带着这几条头」是两件事。所以这里走的是真的过滤器链，
 * 断言落在 {@link MockHttpServletResponse} 的响应头上，
 * 并且<b>先有一条对照证明不加这个过滤器时那几条头确实是空的</b>——
 * 少了那条，下面每一条判据在「断言方式本身就取不到值」时同样会绿。
 */
@DisplayName("安全响应头")
class SecurityHeadersFilterTest {
    private static final List<String> PREFIXES = List.of("/config", "/nova");

    private MockHttpServletResponse through(String path) throws Exception {
        return through(path, new MockFilterChain());
    }

    private MockHttpServletResponse through(String path, FilterChain chain) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setRequestURI(path);
        MockHttpServletResponse response = new MockHttpServletResponse();

        new SecurityHeadersFilter(PREFIXES).doFilter(request, response, chain);

        return response;
    }

    /**
     * 🔴 阳性对照：不经过这个过滤器时，那几条头必须是空的
     * <p>
     * 这条对照回答的是「尺子取得到值吗」。它若也绿，说明下面所有判据量的都不是响应头。
     */
    @Test
    @DisplayName("不加过滤器时响应上一条安全头都没有")
    void theRulerSeesTheAbsenceOfHeaders() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/config/api/raw");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new MockFilterChain().doFilter(request, response);

        assertNull(response.getHeader(SecurityHeadersFilter.CONTENT_TYPE_OPTIONS));
        assertNull(response.getHeader(SecurityHeadersFilter.FRAME_OPTIONS));
        assertNull(response.getHeader(HttpHeaders.CACHE_CONTROL));
    }

    @Test
    @DisplayName("任意路径都带上 nosniff")
    void alwaysSendsNosniff() throws Exception {
        assertEquals("nosniff", through("/config").getHeader(SecurityHeadersFilter.CONTENT_TYPE_OPTIONS));
        assertEquals("nosniff", through("/onebot/send").getHeader(SecurityHeadersFilter.CONTENT_TYPE_OPTIONS));
    }

    /**
     * 🔴 这一位是 SAMEORIGIN，不是 DENY
     * <p>
     * DENY 相对 SAMEORIGIN 多防的只有<b>同源</b>嵌套，而同源嵌套正是 NapCat WebUI
     * 同源包装页要用的形态——<b>用 DENY 等于把自家功能当威胁防</b>。
     * <p>
     * 单独写一条「不许是 DENY」是因为：日后有人「顺手加固」成 DENY 时，
     * 上面那条判据（只断言值等于 SAMEORIGIN）也会红，但读到的信息是「值不对」；
     * 这条会连同理由一起告诉他为什么不能改。
     */
    @Test
    @DisplayName("X-Frame-Options 是 SAMEORIGIN 而不是 DENY")
    void framingIsAllowedForSameOrigin() throws Exception {
        String value = through("/config").getHeader(SecurityHeadersFilter.FRAME_OPTIONS);

        assertEquals("SAMEORIGIN", value);
        assertNotEquals("DENY", value,
                "DENY 会连同源嵌套一起挡掉, 而 NapCat WebUI 的同源包装页正是靠同源嵌套工作的");
    }

    @Test
    @DisplayName("控制台与代签发口令的响应不许被存下来")
    void sensitiveNamespacesAreNotStored() throws Exception {
        assertEquals("no-store", through("/config/api/raw").getHeader(HttpHeaders.CACHE_CONTROL));
        assertEquals("no-store", through("/config").getHeader(HttpHeaders.CACHE_CONTROL));
        assertEquals("no-store", through("/nova/readonly-token").getHeader(HttpHeaders.CACHE_CONTROL));
    }

    /**
     * 反面：no-store 不是无条件加的
     * <p>
     * 少了这条，把 {@code isSensitive} 写成恒真也一样全绿。
     */
    @Test
    @DisplayName("其他路径不加 no-store")
    void otherPathsKeepDefaultCaching() throws Exception {
        assertNull(through("/onebot/send").getHeader(HttpHeaders.CACHE_CONTROL));
    }

    /**
     * 前缀匹配不能顺带匹配上别的路径
     * <p>
     * {@code startsWith("/config")} 会把将来某个 {@code /configuration} 一起收进来。
     * 多收一个在功能上完全看不出来，所以只能靠判据钉住。
     */
    @Test
    @DisplayName("前缀只在整段边界上匹配")
    void prefixMatchesOnSegmentBoundary() throws Exception {
        assertNull(through("/configuration/x").getHeader(HttpHeaders.CACHE_CONTROL));
        assertNull(through("/novabot").getHeader(HttpHeaders.CACHE_CONTROL));
    }

    /**
     * 🔴 <b>{@link MockHttpServletResponse} 不照 Servlet 规范来</b>，它在响应提交之后
     * 照样让你写请求头。所以「把三条头挪到 {@code chain.doFilter} 之后」这种改法，
     * 用原版的 Mock 是<b>照样全绿的</b>——我第一版判据就是这样，量的是 Mock 的一个 Map，
     * 不是容器的行为。
     * <p>
     * 这个子类把规范那条补回来：{@code ServletResponse#setHeader} 在响应已提交后
     * <b>不产生任何效果</b>（Tomcat 的实现就是发现 {@code isCommitted()} 直接 return）。
     * <p>
     * 它自己也要先被验一次，见 {@link #theRulerIgnoresHeadersWrittenAfterCommit()}——
     * 否则这只是换了个地方恒真。
     */
    private static final class StrictResponse extends MockHttpServletResponse {
        @Override
        public void setHeader(String name, String value) {
            if (isCommitted()) {
                return;
            }
            super.setHeader(name, value);
        }
    }

    /**
     * 尺子自检：这个响应对象确实会忽略提交之后写入的头
     */
    @Test
    @DisplayName("判据用的响应对象照规范忽略提交后写入的头")
    void theRulerIgnoresHeadersWrittenAfterCommit() throws Exception {
        StrictResponse response = new StrictResponse();

        response.setHeader("X-Before", "1");
        response.flushBuffer();
        response.setHeader("X-After", "1");

        assertEquals("1", response.getHeader("X-Before"), "提交之前写的头应当留下");
        assertNull(response.getHeader("X-After"), "提交之后写的头必须无效, 否则下一条判据是恒真的");
    }

    /**
     * 处理链把响应提交掉之后，头还得在
     * <p>
     * 这正是「必须在 chain 之前写」的那条理由：控制台安全过滤器拒绝请求时就是直接写完返回的，
     * 而<b>被拒绝的那一次访问恰恰最不该被缓存、最不该能被嵌进别人的页面</b>。
     */
    @Test
    @DisplayName("处理链中途提交响应时，头仍在")
    void headersSurviveACommittedResponse() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/config/api/raw");
        request.setRequestURI("/config/api/raw");
        StrictResponse response = new StrictResponse();

        new SecurityHeadersFilter(PREFIXES).doFilter(request, response, (req, res) -> {
            res.getWriter().write("{\"denied\":true}");
            res.flushBuffer();
        });

        assertTrue(response.isCommitted(), "这条判据要的就是响应已提交的情形, 否则它什么也没验到");
        assertEquals("nosniff", response.getHeader(SecurityHeadersFilter.CONTENT_TYPE_OPTIONS));
        assertEquals("SAMEORIGIN", response.getHeader(SecurityHeadersFilter.FRAME_OPTIONS));
        assertEquals("no-store", response.getHeader(HttpHeaders.CACHE_CONTROL));
    }

    /**
     * 排在控制台安全过滤器之前
     * <p>
     * 顺序反了的话，上面那条「响应已提交仍带头」在生产上就不成立了——
     * 401/403 由控制台那道过滤器直接写完返回，本过滤器根本轮不到执行。
     */
    @Test
    @DisplayName("注册顺序排在控制台安全过滤器之前")
    void runsBeforeTheConfigUiSecurityFilter() {
        int order = new SecurityHeadersConfiguration().securityHeadersFilterRegistration().getOrder();

        assertTrue(order < ConfigUiRegistrar.FILTER_ORDER,
                "安全响应头过滤器要排在控制台安全过滤器之前, 否则它拒绝的那些响应带不上头");
    }
}
