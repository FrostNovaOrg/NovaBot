package org.frostnova.nova.core.protocol;

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
final class EventHandshakeOrigins implements HandshakeInterceptor {
    private final Set<String> allowed = new HashSet<>();

    EventHandshakeOrigins(List<String> origins) {
        EventStreamProperties.validateAllowedOrigins(origins);
        for (String origin : origins) {
            allowed.add(origin.toLowerCase(Locale.ROOT));
        }
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler handler, Map<String, Object> attributes) {
        List<String> origins = request.getHeaders().getOrEmpty("Origin");
        if (origins.isEmpty() || (origins.size() == 1 && allowed.contains(origins.get(0).toLowerCase(Locale.ROOT)))) {
            return true;
        }
        response.setStatusCode(HttpStatus.FORBIDDEN);
        return false;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler handler, Exception exception) {
    }
}
