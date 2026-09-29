package org.frostnova.nova.core.config.ui;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.protocol.EventStreamTokenService;
import org.frostnova.nova.core.service.PushTemplateDefaults;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 手写的跨行、锚点与别名写法：界面显示的是启动读到的值，改别的项不连累，改不了就明说
 * <p>
 * 设置页只收键那一行冒号后的片段：块标量显示成「|」，跨行的值只剩半截，
 * 别名显示成「*w」，经合并键继承的子键显示成默认值。判据一律拿启动那一路
 * （{@link ConfigurationFileService#readAsLoaded()}）读出来的当准。
 */
@DisplayName("手写写法：界面显示启动读到的值")
class HandWrittenValuesAsLoadedTest {
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
     * 启动那一路读到的值，按界面的写法摊成文字：名单每项一行
     */
    private String loadedText(String key) throws IOException {
        Object value = service.readAsLoaded().get(key);
        if (value instanceof List<?> list) {
            List<String> items = new ArrayList<>();
            list.forEach(item -> items.add(String.valueOf(item)));
            return String.join("\n", items);
        }
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 各例逐个写文件、比「界面读出」与「启动读到」；返回对不上的那几条
     */
    private List<String> mismatches(String key, String[][] cases) {
        List<String> bad = new ArrayList<>();
        for (String[] c : cases) {
            try {
                write(c[1]);
                String expected = loadedText(key);
                assertNotNull(expected, "启动那一路没读到这一项, 样本本身写错了:\n" + content());
                assertEquals(expected, service.read().get(key), "界面读出的与启动读到的不符:\n" + content());
            } catch (AssertionError | IOException | RuntimeException e) {
                bad.add(c[0] + ": " + e.getMessage());
            }
        }
        return bad;
    }

    /**
     * 抓的用户故障：值写成块标量（{@code |}、{@code >}），设置页上这一项显示成一个「|」；
     * 值里带换行的，单行框装不下，得标成界面不能改、说明去配置文件里改。
     */
    @Test
    @DisplayName("🔴 块标量：界面显示＝启动读到的值, 带换行的标成界面不能改并附说明")
    void blockScalarShowsLoadedValue() throws IOException {
        String key = "novabot.demo.signature";
        String[][] cases = {
                {"| 字面", "novabot:\n  demo:\n    signature: |\n      first\n      second\n    tail: 1\n"},
                {"> 折叠", "novabot:\n  demo:\n    signature: >\n      first\n      second\n    tail: 1\n"},
                {">- 折叠去尾换行", "novabot:\n  demo:\n    signature: >-\n      first\n      second\n    tail: 1\n"},
        };
        List<String> bad = mismatches(key, cases);

        try {
            write(cases[0][1]);
            String reason = service.uiLocked().get(key);
            assertNotNull(reason, "值里带换行, 单行框装不下, 应标成界面不能改");
            assertTrue(reason.contains("配置文件"), "说明里应指明去配置文件里改: " + reason);
            write(cases[2][1]);
            assertFalse(service.uiLocked().containsKey(key), "折成一行的装得下, 不该标成不能改");
        } catch (AssertionError | IOException | RuntimeException e) {
            bad.add("只读标: " + e.getMessage());
        }

        assertTrue(bad.isEmpty(), () -> "块标量 " + bad.size() + " 问未销: " + String.join("; ", bad));
    }

    /**
     * 抓的用户故障：双引号里的值折到了下一行，设置页上只显示前半截、还带着一个引号。
     */
    @Test
    @DisplayName("🔴 双引号值跨行：界面显示＝启动读到的值")
    void quotedAcrossLinesShowsLoadedValue() {
        String[][] cases = {
                {"双引号", "novabot:\n  demo:\n    signature: \"first\n      second line\"\n    tail: 1\n"},
                {"单引号", "novabot:\n  demo:\n    signature: 'first\n      second line'\n    tail: 1\n"},
        };
        List<String> bad = mismatches("novabot.demo.signature", cases);
        assertTrue(bad.isEmpty(), () -> "引号跨行 " + bad.size() + " 问未销: " + String.join("; ", bad));
    }

    /**
     * 抓的用户故障：值写在键的下一行，设置页显示成默认值；键行上只写了锚点的，显示成「&a」。
     */
    @Test
    @DisplayName("🔴 值写在下一行（含键行只有锚点、标签）：界面显示＝启动读到的值")
    void valueOnNextLineShowsLoadedValue() {
        String[][] cases = {
                {"键行无值", "novabot:\n  demo:\n    signature:\n      old text\n    tail: 1\n"},
                {"键行只有锚点", "novabot:\n  demo:\n    signature: &a\n      old text\n    tail: 1\n"},
                {"不带引号跨行", "novabot:\n  demo:\n    signature: first\n      second line\n    tail: 1\n"},
        };
        List<String> bad = mismatches("novabot.demo.signature", cases);
        assertTrue(bad.isEmpty(), () -> "下一行的值 " + bad.size() + " 问未销: " + String.join("; ", bad));
    }

    /**
     * 抓的用户故障：名单里某一项折到了下一行，设置页上这一项只显示第一行那段。
     */
    @Test
    @DisplayName("🔴 名单项跨行：界面显示＝启动读到的整份名单")
    void listItemAcrossLinesShowsLoadedValue() {
        String[][] cases = {
                {"首项跨行", "novabot:\n  demo:\n    words:\n      - first\n        more\n      - b\n    tail: 1\n"},
                {"引号项跨行", "novabot:\n  demo:\n    words:\n      - a\n      - \"quoted\n        rest\"\n    tail: 1\n"},
        };
        List<String> bad = mismatches("novabot.demo.words", cases);
        assertTrue(bad.isEmpty(), () -> "名单项跨行 " + bad.size() + " 问未销: " + String.join("; ", bad));
    }

    /**
     * 抓的用户故障：值写成别名 {@code *w}，设置页显示字面「*w」，而程序读到的是锚点那一项的值。
     */
    @Test
    @DisplayName("🔴 别名作值：界面显示＝启动读到的值, 引到名单的标成界面不能改")
    void aliasShowsLoadedValue() throws IOException {
        String key = "novabot.demo.copy";
        String[][] cases = {
                {"别名引标量", "novabot:\n  demo:\n    greeting: &w hello\n    copy: *w\n"},
                {"别名引名单", "novabot:\n  demo:\n    words: &l\n      - a\n      - b\n    copy: *l\n"},
        };
        List<String> bad = mismatches(key, cases);

        try {
            write(cases[1][1]);
            assertNotNull(service.uiLocked().get(key), "别名引到名单, 界面写回会拆掉引用, 应标成界面不能改");
            write(cases[0][1]);
            assertFalse(service.uiLocked().containsKey(key), "别名引标量改得了, 不该标成不能改");
        } catch (AssertionError | IOException | RuntimeException e) {
            bad.add("只读标: " + e.getMessage());
        }

        assertTrue(bad.isEmpty(), () -> "别名 " + bad.size() + " 问未销: " + String.join("; ", bad));
    }

    /**
     * 抓的用户故障：一块设置经合并键 {@code <<: *b} 从别处继承，设置页上这些项显示成默认值。
     */
    @Test
    @DisplayName("🔴 合并键继承的子键：界面显示＝启动读到的值, 自己写了的压过继承的")
    void mergedChildShowsLoadedValue() throws IOException {
        write("novabot:\n  demo:\n    base: &b\n      color: red\n      size: 2\n"
                + "    item:\n      <<: *b\n      size: 3\n");
        List<String> bad = new ArrayList<>();
        Map<String, String> shown = service.read();
        for (String key : List.of("novabot.demo.item.color", "novabot.demo.item.size")) {
            try {
                assertEquals(loadedText(key), shown.get(key), key + " 界面读出的与启动读到的不符");
            } catch (AssertionError | IOException | RuntimeException e) {
                bad.add(e.getMessage());
            }
        }
        assertTrue(bad.isEmpty(), () -> "合并键 " + bad.size() + " 问未销: " + String.join("; ", bad));
    }

    /**
     * 抓的用户故障：块标量或跨行值的内容里有一行像「词: 值」，被当成一个配置键，
     * 这一项在界面上的值与名单都跟着乱。
     */
    @Test
    @DisplayName("🔴 续行与块标量内容里的「词: 」不当成键")
    void colonInsideContinuationIsNotAKey() {
        String[][] cases = {
                {"块标量", "novabot:\n  demo:\n    signature: |\n      hello: world\n      - item\n    tail: 1\n"},
                {"双引号跨行", "novabot:\n  demo:\n    signature: \"first\n      say: hi\"\n    tail: 1\n"},
        };
        List<String> bad = new ArrayList<>(mismatches("novabot.demo.signature", cases));
        for (String[] c : cases) {
            try {
                write(c[1]);
                List<String> phantom = service.read().keySet().stream()
                        .filter(k -> k.startsWith("novabot.demo.signature.")).toList();
                assertEquals(List.of(), phantom, "内容行被当成了键");
                assertEquals("1", service.read().get("novabot.demo.tail"), "下一个键应照常读出");
            } catch (AssertionError | IOException | RuntimeException e) {
                bad.add(c[0] + " 键: " + e.getMessage());
            }
        }
        assertTrue(bad.isEmpty(), () -> "内容行当键 " + bad.size() + " 问未销: " + String.join("; ", bad));
    }

    /**
     * 抓的用户故障：名单里有一项折到了下一行，使用者在界面只改了同一名单里的另一项，
     * 保存后那一项的续行悄悄没了，程序读到的从「首段＋续行」变成只剩首段。
     */
    @Test
    @DisplayName("🔴 名单项跨行：改同名单别的项后, 这一项启动值不变（或整批拒存、文件不动）")
    void editingSiblingKeepsCrossLineItem() throws IOException {
        String key = "novabot.demo.words";
        write("novabot:\n  demo:\n    words:\n      - first\n        more\n      - b\n    tail: 1\n");
        String text = content();
        Object before = ((List<?>) service.readAsLoaded().get(key)).get(0);

        // 照界面那样改：拿界面读出的整份名单, 只换最后一项
        List<String> items = new ArrayList<>(Arrays.asList(service.read().get(key).split("\n", -1)));
        items.set(items.size() - 1, "z");
        try {
            service.write(Map.of(key, String.join("\n", items)));
        } catch (IOException rejected) {
            assertEquals(text, content(), "拒存时文件一个字节不动");
            return;
        }
        List<?> after = (List<?>) service.readAsLoaded().get(key);
        assertEquals(before, after.get(0), "没改的那一项启动值变了:\n" + content());
        assertEquals("z", after.get(after.size() - 1), "改的那一项应是新值:\n" + content());
    }

    /**
     * 抓的用户故障：一项上带着锚点、文件别处用别名引用它，在界面改了这一项，
     * 写回后锚点没了、别名悬空，整份配置读不了，程序起不来；或者引用它的项跟着悄悄变了。
     */
    @Test
    @DisplayName("🔴 锚点被别处引用：改了带锚点的项, 文件照样读得通、引用它的项不变（或整批拒存、文件不动）")
    void editingReferencedAnchorKeepsFileReadable() throws IOException {
        write("novabot:\n  demo:\n    greeting: &w hello\n    copy: *w\n    tail: 1\n");
        String text = content();
        try {
            service.write(Map.of("novabot.demo.greeting", "hi"));
        } catch (IOException rejected) {
            assertEquals(text, content(), "拒存时文件一个字节不动");
            assertTrue(rejected.getMessage().contains("本批全部未保存"), "拒存要说清整批没存: " + rejected.getMessage());
            return;
        }
        Map<String, Object> loaded;
        try {
            loaded = service.readAsLoaded();
        } catch (IOException unreadable) {
            throw new AssertionError("写回后文件读不通:\n" + content(), unreadable);
        }
        assertEquals("hi", loaded.get("novabot.demo.greeting"), "改的那一项应是新值:\n" + content());
        assertEquals("hello", loaded.get("novabot.demo.copy"), "引用它的项不该跟着变:\n" + content());
    }

    /**
     * 抓的用户故障：一块设置经合并键继承，在界面改其中一个继承来的子键，
     * 保存后启动读到的不是改的值、或同块别的继承项悄悄没了、或文件读不通。
     */
    @Test
    @DisplayName("🔴 合并键子键：改后启动读到改的值、同块别的项不变（或整批拒存、文件不动）")
    void editingMergedChildTakesEffect() throws IOException {
        String[][] cases = {
                {"合并键", "novabot:\n  demo:\n    base: &b\n      color: red\n      size: 2\n"
                        + "    item:\n      <<: *b\n      size: 3\n", "novabot.demo.item.color", "novabot.demo.item.size"},
                {"合并进来的是一整块", "novabot:\n  demo:\n    base: &b\n      style:\n        color: red\n        size: 2\n"
                        + "    item:\n      <<: *b\n", "novabot.demo.item.style.color", "novabot.demo.item.style.size"},
                {"整块用别名", "novabot:\n  demo:\n    base: &b\n      color: red\n      size: 2\n"
                        + "    item: *b\n", "novabot.demo.item.color", "novabot.demo.item.size"},
        };
        List<String> bad = new ArrayList<>();
        for (String[] c : cases) {
            try {
                write(c[1]);
                String text = content();
                Map<String, Object> before = service.readAsLoaded();
                try {
                    service.write(Map.of(c[2], "blue"));
                } catch (IOException rejected) {
                    assertEquals(text, content(), "拒存时文件一个字节不动");
                    continue;
                }
                Map<String, Object> after;
                try {
                    after = service.readAsLoaded();
                } catch (IOException unreadable) {
                    throw new AssertionError("写回后文件读不通:\n" + content(), unreadable);
                }
                assertEquals("blue", after.get(c[2]), "启动读到的应是改的值:\n" + content());
                assertEquals(before.get(c[3]), after.get(c[3]), "同块别的项不该变:\n" + content());
                assertEquals(before.get("novabot.demo.base.color"), after.get("novabot.demo.base.color"),
                        "被继承的那一块不该变:\n" + content());
            } catch (AssertionError | IOException | RuntimeException e) {
                bad.add(c[0] + ": " + e.getMessage());
            }
        }
        assertTrue(bad.isEmpty(), () -> "合并键子键 " + bad.size() + " 问未销: " + String.join("; ", bad));
    }

    /**
     * 抓的用户故障：一项写成引用别处一整块的别名（{@code item: *b}），在设置页给它填值被拒存，
     * 说明里没写出 {@code *b}，使用者在配置文件里对不上是哪个别名。
     */
    @Test
    @DisplayName("🔴 整块别名拒存：说明里点出配置文件里写的别名名")
    void wholeBlockAliasRejectionNamesTheAlias() throws IOException {
        write("novabot:\n  demo:\n    base: &b\n      color: red\n      size: 2\n"
                + "    item: *b\n");
        String text = content();
        try {
            service.write(Map.of("novabot.demo.item", "blue"));
            throw new AssertionError("给整块别名填值应拒存, 文件不该被改");
        } catch (IOException rejected) {
            assertEquals(text, content(), "拒存时文件一个字节不动");
            assertTrue(rejected.getMessage().contains("别名 *b,"),
                    "拒存说明要点出配置文件里写的别名 *b: " + rejected.getMessage());
        }
    }

    /**
     * 抓的用户故障：别名写在键的下一行（{@code item:} 换行 {@code *b}），在设置页给它填值被拒存，
     * 说明里没写出 {@code *b}，使用者在配置文件里对不上是哪个别名。
     */
    @Test
    @DisplayName("🔴 值在下一行的整块别名拒存：说明里点出别名名")
    void wholeBlockAliasOnNextLineRejectionNamesTheAlias() throws IOException {
        write("novabot:\n  demo:\n    base: &b\n      color: red\n      size: 2\n"
                + "    item:\n      *b\n");
        String text = content();
        try {
            service.write(Map.of("novabot.demo.item", "blue"));
            throw new AssertionError("给整块别名填值应拒存, 文件不该被改");
        } catch (IOException rejected) {
            assertEquals(text, content(), "拒存时文件一个字节不动");
            assertTrue(rejected.getMessage().contains("别名 *b,"),
                    "拒存说明要点出配置文件里写的别名 *b: " + rejected.getMessage());
        }
    }

    /**
     * 抓的用户故障：设置页上这一项的说明写着「别名（*名字）」，三个字是占位，没换成配置文件里写的别名。
     */
    @Test
    @DisplayName("🔴 只读说明点出配置文件里的别名, 不出现占位")
    void lockReasonNamesTheAlias() throws IOException {
        write("novabot:\n  demo:\n    base: &b\n      color: red\n      size: 2\n"
                + "    item:\n      *b\n");
        String block = service.uiLocked().get("novabot.demo.item");
        assertNotNull(block, "整块别名应标成界面不能改");
        assertTrue(block.contains("（*b）"), "只读说明要点出别名 *b: " + block);
        assertFalse(block.contains("*名字"), "只读说明不该留下占位: " + block);

        write("novabot:\n  demo:\n    words: &l\n      - a\n      - b\n    copy: *l\n");
        String list = service.uiLocked().get("novabot.demo.copy");
        assertNotNull(list, "别名引到的名单应标成界面不能改");
        assertTrue(list.contains("（*l）"), "只读说明要点出别名 *l: " + list);
        assertFalse(list.contains("*名字"), "只读说明不该留下占位: " + list);
    }

    /**
     * 抓的用户故障：界面不能改的项，读数里没有那一栏，设置页照常摆一个能打字的框。
     */
    @Test
    @DisplayName("🔴 界面值出口带上「界面不能改」那一栏")
    @SuppressWarnings("unchecked")
    void valuesEndpointCarriesLocks() throws IOException {
        write("novabot:\n  demo:\n    signature: |\n      first\n      second\n");
        NovaCoreProperties properties = new NovaCoreProperties();
        properties.getLive().setLiveDataPath(dir.resolve("data.json").toString());

        ConfigUiController controller = new ConfigUiController(
                new ConfigurationMetadataService(),
                service,
                properties,
                mock(org.frostnova.nova.core.datasource.AbstractDataSource.class),
                mock(org.springframework.beans.factory.ObjectProvider.class),
                mock(ConfigurationValidator.class),
                mock(org.frostnova.nova.core.service.NovaSenderService.class),
                mock(org.frostnova.nova.core.sender.NovaMessageSender.class),
                mock(org.springframework.beans.factory.ObjectProvider.class),
                mock(org.frostnova.nova.core.health.PushActivityRecorder.class),
                mock(org.frostnova.nova.core.service.NovaEventHandlerService.class),
                mock(org.frostnova.nova.core.datasource.DataSourceServiceRegistry.class),
                mock(ConfigurationLevelResolver.class),
                new ConfigurationLabelResolver(mock(org.springframework.context.ApplicationContext.class)),
                new ConfigurationEffectResolver(mock(org.springframework.context.ApplicationContext.class)),
                new ConfigurationDangerResolver(mock(org.springframework.context.ApplicationContext.class)),
                RuntimeConfigurationApplier.bench(properties).build(),
                mock(org.springframework.beans.factory.ObjectProvider.class),
                mock(org.springframework.beans.factory.ObjectProvider.class),
                new EventStreamTokenService(properties.getLive()),
                mock(org.springframework.beans.factory.ObjectProvider.class),
                mock(org.frostnova.nova.core.sender.PushGate.class),
                mock(org.frostnova.nova.core.service.LiveDataService.class),
                mock(org.frostnova.nova.core.timeline.TimelineStore.class),
                mock(org.frostnova.nova.core.config.ui.auth.ConfigUiAuthService.class),
                new PushTemplateDefaults(new NovaCoreProperties()),
                mock(UpdateCheckService.class));

        JSONObject result = controller.values();
        assertTrue(result.getBooleanValue("success"), "读配置本身不该失败: " + result);
        Object locked = result.get("locked");
        assertTrue(locked instanceof Map<?, ?>, "读数里没有「界面不能改」那一栏: " + result.keySet());
        assertNotNull(((Map<String, Object>) locked).get("novabot.demo.signature"), "带换行的块标量应在那一栏里: " + locked);
    }

    /**
     * 抓的用户故障：键和写在下一行的别名之间夹了一行注释，设置页当这项没有值，
     * 填上字保存后，配置文件里原来引用的那一整块设置没了。
     */
    @Test
    @DisplayName("🔴 键与下一行别名之间夹一行注释：认得出别名、锁住，填值被拒，文件不动")
    void aliasOnNextLineWithCommentBetweenIsLockedAndRejected() throws IOException {
        String[] texts = {
                "novabot:\n  demo:\n    base: &b\n      color: red\n      size: 2\n"
                        + "    item:\n    # 说明\n      *b\n",
                "novabot:\n  demo:\n    base: &b\n      color: red\n      size: 2\n"
                        + "    item:\n# 说明\n      *b\n",
        };
        for (String text : texts) {
            write(text);
            String before = content();
            String block = service.uiLocked().get("novabot.demo.item");
            assertNotNull(block, "夹着注释也应认出是别名、标成界面不能改:\n" + before);
            assertTrue(block.contains("（*b）"), "只读说明要点出别名 *b: " + block);
            try {
                service.write(Map.of("novabot.demo.item", "blue"));
                throw new AssertionError("给这一项填值应拒存, 文件不该被改:\n" + content());
            } catch (IOException rejected) {
                assertEquals(before, content(), "拒存时文件一个字节不动");
                assertTrue(rejected.getMessage().contains("别名 *b,"),
                        "拒存说明要点出别名 *b: " + rejected.getMessage());
            }
        }
    }

    /**
     * 抓的用户故障：下一行是一行名单、其中一项是别名，设置页的说明却写这项本身写成了别名，
     * 按说明去配置文件里对不上。
     */
    @Test
    @DisplayName("🔴 行内名单含别名项：说明照实说名单里有一项是别名")
    void flowListAliasItemIsDescribedAsSuch() throws IOException {
        write("novabot:\n  demo:\n    one: &s hello\n    copy:\n      [*s, x]\n");
        String reason = service.uiLocked().get("novabot.demo.copy");
        assertNotNull(reason, "名单里有别名项应标成界面不能改");
        assertTrue(reason.contains("名单里有一项是别名"), "说明应照实说: " + reason);
        assertTrue(reason.contains("（*s）"), "能点出名字就点出: " + reason);
        assertFalse(reason.contains("写成了别名"), "不要说成这一项本身写成了别名: " + reason);
    }

    /**
     * 抓的用户故障：跨行的值后面跟了一行与键对齐的注释，在界面改了这项的值，
     * 这行注释被一起删掉，或者挪到别的地方。
     * <p>
     * 值里带换行的块标量是界面改不了的项，写口照锁拒存、改不了值；这一格用折成一行的
     * 跨行值当夹具，守的还是同一条：改值时对齐的注释留原地。
     */
    @Test
    @DisplayName("🔴 跨行的值后跟一行与键对齐的注释：改值时这行注释留在原地")
    void blockScalarKeepsFollowingAlignedComment() throws IOException {
        write("novabot:\n  demo:\n    signature: first\n      second\n    # 说明\n    tail: 1\n");
        service.write(Map.of("novabot.demo.signature", "z"));
        String text = content();
        assertEquals("novabot:\n  demo:\n    signature: z\n    # 说明\n    tail: 1\n", text,
                "注释应留在这项和下一项之间, 不该被吞或挪走");
        assertEquals("z", String.valueOf(service.readAsLoaded().get("novabot.demo.signature")));
        assertEquals(1, ((Number) service.readAsLoaded().get("novabot.demo.tail")).intValue());
    }

    /**
     * 抓的用户故障：名单里有一项是加了引号、以星号开头的文字，设置页把整份名单锁住，
     * 说明还让人去配置文件里找一个并不存在的别名。
     */
    @Test
    @DisplayName("🔴 行内名单里带引号的星号开头文字：不锁、不说成别名，改了照常保存")
    void quotedStarInFlowListIsPlainText() throws IOException {
        String key = "novabot.demo.copy";
        String[] texts = {
                "novabot:\n  demo:\n    one: &s hello\n    copy:\n      [\"*s\", x]\n",
                "novabot:\n  demo:\n    one: &s hello\n    copy:\n      ['*s', x]\n",
                "novabot:\n  demo:\n    one: &s hello\n    copy: [\"*s\", x]\n",
                "novabot:\n  demo:\n    one: &s hello\n    copy: ['*s', x]\n",
        };
        for (String text : texts) {
            write(text);
            String reason = service.uiLocked().get(key);
            assertFalse(service.uiLocked().containsKey(key),
                    "加了引号的是普通文字, 不该锁、不该说成别名: " + reason);
            assertEquals(List.of("*s", "x"), service.readAsLoaded().get(key),
                    "读回应是引号里的文字, 不是别名指到的 hello:\n" + content());
            assertEquals("*s\nx", service.read().get(key), "界面显示应与启动读到的一致");

            service.write(Map.of(key, "*s\nz"));
            assertEquals(List.of("*s", "z"), service.readAsLoaded().get(key),
                    "改了这份名单应照常保存, 读回与文件一致:\n" + content());
            assertEquals("*s\nz", service.read().get(key), "保存后再读应与启动读到的一致:\n" + content());
            assertEquals("hello", service.readAsLoaded().get("novabot.demo.one"),
                    "旁边那项不该被牵连:\n" + content());
        }
    }

    /**
     * 抓的用户故障：名单里有一项是加了引号、以星号开头的文字，旁边还有嵌套名单、项上的锚点或标签，
     * 或是程序会另读成别的值的写法。设置页应照旧锁住，存一个字不改文件；
     * 锁住时这一格显示的是程序实际读到的值。
     */
    @Test
    @DisplayName("🔴 带引号的星号项旁有嵌套名单、项上锚点或标签：照旧锁住，界面显示程序读到的值，存值不改文件")
    void quotedStarBesideNestedAnchorOrTagStaysLocked() throws IOException {
        String key = "novabot.demo.copy";
        String[][] cases = {
                {"嵌套名单", "novabot:\n  demo:\n    copy:\n      [\"*s\", [a, b]]\n    tail: 1\n", ""},
                {"项上锚点", "novabot:\n  demo:\n    copy:\n      [\"*s\", &x y]\n    tail: 1\n", "*s\ny"},
                {"项上标签", "novabot:\n  demo:\n    copy:\n      [\"*s\", !!str 1]\n    tail: 1\n", "*s\n1"},
                {"yes", "novabot:\n  demo:\n    copy:\n      [\"*s\", yes]\n    tail: 1\n", "*s\ntrue"},
        };
        List<String> bad = new ArrayList<>();
        for (String[] c : cases) {
            try {
                write(c[1]);
                String before = content();
                String reason = service.uiLocked().get(key);
                if (reason == null || !reason.contains("写成了别名") || reason.contains("名单里有一项是别名")) {
                    bad.add(c[0] + ": 应照别名整项锁住, 实际: " + reason + " 界面=" + service.read().get(key));
                    continue;
                }
                if (!c[2].isEmpty()) {
                    String shown = service.read().get(key);
                    if (!c[2].equals(shown)) {
                        bad.add(c[0] + ": 锁住时界面应显示程序读到的值 "
                                + c[2].replace("\n", "/") + ", 实际: "
                                + String.valueOf(shown).replace("\n", "/"));
                    }
                }
                try {
                    service.write(Map.of(key, "z"));
                    bad.add(c[0] + ": 存一个字应拒存, 文件不该被改:\n" + content());
                } catch (IOException rejected) {
                    if (!before.equals(content())) {
                        bad.add(c[0] + ": 拒存时文件被改了:\n" + content());
                    }
                }
                if (!before.equals(content())) {
                    bad.add(c[0] + ": 存值后文件变了:\n" + content());
                }
            } catch (AssertionError | IOException | RuntimeException e) {
                bad.add(c[0] + ": " + e.getMessage());
            }
        }
        assertTrue(bad.isEmpty(), () -> String.join("\n", bad));
    }
}
