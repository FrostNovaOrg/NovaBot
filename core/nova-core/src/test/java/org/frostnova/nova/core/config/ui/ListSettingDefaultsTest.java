package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 设置页上每行一项的列表设置：字段表给的默认值与程序没写这一项时真用的一致
 * <p>
 * 抓的用户故障：编译期元数据读不出 {@code new ArrayList<>(List.of(...))} 这类初值，
 * 字段表里列表项的默认值一律是空。控制台「允许的来源 IP」默认只放行本机两条，设置页却画成空框，
 * 「默认：」那行写「空」，用户以为是往里加一条的框，只填一个外部地址就保存，本机从下一个请求起就进不来；
 * 点「恢复默认」则把框清空，保存又因名单为空被拒。
 * <p>
 * 「程序真用的」这一侧不走字段表那条路：扫类路径上全部 {@code @ConfigurationProperties} 类，
 * 各新建一份，按配置文件写出时用的那套展开规则（{@link ConfigurationPropertyFields#values}）读出初值。
 */
@DisplayName("列表设置的默认值与程序真用的一致")
class ListSettingDefaultsTest {
    private static final String CONSOLE_ALLOW_IPS = "novabot.core.config-ui.allow-ips";

    @Test
    @DisplayName("配置文件没写控制台来源 IP 时，设置页拿到的默认值是本机两条")
    void consoleAllowListDefaultIsLoopback() {
        // 抓的故障：默认名单下框是空的，用户只填外部地址就把自己挡在控制台外
        Object shown = field(CONSOLE_ALLOW_IPS).defaultValue();
        assertEquals(List.of("127.0.0.1/32", "::1/128"), shown);
    }

    @Test
    @DisplayName("每一项列表设置：字段表的默认值等于新建配置对象里那个字段的初值")
    void everyListDefaultMatchesTheProgram() throws Exception {
        // 抓的故障：某个列表项初值不空，字段表却给空（或给了别的），设置页的框、「默认：」那行、
        // 「恢复默认」三处都与程序没写这一项时真用的对不上
        Map<String, Object> real = ConfigurationPropertyFields.values(freshPropertyObjects());
        Map<String, String> mismatched = new TreeMap<>();
        int compared = 0;
        for (ConfigurationMetadataService.ConfigurationField field : new ConfigurationMetadataService().getFields()) {
            if (!"list".equals(field.widget()) || !real.containsKey(field.name())) {
                continue;
            }
            compared++;
            List<String> expected = texts(real.get(field.name()));
            if (!Objects.equals(expected, field.defaultValue())) {
                mismatched.put(field.name(), "字段表 " + field.defaultValue() + "，程序 " + expected);
            }
        }
        assertTrue(compared >= 5, "比到的列表项太少（" + compared + "），这一格量在空集上");
        assertEquals(Map.of(), mismatched);
    }

    private static ConfigurationMetadataService.ConfigurationField field(String name) {
        return new ConfigurationMetadataService().getFields().stream()
                .filter(field -> field.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("字段表里没有 " + name));
    }

    /**
     * 类路径上 org.frostnova.nova 下全部标了 {@code @ConfigurationProperties} 的类，各新建一份；建不出的跳过
     */
    private static List<Object> freshPropertyObjects() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(ConfigurationProperties.class));
        List<Object> objects = new ArrayList<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents("org.frostnova.nova")) {
            try {
                Constructor<?> constructor = Class.forName(candidate.getBeanClassName()).getDeclaredConstructor();
                constructor.setAccessible(true);
                objects.add(constructor.newInstance());
            } catch (ReflectiveOperationException | LinkageError skipped) {
                // 没有无参构造的不在这一格里
            }
        }
        return objects;
    }

    private static List<String> texts(Object value) {
        if (value == null) {
            return null;
        }
        List<String> result = new ArrayList<>();
        if (value instanceof Collection<?> items) {
            items.forEach(item -> result.add(String.valueOf(item)));
        }
        return result;
    }
}
