package org.frostnova.nova.core.properties;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 配置项在设置页上的中文名
 * <p>
 * 界面标题原先取键名末段（如 {@code quiet-start}），使用者认不出那是什么。
 * 标注在 {@code @ConfigurationProperties} 类的字段上，由配置界面在运行期读取；
 * 未标注的配置项回退为键名末段。
 * <p>
 * 单位不写进名字：说明里「单位：X」由 schema 抽成独立字段，界面显示在输入框旁。
 *
 * <h2>本注解为什么在核心模块里</h2>
 * 它要标到本模块这几节配置的字段上（网络、线程池、日志、直播、数据源、事件输出）。
 * 放在运行壳那一侧的话，核心就得反过来引用壳，边界尺会当场红——
 * 那道边界比「两个注解摆在一起」更值钱。形态与 {@link ConfigEffect} 相同。
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ConfigLabel {
    /**
     * 设置页上显示的中文名
     * @return 中文名
     */
    String value();
}
