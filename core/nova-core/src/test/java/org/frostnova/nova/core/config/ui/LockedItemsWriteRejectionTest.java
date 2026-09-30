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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 设置页锁住的项，保存接口也照锁拒存
 * <p>
 * 抓的使用者故障：锁住的格只在界面上画成只读，绕过界面直接调保存接口给
 * 别名引到的名单送一个值，整份名单被换成一个字，存的时候不拒；
 * 锁住的项送来带换行的值（把界面上的多行值原样改一改再送回），拒语说的是
 * 「值不能包含换行」，照着改也存不进去，不知道真正卡在哪。
 * <p>
 * 拒语与界面上的只读说明同源，这里只钉住稳定的那几段与别名名，
 * 不逐字钉整句。
 */
@DisplayName("锁住的项：保存接口照锁拒存, 拒语说锁的原因")
class LockedItemsWriteRejectionTest {
    @TempDir
    Path dir;

    private Path config;
    private ConfigurationFileService service;

    /** 别名引到名单的一格：设置页上只读，显示的是启动读到的整份名单 */
    private static final String ALIAS_TO_LIST =
            "novabot:\n  demo:\n    words: &l\n      - a\n      - b\n    copy:\n      *l\n";

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
     * 抓的使用者故障：绕过界面的锁直接调保存接口，给别名引到的名单送一个值，
     * 文件成了 {@code copy: z}，整份名单被换成一个字，存的时候不拒。
     */
    @Test
    @DisplayName("🔴 别名引到名单的格送一个字：整批拒存, 文件一字不动, 拒语是锁的说明")
    void aliasListCellRejectsSingleValue() throws IOException {
        write(ALIAS_TO_LIST);
        String text = content();
        assertNotNull(service.uiLocked().get("novabot.demo.copy"), "别名引到名单, 这一格界面上应是只读的");

        IOException rejected = assertThrows(IOException.class,
                () -> service.write(Map.of("novabot.demo.copy", "z")),
                "给锁住的名单格送一个值, 应整批拒存");
        assertEquals(text, content(), "拒存时文件一个字节不动");
        assertTrue(rejected.getMessage().contains("界面改不了"),
                "拒语应说清这一格界面改不了: " + rejected.getMessage());
        assertTrue(rejected.getMessage().contains("*l"),
                "拒语应带上锁自己的说明（点出别名 *l）: " + rejected.getMessage());
        System.out.println("送单值被拒: " + rejected.getMessage());
    }

    /**
     * 抓的使用者故障：锁住的项送来带换行的值，拒语说的是「值不能包含换行」，
     * 使用者照着改也存不进去，不知道真正卡在锁上。
     */
    @Test
    @DisplayName("🔴 锁住的项送带换行的值：拒语说锁的原因, 不再说值不能包含换行")
    void lockedCellWithMultilineValueGetsLockReason() throws IOException {
        write(ALIAS_TO_LIST);
        String text = content();

        IOException rejected = assertThrows(IOException.class,
                () -> service.write(Map.of("novabot.demo.copy", "a\nb\nx")),
                "锁住的项送改过的多行值, 应整批拒存");
        assertEquals(text, content(), "拒存时文件一个字节不动");
        assertTrue(rejected.getMessage().contains("界面改不了"),
                "拒语应说清这一格界面改不了: " + rejected.getMessage());
        assertFalse(rejected.getMessage().contains("不能包含换行"),
                "拒语不该再说成换行的事: " + rejected.getMessage());
        System.out.println("送带换行的值被拒: " + rejected.getMessage());
    }

    /**
     * 抓的使用者故障：锁住的格原样送回（界面值没改）被「值不能包含换行」拦下，
     * 明明什么都没改却存不进去。
     */
    @Test
    @DisplayName("🔴 同一格原样送回：放行, 文件一字不动")
    void lockedCellSentBackUnchangedPasses() throws IOException {
        write(ALIAS_TO_LIST);
        String text = content();
        String shown = service.read().get("novabot.demo.copy");
        assertEquals("a\nb", shown, "界面显示的应是启动读到的整份名单");

        assertEquals(List.of(), service.write(Map.of("novabot.demo.copy", shown)),
                "原样送回＝没改, 不算改动");
        assertEquals(text, content(), "没改时文件一个字节不动");
    }

