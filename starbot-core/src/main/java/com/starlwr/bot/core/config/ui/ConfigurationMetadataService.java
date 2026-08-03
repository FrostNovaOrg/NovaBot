package com.starlwr.bot.core.config.ui;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 配置项元数据服务
 * <p>
 * 配置界面的字段表并非手工维护，而是读取 Spring Boot 配置处理器在编译期生成的
 * {@code META-INF/spring-configuration-metadata.json}。该文件由各模块的
 * {@code @ConfigurationProperties} 类及其 Javadoc 自动生成，因此界面上的字段
 * 与代码中的配置项在结构上不可能不一致：代码里加了配置项，界面自动出现；
 * 删了配置项，界面自动消失。
 * <p>
 * 由于插件由独立的类加载器加载，元数据在插件加载完毕后才齐全，因此采用懒加载。
 */
@Slf4j
@Service
public class ConfigurationMetadataService {
    /**
     * 元数据文件在类路径中的位置
     */
    private static final String METADATA_LOCATION = "classpath*:META-INF/spring-configuration-metadata.json";

    /**
     * 元数据文件在 jar 内的路径
     */
    private static final String METADATA_ENTRY = "META-INF/spring-configuration-metadata.json";

    /**
     * 插件目录，与插件加载器保持一致
     */
    private static final String PLUGIN_DIRECTORY = "plugins";

    /**
     * 界面中展示的配置项前缀，其余框架自身的配置不予展示
     */
    private static final List<String> VISIBLE_PREFIXES = List.of("starbot.");

    /**
     * 已合并的配置项，按配置项名排序
     */
    private volatile List<ConfigurationField> fields;

    /**
     * 全部配置项的名称与类型，<b>不受展示前缀限制</b>，含 Spring 等框架自身的配置项
     * <p>
     * 供保存前的类型校验使用：界面只展示 starbot 的配置项，但使用者在「配置文件」页签里能改到
     * server.port 之类的框架配置，这些同样写错就起不来，因此校验不能只覆盖 starbot 前缀。
     * 此处只保留名称与类型，不保留说明与默认值——框架的配置项数以千计，全量驻留并不划算。
     */
    private volatile Map<String, String> knownTypes;

    /**
     * 获取界面中展示的可配置项
     * @return 配置项列表
     */
    public List<ConfigurationField> getFields() {
        if (fields == null) {
            synchronized (this) {
                if (fields == null) {
                    fields = load();
                }
            }
        }

        return fields;
    }

    /**
     * 获取全部配置项的名称与类型，含框架自身的配置项
     * @return 配置项名到类型的映射
     */
    public Map<String, String> getKnownTypes() {
        // 类型表与展示字段在同一次加载中产出
        getFields();
        return knownTypes;
    }

    /**
     * 按配置项分组，分组键为去掉最后一段后的前缀
     * @return 分组后的配置项
     */
    public Map<String, List<ConfigurationField>> getGroupedFields() {
        Map<String, List<ConfigurationField>> grouped = new LinkedHashMap<>();

        for (ConfigurationField field : getFields()) {
            String name = field.name();
            int lastDot = name.lastIndexOf('.');
            String group = lastDot < 0 ? name : name.substring(0, lastDot);

            grouped.computeIfAbsent(group, key -> new ArrayList<>()).add(field);
        }

        return grouped;
    }

