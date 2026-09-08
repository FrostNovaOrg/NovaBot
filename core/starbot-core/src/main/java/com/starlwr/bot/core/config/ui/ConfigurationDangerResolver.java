package com.starlwr.bot.core.config.ui;

import com.starlwr.bot.core.config.ConfigDanger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 危险项解析器
 * <p>
 * 从 {@code @ConfigurationProperties} 类上反射读取 {@link ConfigDanger}，得到
 * 「配置项名 → 改到哪个值要先问一句、问什么」。「哪个键对应哪个字段」这条规则不在本类里，
 * 见 {@link ConfigurationPropertyFields}。
 * <p>
 * 插件带来的危险项也在其中：它们的配置类由插件的类加载器装进来，而这里走的是同一张字段表，
 * 因此<b>核心的界面文件里一个具体平台的名字也没有</b>——某个平台的「匿名模式」跟着那个插件
 * 一起来、一起走。
 */
@Slf4j
@Service
public class ConfigurationDangerResolver {
    private final ApplicationContext context;

    private volatile Map<String, Danger> dangers;

    @Autowired
    public ConfigurationDangerResolver(ApplicationContext context) {
        this.context = context;
    }

    /**
     * 一个危险项
     *
     * @param value 改成哪个值算危险
     * @param title 确认框标题
     * @param consequence 后果
     */
    public record Danger(String value, String title, String consequence) {
    }

    /**
     * 获取配置项名到危险声明的映射
     * @return 映射，没声明的配置项不在其中
     */
    public Map<String, Danger> getDangers() {
        if (dangers == null) {
            synchronized (this) {
                if (dangers == null) {
                    dangers = resolve();
                }
            }
        }

        return dangers;
    }

    private Map<String, Danger> resolve() {
        // 界面额外展示的框架配置项没有 @ConfigurationProperties 类可反射，声明在那边直接写
        Map<String, Danger> result = new LinkedHashMap<>(ExternalConfigurationFields.dangers());

        for (Map.Entry<String, Field> entry : ConfigurationPropertyFields.scan(context).entrySet()) {
            ConfigDanger danger = entry.getValue().getAnnotation(ConfigDanger.class);
            if (danger != null) {
                result.put(entry.getKey(), new Danger(danger.value(), danger.title(), danger.consequence()));
            }
        }

        log.info("已解析 {} 个需要先问一句的配置项", result.size());
        return Map.copyOf(result);
    }
}