    /** 经合并键继承、文件里没有自己那一行的锁住名单：首项写成块标量，读回的值带换行 */
    private static final String MERGED_INHERITED_LIST =
            "novabot:\n  demo:\n    base: &b\n      words:\n        - |-\n          first\n          second\n"
                    + "        - b\n    item:\n      <<: *b\n    tail: 1\n";

    /**
     * 抓的使用者故障：经合并键继承来的锁住项原样送回（界面值没改），文件里多出一份名单——
     * 这一项在文件里本没有自己的行，值是继承来的；写成实有的一行，下一回改名换位置就接不上。
     */
    @Test
    @DisplayName("🔴 继承来的锁住项原样送回：从本批剔除、不写进文件；送别的值照旧整批拒存")
    void inheritedLockedItemSentBackUnchangedIsNotWritten() throws IOException {
        write(MERGED_INHERITED_LIST);
        String text = content();
        String key = "novabot.demo.item.words";
        String shown = service.read().get(key);
        assertEquals("first\nsecond\nb", shown, "界面显示的应是继承来的整份名单");
        assertNotNull(service.uiLocked().get(key), "继承来的、项带换行的名单应挂着锁");

        assertEquals(List.of(), service.write(Map.of(key, shown)), "原样送回＝没改, 不算改动");
        assertEquals(text, content(), "原样送回不写进文件——文件里没有这一行, 写了就成实有的一份");

        IOException rejected = assertThrows(IOException.class,
                () -> service.write(Map.of(key, "first\nsecond\nb\nx")),
                "继承来的锁住项送改过的值, 应整批拒存");
        assertEquals(text, content(), "拒存时文件一个字节不动");
        assertTrue(rejected.getMessage().contains("界面改不了"),
                "拒语应说清这一格界面改不了: " + rejected.getMessage());
        System.out.println("继承项送改过的值被拒: " + rejected.getMessage());
    }

    /**
     * 抓的使用者故障：配置文件本身启动时读不通时，挂「读不通」锁的项
     * 界面上说为不写错不在界面改，保存接口却照样写进去。
     */
    @Test
    @DisplayName("🔴 读不通的文件：挂锁的项照拒, 没上锁的项照旧可存")
    void unreadableFileLocksItsItemButNotOthers() throws IOException {
        write("novabot:\n  demo:\n    broken: [a\n    copy:\n      *l\n    tail: 1\n");
        String text = content();
        assertNotNull(service.uiLocked().get("novabot.demo.copy"), "读不通的文件里这一项应挂着锁");

        IOException rejected = assertThrows(IOException.class,
                () -> service.write(Map.of("novabot.demo.copy", "z")),
                "挂「读不通」锁的项送值, 应整批拒存");
        assertTrue(rejected.getMessage().contains("界面改不了"),
                "拒语应说清这一格界面改不了: " + rejected.getMessage());
        assertEquals(text, content(), "拒存时文件一个字节不动");

        assertEquals(List.of("novabot.demo.tail"), service.write(Map.of("novabot.demo.tail", "2")),
                "同一份文件里没上锁的普通项应照旧可存");
        assertEquals("2", service.read().get("novabot.demo.tail"), "改的值应已落盘");
    }

    /** 分了几段（---）的配置文件：各段怎么叠比不准，程序照常启动 */
    private static final String SEGMENTED_WITH_KEY_ROW_LISTS =
            "novabot:\n  demo:\n    words: &l\n      - a\n    copy: [x, y]\n    aliased: [*l]\n    tail: 1\n"
                    + "---\nspring:\n  application:\n    name: demo\n";

    /**
     * 抓的用户故障：文件分了几段（---）时程序照常启动，键行上没有记号的普通名单却被锁成
     * 「程序启动时读这份配置文件也没读出这一项」，改不了——比不了时本该照旧能改。
     */
    @Test
    @DisplayName("🔴 分了几段的文件：键行普通名单不锁、照旧可改；带别名记号的照旧锁")
    void segmentedFileKeepsPlainKeyRowListEditable() throws IOException {
        write(SEGMENTED_WITH_KEY_ROW_LISTS);
        String key = "novabot.demo.copy";
        assertFalse(service.uiLocked().containsKey(key),
                "分了几段的文件里键行普通名单不该锁: " + service.uiLocked().get(key));
        assertEquals("x\ny", service.read().get(key), "界面应显示名单各项");
        assertEquals(List.of(key), service.write(Map.of(key, "x\nz")), "比不了时照旧可改");
        assertEquals("x\nz", service.read().get(key), "改的值应已落盘");
        assertTrue(content().contains("- z"), "名单应按每行一项写回:\n" + content());

        assertNotNull(service.uiLocked().get("novabot.demo.aliased"), "带别名记号的名单照旧锁");
        IOException rejected = assertThrows(IOException.class,
                () -> service.write(Map.of("novabot.demo.aliased", "z")),
                "带记号的名单送值, 应整批拒存");
        assertTrue(rejected.getMessage().contains("界面改不了"),
                "拒语应说清这一格界面改不了: " + rejected.getMessage());
    }

