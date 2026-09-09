package org.frostnova.nova.adapter.onebot.security;

import org.frostnova.nova.core.util.IpMatcher;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.adapter.onebot.config.OneBotAdapterPluginProperties;
import org.frostnova.nova.adapter.onebot.enums.ResultCode;
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

    /**
     * 🔴 X-Forwarded-For 取右的核心判据
     * <p>
     * 反代是<b>追加</b>式的：客户端自己带一个 {@code X-Forwarded-For: 127.0.0.1} 过来，
     * 反代把真实对端接在后面，上游收到 {@code 127.0.0.1, 203.0.113.7}。
     * 取最左侧的话，白名单看到的是客户端自己写的那个地址——<b>白名单就此被一个请求头绕过</b>。
     * <p>
     * 本判据在取左的实现上是<b>红的</b>（那时会放行，返回 200）。
     */
    @Test
    @DisplayName("客户端伪造在最左侧的地址绕不过 IP 白名单")
    void forgedLeftmostAddressCannotBypassTheWhitelist() throws Exception {
        security.setTrustProxy(true);

        MockHttpServletRequest request = request("203.0.113.7", "Bearer " + TOKEN);
        // 左：客户端伪造的；右：反代追加的真实对端
        request.addHeader("X-Forwarded-For", "127.0.0.1, 203.0.113.7");

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter().doFilter(request, response, chain);

        assertEquals(403, response.getStatus(), "取的是客户端能写的那一段, 白名单被请求头绕过了");
        assertEquals(ResultCode.FORBIDDEN_ADDRESS.getCode(), businessCode(response));
        assertNull(chain.getRequest());
    }

    /**
     * 上一条的反面：取右不能把该放的也一起挡掉
     * <p>
     * 这条判据的旧版本写的是 {@code "127.0.0.1, 10.0.0.1"} 期望 200——
     * 也就是说，<b>它当时把「取最左侧」这个漏洞本身钉成了判据</b>，于是漏洞被绿灯护着。
     * 改判据不是为了让它变绿，是因为它原本量错了东西。
     */
    @Test
    @DisplayName("显式信任反向代理后，采信反代追加的那一段")
    void honoursTheAddressAppendedByTheProxy() throws Exception {
        security.setTrustProxy(true);

        MockHttpServletRequest request = request("203.0.113.7", "Bearer " + TOKEN);
        // 左：客户端伪造的公网地址；右：反代追加的真实对端，它在白名单里
        request.addHeader("X-Forwarded-For", "203.0.113.9, 127.0.0.1");

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter().doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertNotNull(chain.getRequest());
    }

    /**
     * 覆写式反代（我们自己的部署正是这种）在改动前后行为一致
     * <p>
     * nginx 写 {@code proxy_set_header X-Forwarded-For $remote_addr;} 时头里<b>只有一段</b>，
     * 最左与最右是同一个值。这条判据存在的意义是<b>划清受影响范围</b>：
     * 这次改的是「别人照模板部署」的情形，不是我们线上的行为。
     */
    @Test
    @DisplayName("覆写式反代只送一段地址时照常放行")
    void singleHopHeaderStillWorks() throws Exception {
        security.setTrustProxy(true);

        MockHttpServletRequest request = request("203.0.113.7", "Bearer " + TOKEN);
        request.addHeader("X-Forwarded-For", "127.0.0.1");

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter().doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertNotNull(chain.getRequest());
    }

    /**
     * 头里全是逗号与空白时不能把空串当地址
     * <p>
     * 空串匹配不上白名单（失败方向是对的），但审计日志里的来源会变成一片空白——
     * 而「谁在敲门」正是那行日志唯一的用处。所以要退回到后面的兜底去取。
     */
    @Test
    @DisplayName("X-Forwarded-For 只有逗号时退回 TCP 对端，不把空串当地址")
    void fallsBackWhenForwardedHeaderCarriesNoAddress() throws Exception {
        security.setTrustProxy(true);

        MockHttpServletRequest request = request("127.0.0.1", "Bearer " + TOKEN);
        request.addHeader("X-Forwarded-For", " , ");

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter().doFilter(request, response, chain);

        assertEquals(200, response.getStatus(), "应退回 TCP 对端 127.0.0.1 后放行");
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
