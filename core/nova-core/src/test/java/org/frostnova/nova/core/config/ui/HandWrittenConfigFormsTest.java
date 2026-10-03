package org.frostnova.nova.core.config.ui;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.config.ui.auth.ConfigUiAuthService;
import org.frostnova.nova.core.config.ui.auth.ConfigUiSessionStore;
import org.frostnova.nova.core.config.ui.auth.LoginThrottle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配置文件里只有手写才写得出来的几种形态
 * <p>
 * 设置页自己写出的配置碰不上这些：跨行的行内名单、双引号里的转义、跨了行的值。
 * 手改过配置文件的人碰得上，而碰上时界面显示的与程序启动读到的不是一回事，
 * 或者一次清空就把文件写坏。判据一律拿启动那一路（SnakeYAML）读出来的当准。
 */
@DisplayName("配置文件手写形")
class HandWrittenConfigFormsTest {
    @TempDir
    Path dir;

    private Path config;
    private ConfigurationFileService service;

    @BeforeEach
    void setUp() {
        config = dir.resolve("application.yml");
        service = new ConfigurationFileService(config);
    }

    private void write(String text) throws IOException {
        Files.writeString(config, text, StandardCharsets.UTF_8);
    }

    private String content() throws IOException {
        return Files.readString(config, StandardCharsets.UTF_8);
    }

    /**
     * 整份文件按 SnakeYAML 读，取点分路径上的值；文件读不了时抛出
     */
    private Object loaded(String path) throws IOException {
        Object node = new Yaml().load(content());
        for (String segment : path.split("\\.")) {
            if (!(node instanceof Map<?, ?> map)) {
                return null;
            }
            node = map.get(segment);
        }
        return node;
    }

    private static String joined(Object list) {
        List<String> items = new ArrayList<>();
        for (Object item : (List<?>) list) {
            items.add(String.valueOf(item));
        }
        return String.join("\n", items);
    }

    /**
     * 抓的用户故障：手写跨行名单里某项带 {@code #} 或撇号，设置页上显示的名单与文件里的对不上。
     */
    @Test
    @DisplayName("🔴 跨行名单里带 # 与撇号：界面读出的各项与 SnakeYAML 整份读出的一致")
    void crossLineListWithHashAndApostrophe() throws IOException {
        String key = "novabot.core.command.admins";
        List<String> bad = new ArrayList<>();

        String[][] cases = {
                {"引号里的 #", "novabot:\n  core:\n    command:\n      admins: [\"a #b\",\n        c]\n"},
                {"词中间的撇号", "novabot:\n  core:\n    command:\n      admins: [don't,\n        x]\n"},
                {"单行同病", "novabot:\n  core:\n    command:\n      admins: [don't, \"x #y\"]   # 管理员\n"},
        };
        for (String[] c : cases) {
            try {
                write(c[1]);
                String expected = joined(loaded(key));
                assertEquals(expected, service.read().get(key), "界面读出的名单与文件不符");
            } catch (AssertionError | IOException | RuntimeException e) {
                bad.add(c[0] + ": " + e.getMessage());
            }
        }

        assertTrue(bad.isEmpty(), () -> "井号与撇号 " + bad.size() + " 问未销: " + String.join("; ", bad));
    }

    /**
     * 抓的用户故障：双引号里写了转义，界面显示成字面反斜杠，存一次再重启读到的值变了。
     */
    @Test
    @DisplayName("🔴 双引号里的转义：名单项界面值与启动读到的一致, 改走再改回后启动读到的不变")
    void escapedListItemsRoundTrip() throws IOException {
        String key = "novabot.demo.words";
        // 不放 \/：启动那一路的 SnakeYAML 按 YAML 1.1 不认它，整份文件就读不了
        write("novabot:\n  demo:\n    words:\n"
                + "      - \"a\\tb\"\n"
                + "      - \"caf\\u00e9\"\n"
                + "      - \"back\\\\slash\"\n"
                + "      - \"\\x41z\"\n"
                + "      - \"q\\\"uote\"\n");
        List<String> bad = new ArrayList<>();
        Object before = service.readAsLoaded().get(key);
        String shown = service.read().get(key);

        try {
            assertEquals(joined(before), shown, "界面读出的名单项应已还原转义");
        } catch (AssertionError e) {
            bad.add("① 读: " + e.getMessage());
        }

        try {
            service.write(Map.of(key, "z"));
            service.write(Map.of(key, shown));
            assertEquals(before, service.readAsLoaded().get(key), "改走再改回后启动读到的名单应不变:\n" + content());
        } catch (AssertionError | IOException | RuntimeException e) {
            bad.add("② 存回: " + e.getMessage());
        }

        assertTrue(bad.isEmpty(), () -> "名单项转义 " + bad.size() + " 问未销: " + String.join("; ", bad));
    }

