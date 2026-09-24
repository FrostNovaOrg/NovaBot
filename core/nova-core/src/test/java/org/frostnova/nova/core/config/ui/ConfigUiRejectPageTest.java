package org.frostnova.nova.core.config.ui;

import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAuthService;
import org.frostnova.nova.core.config.ui.auth.ConfigUiSession;
import org.frostnova.nova.core.config.ui.auth.ConfigUiSessionStore;
import org.frostnova.nova.core.config.ui.auth.LoginThrottle;
import org.frostnova.nova.core.util.IpMatcher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 令牌不对时那一页：图标、标签页图标与深浅色
 * <p>
 * 这一页由安全过滤器直接吐出来，此刻登录前的 {@code /config/assets} 还关着门，
 * 所以图标只能内联或走 data: URI。缺图标、只写浅色时，深色系统下它是一整屏白，
 * 而这正是「令牌不对」的那张脸——第一眼看着像坏了，不像在告诉你钥匙不对。
 * <p>
 * 接口调用回的是 JSON，这条不能跟着 HTML 一起改。
 */
@DisplayName("拒绝页的图标与深浅色")
class ConfigUiRejectPageTest {
    private static final String TOKEN = "operator-token-for-test-0123456789";

    private ConfigUiSecurityFilter tokenFormFilter() {
        NovaCoreProperties.ConfigUi.Auth auth = new NovaCoreProperties.ConfigUi.Auth();

        ConfigUiAuthService authService = new ConfigUiAuthService(auth,
                new ConfigUiSessionStore(Duration.ofHours(24), Duration.ofHours(2)),
                new LoginThrottle(5, Duration.ofMinutes(15)), null);

        assertFalse(authService.isEnabled(), "这一组问的正是未配口令那一形态，前提先自证");

        NovaCoreProperties.ConfigUi.Agreement agreement = new NovaCoreProperties.ConfigUi.Agreement();
        agreement.setAcceptedVersion(ConfigUiAgreement.VERSION);
        agreement.setAcceptedBy(ConfigUiSession.Channel.OPERATOR_TOKEN.wire());

        return new ConfigUiSecurityFilter(TOKEN, new IpMatcher(List.of("0.0.0.0/0", "::/0")),
                authService, auth, agreement);
    }

    private MockHttpServletRequest request(String method, String path, String token) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr("127.0.0.1");
        if (token != null) {
            request.setParameter("token", token);
        }
        return request;
    }

    @Test
    @DisplayName("令牌不对时的页面带产品图标、标签页图标，并跟随系统深浅色")
    void rejectPageHasIconsAndFollowsSystemDark() throws Exception {
        ConfigUiSecurityFilter filter = tokenFormFilter();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request("GET", ConfigUiController.BASE_PATH + "/", "wrong-token"), response, chain);

        assertEquals(HttpStatus.UNAUTHORIZED.value(), response.getStatus(), "令牌不对应当是 401");
        String html = response.getContentAsString();
        assertTrue(html.contains("rel=\"icon\""),
                "标签页要有一枚图标，现在这一栏是空白");
        assertTrue(html.contains("data:image/svg+xml") || html.contains("data:image/png"),
                "登录前取不到 /config/assets，标签页图标只能内联或走 data: URI");
        assertTrue(html.contains("M16 4h32a12") || html.contains("M16 4h32a12 12 0 0 1 12 12"),
                "产品图标要与 config-ui/icon.svg 同一套轮廓（气泡那条路径）");
        assertTrue(html.contains("prefers-color-scheme:dark"),
                "深色系统下不该是一整屏白，要有跟系统的媒体查询");
    }

    @Test
    @DisplayName("接口调用时回的 JSON 不动")
    void apiStillGetsJson() throws Exception {
        ConfigUiSecurityFilter filter = tokenFormFilter();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request("GET", ConfigUiController.BASE_PATH + "/api/timeline", "wrong-token"),
                response, chain);

        assertEquals(HttpStatus.UNAUTHORIZED.value(), response.getStatus());
        String body = response.getContentAsString();
        assertTrue(response.getContentType() != null && response.getContentType().contains("json"),
                "接口调用回的仍是 JSON，不能跟浏览器那张 HTML 混作一谈");
        assertTrue(body.contains("\"success\":false") || body.contains("\"success\": false"),
                "JSON 里 success 为假");
        assertTrue(body.contains("访问令牌不正确") || body.contains("message"),
                "JSON 里带着原因");
        assertFalse(body.contains("<html"), "接口回的不能是 HTML");
    }
}
