package com.starlwr.bot.adapter.onebot.security;

import com.starlwr.bot.core.util.IpMatcher;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.adapter.onebot.config.OneBotAdapterPluginProperties;
import com.starlwr.bot.adapter.onebot.enums.ResultCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("推送接口安全过滤器")
class PushApiSecurityFilterTest {
    private static final String PATH = "/onebot/send";
    private static final String TOKEN = "Xq7-Rt2_Kd9vLm4Zp0Ns";

    private OneBotAdapterPluginProperties.Security security;
    private PushApiTokenStore tokenStore;

    @BeforeEach
    void setUp() {
        security = new OneBotAdapterPluginProperties.Security();
        tokenStore = new PushApiTokenStore();
        tokenStore.register(PATH, TOKEN);
    }

    private PushApiSecurityFilter filter() {
        return new PushApiSecurityFilter(
                security,
                tokenStore,
                new IpMatcher(security.getAllowIps()),
                new RateLimiter(security.getRateLimit().getPermitsPerMinute(), security.getRateLimit().getBurst())
        );
    }

    private MockHttpServletRequest request(String remoteAddr, String authorization) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", PATH);
        request.setRequestURI(PATH);
        request.setRemoteAddr(remoteAddr);
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        return request;
    }

    private int businessCode(MockHttpServletResponse response) throws Exception {
        return JSONObject.parseObject(response.getContentAsString()).getIntValue("code");
    }

    @Test
    @DisplayName("携带正确 Token 的本机请求放行")
    void allowsValidRequest() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter().doFilter(request("127.0.0.1", "Bearer " + TOKEN), response, chain);

        assertEquals(200, response.getStatus());
        assertNotNull(chain.getRequest(), "请求应被放行至后续处理链");
    }

    @Test
    @DisplayName("未携带 Token 的请求被拒绝，且不进入后续处理链")
    void rejectsMissingToken() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter().doFilter(request("127.0.0.1", null), response, chain);

        assertEquals(401, response.getStatus());
        assertEquals(ResultCode.UNAUTHORIZED.getCode(), businessCode(response));
        assertNull(chain.getRequest(), "鉴权失败的请求不应进入后续处理链");
    }

    @Test
    @DisplayName("Token 错误的请求被拒绝")
    void rejectsWrongToken() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter().doFilter(request("127.0.0.1", "Bearer wrong-token-value"), response, chain);

        assertEquals(401, response.getStatus());
        assertNull(chain.getRequest());
    }

    @Test
    @DisplayName("白名单外的来源 IP 被拒绝")
    void rejectsForeignIp() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter().doFilter(request("203.0.113.7", "Bearer " + TOKEN), response, chain);

        assertEquals(403, response.getStatus());
        assertEquals(ResultCode.FORBIDDEN_ADDRESS.getCode(), businessCode(response));
        assertNull(chain.getRequest());
    }

    @Test
    @DisplayName("未受保护的路径直接放行")
    void passesThroughUnprotectedPath() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/onebot/health");
        request.setRequestURI("/onebot/health");
        request.setRemoteAddr("203.0.113.7");

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter().doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertNotNull(chain.getRequest());
    }

    @Test
    @DisplayName("超出频率限制的请求返回 429")
    void rejectsWhenRateLimited() throws Exception {
        security.getRateLimit().setPermitsPerMinute(60);
        security.getRateLimit().setBurst(2);
        PushApiSecurityFilter filter = filter();

        for (int i = 0; i < 2; i++) {
            filter.doFilter(request("127.0.0.1", "Bearer " + TOKEN), new MockHttpServletResponse(), new MockFilterChain());
        }

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request("127.0.0.1", "Bearer " + TOKEN), response, chain);

        assertEquals(429, response.getStatus());
        assertEquals(ResultCode.RATE_LIMITED.getCode(), businessCode(response));
        assertNull(chain.getRequest());
    }

    @Test
    @DisplayName("默认不信任 X-Forwarded-For，无法借伪造请求头绕过 IP 白名单")
    void ignoresForwardedHeaderByDefault() throws Exception {
        MockHttpServletRequest request = request("203.0.113.7", "Bearer " + TOKEN);
        request.addHeader("X-Forwarded-For", "127.0.0.1");

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter().doFilter(request, response, chain);

        assertEquals(403, response.getStatus());
        assertNull(chain.getRequest());
    }

    @Test
    @DisplayName("显式信任反向代理后按 X-Forwarded-For 判定来源")
    void honoursForwardedHeaderWhenTrusted() throws Exception {
        security.setTrustProxy(true);

        MockHttpServletRequest request = request("203.0.113.7", "Bearer " + TOKEN);
        request.addHeader("X-Forwarded-For", "127.0.0.1, 10.0.0.1");

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter().doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertNotNull(chain.getRequest());
    }

    @Test
    @DisplayName("允许通过 X-Access-Token 请求头携带 Token")
    void acceptsAccessTokenHeader() throws Exception {
        MockHttpServletRequest request = request("127.0.0.1", null);
        request.addHeader("X-Access-Token", TOKEN);

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter().doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertNotNull(chain.getRequest());
    }

    @Test
    @DisplayName("放宽白名单后外部来源可正常调用")
    void allowsConfiguredExternalIp() throws Exception {
        security.setAllowIps(List.of("127.0.0.1/32", "203.0.113.0/24"));

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter().doFilter(request("203.0.113.7", "Bearer " + TOKEN), response, chain);

        assertEquals(200, response.getStatus());
        assertNotNull(chain.getRequest());
    }
}
