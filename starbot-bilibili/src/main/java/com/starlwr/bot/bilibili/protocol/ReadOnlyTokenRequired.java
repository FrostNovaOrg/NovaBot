package com.starlwr.bot.bilibili.protocol;

import com.starlwr.bot.core.service.EventStreamTokenService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.List;
import java.util.Map;

/**
 * 要求来访者出示只读口令
 *
 * <h2>为什么它不能只靠回环判据</h2>
 *
 * 反向代理与本程序装在<b>同一台机器</b>上，它转发过来的连接<b>源地址就是回环</b>。
 * 也就是说反代一上线，{@code LoopbackOnly} 就不再等于「人在这台机器上」——
 * <b>任何能连上反代的人在端点看来都是本机</b>。口令是那之后唯一真正的门。
 *
 * <h2>口令走 {@code Sec-WebSocket-Protocol}，不走查询参数</h2>
 *
 * 面板是浏览器应用，而浏览器的 {@code WebSocket} 构造器<b>不能设自定义请求头</b>，
 * 能设的只有子协议。所以口令放在子协议里，形如 {@code nova.token.<口令>}。
 * <p>
 * ⚠️ <b>刻意不接受 {@code ?token=} 查询参数</b>，尽管那样最省事：
 * 查询串会进反向代理的访问日志、浏览器历史与 {@code Referer}，
 * <b>等于把口令抄进好几个我们管不到的地方</b>。检查单里「关闭请求体日志」那条
 * 防的是请求体，防不住请求行。
 * <p>
 * 非浏览器客户端可以用 {@code Authorization: Bearer <口令>}，两种都认。
 */
@Slf4j
class ReadOnlyTokenRequired implements HandshakeInterceptor {
    /**
     * 子协议里承载口令的前缀
     */
    private static final String SUBPROTOCOL_PREFIX = "nova.token.";

    private static final String BEARER_PREFIX = "Bearer ";

    private final EventStreamTokenService tokens;

    ReadOnlyTokenRequired(EventStreamTokenService tokens) {
        this.tokens = tokens;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler handler, Map<String, Object> attributes) {
        if (tokens.verify(presentedToken(request))) {
            return true;
        }

        // 失败的细节（无效／已吊销／没出示、连续第几次）由 EventStreamTokenService 记，
        // 那里统一保证不把口令原文写进日志
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        return false;
    }

    /**
     * 取出来访者出示的口令
     * <p>
     * 两种承载方式都认；<b>查询参数一律不看</b>，理由见类文档。
     */
    private String presentedToken(ServerHttpRequest request) {
        List<String> protocols = request.getHeaders().get("Sec-WebSocket-Protocol");
        if (protocols != null) {
            for (String raw : protocols) {
                // 一个头里可能逗号分隔写了多个子协议
                for (String candidate : raw.split(",")) {
                    String trimmed = candidate.trim();
                    if (trimmed.startsWith(SUBPROTOCOL_PREFIX)) {
                        return trimmed.substring(SUBPROTOCOL_PREFIX.length());
                    }
                }
            }
        }

        String authorization = request.getHeaders().getFirst("Authorization");
        if (authorization != null && authorization.startsWith(BEARER_PREFIX)) {
            return authorization.substring(BEARER_PREFIX.length()).trim();
        }

        return null;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler handler, Exception exception) {
    }
}
