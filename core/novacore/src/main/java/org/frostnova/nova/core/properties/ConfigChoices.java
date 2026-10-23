package org.frostnova.nova.core.properties;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 配置项只认哪几种写法
 * <p>
 * 类型只说得出「这是一串字」。可同样是一串字，扫码登录方式只认 {@code tv} 与 {@code web}
 * 两种：界面上给自由文本框的话，手一滑填出别的字，保存后那条路当场走不通，还得回配置文件里
 * 找是哪一项填岔了。标出候选项，界面上就摆两项选择；存进去的仍是原来那两个字，
 * 不换成标签、序号或别的编码。
 * <p>
 * 标注在 {@code @ConfigurationProperties} 类的字段上，由配置界面在运行期读取。
 * 界面上没有另一张写着键名的表：插件的项标上就照样认。
 * <p>
 * 与 {@link ConfigFormat} 同形、同处。只管界面上摆什么控件，值本身怎么校验仍在服务端。
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ConfigChoices {
    /**
     * 可选的写法，顺序即界面上的顺序
     * @return 候选写法
     */
    String[] value();

    /**
     * 与候选值同序的显示名；未提供的显示名沿用候选值。
     * @return 界面显示名
     */
    String[] labels() default {};
}
