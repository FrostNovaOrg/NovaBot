package org.frostnova.nova.core.api;

import java.util.List;
import java.util.Set;

/**
 * 插件挂在 {@code /nova/api/v1/<自己的一段>/} 下的一个接口
 *
 * @param method  请求方法：GET、POST、PUT、PATCH、DELETE
 * @param path    相对于插件那一段的路径，可含路径变量，例如 {@code streamers/{uid}}；空串即那一段本身
 * @param scope   要求的范围标识，必须是已登记的范围
 * @param handler 处理器
 */
public record NovaApiRoute(String method, String path, String scope, Handler handler) {
    static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");

    public NovaApiRoute {
        if (method == null || !METHODS.contains(method)) {
            throw new IllegalArgumentException("请求方法只认 " + METHODS + ": " + method);
        }
        path = path == null ? "" : path;
        if (path.startsWith("/") || path.endsWith("/") || path.contains("//")) {
            throw new IllegalArgumentException("接口路径不以斜杠开头或结尾: " + path);
        }
        for (String part : segments(path)) {
            if (!part.matches("[a-z0-9][a-z0-9_-]{0,63}") && !part.matches("\\{[a-zA-Z][a-zA-Z0-9]{0,31}}")) {
                throw new IllegalArgumentException("接口路径只用小写字母、数字、连字符与 {变量}: " + path);
            }
        }
        if (scope == null || !NovaApiScope.ID.matcher(scope).matches()) {
            throw new IllegalArgumentException("接口必须写明要求的范围: " + method + " " + path);
        }
        if (handler == null) {
            throw new IllegalArgumentException("接口没有处理器: " + method + " " + path);
        }
    }

    public static NovaApiRoute get(String path, String scope, Handler handler) {
        return new NovaApiRoute("GET", path, scope, handler);
    }

    public static NovaApiRoute post(String path, String scope, Handler handler) {
        return new NovaApiRoute("POST", path, scope, handler);
    }

    static List<String> segments(String path) {
        return path.isEmpty() ? List.of() : List.of(path.split("/"));
    }

    /**
     * 接口处理器
     * <p>
     * 返回值放进 {@code data}：一个对象、一张 Map，或 {@link NovaApiPage}（列表类接口）。
     * 返回 null 时 {@code data} 为空对象。要回错误时抛 {@link NovaApiException}。
     */
    @FunctionalInterface
    public interface Handler {
        Object handle(NovaApiRequest request) throws Exception;
    }
}
