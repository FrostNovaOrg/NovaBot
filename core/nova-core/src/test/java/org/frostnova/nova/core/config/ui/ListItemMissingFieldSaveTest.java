package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 连接页保存时，配置文件里原本没有的字段（第一次没填、后来才填的 Token）要能写进那条连接元素
 * <p>
 * 抓的用户故障：第一次配机器人时 Token 留空，文件里就没有这一行；后来在机器人那边开了 Token，
 * 回连接页填上保存——当场连上了、提示「已保存」，重启后却连不上，因为那一行根本没写进文件，
 * 只能手改配置文件。清空过一次 Token 也是同样下场：那一行被删掉后，再填上同样写不回去。
 * <p>
 * 每格两件事一起断：用 YAML 库把存后的文件读回来，这条元素的字段对；
 * 除新插的行外，别的行与存前逐字节相同（拿整份行清单对）。
 * 以下的模板里 senders 键缩进 6 格、短横 8 格、元素字段 10 格。
 */
@DisplayName("连接元素里原本缺的字段：填上保存后能写进文件, 重启不再丢")
class ListItemMissingFieldSaveTest {
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

    private List<String> lines() throws IOException {
        return Files.readAllLines(config, StandardCharsets.UTF_8);
    }

    /** 存前的行清单在指定位置插入一行后该长什么样：新行插哪儿、别的行动没动，一并钉死 */
    private static List<String> withInserted(List<String> before, int at, String line) {
        List<String> expected = new ArrayList<>(before);
        expected.add(at, line);
        return expected;
    }

    /** 用 YAML 库读回的 onebot 那一节：读不通会在这里抛出来 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> onebot() throws IOException {
        Map<String, Object> root = new Yaml().load(Files.readString(config, StandardCharsets.UTF_8));
        return (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) root.get("novabot")).get("adapter")).get("onebot");
    }

    /** onebot.senders 里第 index 条元素 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> sender(int index) throws IOException {
        return (Map<String, Object>) ((List<Object>) onebot().get("senders")).get(index);
    }

    /**
     * 抓的用户故障：元素里没有 one-bot-websocket-token（第一次保存时留空，那一行从没写进文件），
     * 后来填上保存，返回的改动数是 0、文件一字不动，重启后 Token 丢掉。
     */
    @Test
    @DisplayName("🔴 元素里原本没有的 Token：填上保存后写进这条元素, 计一个改动, 别的行一字不动")
    void missingTokenFieldIsWrittenIntoTheItem() throws IOException {
        write("""
                novabot:
                  adapter:
                    onebot:
                      senders:
                        - name: qq-onebot
                          api: /send
                          one-bot-address: 127.0.0.1
                          delay: 1000
                """);
        List<String> before = lines();

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("name", "qq-onebot");
        fields.put("api", "/send");
        fields.put("one-bot-address", "127.0.0.1");
        fields.put("one-bot-websocket-token", "fake-ws-token-not-real-01");
        fields.put("delay", "1000");

        int changed = service.writeListItemFields("novabot.adapter.onebot.senders", 0, fields);

        assertEquals(1, changed, "没变的字段写回去不算改动, 只有新插的 Token 一项");
        assertEquals(withInserted(before, before.size(), "          one-bot-websocket-token: fake-ws-token-not-real-01"),
                lines(), "新行该插在这条元素最后一个字段之后, 缩进与其他字段对齐");

        Map<String, Object> sender = sender(0);
        assertEquals("fake-ws-token-not-real-01", sender.get("one-bot-websocket-token"),
                "YAML 读回: Token 已在这条元素里");
        assertEquals("qq-onebot", sender.get("name"));
        assertEquals("1000", String.valueOf(sender.get("delay")));
        assertEquals(5, sender.size(), "这条元素该是原有四项加新插的一项, 没多没少: " + sender);
    }

