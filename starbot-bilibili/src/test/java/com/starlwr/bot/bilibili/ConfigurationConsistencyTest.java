package com.starlwr.bot.bilibili;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.bilibili.protocol.NovaEventMapper;
import com.starlwr.bot.core.config.ConfigDanger;
import com.starlwr.bot.core.config.ConfigEffect;
import com.starlwr.bot.core.config.ui.ConfigurationGroups;
import com.starlwr.bot.core.config.ui.ConfigurationMetadataService;
import com.starlwr.bot.core.config.ui.ExternalConfigurationFields;
import com.starlwr.bot.core.config.ui.RuntimeConfigurationApplier;
import com.starlwr.bot.core.config.ui.auth.ConfigUiAuthService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.FileSystemResource;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
     * 生成件写到这里再读回来
     */
    @TempDir
    Path dir;

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
        // 界面额外展示的那几项没有字段可标注，声明写在另一张表里。分母漏掉它们的话，
        // 其中任何一项接进即时生效通道都会被判成「会被写回却没标」——标了，只是这把尺看不见
        declared.addAll(ExternalConfigurationFields.immediateNames());
        // 口令与二次验证当场生效走专用口，不经通用保存，故不进 Applier 名单
        declared.removeIf(ConfigUiAuthService::isDedicatedAuthKey);

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

    /**
     * 设置页上摆着的全部配置项名
     * <p>
     * 分母是两批之和：{@code starbot} 命名空间下未废弃的那些，加上界面额外展示的框架配置项。
     * 只数前一批的话，后一批（累计存储、发件服务、服务端口与监听地址）就自动免检——
     * 而那几项恰恰是没有配置类可反射、最容易被漏掉的。
     * @return 配置项名
     */
    private Set<String> displayedProperties() {
        List<JSONObject> properties = properties();
        assertPopulationCoversAllModules(properties);

        Set<String> names = new LinkedHashSet<>();
        for (JSONObject property : properties) {
            String name = property.getString("name");
            if (name == null || !name.startsWith("starbot.")) {
                continue;
            }
            if (property.containsKey("deprecated") || property.containsKey("deprecation")) {
                continue;
            }
            names.add(name);
        }

        names.addAll(ExternalConfigurationFields.names());
        return names;
    }

    @Test
    @DisplayName("⚠️ 每个配置项都归了设置页的某一组：没有组的那一项，界面上没有它的位置")
    void everyPropertyBelongsToOneGroup() {
        Set<String> names = displayedProperties();

        List<String> orphans = new ArrayList<>();
        for (String name : names) {
            if (ConfigurationGroups.groupOf(name) == null) {
                orphans.add(name);
            }
        }

        assertTrue(orphans.isEmpty(), "以下配置项在设置页的分组表里一条前缀也匹配不上（共 " + names.size()
                + " 项，未归组 " + orphans.size() + " 项），请在 ConfigurationGroups 里补前缀:\n  "
                + String.join("\n  ", orphans));
    }

    @Test
    @DisplayName("分组表里没有指不到任何配置项的死前缀")
    void everyGroupPrefixStillMatchesSomething() {
        Set<String> names = displayedProperties();

        List<String> dead = new ArrayList<>();
        for (String prefix : ConfigurationGroups.prefixes()) {
            boolean used = names.stream().anyMatch(name -> name.equals(prefix) || name.startsWith(prefix + "."));
            if (!used) {
                dead.add(prefix);
            }
        }

        assertTrue(dead.isEmpty(), "以下分组前缀指不到任何现存配置项，多半是键改名或删掉后留下的:\n  "
                + String.join("\n  ", dead));
    }

    @Test
    @DisplayName("⚠️ 危险项的声明都指得到界面上真有的配置项，且四个都还在")
    void dangerDeclarationsPointAtRealProperties() throws ReflectiveOperationException {
        Set<String> names = displayedProperties();
        ClassLoader loader = modulesClassLoader();

        // 标在字段上的那些：走的是与生效时机同一张字段表，因此插件带来的也在其中
        Set<String> declared = new LinkedHashSet<>();
        for (JSONObject property : properties()) {
            String name = property.getString("name");
            if (name == null || !name.startsWith("starbot.")) {
                continue;
            }

            Class<?> type = Class.forName(property.getString("sourceType"), false, loader);
            if (type.getDeclaredField(fieldNameOf(name)).getAnnotation(ConfigDanger.class) != null) {
                declared.add(name);
            }
        }
        // 没有字段可标的那几项，声明写在另一张表里，因此可能落单
        declared.addAll(ExternalConfigurationFields.dangerousNames());

        List<String> orphans = new ArrayList<>(declared);
        orphans.removeAll(names);
        assertTrue(orphans.isEmpty(), "以下配置项声明了「改到某档要先问一句」，但它根本不在界面上——"
                + "一条指向不存在之物的声明，界面上看不出任何异常:\n  " + String.join("\n  ", orphans));

        // 四个围栏是定稿定下的。少一个就是某一处的确认框悄悄没了，而界面照常好用
        assertEquals(Set.of(
                        "server.address",
                        "starbot.core.exec.enabled",
                        "starbot.core.config-ui.auth.operator-token",
                        "starbot.bilibili.account.anonymous"),
                declared,
                "危险项与定稿定下的那四个对不上。加围栏是好事，但要连同这一行一起改，"
                        + "撤围栏则须先说清为什么");
    }

    @Test
    @DisplayName("八个组每组都有配置项，没有点开是空的组")
    void noEmptyGroup() {
        Set<String> names = displayedProperties();

        List<String> empty = new ArrayList<>();
        for (ConfigurationGroups.Group group : ConfigurationGroups.all()) {
            if (names.stream().noneMatch(name -> group.equals(ConfigurationGroups.groupOf(name)))) {
                empty.add(group.id() + "（" + group.title() + "）");
            }
        }

        assertTrue(empty.isEmpty(), "以下组一个配置项都没有，界面上会立着一个点开什么都没有的标题:\n  "
                + String.join("\n  ", empty));
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

    /**
     * 随发行包交付的那份配置示例
     * <p>
     * 🔴 <b>找不到就红，不是找不到就跳过。</b>这三条判据原先都写着「文件不在就 return」，
     * 于是 5.1 把它从 {@code application.yml} 改名成 {@code application.example.yml} 的那一刻，
     * 三条一起变成空跑——而空跑的绿与真的量过一遍的绿，在测试报告上长得一样。
     * @return 模板路径
     */
    private Path releaseTemplate() {
        Path template = repositoryRoot().resolve("dist/templates/application.example.yml");
        assertTrue(Files.exists(template), "找不到发行包的配置示例 " + template + " —— 改过名就把这里一起改");
        return template;
    }

    @Test
    @DisplayName("配置模板中不含已不存在的配置项")
    void templateHasNoUnknownProperties() throws IOException {
        Path template = releaseTemplate();

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
            // 限 2 段：整行只有一个 "#" 时，不限段数的 split 会把两侧的空串都丢掉、
            // 返回长度为 0 的数组，取 [0] 当场数组越界 —— 判据不是红，是崩
            String line = raw.split("#", 2)[0];
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
        Path template = releaseTemplate();

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

    /**
     * 首次保存写出的那份 application.yml，键集必须涵盖全部模块——含插件键
     *
     * <h2>为什么放在插件侧的模块里量</h2>
     * 核心侧 {@code ConfigurationTemplateTest} 的键集判据只跑在核心模块的类路径上，
     * 而插件键（{@code starbot.bilibili.*}、{@code starbot.adapter.onebot.*}）不在那条
     * 类路径上——<b>那几格量不到它们，生成件真缺了插件键也照样绿</b>。本类站在反应堆里
     * 最后构建的模块，读得到全部模块的编译期元数据，正好把分母补全。生产码本身不缺：
     * {@link ConfigurationMetadataService} 在运行时既扫类路径、也读 plugins/ 目录下的插件 jar。
     *
     * <h2>两侧各走什么路</h2>
     * 期望一侧用本类现成的多模块聚合（{@link #displayedProperties()}），不另抄清单；
     * 实际一侧把插件 jar 摆进 plugins/ 目录，让生产加载器把「类路径＋插件」两条来路走齐，
     * 再走生产渲染器写出（{@code ConfigurationTemplate} 是 config.ui 包的包私有件，
     * 不为这一格把它放宽成 public，从外头借一口）。比对只量「期望的每一键都有它的一行」，
     * 不量「一行不多」：期望侧读各模块的源码元数据、实际侧读类路径与 jar，来路不同，
     * 实际侧带出旧构建残留的键不构成使用者的损失——判红只该发生在「设置页上有、文件里没有」。
     */
    @Test
    @DisplayName("⚠️ 生成件键集 —— 插件键与核心键同进首次保存写出的文件, 期望的每一键都有它的一行")
    void generatedFileCarriesEveryModulesKeys() throws Exception {
        Set<String> expected = displayedProperties();
        assertTrue(expected.stream().anyMatch(name -> name.startsWith("starbot.bilibili.")),
                "分母自证：哔哩哔哩插件键一个都不在期望集里，这一格会量在空集上");
        assertTrue(expected.stream().anyMatch(name -> name.startsWith("starbot.adapter.onebot.")),
                "分母自证：适配器插件键一个都不在期望集里，这一格量不到插件来路");

        // plugins/ 是运行时元数据的第二条来路（适配器与扩展的键只能从这条路来）。
        // 目录建在进程工作目录下，与 ConfigurationMetadataService 的 PLUGIN_DIRECTORY 同址；
        // 只有这一格自己建的目录才在收尾时删，别的进程放进去的件不动
        Path plugins = Path.of("plugins");
        boolean owned = Files.notExists(plugins);
        if (owned) {
            Files.createDirectory(plugins);
        }

        List<Path> installed = new ArrayList<>();
        try {
            Path root = repositoryRoot();
            for (String module : pluginModules()) {
                Path target = root.resolve(module).resolve("target");
                if (!Files.isDirectory(target)) {
                    continue;
                }
                try (Stream<Path> jars = Files.list(target)) {
                    for (Path jar : jars.filter(path -> path.getFileName().toString().endsWith(".jar")).toList()) {
                        Path copy = plugins.resolve(jar.getFileName());
                        Files.copy(jar, copy);
                        installed.add(copy);
                    }
                }
            }
            assertFalse(installed.isEmpty(),
                    "一个插件 jar 都没摆进 plugins/ —— 单模块跑这一格前先整盘构建一次");

            // 实际一侧全程走生产件：加载器的两条来路，加渲染器本身
            List<ConfigurationMetadataService.ConfigurationField> fields =
                    new ConfigurationMetadataService().getFields();
            Method render = Class.forName("com.starlwr.bot.core.config.ui.ConfigurationTemplate")
                    .getDeclaredMethod("render", List.class, Map.class);
            render.setAccessible(true);
            String yaml = (String) render.invoke(null, fields, Map.of());

            Path file = dir.resolve("application.yml");
            Files.writeString(file, yaml, StandardCharsets.UTF_8);

            Set<String> actual = new TreeSet<>(load(file).keySet());
            actual.addAll(commentedKeys(file, expected));

            List<String> missing = new ArrayList<>(expected);
            missing.removeAll(actual);
            assertTrue(missing.isEmpty(),
                    "首次保存写出的文件缺了以下配置项（期望 " + expected.size() + " 项，缺 "
                            + missing.size() + " 项）。设置页上有、文件里没有，使用者照着文件改不到那一项，"
                            + "程序照常启动、什么也不报:\n  " + String.join("\n  ", missing));
        } finally {
            for (Path copy : installed) {
                Files.deleteIfExists(copy);
            }
            if (owned) {
                Files.deleteIfExists(plugins);
            }
        }
    }

    /**
     * 会作为插件装进 plugins/ 目录的模块
     * <p>
     * 按 {@code target/plugin.json} 认定：插件处理器只给插件模块生成它，与部署形态同源，
     * 不手写名单。手写名单的失败形态是「新插件不在名单里，它的键悄悄免检」；
     * 现算的失败形态是「新插件的键在期望侧、不在实际侧，这一格当场红」——红的才是对的。
     * 整盘跑到本模块的测试时，本模块自己的 jar 还没打出来（温树上带着的旧件摆进去也无妨：
     * 比对只量「期望的每一键都有一行」）；它的键走类路径那条来路取到。
     * @return 模块目录名
     */
    private List<String> pluginModules() {
        Path root = repositoryRoot();

        List<String> plugins = new ArrayList<>();
        for (String module : modules()) {
            if (Files.exists(root.resolve(module).resolve("target").resolve("plugin.json"))) {
                plugins.add(module);
            }
        }
        return plugins;
    }

    /**
     * 拿启动时真正在解析这份件的那个解析器读它，取其中的键与值
     * <p>
     * 判法与核心侧 {@code ConfigurationTemplateTest} 同源（那边量核心单模块的定盘星，
     * 这边量全部模块的键集），改摊平规则时两处一起改。自己写的摊平有一条硬规则：
     * <b>空表也是叶子</b>——现成的 {@code YamlPropertySourceLoader} 遇到空 map 会递归进去，
     * 写成空表的那一项于是无声消失，而「写了空表」与「压根没有这一项」必须分得开。
     * @param file 配置文件
     * @return 键到值
     */
    private static Map<String, Object> load(Path file) throws IOException {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);

        Object root;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            root = new Yaml(new SafeConstructor(options)).load(reader);
        }

        Map<String, Object> values = new LinkedHashMap<>();
        flattenNode("", root, values);
        return values;
    }

    /**
     * 把解析出来的树摊成「键路径 → 值」：非空 map 继续往下走，其余就地当叶子
     * @param prefix 到这一层为止的键路径
     * @param node 这一层的值
     * @param out 摊平的结果
     */
    private static void flattenNode(String prefix, Object node, Map<String, Object> out) {
        if (node instanceof Map<?, ?> map && !map.isEmpty()) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = prefix.isEmpty() ? String.valueOf(entry.getKey()) : prefix + "." + entry.getKey();
                flattenNode(key, entry.getValue(), out);
            }
            return;
        }

        out.put(prefix, node);
    }

    /**
     * 文件里被整行注释掉、但名字仍留着的配置项
     * <p>
     * 只认最后一段（判法同核心侧）：注释块里没有缩进关系可循，硬去还原路径反而会认出
     * 一堆并不存在的键。
     * @param file 配置文件
     * @param expected 期望在文件里的键名
     * @return 被注释掉的键名
     */
    private static Set<String> commentedKeys(Path file, Set<String> expected) throws IOException {
        Set<String> live = load(file).keySet();
        String text = Files.readString(file, StandardCharsets.UTF_8);

        Set<String> commented = new LinkedHashSet<>();
        for (String key : expected) {
            if (live.contains(key)) {
                continue;
            }
            String leaf = key.substring(key.lastIndexOf('.') + 1);
            if (text.contains("# " + leaf + ":")) {
                commented.add(key);
            }
        }

        return commented;
    }

    @Test
    @DisplayName("⚠️ 文档与模板里写的协议版本号与代码里的常量一致")
    void protocolVersionIsStatedConsistently() throws IOException {
        // 升 v2 时我按「散在 11 处」逐处改，漏了配置模板——它一直写着 v1。
        // 靠数出来的清单去改，改完没法证明改全了；这条测试改成让机器去找那些地方。
        Pattern mention = Pattern.compile("事件输出协议 v(\\d+)");
        List<String> stale = new ArrayList<>();
        Path root = repositoryRoot();

        for (String relative : List.of("dist/templates/application.example.yml", "docs/user-guide.md", "CHANGELOG.md")) {
            Path file = root.resolve(relative);
            // 三份都在册，缺一份就是有人改了名而没改这里——跳过它等于把这一格量成空集
            assertTrue(Files.exists(file), "找不到 " + relative + " —— 改过名就把这里一起改");
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
