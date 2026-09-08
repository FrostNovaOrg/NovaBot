package com.starlwr.bot.core.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * 新组件注解 {@code @NovaComponent} 的存在格。
 * <p>
 * 组件注解换名前这一格是红的：类还不存在，「只标新注解的类被加载器登记」
 * 无从谈起。换名落位后它绿起来，证明登记所依赖的注解已经在那个包下。
 */
class NovaComponentPresenceTest {

    @Test
    @DisplayName("NovaComponent 注解已存在于 com.starlwr.bot.core.plugin")
    void novaComponentAnnotationExists() {
        assertDoesNotThrow(() -> Class.forName("com.starlwr.bot.core.plugin.NovaComponent"),
                "com.starlwr.bot.core.plugin.NovaComponent 尚不存在");
    }
}