    /**
     * 抓的用户故障：文件里有一处写坏、整份启动读不通时，键行上没有记号的普通名单也被锁成
     * 「程序启动时没读出这一项」、改不了——写坏的那处去文件里修，别的照旧写。
     */
    @Test
    @DisplayName("🔴 读不通的文件：键行普通名单照旧可改；带锚点记号的照旧锁")
    void unreadableFileKeepsPlainKeyRowListEditable() throws IOException {
        write("novabot:\n  demo:\n    copy: [x, y]\n    broken: [a\n    anchored: &l x\n    tail: 1\n");
        String key = "novabot.demo.copy";
        assertFalse(service.uiLocked().containsKey(key),
                "读不通的文件里键行普通名单不该锁: " + service.uiLocked().get(key));
        assertEquals("x\ny", service.read().get(key), "界面应显示名单各项");
        assertEquals(List.of(key), service.write(Map.of(key, "x\nz")), "比不了时照旧可改");
        assertEquals("x\nz", service.read().get(key), "改的值应已落盘");
        assertTrue(content().contains("broken: [a"), "写坏的那一行不动:\n" + content());

        assertNotNull(service.uiLocked().get("novabot.demo.anchored"), "带锚点记号的照旧锁");
        IOException rejected = assertThrows(IOException.class,
                () -> service.write(Map.of("novabot.demo.anchored", "z")),
                "带记号的项送值, 应整批拒存");
        assertTrue(rejected.getMessage().contains("界面改不了"),
                "拒语应说清这一格界面改不了: " + rejected.getMessage());
    }

    /**
     * 抓的使用者故障：名单跨了行、中间只夹一行空行或注释行时，保存的拒语说「里面套着的
     * 方括号、花括号或「词: 值」界面读不了」——里面并没有这些，照着去找找不到；
     * 拒语该说真实的原因，真套着写法的照旧说套着。
     */
    @Test
    @DisplayName("🔴 跨行名单中间夹空行或注释行：拒语说夹了行、不提套着的写法；真套着的照旧说套着")
    void crossLineListWithBlankOrCommentSaysThatNotNested() throws IOException {
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
                IOException rejected = assertThrows(IOException.class,
                        () -> service.write(Map.of(key, "z")),
                        "跨行名单这一格应整批拒存");
                assertTrue(rejected.getMessage().contains("跨了行"),
                        "拒语该说名单跨了行: " + rejected.getMessage());
                assertTrue(rejected.getMessage().contains("夹着空行") && rejected.getMessage().contains("注释行"),
                        "拒语该说中间夹了空行或注释行: " + rejected.getMessage());
                assertFalse(rejected.getMessage().contains("套着"),
                        "里面没有套着的写法, 拒语不该让人去找: " + rejected.getMessage());
                assertEquals(text, content(), "拒存时文件一个字节不动");
                System.out.println(c[0] + "被拒: " + rejected.getMessage());
            } catch (AssertionError | IOException | RuntimeException e) {
                bad.add(c[0] + ": " + e.getMessage());
            }
        }

        try {
            write("novabot:\n  demo:\n    copy: [{a: 1},\n      {b: 2}]\n    tail: 1\n");
            IOException nested = assertThrows(IOException.class,
                    () -> service.write(Map.of(key, "z")),
                    "套着对象的跨行名单应整批拒存");
            assertTrue(nested.getMessage().contains("套着"),
                    "真套着写法的拒语该说套着: " + nested.getMessage());
            assertFalse(nested.getMessage().contains("空行"),
                    "没夹空行的不该说夹了空行: " + nested.getMessage());
            System.out.println("套着写法被拒: " + nested.getMessage());
        } catch (AssertionError | IOException | RuntimeException e) {
            bad.add("对照: " + e.getMessage());
        }
        assertTrue(bad.isEmpty(), () -> String.join("\n", bad));
    }
}
