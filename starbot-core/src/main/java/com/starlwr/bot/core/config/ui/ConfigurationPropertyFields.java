package com.starlwr.bot.core.config.ui;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 配置项名到它所在字段的映射
 * <p>
 * 界面要从字段上读的东西不止一样：重要程度决定它摆在常用区还是高级区，生效时机决定保存之后
 * 那句话怎么写。<b>但「哪个键对应哪个字段」只有一条规则</b>——遍历
 * {@code @ConfigurationProperties} 类、逐层展开嵌套配置、字段名按短横线拼成键名。
 * 这条规则写两遍就会有两份，而它们分家的表现是：同一个配置项在一把尺眼里存在、
 * 在另一把眼里不存在，于是重要程度读得到而生效时机读不到，界面上却看不出任何异常。
 * <p>
 * 之所以用反射而不是走编译期元数据：Spring 的配置元数据处理器只认它自己的注解，
 * 自定义注解不会出现在生成的 JSON 里。而反射能让标注就写在字段旁边，与配置项本身同增同减，
 * 不需要另外维护一份清单——那种清单迟早会与代码脱节。
 */
@Slf4j
final class ConfigurationPropertyFields {
    /**
     * 单个配置类的最大递归深度，防止自引用结构导致无限展开
     */
    private static final int MAX_DEPTH = 6;

    private ConfigurationPropertyFields() {
    }

    /**
     * 扫描容器里全部配置类的字段
     * @param context 应用上下文
     * @return 配置项名到字段，按遍历顺序
     */
    static Map<String, Field> scan(ApplicationContext context) {
        Map<String, Field> result = new LinkedHashMap<>();

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
                log.debug("扫描 {} 的配置字段失败: {}", type.getName(), e.getMessage());
            }
        }

        return Collections.unmodifiableMap(result);
    }

    /**
     * 递归遍历配置类的字段
     */
    private static void walk(Class<?> type, String prefix, Map<String, Field> result, int depth) {
        if (depth > MAX_DEPTH || type == null) {
            return;
        }

        for (Field field : type.getDeclaredFields()) {
            if (field.isSynthetic() || Modifier.isStatic(field.getModifiers())) {
                continue;
            }

            String name = prefix.isEmpty() ? toKebab(field.getName()) : prefix + "." + toKebab(field.getName());
            result.putIfAbsent(name, field);

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
    private static boolean isNestedConfig(Class<?> type) {
        return !type.isPrimitive()
                && !type.isEnum()
                && !Collection.class.isAssignableFrom(type)
                && !Map.class.isAssignableFrom(type)
                && type.getName().startsWith("com.starlwr.");
    }

    /**
     * 驼峰转短横线，与配置文件中的书写形式一致
     */
    static String toKebab(String name) {
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
