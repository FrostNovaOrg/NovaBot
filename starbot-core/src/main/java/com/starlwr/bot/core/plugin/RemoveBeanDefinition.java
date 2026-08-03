package com.starlwr.bot.core.plugin;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 插件加载需移除的类定义
 */
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RemoveBeanDefinition {
    /**
     * 需移除的类定义名称
     */
    String[] name() default {};

    /**
     * 需移除的类型
     */
    Class<?>[] type() default {};
}
