package com.starlwr.bot.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
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

import org.springframework.core.io.ClassPathResource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 第一次保存时写出来的那份 application.yml
 * <p>
 * 发行包不再带配置文件，它由程序自己写出来。<b>于是「配置面」与「配置文件」之间第一次有了
 * 一条钉得住的关系</b>——而这几格量的就是那条关系。
 * <p>
 * 先红读数（2026-09-04，改之前，量的是当时随包发的那份手写件
 * {@code dist/templates/application.yml}）：<b>定盘星 80 项里，它缺 24 项</b>
 * （{@code command.admins}、{@code live.*} 四项、{@code network*.*} 六项、{@code paint.*} 三项、
 * {@code plugin.*} 两项、{@code push.at-all-*} 两项、{@code datasource.*} 两项、
 * {@code agreement.*} 三项、{@code sender}）。手写件与配置面之间没有任何东西钉住，
 * 🔴 <b>而缺项的表现是「设置页上有、配置文件里没有」——使用者照着文件改，改不到那一项，
 * 程序照常启动、什么也不报。</b>
 *
 * <h2>四格各钉一层</h2>
 * <ul>
 *   <li>① <b>键集</b>：写出来的件里，配置面上每一个键都有它的位置，一个不多一个不少。</li>
 *   <li>② <b>注释掉的那几个</b>：只许是那张「写成空值会起不来」的表里的项，
 *       且必须真的在文件里留着名字——否则「注释掉」与「压根没写」长得一样。</li>
 *   <li>③ <b>值</b>：读回来逐键与运行中的默认值同串。写出一份默认值被抹掉的配置，
 *       与写出一份完整的配置，在「每一项都写全了」这句话上长得一样。</li>
 *   <li>④ <b>写口</b>：文件不在时保存一项，文件出得来、那一项是新值、其余仍是默认值（阳）；
 *       文件已在且被改过时，保存别的项<b>不动</b>改过的那一项（阴）。</li>
 * </ul>
 */
@DisplayName("首次保存写出的配置文件")
class ConfigurationTemplateTest {
    /**
     * 配置面定盘星，与 {@code ConfigurationSurfaceBaselineTest} 读的是同一份样本
     * <p>
     * 不另抄一份键名清单：抄一份就有了两个「配置面有哪些键」的答案，
     * 而它们分家的表现是这一格绿着、而写出来的文件缺了几项。
     */
    private static final String BASELINE = "configuration-baseline/config-keys.txt";

    @TempDir
    Path dir;