    /**
     * 从类路径加载并合并全部模块的配置元数据
     * @return 配置项列表
     */
    private List<ConfigurationField> load() {
        Map<String, ConfigurationField> merged = new LinkedHashMap<>();
        Map<String, String> types = new HashMap<>();

        // 核心自身的元数据来自当前类路径
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver(getClass().getClassLoader())
                    .getResources(METADATA_LOCATION);

            for (Resource resource : resources) {
                try (InputStream stream = resource.getInputStream()) {
                    parse(new String(stream.readAllBytes(), StandardCharsets.UTF_8), merged, types);
                } catch (Exception e) {
                    log.debug("解析配置元数据 {} 失败: {}", resource, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("加载核心配置元数据失败", e);
        }

        // 插件由独立的类加载器加载，其元数据不在当前类路径上，需直接从插件 jar 中读取
        loadFromPluginJars(merged, types);

        List<ConfigurationField> result = new ArrayList<>(merged.values());
        result.sort(Comparator.comparing(ConfigurationField::name));

        this.knownTypes = Map.copyOf(types);

        log.info("配置界面已加载 {} 个可配置项, 另有 {} 个配置项可参与保存前校验", result.size(), types.size());

        return result;
    }

    /**
     * 从插件目录下的 jar 中读取配置元数据
     * @param merged 合并结果
     * @param types 类型表
     */
    private void loadFromPluginJars(Map<String, ConfigurationField> merged, Map<String, String> types) {
        Path directory = Path.of(PLUGIN_DIRECTORY);
        if (!Files.isDirectory(directory)) {
            return;
        }

        try (Stream<Path> jars = Files.list(directory)) {
            jars.filter(path -> path.getFileName().toString().endsWith(".jar")).forEach(path -> {
                try (JarFile jar = new JarFile(path.toFile())) {
                    JarEntry entry = jar.getJarEntry(METADATA_ENTRY);
                    if (entry == null) {
                        return;
                    }

                    try (InputStream stream = jar.getInputStream(entry)) {
                        parse(new String(stream.readAllBytes(), StandardCharsets.UTF_8), merged, types);
                    }
                } catch (Exception e) {
                    log.debug("读取插件 {} 的配置元数据失败: {}", path.getFileName(), e.getMessage());
                }
            });
        } catch (IOException e) {
            log.debug("遍历插件目录失败: {}", e.getMessage());
        }
    }

    /**
     * 解析单个元数据文件
     * @param content 文件内容
     * @param merged 合并结果
     */
    private void parse(String content, Map<String, ConfigurationField> merged, Map<String, String> types) {
        JSONArray properties = JSON.parseObject(content).getJSONArray("properties");
        if (properties == null) {
            return;
        }

        for (int i = 0; i < properties.size(); i++) {
            JSONObject property = properties.getJSONObject(i);

            String name = property.getString("name");
            if (name == null) {
                continue;
            }

            String type = property.getString("type");

            // 类型表不做前缀过滤：使用者能在「配置文件」页签改到框架自身的配置项，
            // 那些同样写错就起不来，校验必须覆盖
            if (type != null) {
                types.putIfAbsent(name, type);
            }

            if (VISIBLE_PREFIXES.stream().noneMatch(name::startsWith)) {
                continue;
            }

            // 已废弃的配置项不在界面中展示，避免误导使用者
            if (property.containsKey("deprecated") || property.containsKey("deprecation")) {
                continue;
            }

            merged.put(name, new ConfigurationField(name, type,
                    cleanDescription(property.getString("description")),
                    property.get("defaultValue")));
        }
    }

    /**
     * 清理配置项说明
     * <p>
     * 元数据中的说明直接取自 Javadoc，含有 &lt;p&gt; 等 HTML 标签，直接展示在界面上会出现标签文本。
     * @param description 原始说明
     * @return 清理后的说明
     */
    private String cleanDescription(String description) {
        if (description == null) {
            return null;
        }

        return description
                .replaceAll("<[^>]+>", "")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
                .replaceAll("[ \\t]+", " ")
                .strip();
    }

    /**
     * 一个可配置项
     *
     * @param name 配置项名，例如 starbot.bilibili.dynamic.draw-logo
     * @param type 配置项的 Java 类型全限定名
     * @param description 配置项说明，取自 Javadoc
     * @param defaultValue 默认值
     */
    public record ConfigurationField(String name, String type, String description, Object defaultValue) {
        /**
         * 推断供界面使用的控件类型
         * @return 控件类型：boolean、integer、number、list、complex、string
         */
        public String widget() {
            if (type == null) {
                return "string";
            }

            if (type.equals("java.lang.Boolean") || type.equals("boolean")) {
                return "boolean";
            }
            if (type.equals("java.lang.Integer") || type.equals("int")
                    || type.equals("java.lang.Long") || type.equals("long")) {
                return "integer";
            }
            if (type.equals("java.lang.Double") || type.equals("double")
                    || type.equals("java.lang.Float") || type.equals("float")) {
                return "number";
            }
            if (type.startsWith("java.util.List") || type.startsWith("java.util.Set") || type.endsWith("[]")) {
                // 元素为自定义类型的列表结构复杂，界面无法用简单控件表达，
                // 标记为只读并引导使用者到配置文件页签编辑，避免出现「改了界面却不生效」的假象
                return type.contains("com.starlwr.") ? "complex" : "list";
            }

            return "string";
        }
    }
}
