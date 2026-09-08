package com.starlwr.bot.bilibili;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 本模块对 Spring 的自报还在，且指着的正是本模块那个类
 * <p>
 * 为什么要有这么一格、为什么比的是去重后的集合，见 {@code OneBotAdapterSelfDeclarationTest}
 * 的类注释，此处不复述。
 */
@DisplayName("哔哩哔哩插件自报")
class BilibiliSelfDeclarationTest {
    private static final String IMPORTS =
            "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";
    private static final String OWN_PACKAGE = "com.starlwr.bot.bilibili.";

    @Test
    @DisplayName("自报文件恰指本模块那个自报类, 类上两个注解俱在, 扫描排除自身")
    void selfDeclarationStaysWired() {
        List<String> red = new ArrayList<>();

        try {
            assertEquals(Set.of(BilibiliPluginAutoConfiguration.class.getName()), declaredInOwnPackage(),
                    "类路径上属于本模块的自报条目不是恰这一个类；读到的文件：" + sources());
        } catch (Throwable t) {
            red.add("① " + t.getMessage());
        }

        try {
            assertTrue(BilibiliPluginAutoConfiguration.class.isAnnotationPresent(AutoConfiguration.class),
                    "自报类没挂 @AutoConfiguration, 写在自报文件里也不会被装进容器");
            assertTrue(BilibiliPluginAutoConfiguration.class.isAnnotationPresent(ComponentScan.class),
                    "自报类没挂 @ComponentScan, 装进来了也不会去扫本模块的组件");
        } catch (Throwable t) {
            red.add("② " + t.getMessage());
        }

        try {
            assertTrue(excludesItself(), "@ComponentScan 没有按类型排除自身, 同一个配置类会以两个名字进容器两次");
        } catch (Throwable t) {
            red.add("③ " + t.getMessage());
        }

        if (!red.isEmpty()) {
            fail(red.size() + " 问红：" + String.join("；", red));
        }
    }

    private static boolean excludesItself() {
        ComponentScan annotation = BilibiliPluginAutoConfiguration.class.getAnnotation(ComponentScan.class);
        if (annotation == null) {
            return fail("自报类上没有 @ComponentScan");
        }
        for (ComponentScan.Filter filter : annotation.excludeFilters()) {
            if (filter.type() == FilterType.ASSIGNABLE_TYPE
                    && Arrays.asList(filter.classes()).contains(BilibiliPluginAutoConfiguration.class)) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> declaredInOwnPackage() throws Exception {
        Set<String> out = new TreeSet<>();
        for (URL url : sources()) {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(url.openStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith(OWN_PACKAGE)) {
                        out.add(trimmed);
                    }
                }
            }
        }
        return out;
    }

    /** 红的时候要答得出「读的是哪几份文件」，否则只知道对不上、不知道对的是谁 */
    private static List<URL> sources() throws Exception {
        List<URL> out = new ArrayList<>();
        Enumeration<URL> found = BilibiliSelfDeclarationTest.class.getClassLoader().getResources(IMPORTS);
        while (found.hasMoreElements()) {
            out.add(found.nextElement());
        }
        return out;
    }
}
