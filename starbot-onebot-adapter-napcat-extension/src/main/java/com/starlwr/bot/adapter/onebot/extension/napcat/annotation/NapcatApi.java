package com.starlwr.bot.adapter.onebot.extension.napcat.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Napcat 扩展接口注解
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface NapcatApi {
    /**
     * 接口名称
     */
    String name();

    /**
     * 接口地址
     */
    String url();
}
