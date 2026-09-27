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
            new ConfigUiAuthService(properties,
                    new ConfigUiSessionStore(Duration.ofHours(24), Duration.ofHours(2)),
                    new LoginThrottle(5, Duration.ofMinutes(15)), service);
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