    @Test
    @DisplayName("🔴 双引号里的转义：标量界面值与启动读到的一致, 原样再存不动那一行, 改走再改回后启动读到的不变")
    void escapedScalarRoundTrip() throws IOException {
        String key = "novabot.demo.signature";
        String other = "novabot.demo.motto";
        write("novabot:\n  demo:\n"
                + "    signature: \"say \\\"hi\\\"\"   # 签名\n"
                + "    motto: \"tab\\there caf\\u00e9 \\\\ end\"\n");
        List<String> bad = new ArrayList<>();

        try {
            assertEquals("say \"hi\"", service.read().get(key), "界面应读到还原后的引号");
            assertEquals(service.readAsLoaded().get(other), service.read().get(other), "界面值应与启动读到的一致");
        } catch (AssertionError | IOException e) {
            bad.add("① 读: " + e.getMessage());
        }

        try {
            String text = content();
            assertEquals(List.of(), service.write(Map.of(key, service.read().get(key),
                    other, service.read().get(other))), "界面值与原来一样时不算改动");
            assertEquals(text, content(), "界面值与原来一样时文件一个字节不动");
        } catch (AssertionError | IOException e) {
            bad.add("② 原样再存: " + e.getMessage());
        }

        try {
            Object otherBefore = service.readAsLoaded().get(other);
            String otherShown = service.read().get(other);
            service.write(Map.of(key, "bye", other, "bye"));
            service.write(Map.of(key, "say \"hi\"", other, otherShown));
            assertEquals("say \"hi\"", service.readAsLoaded().get(key), "改走再改回后启动读到的应是原值:\n" + content());
            assertEquals("say \"hi\"", service.read().get(key), "改走再改回后界面读到的应是原值");
            assertEquals(otherBefore, service.readAsLoaded().get(other), "带制表符的值改走再改回后启动读到的应不变:\n" + content());
            assertTrue(content().contains("# 签名"), "行尾注释应留着:\n" + content());
        } catch (AssertionError | IOException | RuntimeException e) {
            bad.add("③ 改走再改回: " + e.getMessage());
        }

        assertTrue(bad.isEmpty(), () -> "标量转义 " + bad.size() + " 问未销: " + String.join("; ", bad));
    }

    /**
     * 抓的用户故障：手写成跨行的值一清空，文件读不了，或续行并进上一个键、悄悄改了它的值。
     */
    @Test
    @DisplayName("🔴 跨行的值清空时连续行一起删：文件整份读得了, 上一个键的值不变")
    void clearingRemovesContinuationLines() throws IOException {
        List<String> bad = new ArrayList<>();

        try {
            write("novabot:\n  core:\n    command:\n"
                    + "      prefix: '!'\n"
                    + "      admins: [111,\n"
                    + "        222]\n"
                    + "      tail: 1\n");
            Map<String, String> clear = new HashMap<>();
            clear.put("novabot.core.command.admins", null);
            service.write(clear);
            assertEquals("!", loaded("novabot.core.command.prefix"), "上一个键不该被改:\n" + content());
            assertEquals(null, loaded("novabot.core.command.admins"), "名单应已删掉:\n" + content());
            assertEquals(1, loaded("novabot.core.command.tail"), "下一个键不该被动:\n" + content());
        } catch (AssertionError | IOException | RuntimeException e) {
            bad.add("① 跨行名单显式清除: " + e.getMessage());
        }

        try {
            write("novabot:\n  demo:\n"
                    + "    greeting: hello\n"
                    + "    signature: first\n"
                    + "      second\n"
                    + "    # 下一项的说明\n"
                    + "    tail: 1\n");
            Map<String, String> clear = new HashMap<>();
            clear.put("novabot.demo.signature", null);
            service.write(clear);
            assertEquals("hello", loaded("novabot.demo.greeting"), "上一个键不该被续行并进去:\n" + content());
            assertEquals(null, loaded("novabot.demo.signature"), "值应已删掉:\n" + content());
            assertTrue(content().contains("# 下一项的说明"), "下一项的说明不属于被删的值:\n" + content());
        } catch (AssertionError | IOException | RuntimeException e) {
            bad.add("② 跨行普通值显式清除: " + e.getMessage());
        }

        try {
            write("spring:\n  data:\n    redis:\n"
                    + "      password: pw\n"
                    + "      host: redis.internal\n"
                    + "        .example\n"
                    + "      port: 6379\n");
            service.write(Map.of("spring.data.redis.host", ""));
            assertEquals("pw", loaded("spring.data.redis.password"), "上一个键不该被续行并进去:\n" + content());
            assertEquals(null, loaded("spring.data.redis.host"), "留空即删的键应已删掉:\n" + content());
            assertEquals(6379, loaded("spring.data.redis.port"), "下一个键不该被动:\n" + content());
        } catch (AssertionError | IOException | RuntimeException e) {
            bad.add("③ 空值即删的跨行值: " + e.getMessage());
        }

        assertTrue(bad.isEmpty(), () -> "清空续行 " + bad.size() + " 问未销: " + String.join("; ", bad));
    }

