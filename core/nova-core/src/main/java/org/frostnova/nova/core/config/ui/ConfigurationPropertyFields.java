package org.frostnova.nova.core.config.ui;

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
     * 沿同一条规则读出一批配置对象<b>此刻的取值</b>
     * <p>
     * 与 {@link #scan} 同住一个类、共用 {@link #walk} 那套展开规则，是有意的：
     * 「哪个键对应哪个字段」写两遍就会有两份。这里多问的只是一句「那个字段现在装着什么」。
     * <p>
     * 用途是<b>生成一份完整的配置文件</b>：编译期元数据里的默认值只有写成字面量的那些才有，
     * 像 {@code allow-ips} 这种在 Java 里初始化成一份清单的，元数据那一栏是空的。
     * 照元数据写出去等于把「默认放行本机回环」悄悄换成「白名单为空、全部拒绝」——
     * 🔴 <b>一份「按默认值写出来」的配置，和一份「把默认值抹掉写出来」的配置，
     * 在「每一项都写全了」这句话上长得一样。</b>
     * @param beans 配置对象，通常是容器里全部标了 {@code @ConfigurationProperties} 的 bean
     * @return 配置项名到当前取值，取不到值的项不在其中
     */
    static Map<String, Object> values(Collection<?> beans) {
        Map<String, Object> result = new LinkedHashMap<>();

        for (Object bean : beans) {
            ConfigurationProperties annotation = AnnotationUtils.findAnnotation(bean.getClass(), ConfigurationProperties.class);
            if (annotation == null) {
                continue;
            }

            String prefix = annotation.prefix().isEmpty() ? annotation.value() : annotation.prefix();
            Class<?> type = ClassUtils.getUserClass(bean);

            try {
                walkValues(bean, type, prefix, result, 0);
            } catch (Exception e) {
                log.debug("读取 {} 的配置默认值失败: {}", type.getName(), e.getMessage());
            }
        }

        return Collections.unmodifiableMap(result);
    }

    /**
     * 递归遍历配置对象，逐个字段读出当前值
     */
    private static void walkValues(Object instance, Class<?> type, String prefix, Map<String, Object> result, int depth) {
        if (depth > MAX_DEPTH || type == null || instance == null) {
            return;
        }

        for (Field field : type.getDeclaredFields()) {
            if (field.isSynthetic() || Modifier.isStatic(field.getModifiers())) {
                continue;
            }

            String name = prefix.isEmpty() ? toKebab(field.getName()) : prefix + "." + toKebab(field.getName());

            Object value;
            try {
                field.setAccessible(true);
                value = field.get(instance);
            } catch (RuntimeException | ReflectiveOperationException e) {
                // 读不到就当没有这一项，不塞一个编出来的值：
                // 一个「读不到所以没写」的键，和一个「读到了、值就是空」的键，在文件里长得一样
                log.debug("读取配置字段 {} 失败: {}", name, e.getMessage());
                continue;
            }

            if (isNestedConfig(field.getType())) {
                walkValues(value, field.getType(), name, result, depth + 1);
            } else {
                result.putIfAbsent(name, value);
            }
        }
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
                && type.getName().startsWith("org.frostnova.nova.");
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
