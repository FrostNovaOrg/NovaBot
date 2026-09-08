package com.starlwr.bot.core.plugin;

import org.springframework.stereotype.Component;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 旧名组件注解，已由 {@link NovaComponent} 取代，下一发行版删
 * <p>
 * 旧名仍然认得：与新名一样带着 {@link Component} 元注解，扫描注册两名皆认，
 * 旧插件不改也能继续注册；新代码请用 {@link NovaComponent}。
 */
@Deprecated
@Component
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface StarBotComponent {
}
