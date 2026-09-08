package com.starlwr.bot.core.plugin;

import com.starlwr.bot.core.plugin.scanfixture.BothNamesScanFixture;
import com.starlwr.bot.core.plugin.scanfixture.NovaOnlyScanFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 新组件注解 {@code @NovaComponent} 的登记面
 * <p>
 * 换名后新注解必须自己立得住：只标新注解的类要被扫描登记；同时旧注解留作别名，
 * 同一个类上两名同标时只登记一次——否则从旧名迁到新名的过程中多标一版的插件
 * 会被当成两个组件，各领一份依赖、各收一次事件。
 * <p>
 * 旧名单独登记的那一路由对照格 {@code LegacyComponentScanControlTest} 量，本件不重复。
 */
class NovaComponentRegistrationTest {

    @Test
    @DisplayName("只标 @NovaComponent 的类被加载器登记")
    void novaOnlyComponentRegistered() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.scan("com.starlwr.bot.core.plugin.scanfixture");
            context.refresh();
            assertEquals(1, context.getBeanNamesForType(NovaOnlyScanFixture.class).length,
                    "只标新注解的类应恰被登记一次");
        }
    }

    @Test
    @DisplayName("同一个类同标新旧两个注解只登记一次")
    void bothNamesOnOneClassRegisteredOnce() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.scan("com.starlwr.bot.core.plugin.scanfixture");
            context.refresh();
            assertEquals(1, context.getBeanNamesForType(BothNamesScanFixture.class).length,
                    "两名同标应仍只是一个组件，登记两次会让它各领一份依赖、各收一次事件");
        }
    }
}
