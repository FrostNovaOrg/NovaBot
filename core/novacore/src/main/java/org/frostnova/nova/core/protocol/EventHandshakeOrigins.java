package org.frostnova.nova.core.protocol;

import lombok.extern.slf4j.Slf4j;
import org.frostnova.nova.core.properties.EventStreamProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 按配置的页面来源列表检查握手。 */
@Slf4j
final class EventHandshakeOrigins implements HandshakeInterceptor {
    /**
     * 设置页上登记页面来源的那一项在哪
     */
    static final String SETTING = "「设置 → 高级选项 → 服务 → 事件输出 · 页面来源」";

    private final Set<String> allowed = new HashSet<>();

    private final EventStreamStatus status;

    EventHandshakeOrigins(List<String> origins, EventStreamStatus status) {
        EventStreamProperties.validateAllowedOrigins(origins);
        // 配置文件里手写的 :80、:443 照样去掉再比：浏览器发来的值不带默认端口
        for (String origin : origins) {
            allowed.add(EventStreamProperties.withoutDefaultPort(origin).toLowerCase(Locale.ROOT));
        }
        this.status = status;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler handler, Map<String, Object> attributes) {
        List<String> origins = request.getHeaders().getOrEmpty("Origin");
        if (origins.isEmpty() || (origins.size() == 1 && allowed.contains(origins.get(0).toLowerCase(Locale.ROOT)))) {
            return true;
        }
        // 只在没直接对上的这一条路上多看一眼：直接对上的连接不多做任何事
        if (origins.size() == 1 && EventStreamProperties.isOrigin(origins.get(0))) {
            // 手写的客户端可能带着 :80、:443 来，与配置用同一个规整法去掉默认端口再比
            String origin = EventStreamProperties.withoutDefaultPort(origins.get(0));
            if (allowed.contains(origin.toLowerCase(Locale.ROOT))) {
                return true;
            }
            if (status.unlisted(origin)) {
                log.warn("事件输出拒绝了页面来源 {} 的连接：该来源未登记。如需让这个页面连接，请在控制台"
                        + SETTING + "中添加它，然后重启", origin);
            }
        }
        response.setStatusCode(HttpStatus.FORBIDDEN);
        return false;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler handler, Exception exception) {
    }
}
