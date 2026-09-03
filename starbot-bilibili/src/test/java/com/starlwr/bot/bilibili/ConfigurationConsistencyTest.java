package com.starlwr.bot.bilibili;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.bilibili.protocol.NovaEventMapper;
import com.starlwr.bot.core.config.ConfigEffect;
import com.starlwr.bot.core.config.ui.RuntimeConfigurationApplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 配置项一致性测试
 * <p>
 * 校验四件事，任一不满足即视为配置体系出现漂移：
 * <ol>
 *   <li>每个声明的配置项都真实生效，不存在「改了没反应」的虚空配置</li>
 *   <li>发行包中的 application.yml 模板不含已不存在的配置项</li>
 *   <li>配置项的中文说明齐备，配置界面依赖这些说明生成字段提示</li>
 *   <li>每个配置项都标了生效时机，且标成即时生效的那些真的会被写回运行中的配置</li>
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
     * 从聚合工程的 pom 里现算参与检查的模块
     * <p>
     * 原先这里是一张手写的模块名单。手写名单有一个安静的失败形态：<b>新加的模块不在名单里，
     * 于是它生成的配置项在这把尺眼里根本不存在</b>——模板里写着的那些键会被判成「代码里已不存在」，
     * 而反过来，新模块声明的虚空配置与缺说明的配置项一个都查不出来，尺照样报绿。
     * 名单从 {@code <modules>} 现算，加模块这件事就不再需要有人记得同时改这里。
     * @return 模块目录名
     */
    private List<String> modules() {
        String pom;
        try {
            pom = Files.readString(repositoryRoot().resolve("pom.xml"), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取聚合工程 pom.xml 失败", e);
        }

        List<String> modules = new ArrayList<>();
        Matcher matcher = Pattern.compile("<module>([^<]+)</module>").matcher(pom);
        while (matcher.find()) {
            modules.add(matcher.group(1).trim());
        }

        assertFalse(modules.isEmpty(), "聚合工程 pom.xml 里一个 <module> 都没读到，这把尺量的是空集");
        return modules;
    }

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
        for (String module : modules()) {
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
        for (String module : modules()) {
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
     * 由配置项名推导它在配置类里的字段名
     * <p>
     * 只有一份实现：读取方式的推导与生效时机的字段定位问的是同一件事——
     * 「这个键对应哪个字段」。两处各写一遍短横线转驼峰，迟早会在某个边角上分家，
     * 而分家之后其中一把尺量的是不存在的字段，报出来的却是「这一项没标」。
     * @param name 配置项名，例如 starbot.bilibili.dynamic.draw-logo
     * @return 字段名，例如 drawLogo
     */
    private String fieldNameOf(String name) {
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

        return camel.toString();
    }

    /**
     * 由配置项名推导其可能的读取方式
     * @param name 配置项名，例如 starbot.bilibili.dynamic.draw-logo
     * @return 判定该配置项已被使用的候选片段
     */
    private List<String> usageMarkers(String name) {
        String field = fieldNameOf(name);
        String capitalized = Character.toUpperCase(field.charAt(0)) + field.substring(1);

        // getter / isser 调用，或完整属性名出现在 @ConditionalOnProperty、@Value、logback.xml 等处
        return List.of("get" + capitalized + "()", "is" + capitalized + "()", name);
    }

    /**
     * 能看到全部模块编译产物的类加载器
     * <p>
     * 本测试所在模块的类路径上<b>没有适配器那两个插件模块</b>——它们不是本模块的依赖，
     * 也不该为了一把尺去变成依赖。但生效时机标在它们的配置类字段上，读不到就等于
     * 那 20 个配置项自动免检，而尺照样报绿。
     * <p>
     * 采用双亲优先的普通委派：核心与本模块的类仍从测试类路径加载，因此
     * {@code ConfigEffect} 只有一份，注解比对不会因为「同名不同类」而恒不相等。
     * @return 类加载器
     */
    private ClassLoader modulesClassLoader() {
        Path root = repositoryRoot();

        List<URL> urls = new ArrayList<>();
        for (String module : modules()) {
            Path classes = root.resolve(module).resolve("target/classes");
            if (!Files.isDirectory(classes)) {
                continue;
            }
            try {
                urls.add(classes.toUri().toURL());
            } catch (MalformedURLException e) {
                throw new IllegalStateException("拼不出 " + classes + " 的 URL", e);
            }
        }

        return new URLClassLoader(urls.toArray(new URL[0]), getClass().getClassLoader());
    }

    /**
     * 确认这批配置项覆盖了全部声明过配置类的模块
     * <p>
     * 元数据文件缺席时 {@link #properties()} 是直接跳过的。跳过的那个模块<b>连同它的配置项
     * 一起从分母里消失</b>，于是「一个没标的都没有」与「根本没量到」在读数上长得一模一样。
     * 这里把分母钉在源码上：源码里写了 {@code @ConfigurationProperties} 的模块，
     * 就必须在这批配置项里出现。
     * @param properties 已读到的配置项
     */
    private void assertPopulationCoversAllModules(List<JSONObject> properties) {
        assertFalse(properties.isEmpty(), "一个配置项都没读到，这把尺量的是空集");

        Set<String> covered = new LinkedHashSet<>();
        for (JSONObject property : properties) {
            String sourceType = property.getString("sourceType");
            if (sourceType != null) {
                covered.add(sourceType);
            }
        }

        Path root = repositoryRoot();
        List<String> missing = new ArrayList<>();

        for (String module : modules()) {
            Path main = root.resolve(module).resolve("src/main/java");
            if (!Files.exists(main)) {
                continue;
            }

            boolean declares;
            try (Stream<Path> files = Files.walk(main)) {
                declares = files.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".java"))
                        .anyMatch(path -> {
                            try {
                                return Files.readString(path, StandardCharsets.UTF_8)
                                        .contains("@ConfigurationProperties(");
                            } catch (IOException e) {
                                throw new IllegalStateException("读取 " + path + " 失败", e);
                            }
                        });
            } catch (IOException e) {
                throw new IllegalStateException("遍历 " + main + " 失败", e);
            }

            if (declares && covered.stream().noneMatch(type -> belongsTo(root, module, type))) {
                missing.add(module);
            }
        }

        assertTrue(missing.isEmpty(), "以下模块声明了配置类却一个配置项也没量到，"
                + "多半是它的 target/classes 还没构建，此时其余各格报的绿只覆盖了一部分配置项:\n  "
                + String.join("\n  ", missing));
    }

    /**
     * 判断某个配置类是否出自指定模块
     * @param root 仓库根目录
     * @param module 模块目录名
     * @param sourceType 配置类全限定名，内部类以 $ 分隔
     * @return 出自该模块时返回 true
     */
    private boolean belongsTo(Path root, String module, String sourceType) {
        String outer = sourceType.contains("$") ? sourceType.substring(0, sourceType.indexOf('$')) : sourceType;
        return Files.exists(root.resolve(module).resolve("src/main/java")
                .resolve(outer.replace('.', '/') + ".java"));
    }

    /**
     * 读出一个配置项标注的生效时机
     * @param property 配置项元数据
     * @param loader 能看到全部模块的类加载器
     * @return 生效时机，未标注时为 null
     * @throws ReflectiveOperationException 配置类或字段找不到时抛出
     */
    private ConfigEffect.Effect effectOf(JSONObject property, ClassLoader loader) throws ReflectiveOperationException {
        Class<?> type = Class.forName(property.getString("sourceType"), false, loader);
        ConfigEffect effect = type.getDeclaredField(fieldNameOf(property.getString("name")))
                .getAnnotation(ConfigEffect.class);
        return effect == null ? null : effect.value();
    }

    @Test
    @DisplayName("⚠️ 每个配置项都标了生效时机：标不出来的那一项，界面只能编一个说法")
    void everyPropertyDeclaresItsEffect() throws ReflectiveOperationException {
        List<JSONObject> properties = properties();
        assertPopulationCoversAllModules(properties);

        ClassLoader loader = modulesClassLoader();
        List<String> unmarked = new ArrayList<>();

        for (JSONObject property : properties) {
            if (effectOf(property, loader) == null) {
                unmarked.add(property.getString("name"));
            }
        }

        assertTrue(unmarked.isEmpty(), "以下配置项没有标注生效时机（共 " + properties.size()
                + " 项，未标 " + unmarked.size() + " 项），请在字段上补 @ConfigEffect:\n  "
                + String.join("\n  ", unmarked));
    }

    @Test
    @DisplayName("⚠️ 标成即时生效的配置项，保存时真的会被写回运行中的配置")
    void immediatePropertiesAreActuallyApplied() throws ReflectiveOperationException {
        List<JSONObject> properties = properties();
        assertPopulationCoversAllModules(properties);

        ClassLoader loader = modulesClassLoader();
        Set<String> declared = new LinkedHashSet<>();

        for (JSONObject property : properties) {
            if (effectOf(property, loader) == ConfigEffect.Effect.IMMEDIATE) {
                declared.add(property.getString("name"));
            }
        }

        // 声明与名单是同一条规则的两个读者。只对其中一边加项，界面会照着声明说「已生效」，
        // 而保存那一步压根没碰运行中的配置——改了不生效，且没有任何提示说它没生效
        Set<String> applied = RuntimeConfigurationApplier.supportedKeys();

        List<String> promisedOnly = new ArrayList<>(declared);
        promisedOnly.removeAll(applied);
        List<String> appliedOnly = new ArrayList<>(applied);
        appliedOnly.removeAll(declared);

        assertTrue(promisedOnly.isEmpty(),
                "以下配置项标成即时生效，但保存时没有任何代码把新值写回运行中的配置:\n  "
                        + String.join("\n  ", promisedOnly));
        assertTrue(appliedOnly.isEmpty(),
                "以下配置项保存时会被写回运行中的配置，却没标成即时生效，界面会白让人重启一次:\n  "
                        + String.join("\n  ", appliedOnly));
        assertFalse(declared.isEmpty(), "一个即时生效的配置项都没有，这一格此刻量的是空集");
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
    @DisplayName("⚠️ 配置模板本身能被解析：模板起不来，等于发行包开箱即坏")
    void templateIsParseable() throws IOException {
        Path template = repositoryRoot().resolve("dist/templates/application.yml");
        if (!Files.exists(template)) {
            return;
        }

        // 用启动时真正在跑的那个加载器来解析，而不是自己写一遍。
        // 上面那条按行扫键路径的检查看不见「同一个键写了两遍」这类结构性错误:
        // 模板里 starbot.core.log 出现过两次，逐行扫过去两次都是合法的键路径，
        // 而 SnakeYAML 直接抛 DuplicateKeyException，程序连启动都启动不了，
        // 只会掉进安全模式，报的还是一句 YAML 报错。新装的人第一步就撞上
        try {
            new YamlPropertySourceLoader().load("template", new FileSystemResource(template.toFile()));
        } catch (Exception e) {
            fail("发行包的配置模板无法被解析，照它安装的人会直接进安全模式: " + e.getMessage());
        }
    }

    @Test
    @DisplayName("⚠️ 文档与模板里写的协议版本号与代码里的常量一致")
    void protocolVersionIsStatedConsistently() throws IOException {
        // 升 v2 时我按「散在 11 处」逐处改，漏了配置模板——它一直写着 v1。
        // 靠数出来的清单去改，改完没法证明改全了；这条测试改成让机器去找那些地方。
        Pattern mention = Pattern.compile("事件输出协议 v(\\d+)");
        List<String> stale = new ArrayList<>();
        Path root = repositoryRoot();

        for (String relative : List.of("dist/templates/application.yml", "docs/user-guide.md", "CHANGELOG.md")) {
            Path file = root.resolve(relative);
            if (!Files.exists(file)) {
                continue;
            }
            List<String> lines = Files.readAllLines(file);
            for (int i = 0; i < lines.size(); i++) {
                Matcher matcher = mention.matcher(lines.get(i));
                while (matcher.find()) {
                    int stated = Integer.parseInt(matcher.group(1));
                    // 更新日志会提到历史版本，那是沿革不是现状；只有「未发布」之前的正文才必须是当前版本。
                    // 这里的判据从简：写着比当前版本号更小的，一律当漏改
                    if (stated != NovaEventMapper.PROTOCOL_VERSION) {
                        stale.add(relative + ":" + (i + 1) + " 写的是 v" + stated);
                    }
                }
            }
        }

        assertTrue(stale.isEmpty(), "协议版本号与 NovaEventMapper.PROTOCOL_VERSION（当前 "
                + NovaEventMapper.PROTOCOL_VERSION + "）不一致:\n  " + String.join("\n  ", stale));
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