    /**
     * 抓的用户故障：清空过一次 Token（那一行按「空值＝删行」被删掉），之后再填上保存，
     * 写不回文件，重启后 Token 丢掉。
     */
    @Test
    @DisplayName("🔴 清空过一次的 Token 再填上：那一行又回来了, 值是新的")
    void clearedTokenFieldComesBackOnNextSave() throws IOException {
        write("""
                novabot:
                  adapter:
                    onebot:
                      senders:
                        - name: qq-onebot
                          api: /send
                          one-bot-websocket-token: old-fake-ws-token-not-real-02
                          delay: 1000
                """);

        Map<String, String> clearing = new LinkedHashMap<>();
        clearing.put("name", "qq-onebot");
        clearing.put("api", "/send");
        clearing.put("one-bot-websocket-token", "");
        clearing.put("delay", "1000");
        assertEquals(1, service.writeListItemFields("novabot.adapter.onebot.senders", 0, clearing),
                "清空 Token 该删那一行, 计一个改动");
        List<String> cleared = lines();
        assertFalse(cleared.stream().anyMatch(l -> l.contains("one-bot-websocket-token")),
                "清空后文件里不该有这一行:\n" + String.join("\n", cleared));

        Map<String, String> refilling = new LinkedHashMap<>();
        refilling.put("name", "qq-onebot");
        refilling.put("api", "/send");
        refilling.put("one-bot-websocket-token", "fake-ws-token-not-real-02b");
        refilling.put("delay", "1000");
        assertEquals(1, service.writeListItemFields("novabot.adapter.onebot.senders", 0, refilling),
                "再填上该把这一行插回去, 计一个改动");

        assertEquals(withInserted(cleared, cleared.size(), "          one-bot-websocket-token: fake-ws-token-not-real-02b"),
                lines(), "除新插的一行外, 清空后的其他行一字不动");
        Map<String, Object> sender = sender(0);
        assertEquals("fake-ws-token-not-real-02b", sender.get("one-bot-websocket-token"),
                "YAML 读回: 这一项又在了, 值是新的");
        assertEquals("qq-onebot", sender.get("name"));
        assertEquals("1000", String.valueOf(sender.get("delay")));
    }

    /**
     * 抓的用户故障：元素只有短横那一行带着字段（{@code - name: qq-onebot}）时，新行若按短横对齐，
     * 整份文件读不了，重启直接掉进安全模式。
     */
    @Test
    @DisplayName("🔴 元素只有短横那一行带字段：新行按字段那一列对齐, 读回不报错, 字段仍在这条元素里")
    void insertedFieldAlignsWithFieldColumnWhenOnlyTheDashLineHasFields() throws IOException {
        write("""
                novabot:
                  adapter:
                    onebot:
                      senders:
                        - name: qq-onebot
                """);
        List<String> before = lines();

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("name", "qq-onebot");
        fields.put("one-bot-websocket-token", "fake-ws-token-not-real-03");

        int changed = service.writeListItemFields("novabot.adapter.onebot.senders", 0, fields);

        assertEquals(1, changed);
        // 10 个空格＝字段那一列（短横在第 8 列）, 对到短横上的话 YAML 读不回来
        assertEquals(withInserted(before, before.size(), "          one-bot-websocket-token: fake-ws-token-not-real-03"),
                lines(), "新行该按字段那一列对齐, 不是按短横");

        Map<String, Object> sender = sender(0);
        assertEquals("fake-ws-token-not-real-03", sender.get("one-bot-websocket-token"),
                "YAML 读回不报错, Token 仍在这条元素里");
        assertEquals("qq-onebot", sender.get("name"));
        assertEquals(2, sender.size(), "这条元素该只有名字与 Token 两项: " + sender);
    }