    /**
     * 抓的用户故障：手写名单中间夹着注释或空行，界面上看不见后面那几项；在界面改一项再存，那几项就没了。
     */
    @Test
    @DisplayName("🔴 块名单中间夹注释、空行：界面读出的各项与启动读到的一致, 界面改一项后启动读到的就是界面那份")
    void blockListWithCommentsBetweenItems() throws IOException {
        String key = "novabot.demo.words";
        List<String> bad = new ArrayList<>();

        String[][] cases = {
                {"注释在项之间", "      - a\n      # 说明\n      - b\n      - c\n"},
                {"空行在项之间", "      - a\n\n      - b\n\n      - c\n"},
                {"注释在最后一项之后", "      - a\n      - b\n      # 末项之后的说明\n"},
                {"注释在键与首项之间", "      # 首项之前的说明\n      - a\n      - b\n"},
        };
        for (String[] c : cases) {
            try {
                write("novabot:\n  demo:\n    words:\n" + c[1] + "    tail: 1\n");
                String shown = service.read().get(key);
                assertEquals(joined(loaded(key)), shown, "界面读出的名单与文件不符");

                String edited = "z" + shown.substring(shown.indexOf('\n'));
                service.write(Map.of(key, edited));
                assertEquals(edited, joined(service.readAsLoaded().get(key)), "界面改一项后启动读到的应是界面那份:\n" + content());
                assertEquals(1, loaded("novabot.demo.tail"), "下一个键不该被动:\n" + content());
            } catch (AssertionError | IOException | RuntimeException e) {
                bad.add(c[0] + ": " + e.getMessage());
            }
        }

        assertTrue(bad.isEmpty(), () -> "名单夹注释 " + bad.size() + " 问未销: " + String.join("; ", bad));
    }

    /**
     * 抓的用户故障：名单项后面写了行尾注释，界面把注释也显示成项的一部分，存回去注释就进了值里。
     */
    @Test
    @DisplayName("🔴 块名单项带行尾注释：界面读出的各项与启动读到的一致, 引号里的 # 不算注释, 存回后启动读到的不变")
    void blockListItemsWithTrailingComments() throws IOException {
        String key = "novabot.demo.words";
        write("novabot:\n  demo:\n    words:\n"
                + "      - a # 不带引号\n"
                + "      - \"b c\"   # 双引号\n"
                + "      - 'it''s' # 单引号\n"
                + "      - \"x # y\"\n"
                + "      - don't # 词中撇号\n"
                + "      - p#q\n"
                + "    tail: 1\n");
        List<String> bad = new ArrayList<>();
        Object before = service.readAsLoaded().get(key);
        String shown = service.read().get(key);

        try {
            assertEquals(joined(before), shown, "界面读出的名单项应不带行尾注释");
        } catch (AssertionError e) {
            bad.add("① 读: " + e.getMessage());
        }

        try {
            service.write(Map.of(key, "z"));
            service.write(Map.of(key, shown));
            assertEquals(before, service.readAsLoaded().get(key), "改走再改回后启动读到的名单应不变:\n" + content());
        } catch (AssertionError | IOException | RuntimeException e) {
            bad.add("② 存回: " + e.getMessage());
        }

        assertTrue(bad.isEmpty(), () -> "名单项行尾注释 " + bad.size() + " 问未销: " + String.join("; ", bad));
    }

