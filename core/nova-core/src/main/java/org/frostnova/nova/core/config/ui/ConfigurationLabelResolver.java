package org.frostnova.nova.core.config.ui;

import org.frostnova.nova.core.properties.ConfigFormat;
import org.frostnova.nova.core.properties.ConfigLabel;
import org.frostnova.nova.core.properties.ConfigSummary;
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
 * 同一趟里顺带读出设置页上另外两样「这一项怎么摆」的标注：行内短说明（{@link ConfigSummary}）
 * 与值的种类（{@link ConfigFormat}）。三样答的都是同一个问题，分三个解析器就要把字段扫三遍。
 * 「哪个键对应哪个字段」这条规则不在本类里，见 {@link ConfigurationPropertyFields}。
 */
@Slf4j
@Service
public class ConfigurationLabelResolver {
    /**
     * 一项的短说明
     *
     * @param text 行内常驻的那一句，可为空串（行内不写字）
     * @param details 整段说明是否放进问号里
     */
    public record Summary(String text, boolean details) {
    }

    private final ApplicationContext context;

    private volatile Map<String, String> labels;

    private volatile Map<String, Summary> summaries;

    private volatile Map<String, ConfigFormat.Format> formats;

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

    /**
     * 获取配置项名到短说明的映射
     * @return 映射，未标注的配置项不在其中（界面照旧显示整段说明）
     */
    public Map<String, Summary> getSummaries() {
        if (summaries == null) {
            synchronized (this) {
                if (summaries == null) {
                    Map<String, Summary> result = new HashMap<>(ExternalConfigurationFields.summaries());
                    for (Map.Entry<String, Field> entry : ConfigurationPropertyFields.scan(context).entrySet()) {
                        ConfigSummary summary = entry.getValue().getAnnotation(ConfigSummary.class);
                        if (summary != null) {
                            result.put(entry.getKey(), new Summary(summary.value(), summary.details()));
                        }
                    }
                    summaries = Map.copyOf(result);
                }
            }
        }

        return summaries;
    }

    /**
     * 获取配置项名到值的种类的映射
     * @return 映射，未标注的配置项不在其中
     */
    public Map<String, ConfigFormat.Format> getFormats() {
        if (formats == null) {
            synchronized (this) {
                if (formats == null) {
                    Map<String, ConfigFormat.Format> result = new HashMap<>();
                    for (Map.Entry<String, Field> entry : ConfigurationPropertyFields.scan(context).entrySet()) {
                        ConfigFormat format = entry.getValue().getAnnotation(ConfigFormat.class);
                        if (format != null) {
                            result.put(entry.getKey(), format.value());
                        }
                    }
                    formats = Map.copyOf(result);
                }
            }
        }

        return formats;
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
