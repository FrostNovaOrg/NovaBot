package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 设置页存回名单时不把配置写坏、不把值改掉
 * <p>
 * 判据一律拿启动那一路（{@link ConfigurationFileService#readAsLoaded()}）读到的当准：
 * 界面上显示的字、存回之后程序读到的字，两者得是同一个。
 */
@DisplayName("名单存回：不写坏文件、不改掉值")
class ListSaveKeepsWrittenFormTest {
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

    private static List<String> strings(Object list) {
        List<String> items = new ArrayList<>();
        for (Object item : (List<?>) list) {
            items.add(String.valueOf(item));
        }
        return items;
    }

    /**
     * 抓的用户故障：对象列表写在键下一行的方括号里，在设置页给它加第一项（或改、删一项），
     * 一存整份配置读不通，重启起不来。
     */
    @Test
    @DisplayName("🔴 下一行非空对象列表：建、改、删一项都整批拒存, 文件一字不动, 启动照旧读得通, 拒语与界面的锁同一说法")
    void nextLineObjectListRejectsItemWrites() throws IOException {
        write("novabot:\n  demo:\n    rules:\n      [{name: a, qq: 1}]\n    tail: 1\n");
        String text = content();
        String key = "novabot.demo.rules";
        String lock = service.uiLocked().get(key);
        assertNotNull(lock, "下一行方括号里的对象列表, 界面上这一格应挂着锁");

        List<String> bad = new ArrayList<>();
        Map<String, Map<String, String>> attempts = new LinkedHashMap<>();
        attempts.put("建一项", Map.of("name", "b"));
        attempts.put("删字段", Map.of("qq", ""));
        for (int index = 0; index < 2; index++) {
            for (Map.Entry<String, Map<String, String>> attempt : attempts.entrySet()) {
                String label = attempt.getKey() + "#" + index;
                int at = index;
                try {
                    IOException refused = assertThrows(IOException.class,
                            () -> service.writeListItemFields(key, at, attempt.getValue()),
                            "下一行方括号里的对象列表, 这一路改不了, 应整批拒存");
                    assertEquals(text, content(), "拒存时文件一个字节不动");
                    assertTrue(refused.getMessage().contains(lock),
                            "拒语应与界面那把锁同一说法: " + refused.getMessage());
                    System.out.println("对象列表" + label + "被拒: " + refused.getMessage());
                } catch (AssertionError | IOException | RuntimeException e) {
                    bad.add(label + ": " + e.getMessage());
                }
            }
        }
        try {
            assertEquals("a", String.valueOf(service.readAsLoaded().get(key + "[0].name")),
                    "启动那一路应照旧读得通、读到原来那一项");
        } catch (AssertionError | IOException e) {
            bad.add("启动读: " + e.getMessage());
        }
        assertTrue(bad.isEmpty(), () -> String.join("\n", bad));
    }

    /**
     * 抓的用户故障：对象列表在键下一行写成空的 {@code []}，在设置页建第一项，
     * 新项与旧的方括号并存，整份配置读不通。
     */
    @Test
    @DisplayName("🔴 下一行 []：建第一项换掉那一行, 启动读回的就是新建的那一项")
    void nextLineEmptyListCreatesFirstItem() throws IOException {
        write("novabot:\n  demo:\n    rules:\n      []\n    tail: 1\n");
        String key = "novabot.demo.rules";
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("name", "b");
        fields.put("qq", "2");

        assertEquals(2, service.writeListItemFields(key, 0, fields), "两个字段都应写下");
        String text = content();
        assertFalse(text.contains("[]"), "空表那一行应换成新元素:\n" + text);
        Map<String, Object> loaded = service.readAsLoaded();
        assertEquals("b", String.valueOf(loaded.get(key + "[0].name")), "启动读回的应是新建的那一项:\n" + text);
        assertEquals("2", String.valueOf(loaded.get(key + "[0].qq")), "启动读回的应是新建的那一项:\n" + text);
        assertEquals("1", String.valueOf(loaded.get("novabot.demo.tail")), "别的项不动:\n" + text);
    }

    /** 带引号写才是字面文字的词与数：裸写的话启动那一路读成真假、空或另一个数 */
    private static final List<String> READ_OTHERWISE_WHEN_BARE =
            List.of("yes", "on", "off", "no", "~", "null", "012", "1_000", "0x1F", "1.50");

    /**
     * 抓的用户故障：名单里特意加了引号的 yes、on、~、012 这类词，在设置页给名单加一项存回后
     * 引号没了，程序读到的变成真假、空或另一个数。
     */
    @Test
    @DisplayName("🔴 名单带引号的 yes、on、off、no、~、null、012、1_000、0x1F、1.50：加一项存回后启动读回原字")
    void quotedWordsSurviveListSave() throws IOException {
        StringBuilder flow = new StringBuilder();
        for (String word : READ_OTHERWISE_WHEN_BARE) {
            flow.append(flow.length() == 0 ? "" : ", ").append('"').append(word).append('"');
        }
        write("novabot:\n  demo:\n    copy: [" + flow + ", 'on']\n    tail: 1\n");
        String key = "novabot.demo.copy";
        List<String> expected = new ArrayList<>(READ_OTHERWISE_WHEN_BARE);
        expected.add("on");
        assertEquals(String.join("\n", expected), service.read().get(key), "界面应显示引号里的原字");

        expected.add("zz");
        assertEquals(List.of(key), service.write(Map.of(key, String.join("\n", expected))), "加一项应存下");
        String text = content();
        assertEquals(expected, strings(service.readAsLoaded().get(key)), "启动读回的应是原字:\n" + text);
        text.lines().filter(l -> l.contains("yes")).forEach(l -> System.out.println("yes 那一行: " + l));
    }

    /**
     * 抓的用户故障：单个值填 yes、~、012 这类词，存下后程序读到的是真假、空或另一个数。
     */
    @Test
    @DisplayName("🔴 单个值填 yes、~、012 这类词：存下后启动读回原字")
    void wordsSurviveScalarSave() throws IOException {
        write("novabot:\n  demo:\n    word: x\n    tail: 1\n");
        String key = "novabot.demo.word";
        List<String> bad = new ArrayList<>();
        for (String word : READ_OTHERWISE_WHEN_BARE) {
            try {
                service.write(Map.of(key, word));
                assertEquals(word, String.valueOf(service.readAsLoaded().get(key)), "启动读回的应是原字:\n" + content());
            } catch (AssertionError | IOException e) {
                bad.add(word + ": " + e.getMessage());
            }
        }
        assertTrue(bad.isEmpty(), () -> String.join("\n", bad));
    }

    /**
     * 抓的用户故障：本来裸写就读得对的 true、123，存回后平白多了引号，
     * 布尔、数字类的设置变成了文字。
     */
    @Test
    @DisplayName("🔴 名单里的 true、123 存回后仍裸写")
    void plainTrueAndNumberStayBare() throws IOException {
        write("novabot:\n  demo:\n    copy: [true, 123]\n    flag: false\n    tail: 1\n");
        String key = "novabot.demo.copy";
        assertEquals(java.util.Set.of(key, "novabot.demo.flag"),
                java.util.Set.copyOf(service.write(Map.of(key, "true\n123\nzz", "novabot.demo.flag", "true"))),
                "两项都应存下");
        String text = content();
        assertTrue(text.contains("- true\n") && text.contains("- 123\n"), "裸写读得对的不该加引号:\n" + text);
        assertTrue(text.contains("flag: true\n"), "单值裸写读得对的也不该加引号:\n" + text);
    }

    /**
     * 抓的用户故障：名单里写成 {@code ""} 的空项，界面上是一行空，存回后空项没了。
     */
    @Test
    @DisplayName("🔴 名单里有空项：界面锁住, 改了整批拒存, 拒语说有空的一项显示不出来")
    void emptyItemListIsLocked() throws IOException {
        write("novabot:\n  demo:\n    copy: [a, \"\"]\n    tail: 1\n");
        String text = content();
        String key = "novabot.demo.copy";
        String lock = service.uiLocked().get(key);
        assertNotNull(lock, "名单里有空项, 界面上这一格应锁住");
        assertTrue(lock.contains("空的一项"), "锁的说明应说名单里有空的一项: " + lock);

        IOException refused = assertThrows(IOException.class,
                () -> service.write(Map.of(key, "a\nzz")),
                "有空项的名单被改, 应整批拒存");
        assertEquals(text, content(), "拒存时文件一个字节不动");
        assertTrue(refused.getMessage().contains("空的一项"), "拒语应说名单里有空的一项: " + refused.getMessage());
        System.out.println("空项名单被拒: " + refused.getMessage());
    }

    /**
     * 抓的用户故障：名单跨了行、中间夹空行或注释行，界面上看到的是半截「[a,」，以为配置只写了一项。
     */
    @Test
    @DisplayName("🔴 跨行夹空行、注释行的名单：显示启动读到的全部各项; 启动也读不出时显示原文整段")
    void interruptedCrossLineListShowsAllItems() throws IOException {
        String key = "novabot.demo.copy";
        String[][] cases = {
                {"夹空行", "novabot:\n  demo:\n    copy: [a,\n\n      b]\n    tail: 1\n"},
                {"夹注释行", "novabot:\n  demo:\n    copy: [a,\n      # 注\n      b]\n    tail: 1\n"},
        };
        List<String> bad = new ArrayList<>();
        for (String[] c : cases) {
            try {
                write(c[1]);
                assertEquals("a\nb", service.read().get(key), "应显示启动读到的各项, 一行一项");
            } catch (AssertionError | IOException e) {
                bad.add(c[0] + ": " + e.getMessage());
            }
        }
        try {
            write("novabot:\n  demo:\n    copy: [a,\n\n      b]\n    broken: [x\n    tail: 1\n");
            String shown = service.read().get(key);
            assertEquals("[a, b]", shown, "启动读不出时应显示原文整段, 不显示半截");
        } catch (AssertionError | IOException e) {
            bad.add("读不通: " + e.getMessage());
        }
        assertTrue(bad.isEmpty(), () -> String.join("\n", bad));
    }

    /**
     * 抓的用户故障：名单的方括号忘了收口，拒语却让人去找一个并不存在的套着的写法。
     */
    @Test
    @DisplayName("🔴 方括号没收口就到了下一个键：拒语说没收口, 不提套着")
    void unclosedCrossLineListSaysUnclosed() throws IOException {
        write("novabot:\n  demo:\n    copy: [a,\n      b\n    tail: 1\n");
        String text = content();
        IOException refused = assertThrows(IOException.class,
                () -> service.write(Map.of("novabot.demo.copy", "z")),
                "没收口的名单应整批拒存");
        assertEquals(text, content(), "拒存时文件一个字节不动");
        assertTrue(refused.getMessage().contains("没有收口"), "拒语应说方括号没有收口: " + refused.getMessage());
        assertFalse(refused.getMessage().contains("套着"), "里面没有套着的写法, 拒语不该提: " + refused.getMessage());
        System.out.println("没收口被拒: " + refused.getMessage());
    }

    /**
     * 抓的用户故障：名单跨了行、中间夹了空行或注释行，界面上这一格却是能改的框，
     * 改完点保存整批被拒，同批别的改动也没存上。
     */
    @Test
    @DisplayName("🔴 跨行夹空行、注释行的名单：界面锁住, 说明与拒语同说夹了行; 显示仍是读到的各项; 原样送回不写, 改了才拒")
    void interruptedCrossLineListIsLockedOnUi() throws IOException {
        String key = "novabot.demo.copy";
        String[][] cases = {
                {"夹空行", "novabot:\n  demo:\n    copy: [a,\n\n      b]\n    tail: 1\n"},
                {"夹注释行", "novabot:\n  demo:\n    copy: [a,\n      # 注\n      b]\n    tail: 1\n"},
        };
        List<String> bad = new ArrayList<>();
        for (String[] c : cases) {
            try {
                write(c[1]);
                String text = content();
                String lock = service.uiLocked().get(key);
                assertNotNull(lock, "夹空行的跨行名单, 界面上这一格应锁住");
                assertTrue(lock.contains("跨了行") && lock.contains("夹着空行") && lock.contains("注释行"),
                        "锁的说明该说名单跨了行、中间夹着空行或注释行: " + lock);
                assertFalse(lock.contains("套着"), "里面没有套着的写法, 说明不该提: " + lock);
                assertEquals("a\nb", service.read().get(key), "锁住后显示的仍是启动读到的各项, 一行一项");
                assertEquals(List.of(), service.write(Map.of(key, "a\nb")), "原样送回＝没改, 应放行且不算改动");
                assertEquals(text, content(), "原样送回不该写文件");
                IOException refused = assertThrows(IOException.class,
                        () -> service.write(Map.of(key, "z")), "改了应整批拒存");
                assertTrue(refused.getMessage().contains(lock), "拒语应与界面那把锁同一说法: " + refused.getMessage());
                assertEquals(text, content(), "拒存时文件一个字节不动");
                System.out.println(c[0] + "锁说明: " + lock);
            } catch (AssertionError | IOException | RuntimeException e) {
                bad.add(c[0] + ": " + e.getMessage());
            }
        }
        assertTrue(bad.isEmpty(), () -> String.join("\n", bad));
    }

    /**
     * 抓的用户故障：名单的方括号忘了收口，界面上这一格是能改的框，改完点保存整批被拒；
     * 框里还只显示键那一行的半截「[a,」，看不出名单里到底写了什么。
     */
    @Test
    @DisplayName("🔴 方括号没收口就到了下一个键：界面锁住, 说明与拒语同说没收口; 显示原文整段不显示半截; 原样送回不写, 改了才拒")
    void unclosedCrossLineListIsLockedAndShowsWholeSpan() throws IOException {
        String key = "novabot.demo.copy";
        write("novabot:\n  demo:\n    copy: [a,\n      b\n    tail: 1\n");
        String text = content();
        String lock = service.uiLocked().get(key);
        assertNotNull(lock, "没收口的跨行名单, 界面上这一格应锁住");
        assertTrue(lock.contains("跨了行") && lock.contains("没有收口"), "锁的说明该说名单跨了行、方括号没有收口: " + lock);
        assertFalse(lock.contains("套着"), "里面没有套着的写法, 说明不该提: " + lock);
        String shown = service.read().get(key);
        assertEquals("[a, b", shown, "该显示整段原文, 不是键那一行的半截");
        System.out.println("没收口锁说明: " + lock);
        System.out.println("没收口显示: " + shown);
        assertEquals(List.of(), service.write(Map.of(key, shown)), "原样送回＝没改, 应放行且不算改动");
        assertEquals(text, content(), "原样送回不该写文件");
        IOException refused = assertThrows(IOException.class,
                () -> service.write(Map.of(key, "z")), "改了应整批拒存");
        assertTrue(refused.getMessage().contains(lock), "拒语应与界面那把锁同一说法: " + refused.getMessage());
        assertEquals(text, content(), "拒存时文件一个字节不动");
    }

    /**
     * 抓的用户故障：跨行名单收了口、里面套着花括号或「词: 值」，界面上这一格是能改的框，
     * 改完点保存整批被拒；框里还只显示键那一行的半截「[{a: 1},」，看不出名单里到底写了什么。
     */
    @Test
    @DisplayName("🔴 收了口但里面套着的跨行名单：界面锁住, 说明与拒语同说套着; 显示原文整段不显示半截; 原样送回不写, 改了才拒")
    void nestedCrossLineListIsLockedAndShowsWholeSpan() throws IOException {
        String key = "novabot.demo.copy";
        String[][] cases = {
                {"全是套着的项", "novabot:\n  demo:\n    copy: [{a: 1},\n      {b: 2}]\n    tail: 1\n", "[{a: 1}, {b: 2}]"},
                {"普通项与套着的混写", "novabot:\n  demo:\n    copy: [a,\n      {b: 2}]\n    tail: 1\n", "[a, {b: 2}]"},
        };
        List<String> bad = new ArrayList<>();
        for (String[] c : cases) {
            try {
                write(c[1]);
                String text = content();
                String lock = service.uiLocked().get(key);
                assertNotNull(lock, "套着的跨行名单, 界面上这一格应锁住");
                assertTrue(lock.contains("跨了行") && lock.contains("套着"), "锁的说明该说名单跨了行、里面套着: " + lock);
                assertFalse(lock.contains("空行"), "没夹空行的说明不该提空行: " + lock);
                assertEquals(c[2], service.read().get(key), "该显示整段原文, 不是键那一行的半截");
                assertEquals(List.of(), service.write(Map.of(key, c[2])), "原样送回＝没改, 应放行且不算改动");
                assertEquals(text, content(), "原样送回不该写文件");
                IOException refused = assertThrows(IOException.class,
                        () -> service.write(Map.of(key, "z")), "改了应整批拒存");
                assertTrue(refused.getMessage().contains(lock), "拒语应与界面那把锁同一说法: " + refused.getMessage());
                assertEquals(text, content(), "拒存时文件一个字节不动");
                System.out.println(c[0] + "锁说明: " + lock);
                System.out.println(c[0] + "显示: " + c[2]);
            } catch (AssertionError | IOException | RuntimeException e) {
                bad.add(c[0] + ": " + e.getMessage());
            }
        }
        assertTrue(bad.isEmpty(), () -> String.join("\n", bad));
    }
}
