package org.frostnova.nova.core.config.ui;

import org.frostnova.nova.core.properties.ConfigLabel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

/**
 * 配置项中文名解析器
 * <p>
 * 从 {@code @ConfigurationProperties} 类上反射读取 {@link ConfigLabel}，得到「配置项名 → 中文名」。
 * 「哪个键对应哪个字段」这条规则不在本类里，见 {@link ConfigurationPropertyFields}。
 */
@Slf4j
@Service
public class ConfigurationLabelResolver {
    private final ApplicationContext context;

    private volatile Map<String, String> labels;

    @Autowired
    public ConfigurationLabelResolver(ApplicationContext context) {
        this.context = context;
    }

    /**
     * 获取配置项名到中文名的映射
     * @return 映射，未标注的配置项不在其中
     */
    public Map<String, String> getLabels() {
        if (labels == null) {
            synchronized (this) {
                if (labels == null) {
                    labels = resolve();
                }
            }
        }

        return labels;
    }

    private Map<String, String> resolve() {
        Map<String, String> result = new HashMap<>();

        // 界面额外展示的框架配置项没有 @ConfigurationProperties 类可反射，名字在那边直接声明
        result.putAll(ExternalConfigurationFields.labels());

        for (Map.Entry<String, Field> entry : ConfigurationPropertyFields.scan(context).entrySet()) {
            ConfigLabel label = entry.getValue().getAnnotation(ConfigLabel.class);
            if (label != null) {
                result.put(entry.getKey(), label.value());
            }
        }

        log.info("已解析 {} 个配置项的中文名标注", result.size());
        return Map.copyOf(result);
    }
}
