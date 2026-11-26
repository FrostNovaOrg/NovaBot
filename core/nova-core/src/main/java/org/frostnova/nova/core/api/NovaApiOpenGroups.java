package org.frostnova.nova.core.api;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 哪几组程序接口对任何来源地址开放
 * <p>
 * 设置项 {@code novabot.core.config-ui.api-any-source-groups} 每个请求现读，保存即生效。
 * 清单里只有<b>此刻登记了的</b>组名起作用：插件卸掉之后它那一组的名字留在清单里也开不了什么，
 * 写错的、不存在的组名同样不起作用，也不报错。
 * <p>
 * 开放只免去「来源名单」这一道：令牌、范围、仅管理员范围、无效令牌限速与路径检查一道不少。
 * 底座自带的 {@code /me} 不单列：至少有一组开放时随之开放——拿着这组令牌的程序要先能问一句「我是谁」。
 */
public final class NovaApiOpenGroups {
    private final Supplier<List<String>> configured;

    private final NovaApiRegistry registry;

    /**
     * @param configured 设置项此刻的值，每次现取
     * @param registry   登记了的接口组
     */
    public NovaApiOpenGroups(Supplier<List<String>> configured, NovaApiRegistry registry) {
        this.configured = configured;
        this.registry = registry;
    }

    /** 一组也不开放 */
    public static NovaApiOpenGroups none(NovaApiRegistry registry) {
        return new NovaApiOpenGroups(List::of, registry);
    }

    /**
     * 此刻真正开放着的组：清单里写了、而且登记了的
     * @return 组名，按清单里的先后
     */
    public Set<String> effective() {
        List<String> listed = configured.get();
        Set<String> result = new LinkedHashSet<>();
        if (listed == null) {
            return result;
        }
        Set<String> registered = registry.segments();
        for (String name : listed) {
            if (name != null && registered.contains(name.strip())) {
                result.add(name.strip());
            }
        }
        return result;
    }

    /**
     * 这一组此刻对任何来源地址开不开放
     * @param group {@code /nova/api/v1/} 之后的第一段，已解码；不在 v1 之下时为 null
     * @return 开放时为 true
     */
    public boolean opens(String group) {
        if (group == null) {
            return false;
        }
        Set<String> open = effective();
        return open.contains(group) || (NovaApiRegistry.RESERVED.contains(group) && !open.isEmpty());
    }
}
