package org.frostnova.nova.core.properties;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 字符串配置项里装的是哪一种值
 * <p>
 * 类型只说得出「这是一串字」。可同样是一串字，静音时段的「08:00」是一天里的时刻，
 * 凭据文件的位置是一条路径——前者该给时间选择框，免得手敲「HH:mm」敲错一位就整段失效；
 * 后者该用等宽字显示，免得 {@code l} 与 {@code 1}、{@code O} 与 {@code 0} 认不出来。
 * <p>
 * 标注在 {@code @ConfigurationProperties} 类的字段上，由配置界面在运行期读取。
 * 界面上没有另一张写着键名的表：插件的项标上就照样认。
 * <p>
 * 只管怎么显示与怎么输入，<b>不管机密与否</b>：那由服务端另外判（见运行壳侧的 {@code SensitiveFields}）。
 * 与 {@link ConfigLabel} 同形、同处。
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ConfigFormat {
    /**
     * 值的种类
     * @return 种类
     */
    Format value();

    /**
     * 值的种类
     */
    enum Format {
        /**
         * 一天里的时刻，按 {@code HH:mm} 存，空串表示不设
         */
        TIME_OF_DAY,

        /**
         * 文件或目录的位置
         */
        PATH
    }
}
