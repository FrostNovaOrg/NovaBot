package org.frostnova.nova.report;

import org.frostnova.nova.report.factory.NovaCommonPainterFactory;
import org.frostnova.nova.report.painter.BilibiliLiveReportPainter;
import org.frostnova.nova.report.util.FontUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.core.type.filter.AssignableTypeFilter;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 绘图器工厂与字体工具落在本插件自报类的扫描射程里
 *
 * <h2>这一格补的是什么洞</h2>
 * 这两个类是带组件注解的 Bean，从核心搬进本插件时换了包名。<b>搬进一个扫不到的包，
 * 编译与全部单元测试都不会有任何反应</b>——单元测试里它们各自是 {@code new} 出来的，
 * 谁也不经过容器；要到起动时按类型注入的地方拿不到 Bean，上下文装不起来，进程才退出。
 *
 * <h2>为什么照注解上的配置扫, 而不是照默认射程扫</h2>
 * 组件扫描的射程写在自报类的 {@code @ComponentScan} 上：没写基包时是自报类所在的包及其子包，
 * 写了就以写的为准，还可以挂排除过滤器。本格从注解上把基包与排除过滤器<b>原样取下来</b>再扫，
 * 为的是让极性跟着注解走——有人给注解加一条把这两个包排除出去的规则时，本格当场红。
 * 若图省事按「自报类所在包」写死一个射程，那种改法本格看不见，它会一直绿着。
 *
 * <p>三问各自捕获、末尾汇总；问①是两个阳性锚：扫描当真扫出了东西，
 * 且注解上那条排除规则当真被照搬了进来（自报类自己不在候选里）。
 *
 * @see ReportPluginAutoConfiguration
 */
@DisplayName("画图的组件在插件的扫描射程内")
class PaintingBeansInScanRangeTest {
    /** 射程写在这个类的注解上 */
    private static final Class<?> DECLARER = ReportPluginAutoConfiguration.class;

    /** 这两个类是从核心搬过来的带注解组件，搬完必须仍在射程内 */
    private static final List<Class<?>> MOVED_COMPONENTS =
            List.of(NovaCommonPainterFactory.class, FontUtil.class);

    @Test
    @DisplayName("按自报类注解上的基包与排除规则扫一遍, 绘图器工厂与字体工具都在候选里")
    void movedPaintingComponentsStayInScanRange() {
        List<String> unresolved = new ArrayList<>();
        Set<String> candidates = scanTheWaySpringWould();

        // 问①：两个阳性锚——扫到了东西，且排除规则照搬生效
        try {
            assertTrue(candidates.contains(BilibiliLiveReportPainter.class.getName()),
                    "阳性锚: 本来就在射程里的报告画手都没扫到, 说明本格什么也没扫着; 扫出来的是: " + candidates);
            assertTrue(!candidates.contains(DECLARER.getName()),
                    "阳性锚: 自报类自己该被注解上的排除规则挡在候选之外。没挡住说明本格没照搬那条规则,"
                            + " 那么日后有人加一条排除画图的规则时, 本格也一样看不见");
        } catch (AssertionError e) {
            unresolved.add("问① " + e.getMessage());
        }

        // 问②③：搬过来的两个组件各自在候选里
        for (Class<?> moved : MOVED_COMPONENTS) {
            try {
                assertTrue(candidates.contains(moved.getName()),
                        moved.getSimpleName() + " 不在组件扫描的候选里: 它所在的包 " + moved.getPackageName()
                                + " 落在射程外, 起动时按类型注入的地方会拿不到它");
            } catch (AssertionError e) {
                unresolved.add("问 " + moved.getSimpleName() + " " + e.getMessage());
            }
        }

        assertTrue(unresolved.isEmpty(),
                () -> unresolved.size() + " 问未销: " + String.join("; ", unresolved));
    }

    /** 照自报类注解上写的基包与排除规则扫一遍，回候选组件的全类名 */
    private static Set<String> scanTheWaySpringWould() {
        ComponentScan annotation = DECLARER.getAnnotation(ComponentScan.class);
        assertNotNull(annotation, "自报类上没有 @ComponentScan, 本格量的就不是真射程了");

        ClassPathScanningCandidateComponentProvider provider =
                new ClassPathScanningCandidateComponentProvider(true);
        for (ComponentScan.Filter filter : annotation.excludeFilters()) {
            if (filter.type() != FilterType.ASSIGNABLE_TYPE) {
                // 本格只照搬得了按类型排除这一种。注解上换了别的过滤器而本格照旧扫，
                // 量出来的射程就比真射程宽——宁可在这里停下，也不要给一个偏宽的绿
                fail("注解上出现了本格还不认得的过滤器类型 " + filter.type() + ", 本格须跟着补");
            }
            for (Class<?> excluded : filter.classes()) {
                provider.addExcludeFilter(new AssignableTypeFilter(excluded));
            }
        }

        Set<String> candidates = new LinkedHashSet<>();
        for (String basePackage : basePackages(annotation)) {
            for (BeanDefinition definition : provider.findCandidateComponents(basePackage)) {
                candidates.add(definition.getBeanClassName());
            }
        }
        return candidates;
    }

    /** 注解没写基包时，射程是自报类所在的包及其子包——这正是本插件现在依赖的那条默认 */
    private static List<String> basePackages(ComponentScan annotation) {
        List<String> declared = new ArrayList<>(List.of(annotation.basePackages()));
        declared.addAll(List.of(annotation.value()));
        for (Class<?> marker : annotation.basePackageClasses()) {
            declared.add(marker.getPackageName());
        }
        return declared.isEmpty() ? List.of(DECLARER.getPackageName()) : declared;
    }
}