    /**
     * 抓的用户故障：跨行写的值在界面改掉，重启后新值后面拖着旧的续行。
     */
    @Test
    @DisplayName("🔴 跨行的普通值改值时续行一起换掉：启动读到的就是新值, 只有键行的照旧只换那一行")
    void changingValueReplacesContinuationLines() throws IOException {
        List<String> bad = new ArrayList<>();

        String[][] cases = {
                {"不带引号", "    signature: first\n      second line\n"},
                {"双引号跨行", "    signature: \"first\n      second line\"\n"},
        };
        for (String[] c : cases) {
            try {
                write("novabot:\n  demo:\n    greeting: hello\n" + c[1] + "    # 下一项的说明\n    tail: 1\n");
                service.write(Map.of("novabot.demo.signature", "z"));
                assertEquals("z", service.readAsLoaded().get("novabot.demo.signature"), "启动读到的应是新值:\n" + content());
                assertEquals("hello", loaded("novabot.demo.greeting"), "上一个键不该被动:\n" + content());
                assertEquals(1, loaded("novabot.demo.tail"), "下一个键不该被动:\n" + content());
                assertTrue(content().contains("# 下一项的说明"), "下一项的说明不属于被换的值:\n" + content());
            } catch (AssertionError | IOException | RuntimeException e) {
                bad.add(c[0] + ": " + e.getMessage());
            }
        }

        try {
            // 块标量值里带换行，是界面改不了的项：不走「续行一起换掉」，照锁整批拒存
            write("novabot:\n  demo:\n    greeting: hello\n    signature: |\n      first\n\n      second line\n"
                    + "    # 下一项的说明\n    tail: 1\n");
            String text = content();
            assertThrows(IOException.class, () -> service.write(Map.of("novabot.demo.signature", "z")),
                    "块标量界面改不了, 改值应整批拒存");
            assertEquals(text, content(), "拒存时文件一个字节不动");
        } catch (AssertionError | IOException | RuntimeException e) {
            bad.add("块标量锁住: " + e.getMessage());
        }

        try {
            write("novabot:\n  demo:\n    signature: first   # 签名\n    motto: keep\n");
            service.write(Map.of("novabot.demo.signature", "z"));
            assertEquals("novabot:\n  demo:\n    signature: z       # 签名\n    motto: keep\n", content(), "只有键行的值只换那一行");
        } catch (AssertionError | IOException | RuntimeException e) {
            bad.add("只有键行: " + e.getMessage());
        }

        try {
            write("novabot:\n  demo:\n    signature: first\n      second line\n    tail: 1\n");
            String text = content();
            service.write(Map.of("novabot.demo.signature", service.read().get("novabot.demo.signature")));
            assertEquals(text, content(), "界面值没变时续行不动");
        } catch (AssertionError | IOException | RuntimeException e) {
            bad.add("原样再存: " + e.getMessage());
        }

        assertTrue(bad.isEmpty(), () -> "改值续行 " + bad.size() + " 问未销: " + String.join("; ", bad));
    }

    /**
     * 抓的用户故障：值里带 U+2028 或 U+2029，存完配置文件读不了，程序起不来。
     */
    @Test
    @DisplayName("🔴 值里带 U+2028、U+2029：存后整份文件读得了, 读回的值与界面一致")
    void lineAndParagraphSeparatorsAreEscaped() throws IOException {
        List<String> bad = new ArrayList<>();

        String[][] cases = {
                {"U+2028", "one two"},
                {"U+2029", "one two"},
        };
        for (String[] c : cases) {
            try {
                write("novabot:\n  demo:\n    signature: first\n    tail: 1\n");
                service.write(Map.of("novabot.demo.signature", c[1], "novabot.demo.added", c[1]));
                assertEquals(c[1], loaded("novabot.demo.signature"), "改值: 启动读到的应是原字:\n" + content());
                assertEquals(c[1], loaded("novabot.demo.added"), "新增: 启动读到的应是原字:\n" + content());
                assertEquals(c[1], service.read().get("novabot.demo.signature"), "界面读回的应与写入的一致");
                assertEquals(1, loaded("novabot.demo.tail"), "下一个键不该被动:\n" + content());
            } catch (AssertionError | IOException | RuntimeException e) {
                bad.add(c[0] + ": " + e.getMessage());
            }
        }

        assertTrue(bad.isEmpty(), () -> "换行类字 " + bad.size() + " 问未销: " + String.join("; ", bad));
    }

