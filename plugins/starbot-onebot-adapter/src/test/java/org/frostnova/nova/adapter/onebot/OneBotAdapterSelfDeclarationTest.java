package org.frostnova.nova.adapter.onebot;

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
 * 为什么要有这么一格：那份自报文件是本模块被容器看见的<b>唯一通道</b>，而它坏掉不会报错。
 * 没有格钉着，它被删掉、改名、或者类名写错一个字母，编译照过、别处照绿，
 * 表现只是那个插件<b>安安静静地整个不见了</b>，要到真起一次才看得出来。
 * <p>
 * 认定不按文件路径，按<b>类路径上所有同名自报文件的内容</b>现算：
 * 类路径上本来就有十几份同名文件（Spring Boot 自己的那些），
 * 只取第一份会取到别人家的；写死路径则量的是文件摆在哪儿，不是 Spring 会读到什么。
 * <p>
 * 比的是去重后的<b>集合</b>而不是条数：本模块 pom 里 maven-resources-plugin 的插件级
 * {@code <resources>} 连测试资源那一趟也一起改了，于是 {@code src/main/resources} 会同时落进
 * {@code target/classes} 与 {@code target/test-classes}，测试类路径上因此有两份同样的自报文件。
 * 那是构建摆放的重复，不是「声明了两个类」，按条数判会红在一件与本格无关的事情上。
 */
@DisplayName("适配器插件自报")
class OneBotAdapterSelfDeclarationTest {
    private static final String IMPORTS =
            "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";
    private static final String OWN_PACKAGE = "org.frostnova.nova.adapter.onebot.";
    private static final String EXTENSION_CLASS =
            "org.frostnova.nova.adapter.onebot.extension.napcat.util.NapcatServiceHolder";
    private static final String OWN_COMPONENT_CLASS =
            "org.frostnova.nova.adapter.onebot.alert.QqAlertChannel";

    @Test
    @DisplayName("自报文件恰指本模块那个自报类, 类上两个注解俱在, 扫描排除自身与扩展那一段包名")
    void selfDeclarationStaysWired() {
        List<String> red = new ArrayList<>();

        try {
            assertEquals(Set.of(OneBotAdapterPluginAutoConfiguration.class.getName()), declaredInOwnPackage(),
                    "类路径上属于本模块的自报条目不是恰这一个类；读到的文件：" + sources());
        } catch (Throwable t) {
            red.add("① " + t.getMessage());
        }

        try {
            assertTrue(OneBotAdapterPluginAutoConfiguration.class.isAnnotationPresent(AutoConfiguration.class),
                    "自报类没挂 @AutoConfiguration, 写在自报文件里也不会被装进容器");
            assertTrue(OneBotAdapterPluginAutoConfiguration.class.isAnnotationPresent(ComponentScan.class),
                    "自报类没挂 @ComponentScan, 装进来了也不会去扫本模块的组件");
        } catch (Throwable t) {
            red.add("② " + t.getMessage());
        }

        try {
            assertTrue(excludesItself(), "@ComponentScan 没有按类型排除自身, 同一个配置类会以两个名字进容器两次");
        } catch (Throwable t) {
            red.add("③ " + t.getMessage());
        }

        try {
            // 两向：那条正则要挡得住扩展模块的类，又不能顺手把本模块自己的组件也挡掉。
            // 只验前一向的话，一条恒真的正则（比如 ".*"）照样能过这一格，而它会让整个模块空掉
            String pattern = extensionExcludePattern();
            assertTrue(EXTENSION_CLASS.matches(pattern), "排除正则挡不住扩展模块的类：" + pattern);
            assertTrue(!OWN_COMPONENT_CLASS.matches(pattern), "排除正则连本模块自己的组件也挡掉了：" + pattern);
        } catch (Throwable t) {
            red.add("④ " + t.getMessage());
        }

        if (!red.isEmpty()) {
            fail(red.size() + " 问红：" + String.join("；", red));
        }
    }

    private static boolean excludesItself() {
        for (ComponentScan.Filter filter : scan().excludeFilters()) {
            if (filter.type() == FilterType.ASSIGNABLE_TYPE
                    && Arrays.asList(filter.classes()).contains(OneBotAdapterPluginAutoConfiguration.class)) {
                return true;
            }
        }
        return false;
    }

    private static String extensionExcludePattern() {
        for (ComponentScan.Filter filter : scan().excludeFilters()) {
            if (filter.type() == FilterType.REGEX && filter.pattern().length > 0) {
                return filter.pattern()[0];
            }
        }
        return fail("@ComponentScan 上没有按正则排除的那一条");
    }

    private static ComponentScan scan() {
        ComponentScan annotation = OneBotAdapterPluginAutoConfiguration.class.getAnnotation(ComponentScan.class);
        if (annotation == null) {
            return fail("自报类上没有 @ComponentScan");
        }
        return annotation;
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
        Enumeration<URL> found = OneBotAdapterSelfDeclarationTest.class.getClassLoader().getResources(IMPORTS);
        while (found.hasMoreElements()) {
            out.add(found.nextElement());
        }
        return out;
    }
}
