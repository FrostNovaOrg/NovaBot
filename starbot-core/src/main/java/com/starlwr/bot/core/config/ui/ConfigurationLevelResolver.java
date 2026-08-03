package com.starlwr.bot.core.config.ui;

import com.starlwr.bot.core.config.ConfigLevel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * 配置项重要程度解析器
 * <p>
 * 从 {@code @ConfigurationProperties} 类上反射读取 {@link ConfigLevel}，得到「配置项名 → 重要程度」。
 * <p>
 * 之所以用反射而不是走编译期元数据：Spring 的配置元数据处理器只认它自己的注解，
 * 自定义注解不会出现在生成的 JSON 里。而反射能让重要程度标注就写在字段旁边，
 * 与配置项本身同增同减，不需要另外维护一份清单——那种清单迟早会与代码脱节。
 */
@Slf4j
@Service
public class ConfigurationLevelResolver {
    /**
     * 单个配置类的最大递归深度，防止自引用结构导致无限展开
     */
    private static final int MAX_DEPTH = 6;

    private final ApplicationContext context;

    private volatile Map<String, ConfigLevel.Level> levels;

    @Autowired
    public ConfigurationLevelResolver(ApplicationContext context) {
        this.context = context;
    }

    /**
     * 获取配置项名到重要程度的映射
     * @return 映射，未标注的配置项不在其中
     */
    public Map<String, ConfigLevel.Level> getLevels() {
        if (levels == null) {
            synchronized (this) {
                if (levels == null) {
                    levels = resolve();
                }
            }
        }

        return levels;
    }

    private Map<String, ConfigLevel.Level> resolve() {
        Map<String, ConfigLevel.Level> result = new HashMap<>();

        Collection<Object> beans = context.getBeansWithAnnotation(ConfigurationProperties.class).values();
        for (Object bean : beans) {
            ConfigurationProperties annotation = AnnotationUtils.findAnnotation(bean.getClass(), ConfigurationProperties.class);
            if (annotation == null) {
                continue;
            }

            String prefix = annotation.prefix().isEmpty() ? annotation.value() : annotation.prefix();

            // 标注了 @Configuration 的配置类会被 CGLIB 代理，代理类上只有合成字段，
            // 必须先还原成原始类才能反射到真正的配置字段
            Class<?> type = ClassUtils.getUserClass(bean);

            try {
                walk(type, prefix, result, 0);
            } catch (Exception e) {
                log.debug("解析 {} 的配置项重要程度失败: {}", type.getName(), e.getMessage());
            }
        }

        log.info("已解析 {} 个配置项的重要程度标注", result.size());
        return Map.copyOf(result);
    }

    /**
     * 递归遍历配置类的字段
     */
    private void walk(Class<?> type, String prefix, Map<String, ConfigLevel.Level> result, int depth) {
        if (depth > MAX_DEPTH || type == null) {
            return;
        }

        for (Field field : type.getDeclaredFields()) {
            if (field.isSynthetic() || java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                continue;
            }

            String name = prefix.isEmpty() ? toKebab(field.getName()) : prefix + "." + toKebab(field.getName());

            ConfigLevel level = field.getAnnotation(ConfigLevel.class);
            if (level != null) {
                result.put(name, level.value());
            }

            // 嵌套配置类同样需要展开；集合与基本类型到此为止
            Class<?> fieldType = field.getType();
            if (isNestedConfig(fieldType)) {
                walk(fieldType, name, result, depth + 1);
            }
        }
    }

    /**
     * 判断是否为需要继续展开的嵌套配置类
     */
    private boolean isNestedConfig(Class<?> type) {
        return !type.isPrimitive()
                && !type.isEnum()
                && !Collection.class.isAssignableFrom(type)
                && !Map.class.isAssignableFrom(type)
                && type.getName().startsWith("com.starlwr.");
    }

    /**
     * 驼峰转短横线，与配置文件中的书写形式一致
     */
    private String toKebab(String name) {
        StringBuilder builder = new StringBuilder();
        for (char c : name.toCharArray()) {
            if (Character.isUpperCase(c)) {
                builder.append('-').append(Character.toLowerCase(c));
            } else {
                builder.append(c);
            }
        }
        return builder.toString();
    }
}