    /**
     * 原样写出的字是不是都真能原样读回：可打印段里的每一个 BMP 字与补充平面的抽样，
     * 不带引号写一次（除去 U+2028、U+2029，它们必须转义）、带引号写一次（值里夹了必须加引号的字），
     * 启动那一路读回的都是原值。
     */
    @Test
    @DisplayName("可打印段里的字原样写出, 启动那一路逐字读回")
    void everyPrintableCharacterRoundTrips() throws IOException {
        StringBuilder plain = new StringBuilder("a");
        for (int cp = 0xA0; cp <= 0xFFFD; cp++) {
            if ((cp < 0xD800 || cp > 0xDFFF) && cp != 0x2028 && cp != 0x2029) {
                plain.appendCodePoint(cp);
            }
        }
        for (int cp = 0x10000; cp <= 0x10FFFF; cp += 97) {
            plain.appendCodePoint(cp);
        }
        plain.appendCodePoint(0x10FFFF);
        StringBuilder quoted = new StringBuilder("x: \"y\" \\ #");
        for (int cp = 0x20; cp <= 0x7E; cp++) {
            quoted.appendCodePoint(cp);
        }
        quoted.append(plain).append("  ");

        write("novabot:\n  demo:\n    plain: a\n    quoted: b\n");
        service.write(Map.of("novabot.demo.plain", plain.toString(), "novabot.demo.quoted", quoted.toString()));

        assertEquals(plain.toString(), loaded("novabot.demo.plain"), "不带引号写出的应逐字读回");
        assertEquals(quoted.toString(), loaded("novabot.demo.quoted"), "带引号写出的应逐字读回");
        assertEquals(plain.toString(), service.read().get("novabot.demo.plain"), "界面读回的应与写入的一致");
        assertEquals(quoted.toString(), service.read().get("novabot.demo.quoted"), "界面读回的应与写入的一致");
    }

    /**
     * 抓的用户故障：名单里有只写了短横的空项，界面上少了这一项；只点了保存名单，整份名单就被改写。
     */
    @Test
    @DisplayName("🔴 名单里只有短横的空项：界面读出与启动一致（收成空串）, 原样送回不动文件")
    void emptyListItemsReadAsEmptyStrings() throws IOException {
        String key = "novabot.demo.words";
        write("novabot:\n  demo:\n    words:\n"
                + "      -\n"
                + "      - e2\n"
                + "      - # 空项带注释\n"
                + "      - e4\n"
                + "    tail: 1\n");
        String text = content();
        List<String> bad = new ArrayList<>();
        String shown = service.read().get(key);

        try {
            assertEquals(joined(service.readAsLoaded().get(key)), shown, "界面读出的名单与启动读到的不符");
        } catch (AssertionError e) {
            bad.add("① 读: " + e.getMessage());
        }

        try {
            assertEquals(List.of(), service.write(Map.of(key, shown)), "原样送回不算改动");
            assertEquals(text, content(), "原样送回文件一个字节不动");
        } catch (AssertionError | IOException | RuntimeException e) {
            bad.add("② 原样送回: " + e.getMessage());
        }

        assertTrue(bad.isEmpty(), () -> "名单空项 " + bad.size() + " 问未销: " + String.join("; ", bad));
    }

    /**
     * 抓的用户故障：名单的键行上带着锚点，界面上这一项显示成「&w」；在界面改一项，整份名单变成一个字。
     */
    @Test
    @DisplayName("🔴 键行只有锚点或标签的块名单：界面读出与启动一致, 改一项后启动读到界面那份、键行上的锚点还在")
    void anchoredBlockListReadsAsList() throws IOException {
        String key = "novabot.demo.words";
        List<String> bad = new ArrayList<>();

        String[] heads = {"words: &w", "words: &w   # 说明", "words: !!seq &w"};
        for (String head : heads) {
            try {
                write("novabot:\n  demo:\n    " + head + "\n      - a\n      - b\n    tail: 1\n");
                String shown = service.read().get(key);
                assertEquals(joined(service.readAsLoaded().get(key)), shown, "界面读出的名单与启动读到的不符");

                service.write(Map.of(key, "z\nb"));
                assertEquals("z\nb", joined(service.readAsLoaded().get(key)), "界面改一项后启动读到的应是界面那份:\n" + content());
                assertTrue(content().contains("    " + head + "\n"), "键行上的锚点、标签应原样留着:\n" + content());
                assertEquals(1, loaded("novabot.demo.tail"), "下一个键不该被动:\n" + content());
            } catch (AssertionError | IOException | RuntimeException e) {
                bad.add(head + ": " + e.getMessage());
            }
        }

        assertTrue(bad.isEmpty(), () -> "锚点名单 " + bad.size() + " 问未销: " + String.join("; ", bad));
    }

