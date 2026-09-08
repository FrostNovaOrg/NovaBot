package com.starlwr.bot.core.datasource;

import com.starlwr.bot.core.enums.LivePlatform;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 数据源服务实现类注解
 */
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface DataSourceServiceConfig {
    /**
     * 直播平台标识串，取自 {@link LivePlatform} 实例的 {@code id()}，由各直播平台插件登记
     */
    String name();
}
