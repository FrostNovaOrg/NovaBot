package com.starlwr.bot.core.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 配置项重要程度
 * <p>
 * 全部配置项有数十项，但真正决定系统能否跑起来的只有寥寥几项。界面若一视同仁地平铺，
 * 使用者无从判断该改哪些——问题不在配置项太多，而在没有区分「必须懂」与「可以不管」。
 * <p>
 * 标注在 {@code @ConfigurationProperties} 类的字段上，由配置界面在运行期读取。
 * 未标注的配置项按 {@link Level#ADVANCED} 处理：新增配置项默认收进高级区，
 * 需要展示到前台时再显式标注，避免常用区随时间推移不断膨胀。
 * <p>
 * <b>已知限制</b>：列表元素内部的字段（如 {@code starbot.adapter.onebot.senders} 中的各项）
 * 不会出现在「设置」页的表单里——这类结构无法用简单控件表达，界面统一标记为只读并引导至
 * 「高级：直接编辑配置文件」。标注在此类字段上的重要程度不生效；其中最关键的机器人连接参数
 * 另有专门入口（「机器人」页与首次配置向导），走 {@code /api/setup/bot}。
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ConfigLevel {
    /**
     * 重要程度
     * @return 重要程度
     */
    Level value();

    /**
     * 重要程度枚举
     */
    enum Level {
        /**
         * 必填：不配置系统就无法工作
         */
        BASIC,

        /**
         * 常用：使用者会主动想调整的
         */
        COMMON,

        /**
         * 高级：性能调优、调试开关等，通常无需改动
         */
        ADVANCED
    }
}
