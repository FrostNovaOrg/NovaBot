package com.starlwr.bot.core.service;

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
     * 直播平台，请优先从 {@link LivePlatform} 中获取，若不存在可使用自定义字符串
     */
    String name();
}
