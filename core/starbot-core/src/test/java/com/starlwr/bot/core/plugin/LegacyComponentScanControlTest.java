package com.starlwr.bot.core.plugin;

import com.starlwr.bot.core.plugin.scanfixture.LegacyOnlyScanFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 对照格：只标旧注解 {@code @StarBotComponent} 的类，Spring 的组件扫描仍登记。
 * <p>
 * 组件注解换名前后这一格都要绿。它与新注解走的是同一条扫描路
 * （注解的元注解都是 Spring 的 {@code @Component}），因此它既证明扫描路活着，
 * 也证明旧注解作为别名没有被换名弄丢。
 */
class LegacyComponentScanControlTest {

    @Test
    @DisplayName("只标 @StarBotComponent 的类仍被加载器登记")
    void legacyOnlyComponentStillRegistered() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.scan("com.starlwr.bot.core.plugin.scanfixture");
            context.refresh();
            assertEquals(1, context.getBeanNamesForType(LegacyOnlyScanFixture.class).length,
                    "只标旧注解的类应恰被登记一次");
        }
    }
}
