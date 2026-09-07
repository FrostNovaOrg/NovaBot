package com.starlwr.bot.core.config;

import com.starlwr.bot.core.config.ui.ConfigurationMetadataService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配置面定盘星
 * <p>
 * 配置键是本程序对使用者的接口面：改一个键名，所有既有部署的 application.yml 都失效，
 * 而失效的样子是「那一项悄悄回到默认值」——程序照常启动，看不出任何异常。
 * <b>因此配置面的改动必须是一次显式的决定，不能是一次重构的副产品。</b>
 * <p>
 * 前三格各钉一层，任一格红都说明配置面动了；第四格钉的是说明里的平台词：
 * <ul>
 *   <li>① <b>键全集</b>：编译期生成的配置元数据里，展示给使用者的每一个键的
 *       「键名｜类型｜默认值｜说明」逐字与样本相同。少一项、多一项、改一项都红。</li>
 *   <li>② <b>绑定结果</b>：一份覆盖各节的配置喂进绑定器后，绑出来的对象逐字段与样本同值。
 *       ① 只管键长什么样，这一格管「写下去的值真的落到那个字段上」——
 *       键名没动而字段搬了家时，只有这一格会红。</li>
 *   <li>③ <b>字段表项数</b>：配置界面的字段表由元数据分组而来，其项数须与 ① 的键数相等。
 *       分组这一步会按键名的最后一段拆分，<b>漏掉一整组是它独有的失败形态</b>。</li>
 *   <li>④ <b>留核键说明零平台词</b>：留在核心的键，说明只说「平台」「机器人」，
 *       不写具体平台名——平台名属于插件。迁移中的两节暂豁免，整节迁走后豁免也删，
 *       问②会在基线里找不到它们的键时提醒。</li>
 * </ul>
 * <p>
 * 样本对不上时，实际值会写到 {@code target/configuration-baseline/} 下，便于逐行比对；
 * <b>确认是有意改动之后才更新样本</b>——反过来做，这三格就只是在记录既成事实。
 */
@DisplayName("配置面定盘星")
class ConfigurationSurfaceBaselineTest {
    /**
     * 样本所在的类路径目录
     * <p>
     * 目录名不叫 {@code config/}：Spring Boot 会在 {@code classpath:/config/} 下找配置文件，
     * 往那里放东西等于给测试悄悄加一层配置源。
     */
    private static final String BASELINE = "configuration-baseline/";

    private static final String KEYS_FILE = "config-keys.txt";

    private static final String BINDING_FILE = "binding-dump.txt";

    private static final String COVERAGE_YML = "coverage.yml";

    /**
     * 说明里不许出现的平台名——留在核心的键，说明只说「平台」「机器人」
     */
    private static final List<String> PLATFORM_WORDS = List.of("QQ", "NapCat", "OneBot");

    /**
     * 迁移途中暂豁免的一节：代登录凭据四键。告警目标三项已迁走。
     * 整节迁去插件之后，基线里不再有这个前缀的键，问②会红——那是在提醒把豁免一并删掉。
     */
    private static final List<String> MIGRATING_PREFIXES =
            List.of("starbot.core.config-ui.napcat.");

    @Test
    @DisplayName("① 键全集 —— 键名、类型、默认值、说明逐项与样本同串")
    void keysMatchBaseline() throws IOException {
        List<String> actual = describeFields();
        writeActual(KEYS_FILE, actual);

        assertEquals(readBaseline(KEYS_FILE), actual,
                "配置键全集与样本不符。改键名/删键/改默认值/改说明都会走到这里——"
                        + "确认是有意改动后，用 target/configuration-baseline/" + KEYS_FILE + " 更新样本");
    }

    @Test
    @DisplayName("② 绑定结果 —— 覆盖各节的配置绑出的对象逐字段同值")
    void bindingMatchesBaseline() throws IOException {
        Binder binder = new Binder(new MapConfigurationPropertySource(loadCoverage()));

        StarBotCoreProperties core = new StarBotCoreProperties();
        binder.bind("starbot.core", Bindable.ofInstance(core));

        EventStreamProperties stream = new EventStreamProperties();
        binder.bind(EventStreamProperties.PREFIX, Bindable.ofInstance(stream));

        List<String> actual = new ArrayList<>();
        collect("starbot.core", core, actual);
        collect(EventStreamProperties.PREFIX, stream, actual);
        Collections.sort(actual);
        writeActual(BINDING_FILE, actual);

        assertEquals(readBaseline(BINDING_FILE), actual,
                "绑定结果与样本不符。键名没变而字段换了落点时，只有这一格会红——"
                        + "确认是有意改动后，用 target/configuration-baseline/" + BINDING_FILE + " 更新样本");
    }

