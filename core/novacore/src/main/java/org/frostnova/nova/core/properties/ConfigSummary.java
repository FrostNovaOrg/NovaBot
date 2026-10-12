package org.frostnova.nova.core.properties;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 配置项在设置页上常驻的那一句短说明
 * <p>
 * 设置页每一项底下原先摆着整段说明（取自字段的 Javadoc），一百多项摊开，页面很长，
 * 真正要紧的那半句埋在原理和例子中间。标了本注解的项，行内只留这一句：
 * <b>改之前就得知道的后果或填写约束</b>（「0 表示关闭」「关闭后需要重新扫码」这类）；
 * 整段说明挪进旁边的问号里，要看再点开。
 * <p>
 * 没标的项照旧在行内显示整段说明。插件的配置项不标也不会少一个字。
 * <p>
 * 写之前先对到代码的实际行为：这一句常驻在眼前，说错了比不说更坏。
 * <p>
 * 与 {@link ConfigLabel} 同形、同处，理由也相同：它要标到本模块那几节配置的字段上。
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ConfigSummary {
    /**
     * 行内常驻的短说明。留空表示行内不再写字：名字、单位与控件已经说清楚了
     * @return 短说明
     */
    String value();

    /**
     * 整段说明是否放进问号里。名字与短说明已经把话说完的项写 false，问号也不出
     * @return 是否给问号
     */
    boolean details() default true;
}