    /**
     * 抓的用户故障：上级块的键行带着锚点或标签，在界面给它填个值，底下别的设置悄悄没了。
     * <p>
     * 这种键底下是子项而不是续行：写口整批拒存、文件一个字节不动。
     */
    @Test
    @DisplayName("🔴 键行只有锚点或标签的上级块改值：整批拒存, 子项还在、文件不动")
    void anchoredOrTaggedParentKeepsChildren() throws IOException {
        List<String> bad = new ArrayList<>();

        String[] heads = {"base: &b", "base: !!map", "base: &b !!map   # 说明"};
        for (String head : heads) {
            try {
                write("novabot:\n  demo:\n    " + head + "\n      child: c\n    tail: 1\n");
                String text = content();
                assertThrows(IOException.class, () -> service.write(Map.of("novabot.demo.base", "z")),
                        "上级块改值应整批拒存");
                assertEquals(text, content(), "拒存时文件一个字节不动");
                assertEquals("c", loaded("novabot.demo.base.child"), "子项不该被删");
            } catch (AssertionError | IOException | RuntimeException e) {
                bad.add(head + ": " + e.getMessage());
            }
        }

        assertTrue(bad.isEmpty(), () -> "锚点上级块 " + bad.size() + " 问未销: " + String.join("; ", bad));
    }

    /**
     * 抓的用户故障：值写在下一行、键行上只有锚点或标签的项，在界面改值后旧文字并进新值。
     * <p>
     * 护栏格：键行只有锚点、标签也算「键行上没有值」之后，底下是续行文字的照旧整段换掉。
     */
    @Test
    @DisplayName("键行只有锚点或标签、值写在下一行的标量改值：启动读到新值, 不带旧文字")
    void anchoredScalarOnNextLineIsReplaced() throws IOException {
        List<String> bad = new ArrayList<>();

        String[] heads = {"signature: &a", "signature: !!str", "signature: &a !!str   # 说明"};
        for (String head : heads) {
            try {
                write("novabot:\n  demo:\n    " + head + "\n      old text\n    tail: 1\n");
                service.write(Map.of("novabot.demo.signature", "z"));
                assertEquals("z", service.readAsLoaded().get("novabot.demo.signature"), "启动读到的应是新值:\n" + content());
                assertEquals(1, loaded("novabot.demo.tail"), "下一个键不该被动:\n" + content());
            } catch (AssertionError | IOException | RuntimeException e) {
                bad.add(head + ": " + e.getMessage());
            }
        }

        assertTrue(bad.isEmpty(), () -> "锚点标量续行 " + bad.size() + " 问未销: " + String.join("; ", bad));
    }