    @Test
    @DisplayName("③ 字段表项数 —— 界面分组后的项数与键数相等，且每项都出得来控件与遮蔽判定")
    void fieldTableCountMatchesKeyCount() throws IOException {
        ConfigurationMetadataService service = new ConfigurationMetadataService();

        int keys = service.getFields().size();
        int grouped = service.getGroupedFields().values().stream().mapToInt(List::size).sum();

        assertEquals(keys, grouped, "界面字段表的项数与键数不等 —— 分组这一步漏了整整一组");
        assertEquals(readBaseline(KEYS_FILE).size(), keys,
                "键数与样本行数不等 —— 与 ① 是同一件事的两种数法，两者不一致说明样本自身坏了");

        for (ConfigurationMetadataService.ConfigurationField field : service.getFields()) {
            assertTrue(field.widget() != null && !field.widget().isBlank(),
                    "配置项 " + field.name() + " 推不出控件类型，界面上会是一个空位");
        }
    }

    @Test
    @DisplayName("④ 留核键说明零平台词 —— 非豁免键的说明不含 QQ／NapCat／OneBot；豁免恰一前缀且仍有键；行数与键数同")
    void retainedKeyDescriptionsHaveNoPlatformWords() throws IOException {
        List<String> baseline = readBaseline(KEYS_FILE);
        List<String> unresolved = new ArrayList<>();

        // 问①：非豁免键的说明不得含平台词，红文列出键名；行缺说明段时拿整行受检，别让格式破了溜过去
        try {
            List<String> offenders = new ArrayList<>();
            for (String line : baseline) {
                String[] parts = line.split("\\|", 4);
                String description = parts.length < 4 ? line : parts[3];
                if (!isMigrating(parts[0]) && PLATFORM_WORDS.stream().anyMatch(description::contains)) {
                    offenders.add(parts.length < 4 ? parts[0] + "（行缺说明段）" : parts[0]);
                }
            }
            assertTrue(offenders.isEmpty(),
                    "说明含平台词（" + String.join("／", PLATFORM_WORDS) + "）的留核键: " + String.join("、", offenders));
        } catch (AssertionError e) {
            unresolved.add("问① " + e.getMessage());
        }

        // 问②：豁免恰一前缀，且在基线中仍有键——键迁走后此问红，提醒删豁免
        try {
            for (String prefix : MIGRATING_PREFIXES) {
                assertTrue(baseline.stream().anyMatch(line -> line.startsWith(prefix)),
                        "豁免前缀 " + prefix + " 在基线中已无键——这一节已迁走，把豁免删掉");
            }
            assertEquals(1, MIGRATING_PREFIXES.size(),
                    "豁免须恰一前缀：代登录凭据——增删豁免须是一次显式决定");
        } catch (AssertionError e) {
            unresolved.add("问② " + e.getMessage());
        }

        // 问③：样本行数与现行键数相等——改说明的那只手不许顺带改键数
        try {
            assertEquals(baseline.size(), describeFields().size(), "样本行数与现行键数不等——说明之外键数也动了");
        } catch (AssertionError e) {
            unresolved.add("问③ " + e.getMessage());
        }

        assertTrue(unresolved.isEmpty(),
                () -> "留核键说明零平台词三问中 " + unresolved.size() + " 问未销: " + String.join("; ", unresolved));
    }

    /**
     * 判断一个键是否属于迁移途中暂豁免的两节
     */
    private static boolean isMigrating(String key) {
        return MIGRATING_PREFIXES.stream().anyMatch(key::startsWith);
    }

    /**
     * 把元数据里展示给使用者的配置项摊成逐行文本
     * @return 每项一行，形如 {@code 键名|类型|默认值|说明}
     */
    private static List<String> describeFields() {
        List<String> lines = new ArrayList<>();

        for (ConfigurationMetadataService.ConfigurationField field : new ConfigurationMetadataService().getFields()) {
            lines.add(String.join("|",
                    field.name(),
                    String.valueOf(field.type()),
                    literal(field.defaultValue()),
                    literal(field.description())));
        }

        Collections.sort(lines);
        return lines;
    }

