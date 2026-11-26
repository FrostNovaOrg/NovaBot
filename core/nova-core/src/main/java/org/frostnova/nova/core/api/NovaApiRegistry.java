package org.frostnova.nova.core.api;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 插件登记的范围与接口
 * <p>
 * 启动时收一次，之后只读。一家登记不合规就整家不采用并在日志里点名，其余各家照常。
 */
@Slf4j
public class NovaApiRegistry {
    /** 底座自己用的段名 */
    static final Set<String> RESERVED = Set.of("me");

    private static final Pattern SEGMENT = Pattern.compile("[a-z][a-z0-9-]{0,31}");

    private final Map<String, NovaApiScope> scopes = new LinkedHashMap<>();

    private final Map<String, List<NovaApiRoute>> routes = new HashMap<>();

    /** 查找结果 */
    public sealed interface Match permits Found, WrongMethod, Missing {
    }

    /** 找到了 */
    public record Found(NovaApiRoute route, Map<String, String> variables) implements Match {
    }

    /** 地址对得上，方法对不上 */
    public record WrongMethod() implements Match {
    }

    /** 没有这个地址 */
    public record Missing() implements Match {
    }

    public NovaApiRegistry(List<NovaApiProvider> providers) {
        for (NovaApiProvider provider : providers) {
            try {
                accept(provider);
            } catch (RuntimeException error) {
                log.error("统一接口: {} 的登记不予采用: {}", provider.getClass().getName(), error.getMessage());
            }
        }
    }

    private void accept(NovaApiProvider provider) {
        String segment = provider.segment();
        if (segment == null || !SEGMENT.matcher(segment).matches()) {
            throw new IllegalArgumentException("段名只用小写字母开头的小写字母、数字与连字符: " + segment);
        }
        if (RESERVED.contains(segment)) {
            throw new IllegalArgumentException("段名 " + segment + " 归底座自己用");
        }
        if (routes.containsKey(segment)) {
            throw new IllegalArgumentException("段名 " + segment + " 已被别的插件登记");
        }
        List<NovaApiScope> declared = List.copyOf(provider.scopes());
        Set<String> own = new HashSet<>();
        for (NovaApiScope scope : declared) {
            if (scopes.containsKey(scope.id()) || !own.add(scope.id())) {
                throw new IllegalArgumentException("范围 " + scope.id() + " 重复登记");
            }
        }
        List<NovaApiRoute> list = List.copyOf(provider.routes());
        Set<String> seen = new HashSet<>();
        for (NovaApiRoute route : list) {
            if (!own.contains(route.scope())) {
                throw new IllegalArgumentException("接口 " + route.method() + " " + route.path()
                        + " 要求的范围 " + route.scope() + " 不在本插件登记的范围里");
            }
            String shape = route.method() + " " + route.path().replaceAll("\\{[^}]+}", "{}");
            if (!seen.add(shape)) {
                throw new IllegalArgumentException("接口 " + route.method() + " " + route.path() + " 登记了两次");
            }
        }
        declared.forEach(scope -> scopes.put(scope.id(), scope));
        routes.put(segment, list);
        log.info("统一接口: 已登记 /nova/api/v1/{}/ 下 {} 个接口、{} 项范围", segment, list.size(), declared.size());
    }

    /** 全部已登记的范围，按标识排 */
    public List<NovaApiScope> scopes() {
        return scopes.values().stream().sorted(Comparator.comparing(NovaApiScope::id)).toList();
    }

    public Optional<NovaApiScope> scope(String id) {
        return Optional.ofNullable(scopes.get(id));
    }

    /**
     * 按方法与 {@code /nova/api/v1/} 之后的各段找接口
     * <p>
     * 同一地址对得上好几条时，取写死的段多的那条：{@code streamers/live} 先于 {@code streamers/{uid}}。
     * @param method   请求方法
     * @param segments 各段，已解码
     * @return 查找结果
     */
    public Match match(String method, List<String> segments) {
        if (segments.isEmpty()) {
            return new Missing();
        }
        List<NovaApiRoute> candidates = routes.get(segments.get(0));
        if (candidates == null) {
            return new Missing();
        }
        List<String> rest = segments.subList(1, segments.size());
        Found best = null;
        int bestLiterals = -1;
        boolean pathMatched = false;
        for (NovaApiRoute route : candidates) {
            List<String> pattern = NovaApiRoute.segments(route.path());
            if (pattern.size() != rest.size()) {
                continue;
            }
            Map<String, String> variables = new LinkedHashMap<>();
            int literals = 0;
            boolean ok = true;
            for (int i = 0; i < pattern.size() && ok; i++) {
                String part = pattern.get(i);
                if (part.startsWith("{")) {
                    variables.put(part.substring(1, part.length() - 1), rest.get(i));
                } else if (part.equals(rest.get(i))) {
                    literals++;
                } else {
                    ok = false;
                }
            }
            if (!ok) {
                continue;
            }
            pathMatched = true;
            if (route.method().equals(method) && literals > bestLiterals) {
                best = new Found(route, variables);
                bestLiterals = literals;
            }
        }
        if (best != null) {
            return best;
        }
        return pathMatched ? new WrongMethod() : new Missing();
    }

    /** 各段的接口清单，测试与诊断用 */
    List<NovaApiRoute> routes(String segment) {
        return new ArrayList<>(routes.getOrDefault(segment, List.of()));
    }
}
