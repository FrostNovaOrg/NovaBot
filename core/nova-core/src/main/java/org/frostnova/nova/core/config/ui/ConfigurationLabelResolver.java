package org.frostnova.nova.core.config.ui;

import org.frostnova.nova.core.properties.ConfigChoices;
import org.frostnova.nova.core.properties.ConfigFormat;
import org.frostnova.nova.core.properties.ConfigLabel;
import org.frostnova.nova.core.properties.ConfigSummary;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 配置项中文名解析器
 * <p>
 * 从 {@code @ConfigurationProperties} 类上反射读取 {@link ConfigLabel}，得到「配置项名 → 中文名」。
 * 同一趟里顺带读出设置页上另外几样「这一项怎么摆」的标注：行内短说明（{@link ConfigSummary}）、
 * 值的种类（{@link ConfigFormat}）、只认哪几种写法（{@link ConfigChoices}）。
 * 几样答的都是同一个问题，分几个解析器就要把字段扫几遍。
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

    private volatile Map<String, List<String>> choices;

    private volatile Map<String, Integer> orders;

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

    /**
     * 获取配置项名到候选写法的映射
     * <p>
     * 只认几种写法的项（扫码登录方式这类）在界面上摆两项选择而不是自由文本框；
     * 存进去的仍是原来那几个字。
     * @return 映射，未标注的配置项不在其中
     */
    public Map<String, List<String>> getChoices() {
        if (choices == null) {
            synchronized (this) {
                if (choices == null) {
                    Map<String, List<String>> result = new HashMap<>();
                    for (Map.Entry<String, Field> entry : ConfigurationPropertyFields.scan(context).entrySet()) {
                        ConfigChoices marked = entry.getValue().getAnnotation(ConfigChoices.class);
                        if (marked != null) {
                            result.put(entry.getKey(), List.of(marked.value()));
                        }
                    }
                    choices = Map.copyOf(result);
                }
            }
        }

        return choices;
    }

    /**
     * 获取配置项名到声明次序的映射
     * <p>
     * 次序即代码里的声明顺序：{@code @ConfigurationProperties} 类里字段怎么写就怎么排
     * （嵌套类在声明处整段展开），界面额外展示的框架配置项按 {@link ExternalConfigurationFields}
     * 那张表的先后（地址在前、端口在后，与人填写的顺序一致）。
     * 界面上的先后用它排，而不是按配置键的字母序——字母序会把「静音时段 · 结束」排到「开始」前面。
     * @return 映射，值从 0 起
     */
    public Map<String, Integer> getOrders() {
        if (orders == null) {
            synchronized (this) {
                if (orders == null) {
                    Map<String, Integer> result = new LinkedHashMap<>();
                    for (String name : ExternalConfigurationFields.names()) {
                        result.putIfAbsent(name, result.size());
                    }
                    for (String name : ConfigurationPropertyFields.scan(context).keySet()) {
                        result.putIfAbsent(name, result.size());
                    }
                    orders = Map.copyOf(result);
                }
            }
        }

        return orders;
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