    /**
     * 读覆盖用的配置，摊平成绑定器认的键值表
     * @return 键值表
     */
    private static Map<String, Object> loadCoverage() throws IOException {
        Map<String, Object> flat = new LinkedHashMap<>();

        for (PropertySource<?> source : new YamlPropertySourceLoader()
                .load("coverage", new ClassPathResource(BASELINE + COVERAGE_YML))) {
            for (String name : ((EnumerablePropertySource<?>) source).getPropertyNames()) {
                flat.putIfAbsent(name, source.getProperty(name));
            }
        }

        return flat;
    }

    /**
     * 沿 getter 把配置对象摊成逐行文本
     * <p>
     * 只认 {@code get}/{@code is} 开头的无参公开方法，属性名按 Spring 的松散绑定规则转成串式命名，
     * 于是这份文本的路径与配置键长得一样——绑错了位置一眼看得出来。
     * @param path 当前路径
     * @param value 当前值
     * @param sink 收集器
     */
    private static void collect(String path, Object value, List<String> sink) {
        if (value == null) {
            sink.add(path + "=<null>");
            return;
        }

        if (value instanceof Collection<?> items) {
            if (items.isEmpty()) {
                sink.add(path + "=<empty>");
                return;
            }
            int index = 0;
            for (Object item : items) {
                collect(path + "[" + index++ + "]", item, sink);
            }
            return;
        }

        if (value instanceof Map<?, ?> entries) {
            if (entries.isEmpty()) {
                sink.add(path + "=<empty>");
                return;
            }
            List<String> keys = new ArrayList<>();
            entries.keySet().forEach(key -> keys.add(String.valueOf(key)));
            Collections.sort(keys);
            for (String key : keys) {
                collect(path + "[" + key + "]", entries.get(key), sink);
            }
            return;
        }

        List<Method> getters = value.getClass().getName().startsWith("com.starlwr.") && !value.getClass().isEnum()
                ? getters(value.getClass())
                : List.of();

        if (getters.isEmpty()) {
            sink.add(path + "=" + value);
            return;
        }

        for (Method getter : getters) {
            Object child;
            try {
                child = getter.invoke(value);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("读不到 " + path + " 的 " + getter.getName(), e);
            }
            collect(path + "." + dashed(property(getter)), child, sink);
        }
    }

    /**
     * 取一个类的属性读方法
     * @param type 类型
     * @return 按方法名排序的读方法
     */
    private static List<Method> getters(Class<?> type) {
        List<Method> methods = new ArrayList<>();

        for (Method method : type.getMethods()) {
            if (method.getParameterCount() != 0 || method.getDeclaringClass() == Object.class) {
                continue;
            }
            String name = method.getName();
            boolean read = (name.startsWith("get") && name.length() > 3)
                    || (name.startsWith("is") && name.length() > 2 && method.getReturnType() == boolean.class);
            if (read) {
                methods.add(method);
            }
        }

        methods.sort(Comparator.comparing(Method::getName));
        return methods;
    }

    private static String property(Method getter) {
        String name = getter.getName();
        String bare = name.startsWith("get") ? name.substring(3) : name.substring(2);
        return Character.toLowerCase(bare.charAt(0)) + bare.substring(1);
    }

    private static String dashed(String name) {
        StringBuilder result = new StringBuilder();

        for (char c : name.toCharArray()) {
            if (Character.isUpperCase(c)) {
                if (!result.isEmpty()) {
                    result.append('-');
                }
                result.append(Character.toLowerCase(c));
            } else {
                result.append(c);
            }
        }

        return result.toString();
    }

    /**
     * 把可能带换行的值压成单行
     * <p>
     * 说明取自 Javadoc，本身带换行；一项一行才比得了「少了哪一项」。
     * @param value 原值
     * @return 单行文本
     */
    private static String literal(Object value) {
        if (value == null) {
            return "<null>";
        }

        return String.valueOf(value).replace("\\", "\\\\").replace("\r", "").replace("\n", "\\n");
    }

    private static List<String> readBaseline(String name) throws IOException {
        List<String> lines = new ArrayList<>();

        String content;
        try (InputStream in = new ClassPathResource(BASELINE + name).getInputStream()) {
            content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        for (String line : content.split("\n")) {
            if (!line.isEmpty()) {
                lines.add(line);
            }
        }

        return lines;
    }

    private static void writeActual(String name, List<String> lines) {
        try {
            Path directory = Files.createDirectories(Path.of("target", "configuration-baseline"));
            Files.writeString(directory.resolve(name), String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
