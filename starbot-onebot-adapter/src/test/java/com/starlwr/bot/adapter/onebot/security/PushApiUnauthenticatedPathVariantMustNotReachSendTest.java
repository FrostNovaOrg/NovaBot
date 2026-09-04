package com.starlwr.bot.adapter.onebot.security;

import com.starlwr.bot.core.util.IpMatcher;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.adapter.onebot.config.OneBotAdapterPluginProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.context.support.StaticWebApplicationContext;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.ServletRequestPathUtils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 推送接口的路径变体必须仍走鉴权
 * <p>
 * 过滤器按 {@code getRequestURI()} 做字符串精确查表；框架注册路由时会解码路径、
 * 去掉分号后的矩阵参数。两边语义不一致时，未携带令牌的请求也能落到发送方法上。
 * 本文件断言的是应有的安全行为：凡框架会路由到发送方法的请求，未鉴权不得调用它。
 */
@DisplayName("推送接口路径变体不得绕过鉴权到达发送方法")
class PushApiUnauthenticatedPathVariantMustNotReachSendTest {
    private static final String PATH = "/onebot/send";

    private static final String TOKEN = "Xq7-Rt2_Kd9vLm4Zp0Ns";

    /**
     * 白名单外、未带令牌。过滤器若因查表未命中而放行，后面又命中了发送方法，即绕过成立。
     */
    private static final String FOREIGN_IP = "203.0.113.7";

    private OneBotAdapterPluginProperties.Security security;

    private PushApiTokenStore tokenStore;

    private RequestMappingHandlerMapping mapping;

    private SendProbe probe;

    @BeforeEach
    void setUp() throws Exception {
        security = new OneBotAdapterPluginProperties.Security();
        tokenStore = new PushApiTokenStore();
        tokenStore.register(PATH, TOKEN);

        probe = new SendProbe();
        StaticWebApplicationContext context = new StaticWebApplicationContext();
        context.refresh();
        mapping = new RequestMappingHandlerMapping();
        mapping.setApplicationContext(context);
        mapping.afterPropertiesSet();

        Method method = SendProbe.class.getMethod("send");
        // 与生产侧 OneBotController.register 同一套 paths + POST 注册口
        RequestMappingInfo info = RequestMappingInfo
                .paths(PATH)
                .methods(RequestMethod.POST)
                .options(mapping.getBuilderConfiguration())
                .build();
        mapping.registerMapping(info, probe, method);
    }

    private PushApiSecurityFilter filter() {
        return new PushApiSecurityFilter(
                security,
                tokenStore,
                new IpMatcher(security.getAllowIps()),
                new RateLimiter(security.getRateLimit().getPermitsPerMinute(), security.getRateLimit().getBurst()));
    }

    private MockHttpServletRequest post(String requestUri) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", requestUri);
        request.setRequestURI(requestUri);
        request.setServletPath(requestUri);
        request.setContextPath("");
        request.setRemoteAddr(FOREIGN_IP);
        return request;
    }

    /**
     * 过滤器放行之后，框架是否把这个请求交给了发送方法
     */
    private boolean reachedSend(MockHttpServletRequest request, MockHttpServletResponse response) throws Exception {
        int before = probe.calls;
        filter().doFilter(request, response, (req, res) -> {
            MockHttpServletRequest http = (MockHttpServletRequest) req;
            if (!ServletRequestPathUtils.hasCachedPath(http)) {
                ServletRequestPathUtils.parseAndCache(http);
            }
            HandlerExecutionChain mapped;
            try {
                mapped = mapping.getHandler(http);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            if (mapped != null && mapped.getHandler() instanceof HandlerMethod handler
                    && handler.getBean() == probe) {
                probe.send();
            }
        });
        return probe.calls > before;
    }

    @Test
    @DisplayName("阳性对照：登记路径未带令牌必须拦在过滤器，发送方法零次")
    void exactPathWithoutTokenIsRejectedAndDoesNotReachSend() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean reached = reachedSend(post(PATH), response);

        assertTrue(response.getStatus() == 401 || response.getStatus() == 403,
                "登记路径未带令牌应被过滤器拒绝, 实际 HTTP " + response.getStatus());
        assertEquals(0, probe.calls, "发送方法不该被调用, 实际 " + probe.calls);
        assertTrue(!reached);
    }

    @Test
    @DisplayName("未带令牌的路径变体，凡框架会路由到发送方法的，都不得调用它")
    void unauthenticatedPathVariantMustNotReachSend() throws Exception {
        String[] variants = {
                PATH + "/",
                PATH + "//",
                "//onebot/send",
                "/onebot//send",
                PATH + ";jsessionid=abc",
                PATH + ";x=1",
                "/ONEBOT/send",
                "/onebot/SEND",
                "/onebot/send%2f",
                "/onebot/send%2F",
                "/onebot/%73end",
                "/onebot/send%3bx=1",
                "/onebot/./send",
                "/ctx" + PATH
        };

        List<String> reached = new ArrayList<>();
        List<String> probed = new ArrayList<>();
        for (String uri : variants) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            boolean hit = reachedSend(post(uri), response);
            probed.add(uri + "→status=" + response.getStatus() + ",protected=" + tokenStore.isProtected(uri)
                    + ",reached=" + hit);
            if (hit) {
                reached.add(uri + " (HTTP " + response.getStatus() + ")");
            }
        }

        if (!reached.isEmpty()) {
            fail("未带令牌却到达了发送方法: " + reached + "；全表 " + probed);
        }
    }

    /**
     * 与生产发送方法同一注册方式挂上的探针，调用次数即「处理器被打到」
     */
    static final class SendProbe {
        int calls;

        public JSONObject send() {
            calls++;
            JSONObject body = new JSONObject();
            body.put("ok", true);
            return body;
        }
    }
}
