package org.frostnova.nova.core.plugin;

import org.frostnova.nova.core.plugin.scanfixture.NovaOnlyScanFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 组件注解 {@code @NovaComponent} 的登记面
 * <p>
 * 只标新注解的类要被扫描登记。旧名别名已随包名迁移删除。
 */
class NovaComponentRegistrationTest {

    @Test
    @DisplayName("只标 @NovaComponent 的类被加载器登记")
    void novaOnlyComponentRegistered() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.scan("org.frostnova.nova.core.plugin.scanfixture");
            context.refresh();
            assertEquals(1, context.getBeanNamesForType(NovaOnlyScanFixture.class).length,
                    "只标新注解的类应恰被登记一次");
        }
    }
}
