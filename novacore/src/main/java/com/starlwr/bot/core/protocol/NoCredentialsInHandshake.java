package com.starlwr.bot.core.protocol;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.List;
import java.util.Map;

/**
 * 拒收任何把口令带在握手里的连接
 *
 * <h2>为什么口令不再走握手</h2>
 *
 * 口令曾经走 {@code Sec-WebSocket-Protocol} 子协议。那是<b>请求头</b>，
 * 而请求头会进反向代理的 access log——<b>2026-08-13 实测</b>：Caddy 的 json 日志
 * 默认记录全部请求头，只对 {@code Authorization}、{@code Cookie} 等做遮蔽，
 * 子协议头<b>不在那份名单里，明文落盘</b>。
 * <p>
 * 给 Caddy 打一条 filter 能解决 Caddy，但<b>这是开源产品</b>：
 * 部署者用的是 nginx、Traefik、Apache 还是别的，我们管不到。
 * <b>逐个代理去打补丁是纪律，凭据不进任何 HTTP 工件才是结构。</b>
 * 所以认证挪到了连接建立之后的第一帧（见 {@link NovaEventEndpoint}）。
 *
 * <h2>为什么是「拒收」而不是「忽略」</h2>
 *
 * 忽略等于留了一条看起来还能用的旧通道：客户端照旧把口令塞进子协议，
 * <b>口令照旧落进代理日志</b>，而它自己因为随后发了认证帧所以一切正常，
 * 没有任何人会发现。<b>拒收才能让还在用旧通道的客户端立刻知道要改。</b>
 * <p>
 * ⚠️ 同理拒收 {@code Authorization} 头。它在多数代理里确实会被遮蔽，
 * 但「多数」不是「全部」，而且这条规矩要能一句话讲清：
 * <b>握手里不许有凭据</b>，不是「除了这个头之外不许有」。
 */
@Slf4j
class NoCredentialsInHandshake implements HandshakeInterceptor {
    /**
     * 曾经承载口令的子协议前缀。留着它只为认出来并拒掉
     */
    private static final String LEGACY_SUBPROTOCOL_PREFIX = "nova.token.";

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler handler, Map<String, Object> attributes) {
        if (carriesCredential(request)) {
            // ⚠️ 不把看到的值写进日志——那等于把刚从代理日志里救出来的口令又抄进我们自己的日志
            log.warn("事件流拒绝了一次握手: 请求里携带了凭据。口令已改为连接后的第一帧发送 "
                    + "({\"kind\":\"auth\",\"v\":2,\"token\":\"...\"})，请更新客户端");
            response.setStatusCode(HttpStatus.BAD_REQUEST);
            return false;
        }
        return true;
    }

    private boolean carriesCredential(ServerHttpRequest request) {
        // ⚠️ 用 getOrEmpty 而不是把 HttpHeaders 当 Map 使：
        // Spring 7 起 HttpHeaders 不再实现 Map，containsKey/get 都没有了
        if (!request.getHeaders().getOrEmpty("Authorization").isEmpty()) {
            return true;
        }

        List<String> protocols = request.getHeaders().getOrEmpty("Sec-WebSocket-Protocol");
        for (String header : protocols) {
            for (String item : header.split(",")) {
                if (item.trim().startsWith(LEGACY_SUBPROTOCOL_PREFIX)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler handler, Exception exception) {
        // 无需处理
    }
}
