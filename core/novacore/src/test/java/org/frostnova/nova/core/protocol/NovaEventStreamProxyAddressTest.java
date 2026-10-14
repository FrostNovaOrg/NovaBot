package org.frostnova.nova.core.protocol;

import org.frostnova.nova.core.properties.EventStreamProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.filter.ForwardedHeaderFilter;
import org.springframework.web.servlet.handler.SimpleUrlHandlerMapping;
import org.springframework.web.socket.server.support.WebSocketHttpRequestHandler;

import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;

class NovaEventStreamProxyAddressTest {
    @ParameterizedTest(name = "转发头处理={0}, 代理传来访者地址={1}, 视为本机={2}")
    @CsvSource({"false,true,true", "true,true,false", "true,false,true"})
    @DisplayName("本机反向代理连接按转发头处理与来访者地址判定来源")
    void proxyAddressControlsLoopbackCheck(boolean processForwardedHeaders, boolean proxySendsAddress,
                                          boolean expectedLoopback) throws Exception {
        // 抓说明误称所有代理连接均视为本机，掩盖开启转发头处理且传地址时被拒绝的情形。
        NovaEventStreamConfiguration configuration = new NovaEventStreamConfiguration();
        try {
            EventStreamProperties properties = new EventStreamProperties();
            properties.setEnabled(true);
            SimpleUrlHandlerMapping mapping = (SimpleUrlHandlerMapping) configuration.novaEventStreamHandlerMapping(
                    properties, new NovaEventStream(10), null);
            WebSocketHttpRequestHandler handler = (WebSocketHttpRequestHandler) mapping.getUrlMap().get(properties.getPath());
            MockHttpServletRequest request = new MockHttpServletRequest("GET", properties.getPath());
            request.setRemoteAddr("127.0.0.1");
            request.setRemotePort(12345);
            if (proxySendsAddress) {
                request.addHeader("X-Forwarded-For", "192.0.2.42");
            }
            MockHttpServletResponse response = new MockHttpServletResponse();
            jakarta.servlet.FilterChain handshake = (incoming, outgoing) -> {
                ServletServerHttpRequest serverRequest = new ServletServerHttpRequest((jakarta.servlet.http.HttpServletRequest) incoming);
                boolean allowed = assertDoesNotThrow(() -> handler.getHandshakeInterceptors().get(0).beforeHandshake(
                        serverRequest, new ServletServerHttpResponse((jakarta.servlet.http.HttpServletResponse) outgoing),
                        handler.getWebSocketHandler(), new HashMap<>()));
                System.out.printf("PROXY: forwarded-processing=%s, forwarded-for=%s, remote=%s, loopback-allowed=%s%n",
                        processForwardedHeaders, proxySendsAddress, serverRequest.getRemoteAddress().getAddress().getHostAddress(), allowed);
                assertEquals(expectedLoopback, allowed);
            };
            if (processForwardedHeaders) {
                new ForwardedHeaderFilter().doFilter(request, response, handshake);
            } else {
                handshake.doFilter(request, response);
            }
        } finally {
            configuration.destroy();
        }
    }
}