    /**
     * 随包模板拿来就是第一份配置：界面读出的每一项都得是启动那一路读到的，原样整存一个字节不动。
     */
    @Test
    @DisplayName("随包模板：界面读出的各键与启动读到的逐键一致, 原样整存逐字节不变")
    void bundledTemplateReadsAsLoaded() throws IOException {
        Path root = Path.of("").toAbsolutePath();
        while (!Files.exists(root.resolve("build.sh")) || !Files.exists(root.resolve("pom.xml"))) {
            root = root.getParent();
        }
        Files.copy(root.resolve("dist/templates/application.example.yml"), config);
        String text = content();

        Map<String, String> shown = service.read();
        Map<String, Object> loaded = service.readAsLoaded();
        List<String> bad = new ArrayList<>();
        int compared = 0;
        for (Map.Entry<String, Object> entry : loaded.entrySet()) {
            // 对象名单的元素字段不走 read()，界面另有接口
            if (entry.getKey().matches(".*\\[\\d+]\\..*")) {
                continue;
            }
            compared++;
            Object value = entry.getValue();
            String expected = value instanceof List<?> ? joined(value) : String.valueOf(value);
            String actual = shown.getOrDefault(entry.getKey(), "");
            if (!expected.equals(actual)) {
                bad.add(entry.getKey() + " 界面[" + actual + "] 启动[" + expected + "]");
            }
        }
        for (Map.Entry<String, String> entry : shown.entrySet()) {
            // 只放过 webhook-headers 这一键，是这之前就有的老样子：模板写成 {}，启动那一路不产出属性，
            // 界面照字面显示；它是机密项，界面上遮着、原样送回时剔掉。别的键这样对不上照报
            boolean oldWebhookHeaders = entry.getKey().equals("novabot.core.alert.webhook-headers")
                    && entry.getValue().equals("{}");
            if (!loaded.containsKey(entry.getKey()) && !oldWebhookHeaders) {
                bad.add(entry.getKey() + " 界面有、启动那一路没有");
            }
        }

        int total = compared;
        assertTrue(total > 50, "模板比到的键太少, 比法可能空转: " + total);
        assertTrue(bad.isEmpty(), () -> "模板 " + total + " 键里 " + bad.size() + " 键对不上: " + String.join("; ", bad));
        // 机密项界面上遮着、原样送回时被 SensitiveFields.dropUnchanged 剔掉，整存那一路碰不到它们
        Map<String, String> resaved = new HashMap<>(shown);
        resaved.keySet().removeIf(key -> SensitiveFields.isSensitive(key, null));
        assertTrue(resaved.size() > 50, "整存的键太少, 比法可能空转: " + resaved.size());
        assertEquals(List.of(), service.write(resaved), "原样整存不算改动");
        assertEquals(text, content(), "原样整存文件一个字节不动");
    }

    /**
     * 抓的用户故障：口令是常见词时，日志说同目录别的件「还留着明文」，其实那些件只是正文里有这个词。
     */
    @Test
    @DisplayName("🔴 同目录备份实况按「键: 值」整行认：别的键或正文里有这个词不报, 备份里真留着才报")
    void backupSituationMatchesKeyValueLines() throws IOException {
        write("novabot:\n  core:\n    config-ui:\n      auth:\n        password: admin\n");
        Files.writeString(dir.resolve("notes.txt"), "默认账号是 admin，口令另发\n", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("other.yml"), "user: admin\npassword-hint: admin\n", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("application.yml.20260101-000000.bak"),
                "novabot:\n  core:\n    config-ui:\n      auth:\n        password: admin     # 登录口令\n",
                StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("application.yml.20260102-000000.bak"),
                "novabot:\n  core:\n    config-ui:\n      auth:\n        password: \"admin\"\n",
                StandardCharsets.UTF_8);

        String situation = startupSituation("admin");
        List<String> bad = new ArrayList<>();
        try {
            assertFalse(situation.contains("notes.txt"), "正文里有这个词不算留着明文: " + situation);
            assertFalse(situation.contains("other.yml"), "别的键的值是这个词不算留着明文: " + situation);
        } catch (AssertionError e) {
            bad.add("① 误报: " + e.getMessage());
        }
        try {
            assertTrue(situation.contains("application.yml.20260101-000000.bak"), "备份里真留着要报: " + situation);
            assertTrue(situation.contains("application.yml.20260102-000000.bak"), "带引号的也要报: " + situation);
        } catch (AssertionError e) {
            bad.add("② 漏报: " + e.getMessage());
        }

        assertTrue(bad.isEmpty(), () -> "备份实况 " + bad.size() + " 问未销: " + String.join("; ", bad));
    }

    /**
     * 按启动那一路把明文口令换成哈希写回，取日志里那句同目录实况
     */
    private String startupSituation(String password) {
        Logger logger = (Logger) LoggerFactory.getLogger(ConfigUiAuthService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            NovaCoreProperties.ConfigUi.Auth properties = new NovaCoreProperties.ConfigUi.Auth();
            properties.setPassword(password);
            properties.setTotp(false);
            ConfigUiAuthService auth = new ConfigUiAuthService(properties,
                    new ConfigUiSessionStore(Duration.ofHours(24), Duration.ofHours(2)),
                    new LoginThrottle(5, Duration.ofMinutes(15)), service);
            auth.start();
            return appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(message -> message.contains("改为哈希保存"))
                    .findFirst()
                    .orElse("（没有写回那一句）");
        } finally {
            logger.detachAppender(appender);
        }
    }
}
