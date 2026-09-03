package com.starlwr.bot.core.config.ui;

import com.starlwr.bot.core.config.ConfigLevel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

/**
 * 配置项重要程度解析器
 * <p>
 * 从 {@code @ConfigurationProperties} 类上反射读取 {@link ConfigLevel}，得到「配置项名 → 重要程度」。
 * 「哪个键对应哪个字段」这条规则不在本类里，见 {@link ConfigurationPropertyFields}。
 */
@Slf4j
@Service
public class ConfigurationLevelResolver {
    private final ApplicationContext context;

    private volatile Map<String, ConfigLevel.Level> levels;

    @Autowired
    public ConfigurationLevelResolver(ApplicationContext context) {
        this.context = context;
    }

    /**
     * 获取配置项名到重要程度的映射
     * @return 映射，未标注的配置项不在其中
     */
    public Map<String, ConfigLevel.Level> getLevels() {
        if (levels == null) {
            synchronized (this) {
                if (levels == null) {
                    levels = resolve();
                }
            }
        }

        return levels;
    }

    private Map<String, ConfigLevel.Level> resolve() {
        Map<String, ConfigLevel.Level> result = new HashMap<>();

        // 界面额外展示的框架配置项没有 @ConfigurationProperties 类可反射，等级在那边直接声明
        result.putAll(ExternalConfigurationFields.levels());

        for (Map.Entry<String, Field> entry : ConfigurationPropertyFields.scan(context).entrySet()) {
            ConfigLevel level = entry.getValue().getAnnotation(ConfigLevel.class);
            if (level != null) {
                result.put(entry.getKey(), level.value());
            }
        }

        log.info("已解析 {} 个配置项的重要程度标注", result.size());
        return Map.copyOf(result);
    }
}