    /**
     * 定盘星里的键名
     */
    private static Set<String> baselineKeys() throws IOException {
        String content;
        try (InputStream in = new ClassPathResource(BASELINE).getInputStream()) {
            content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        Set<String> keys = new LinkedHashSet<>();
        for (String line : content.split("\n")) {
            if (!line.isEmpty()) {
                keys.add(line.split("\\|")[0]);
            }
        }

        assertFalse(keys.isEmpty(), "定盘星读成了空的 —— 下面几格会在空集上恒真");
        return keys;
    }

    /**
     * 拿启动时真正在解析这份件的那个解析器读它，取其中的键与值
     * <p>
     * 不自己按缩进扫一遍：自己扫的那一份认不出「同一个键写了两遍」这类结构性错误，
     * 而那种文件启动时直接抛异常、程序连起都起不来。这里把重复键关掉
     * （{@code allowDuplicateKeys(false)}，与 Spring 读配置时的设置相同），
     * <b>于是这一趟顺带也就证明了这份件解析得动。</b>
     *
     * <h2>为什么摊平这一步是自己写的</h2>
     * 现成的 {@code YamlPropertySourceLoader} 摊出来的键集<b>看不见写成空表的那一项</b>：
     * 它遇到空的 map 就递归进去，而空的里面什么也没有，那个键于是无声地不见了
     * （{@code alert.webhook-headers} 正是这样一项）。拿它当尺，
     * 🔴 <b>「文件里写了 {@code webhook-headers: {}}」与「文件里压根没有这一项」读数一样</b>，
     * 而这一格量的正是「一项都不许少」。所以摊平按本文件这一条规则来：<b>空表也是叶子。</b>
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
     * 把解析出来的树摊成「键路径 → 值」
     * <p>
     * 只有<b>非空</b>的 map 才继续往下走；空表、列表、标量、空值一律就地当叶子。
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
     * 写出一份来
     */
    private Path generate() throws IOException {
        Path config = dir.resolve("application.yml");
        assertFalse(Files.exists(config), "夹具起点：文件得是不存在的，否则下面量的是「已有的件不被覆盖」");

        assertTrue(new ConfigurationFileService(config).createIfAbsent(), "这一趟应当真的建了文件");
        return config;
    }

    @Test
    @DisplayName("① 键集 —— 配置面上每一个键都在文件里有位置，一个不多一个不少")
    void everyConfigurationKeyHasALine() throws IOException {
        Path config = generate();

        Set<String> expected = baselineKeys();
        Set<String> live = load(config).keySet();
        Set<String> commented = commentedKeys(config, expected);

        Set<String> actual = new TreeSet<>(live);
        actual.addAll(commented);

        assertEquals(new TreeSet<>(expected), actual,
                "写出来的配置文件与配置面对不上。少的那几项在设置页上有、在文件里没有，"
                        + "使用者照着文件改改不到；多的那几项是文件里留着代码里已经没有的键");
    }

    @Test
    @DisplayName("② 注释掉的只许是「写成空值就起不来」的那几项，而且名字得留在文件里")
    void onlyKeysThatBreakStartupAreCommentedOut() throws IOException {
        Path config = generate();

        Set<String> expected = baselineKeys();
        Set<String> commented = commentedKeys(config, expected);

        assertFalse(commented.isEmpty(),
                "一项也没注释掉 —— 那么下面这条「注释掉的都在免检表里」量的是空集，恒真");

        List<String> unexplained = new ArrayList<>(commented);
        unexplained.removeAll(ConfigurationFileService.BLANK_MEANS_ABSENT);
        assertTrue(unexplained.isEmpty(),
                "以下配置项被注释掉了，而它们不在「写成空值会让程序起不来」那张表里。"
                        + "注释掉一项就是从文件上把它抹掉，得有理由:\n  " + String.join("\n  ", unexplained));
    }

    @Test
    @DisplayName("③ 值 —— 读回来逐键与运行中的默认值同串")
    void valuesAreTheRealDefaults() throws IOException {
        Path config = generate();

        Map<String, Object> written = load(config);
        Map<String, Object> defaults = ConfigurationPropertyFields.values(List.of(
                new com.starlwr.bot.core.config.StarBotCoreProperties(),
                new com.starlwr.bot.core.config.EventStreamProperties(),
                new com.starlwr.bot.core.config.DatasourceProperties()));

        List<String> wrong = new ArrayList<>();
        for (Map.Entry<String, Object> entry : defaults.entrySet()) {
            if (!written.containsKey(entry.getKey())) {
                continue;
            }

            String expected = describe(entry.getValue());
            String actual = describe(written.get(entry.getKey()));
            if (!expected.equals(actual)) {
                wrong.add(entry.getKey() + ": 默认值 [" + expected + "]，文件里 [" + actual + "]");
            }
        }

        assertTrue(wrong.isEmpty(),
                "写出来的值与运行中的默认值不同。差得最要紧的那一类是「Java 里有默认值、"
                        + "而编译期元数据那一栏是空的」——allow-ips 就是这样一项，"
                        + "把它写成空的等于把「默认放行本机回环」换成「谁都拒绝」:\n  "
                        + String.join("\n  ", wrong));

        // 分母自证：上面那一圈要是一项都没比到，它照样是绿的
        assertTrue(written.containsKey("starbot.core.config-ui.allow-ips"),
                "allow-ips 没进这一格的分母 —— 那么这一格并没有量到它");
    }

    @Test
    @DisplayName("④ 写口·阳性 —— 文件不在时保存一项，文件出得来、那一项是新值、其余仍是默认")
    void firstSaveCreatesTheFile() throws IOException {
        Path config = dir.resolve("application.yml");
        ConfigurationFileService service = new ConfigurationFileService(config);
        assertFalse(Files.exists(config), "夹具起点：文件不存在");

        List<String> changed = service.write(Map.of("starbot.core.push.quiet-start", "23:00"));

        assertTrue(Files.exists(config), "第一次保存没有把文件写出来 —— 免配置起步的实例保存一次就报错");
        assertEquals(List.of("starbot.core.push.quiet-start"), changed);
        assertEquals("23:00", service.read().get("starbot.core.push.quiet-start"));
        assertEquals("7827", service.read().get("server.port"), "同一趟写出来的其余项应当还是默认值");
    }

    @Test
    @DisplayName("对象列表按字段写出，读回来仍是字段而不是对象摘要")
    void objectListRoundTripsFieldByField() throws IOException {
        ConfigurationMetadataService.ConfigurationField field =
                new ConfigurationMetadataService.ConfigurationField(
                        "senders", "java.util.List", "推送平台", List.of());
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", "qq-onebot");
        item.put("api", "/send");
        item.put("one-bot-http-token", "secret");

        String yaml = ConfigurationTemplate.render(List.of(field), Map.of("senders", List.of(item)));
        Path file = dir.resolve("object-list.yml");
        Files.writeString(file, yaml, StandardCharsets.UTF_8);

        Map<String, Object> written = load(file);
        Object senders = written.get("senders");
        assertTrue(senders instanceof List<?>, "senders 读回来应当是列表。文件:\n" + yaml);
        List<?> list = (List<?>) senders;
        assertEquals(1, list.size(), yaml);
        assertTrue(list.get(0) instanceof Map<?, ?>,
                "元素应当是对象。按 toString 写出去的那一版读回来是字符串，"
                        + "再写进配置文件就解析不出 name。实值: " + list.get(0) + "\n文件:\n" + yaml);
        assertEquals("qq-onebot", ((Map<?, ?>) list.get(0)).get("name"), yaml);
        assertEquals("/send", ((Map<?, ?>) list.get(0)).get("api"), yaml);
        assertEquals("secret", ((Map<?, ?>) list.get(0)).get("one-bot-http-token"), yaml);
    }

    @Test
    @DisplayName("Java 对象列表同样按字段写出，键名是短横线")
    void javaBeanListRoundTripsFieldByField() throws IOException {
        ConfigurationMetadataService.ConfigurationField field =
                new ConfigurationMetadataService.ConfigurationField(
                        "senders", "java.util.List", "推送平台", List.of());
        DemoSender bean = new DemoSender();
        bean.name = "qq-onebot";
        bean.oneBotAddress = "10.0.0.9";

        String yaml = ConfigurationTemplate.render(List.of(field), Map.of("senders", List.of(bean)));
        Path file = dir.resolve("bean-list.yml");
        Files.writeString(file, yaml, StandardCharsets.UTF_8);

        Map<String, Object> written = load(file);
        Object senders = written.get("senders");
        assertTrue(senders instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?>,
                "Java 对象也该按字段写。实值: " + senders + "\n文件:\n" + yaml);
        Map<?, ?> item = (Map<?, ?>) ((List<?>) senders).get(0);
        assertEquals("qq-onebot", item.get("name"), yaml);
        assertEquals("10.0.0.9", item.get("one-bot-address"), yaml);
    }

    @Test
    @DisplayName("标量列表仍按标量写")
    void scalarListStillRendersAsScalars() throws IOException {
        ConfigurationMetadataService.ConfigurationField field =
                new ConfigurationMetadataService.ConfigurationField(
                        "allow-ips", "java.util.List", "白名单", List.of());
        String yaml = ConfigurationTemplate.render(List.of(field),
                Map.of("allow-ips", List.of("127.0.0.1/32", "::1/128")));
        Path file = dir.resolve("scalar-list.yml");
        Files.writeString(file, yaml, StandardCharsets.UTF_8);

        assertEquals(List.of("127.0.0.1/32", "::1/128"), load(file).get("allow-ips"), yaml);
    }

    /**
     * 配置对象列表里那种 Java bean 的替身：字段名驼峰，写出去该是短横线
     */
    private static final class DemoSender {
        private String name;
        private String oneBotAddress;
    }

    @Test
    @DisplayName("④ 写口·阴性 —— 已有的文件不会被这份模板盖掉")
    void existingFileIsNeverOverwritten() throws IOException {
        Path config = dir.resolve("application.yml");
        Files.writeString(config, """
                server:
                  port: 9000
                starbot:
                  core:
                    push:
                      quiet-start:
                """, StandardCharsets.UTF_8);

        ConfigurationFileService service = new ConfigurationFileService(config);
        assertFalse(service.createIfAbsent(), "文件已经在了，不该再建一次");

        service.write(Map.of("starbot.core.push.quiet-start", "23:00"));

        assertEquals("9000", service.read().get("server.port"),
                "使用者改过的那一项被默认值盖回去了 —— 这是这段代码最坏的失败形态，"
                        + "而它发生之后没有任何现象");
        assertEquals("23:00", service.read().get("starbot.core.push.quiet-start"));
    }

    /**
     * 文件里被整行注释掉、但名字仍留着的配置项
     * <p>
     * 只认最后一段：注释块里没有缩进关系可循，硬去还原路径反而会认出一堆并不存在的键。
     * 分母是定盘星里那些<b>解析器没读到</b>的键，因此不会把正文里的别的字当成配置项。
     * @param file 配置文件
     * @param expected 定盘星里的键名
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

    /**
     * 把一个值压成可比的形式，两侧走的是同一个函数
     */
    private static String describe(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof java.util.Collection<?> items) {
            return items.isEmpty() ? "" : String.join("\n", items.stream().map(String::valueOf).toList());
        }
        if (value instanceof Map<?, ?> entries) {
            return entries.isEmpty() ? "" : String.valueOf(entries);
        }
        return String.valueOf(value);
    }
}
