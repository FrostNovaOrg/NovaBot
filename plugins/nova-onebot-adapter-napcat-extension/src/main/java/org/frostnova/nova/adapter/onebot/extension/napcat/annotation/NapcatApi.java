package org.frostnova.nova.adapter.onebot.extension.napcat.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标出一个方法对应 NapCat 的哪一支扩展接口
 * <p>
 * 标了它的方法不需要方法体：接口清单就是实现，由动态代理照着这里的地址去打请求
 * （见 {@code NapcatHttpAdapterProxy}）。
 * <p>
 * <b>{@link RetentionPolicy#RUNTIME} 是这套写法的地基</b>，不是随手挑的：
 * 代理在运行期靠读这个注解认路，退回默认的 {@code CLASS} 之后每一支接口都会变成
 * 「不支持的方法」——编译照过，要到真去打这支接口的那一刻才炸。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface NapcatApi {
    /**
     * 接口名称，只用于让读代码的人认得出这支接口是干什么的，不参与请求
     */
    String name();

    /**
     * 接口地址，接在推送平台的主机与端口之后，例如 {@code /set_group_todo}
     */
    String url();
}
