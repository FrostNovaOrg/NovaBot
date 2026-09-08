package com.starlwr.bot.core.config;

import com.starlwr.bot.core.properties.NovaBotPrefixes;
import org.apache.commons.logging.Log;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 把上一档 {@code starbot.*} 配置键补进现行 {@code novabot.*} 位置
 * <p>
 * 写在 {@link EnvironmentPostProcessor} 上，是为了让
 * {@code @ConfigurationProperties} 与 {@code @ConditionalOnProperty} 看见同一份现行键：
 * 只在绑定根上叠一趟，条件注解读环境时仍会把「只写了旧键」当成缺席。
 * <p>
 * 现行键已经在场的项不覆盖。启动时每个被用到的前缀打一行
 * 「读到旧键 …，请迁到 …」，不逐键刷屏。
 */
public class NovaBotPrefixAlias implements EnvironmentPostProcessor {
    static final String SOURCE_NAME = "novaBotPrefixAlias";

    private final Log log;

    public NovaBotPrefixAlias(DeferredLogFactory logFactory) {
        this.log = logFactory.getLog(NovaBotPrefixAlias.class);
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, Object> copies = new LinkedHashMap<>();
        Set<String> usedLegacy = new LinkedHashSet<>();

        for (PropertySource<?> source : environment.getPropertySources()) {
            if (SOURCE_NAME.equals(source.getName()) || !(source instanceof EnumerablePropertySource<?> enumerable)) {
                continue;
            }
            for (String name : enumerable.getPropertyNames()) {
                String current = NovaBotPrefixes.toCurrent(name);
                if (current == null || current.equals(name)) {
                    continue;
                }
                if (environment.containsProperty(current)) {
                    continue;
                }
                copies.put(current, source.getProperty(name));
                String legacy = longestLegacy(name);
                if (legacy != null) {
                    usedLegacy.add(legacy);
                }
            }
        }

        if (copies.isEmpty()) {
            return;
        }

        environment.getPropertySources().addFirst(new MapPropertySource(SOURCE_NAME, copies));

        for (Map.Entry<String, String> pair : NovaBotPrefixes.CURRENT_TO_LEGACY) {
            if (usedLegacy.contains(pair.getValue())) {
                log.warn("读到旧键 " + pair.getValue() + "，请迁到 " + pair.getKey());
            }
        }
    }

    private static String longestLegacy(String name) {
        String hit = null;
        for (Map.Entry<String, String> pair : NovaBotPrefixes.CURRENT_TO_LEGACY) {
            String legacy = pair.getValue();
            if (name.equals(legacy) || name.startsWith(legacy + ".")) {
                if (hit == null || legacy.length() > hit.length()) {
                    hit = legacy;
                }
            }
        }
        return hit;
    }
}