    /**
     * 抓的用户故障：元素末尾与下一条元素（或上一层的键）之间隔着注释、空行时，
     * 新行插到它们后面会挂着别人的说明、甚至落到下一条元素或上一层去。
     */
    @Test
    @DisplayName("🔴 后面隔着注释跟着下一条元素或上一层的键：新行插在元素自己的字段后面, 读回仍属于第 1 条")
    void insertedFieldStaysInTheRightItemBeforeTrailingComments() throws IOException {
        List<String> bad = new ArrayList<>();

        // ① 后面隔着注释是下一条元素
        try {
            write("""
                    novabot:
                      adapter:
                        onebot:
                          senders:
                            - name: qq-onebot
                              api: /send
                          # 第二台机器人
                            - name: qq-two
                              api: /send2
                    """);
            List<String> before = lines();
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("name", "qq-onebot");
            fields.put("api", "/send");
            fields.put("one-bot-websocket-token", "fake-ws-token-not-real-04a");
            int changed = service.writeListItemFields("novabot.adapter.onebot.senders", 0, fields);
            assertEquals(1, changed, "只有新插的 Token 算改动");
            assertEquals(withInserted(before, 6, "          one-bot-websocket-token: fake-ws-token-not-real-04a"),
                    lines(), "新行该插在第 1 条元素自己的字段之后、给第 2 条的注释之前");
            Map<String, Object> first = sender(0);
            assertEquals("fake-ws-token-not-real-04a", first.get("one-bot-websocket-token"),
                    "YAML 读回: Token 属于第 1 条元素");
            Map<String, Object> second = sender(1);
            assertFalse(second.containsKey("one-bot-websocket-token"),
                    "Token 不许跑到第 2 条元素去: " + second);
            assertEquals("qq-two", second.get("name"), "第 2 条元素照旧");
        } catch (AssertionError | IOException | RuntimeException e) {
            bad.add("① 下一条元素: " + e.getMessage());
        }

        // ② 后面隔着空行与注释是上一层的键
        try {
            write("""
                    novabot:
                      adapter:
                        onebot:
                          senders:
                            - name: qq-onebot
                              api: /send

                          # 自动探测
                          detect:
                            enable-http-detect: true
                    """);
            List<String> before = lines();
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("name", "qq-onebot");
            fields.put("api", "/send");
            fields.put("one-bot-websocket-token", "fake-ws-token-not-real-04b");
            int changed = service.writeListItemFields("novabot.adapter.onebot.senders", 0, fields);
            assertEquals(1, changed, "只有新插的 Token 算改动");
            assertEquals(withInserted(before, 6, "          one-bot-websocket-token: fake-ws-token-not-real-04b"),
                    lines(), "新行该插在第 1 条元素自己的字段之后、空行与注释之前");
            Map<String, Object> first = sender(0);
            assertEquals("fake-ws-token-not-real-04b", first.get("one-bot-websocket-token"),
                    "YAML 读回: Token 属于第 1 条元素");
            assertEquals("true", String.valueOf(((Map<?, ?>) onebot().get("detect")).get("enable-http-detect")),
                    "上一层的键照旧挂在上一层的下面, 不许被顶掉");
        } catch (AssertionError | IOException | RuntimeException e) {
            bad.add("② 上一层的键: " + e.getMessage());
        }

        assertTrue(bad.isEmpty(), () -> String.join("\n", bad));
    }

    /** 对照格：已有键的改值、空值且键不存在, 这两样行为与从前一致 */
    @Test
    @DisplayName("对照：已有键改值照旧只改那一行; 值为空且键不存在时文件一字不动、返回 0")
    void changingExistingKeyAndSkippingEmptyAbsentKey() throws IOException {
        write("""
                novabot:
                  adapter:
                    onebot:
                      senders:
                        - name: qq-onebot
                          api: /send
                          one-bot-websocket-token: fake-ws-token-not-real-05
                          delay: 1000
                """);
        List<String> before = lines();

        int changed = service.writeListItemFields("novabot.adapter.onebot.senders", 0,
                Map.of("one-bot-websocket-token", "fake-ws-token-not-real-05-new"));

        assertEquals(1, changed);
        List<String> after = lines();
        assertEquals(before.size(), after.size(), "改值不动行数");
        for (int i = 0; i < before.size(); i++) {
            if (i == 6) {
                assertEquals("          one-bot-websocket-token: fake-ws-token-not-real-05-new", after.get(i),
                        "只有 Token 那一行换成新值");
            } else {
                assertEquals(before.get(i), after.get(i), "别的行一字不动: " + before.get(i));
            }
        }
        assertEquals("fake-ws-token-not-real-05-new", sender(0).get("one-bot-websocket-token"));

        // 值为空、元素里也没有这个键：不插空值行, 文件一个字节不动
        write("""
                novabot:
                  adapter:
                    onebot:
                      senders:
                        - name: qq-onebot
                          api: /send
                          delay: 1000
                """);
        String absent = Files.readString(config, StandardCharsets.UTF_8);
        assertEquals(0, service.writeListItemFields("novabot.adapter.onebot.senders", 0,
                Map.of("one-bot-http-token", "")), "空值且键不存在不计改动");
        assertEquals(absent, Files.readString(config, StandardCharsets.UTF_8), "文件一字不动");
    }
}
