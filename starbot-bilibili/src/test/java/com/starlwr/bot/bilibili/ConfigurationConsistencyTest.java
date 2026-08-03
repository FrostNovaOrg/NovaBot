package com.starlwr.bot.bilibili;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配置项一致性测试
 * <p>
 * 校验三件事，任一不满足即视为配置体系出现漂移：
 * <ol>
 *   <li>每个声明的配置项都真实生效，不存在「改了没反应」的虚空配置</li>
 *   <li>发行包中的 application.yml 模板不含已不存在的配置项</li>
 *   <li>配置项的中文说明齐备，配置界面依赖这些说明生成字段提示</li>
 * </ol>
 * <p>
 * 本测试放在反应堆中最后构建的模块，以便读取到全部模块编译期生成的配置元数据。
 */
@DisplayName("配置项一致性")
class ConfigurationConsistencyTest {
    /**
     * 各模块生成的配置元数据相对仓库根目录的路径
     */
    private static final String METADATA_PATH = "target/classes/META-INF/spring-configuration-metadata.json";

    /**
     * 参与检查的模块
     */
    private static final List<String> MODULES = List.of(
            "starbot-core",
            "starbot-onebot-adapter",
            "starbot-onebot-adapter-napcat-extension",
            "starbot-bilibili"
    );

    /**
     * 定位仓库根目录
     * <p>
     * 测试既可能由 Maven 在模块目录下执行，也可能由 IDE 在仓库根目录下执行。
     * @return 仓库根目录
     */
    private Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();

        while (current != null) {
            if (Files.exists(current.resolve("build.sh")) && Files.exists(current.resolve("pom.xml"))) {
                return current;
            }
            current = current.getParent();
        }

        throw new IllegalStateException("未能定位仓库根目录");
    }

    /**
     * 读取全部模块已生成的配置元数据
     * @return 配置项列表
     */
    private List<JSONObject> properties() {
        Path root = repositoryRoot();

        List<JSONObject> properties = new ArrayList<>();
        for (String module : MODULES) {
            Path metadata = root.resolve(module).resolve(METADATA_PATH);
            if (!Files.exists(metadata)) {
                continue;
            }

            try {
                JSONArray array = JSON.parseObject(Files.readString(metadata, StandardCharsets.UTF_8)).getJSONArray("properties");
                if (array != null) {
                    for (int i = 0; i < array.size(); i++) {
                        properties.add(array.getJSONObject(i));
                    }
                }
            } catch (IOException e) {
                throw new IllegalStateException("读取 " + metadata + " 失败", e);
            }
        }

        return properties;
    }

    /**
     * 读取全部模块的主源码与资源文件内容
     * @return 拼接后的全部内容
     */
    private String sourcesAndResources() {
        Path root = repositoryRoot();

        StringBuilder content = new StringBuilder();
        for (String module : MODULES) {
            Path main = root.resolve(module).resolve("src/main");
            if (!Files.exists(main)) {
                continue;
            }

            try (Stream<Path> files = Files.walk(main)) {
                files.filter(Files::isRegularFile)
                        .filter(path -> {
                            String name = path.getFileName().toString();
                            return name.endsWith(".java") || name.endsWith(".xml")
                                    || name.endsWith(".yml") || name.endsWith(".properties");
                        })
                        .forEach(path -> {
                            try {
                                content.append(Files.readString(path, StandardCharsets.UTF_8)).append('\n');
                            } catch (IOException e) {
                                throw new IllegalStateException("读取 " + path + " 失败", e);
                            }
                        });
            } catch (IOException e) {
                throw new IllegalStateException("遍历 " + main + " 失败", e);
            }
        }

        return content.toString();
    }

    /**
     * 由配置项名推导其可能的读取方式
     * @param name 配置项名，例如 starbot.bilibili.dynamic.draw-logo
     * @return 判定该配置项已被使用的候选片段
     */
    private List<String> usageMarkers(String name) {
        String leaf = name.substring(name.lastIndexOf('.') + 1);

        StringBuilder camel = new StringBuilder();
        boolean upper = false;
        for (char c : leaf.toCharArray()) {
            if (c == '-') {
                upper = true;
            } else {
                camel.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }

        String capitalized = Character.toUpperCase(camel.charAt(0)) + camel.substring(1);

        // getter / isser 调用，或完整属性名出现在 @ConditionalOnProperty、@Value、logback.xml 等处
        return List.of("get" + capitalized + "()", "is" + capitalized + "()", name);
    }

    @Test
    @DisplayName("不存在声明了却从未生效的配置项")
    void noDeadProperties() {
        String haystack = sourcesAndResources();

        List<String> dead = new ArrayList<>();
        for (JSONObject property : properties()) {
            String name = property.getString("name");
            if (usageMarkers(name).stream().noneMatch(haystack::contains)) {
                dead.add(name);
            }
        }

        assertTrue(dead.isEmpty(),
                "以下配置项在代码中从未被读取，属于改了不生效的虚空配置，请接线或删除:\n  " + String.join("\n  ", dead));
    }

    @Test
    @DisplayName("配置模板中不含已不存在的配置项")
    void templateHasNoUnknownProperties() throws IOException {
        Path template = repositoryRoot().resolve("dist/templates/application.yml");
        if (!Files.exists(template)) {
            return;
        }

        Set<String> known = new LinkedHashSet<>();
        for (JSONObject property : properties()) {
            known.add(property.getString("name"));
        }

        // 逐行解析模板中的键路径，仅检查 starbot 前缀下的叶子节点
        List<String> unknown = new ArrayList<>();
        List<String> stack = new ArrayList<>();

        // 列表项内部的键属于元素对象而非配置树的一级路径，需整段跳过。
        // 记录列表起始处的缩进，缩进大于它的行都在列表内部。
        int listIndent = -1;

        for (String raw : Files.readAllLines(template, StandardCharsets.UTF_8)) {
            String line = raw.split("#")[0];
            if (line.isBlank()) {
                continue;
            }

            int indent = line.length() - line.stripLeading().length();
            String stripped = line.strip();

            if (listIndent >= 0) {
                if (indent > listIndent) {
                    continue;
                }
                listIndent = -1;
            }

            if (stripped.startsWith("-")) {
                listIndent = indent;
                continue;
            }

            if (!stripped.contains(":")) {
                continue;
            }

            String key = stripped.substring(0, stripped.indexOf(':')).strip();
            String value = stripped.substring(stripped.indexOf(':') + 1).strip();

            int depth = indent / 2;
            while (stack.size() > depth) {
                stack.remove(stack.size() - 1);
            }
            stack.add(key);

            if (value.isEmpty()) {
                continue;
            }

            String path = String.join(".", stack);
            if (path.startsWith("starbot.") && !known.contains(path)) {
                unknown.add(path);
            }
        }

        assertTrue(unknown.isEmpty(),
                "配置模板中存在代码里已不存在的配置项，请更新模板:\n  " + String.join("\n  ", unknown));
    }

    @Test
    @DisplayName("每个配置项都有中文说明")
    void everyPropertyIsDocumented() {
        List<String> undocumented = new ArrayList<>();

        for (JSONObject property : properties()) {
            String description = property.getString("description");
            if (description == null || description.isBlank()) {
                undocumented.add(property.getString("name"));
            }
        }

        assertTrue(undocumented.isEmpty(),
                "以下配置项缺少 Javadoc 说明，配置界面将无法显示字段提示:\n  " + String.join("\n  ", undocumented));
    }
}
