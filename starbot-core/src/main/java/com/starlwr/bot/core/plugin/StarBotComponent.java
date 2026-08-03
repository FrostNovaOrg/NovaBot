package com.starlwr.bot.core.plugin;

import org.springframework.stereotype.Component;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * StarBot 插件组件注解，使用此注解的类将被 StarBot 自动扫描并注册至 Spring 容器
 */
@Component
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface StarBotComponent {
}
