package com.starlwr.bot.adapter.onebot.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * OneBot 接口注解
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface OneBotApi {
    /**
     * 接口名称
     */
    String name();

    /**
     * 接口地址
     */
    String url();

    /**
     * 是否把本次调用的往返耗时记入健康探针的耗时窗
     * <p>
     * 耗时窗量的是「推送变慢」：样本该来自推送类调用。名单类批量拉取（群列表／好友列表／
     * 群成员信息）又多又慢，记进来会把 20 格的耗时窗整轮冲掉，探针从此量的是名单拉取
     * 而不是推送，因此这三处标 {@code false}；其余（发消息、探活用的查信息）照记。
     */
    boolean latency() default true;
}
