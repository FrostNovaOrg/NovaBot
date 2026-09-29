package org.frostnova.nova.core.config.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
}
