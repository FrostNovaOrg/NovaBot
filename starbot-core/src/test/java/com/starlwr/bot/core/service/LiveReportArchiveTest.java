package com.starlwr.bot.core.service;

import com.starlwr.bot.core.config.StarBotCoreProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 下播报告留档测试
 * <p>
 * 这份留档是「场次可点开看报告」唯一的数据来源。三件事必须钉死：
 * 键要与场次归档同源（否则点开的是另一场）、平台名不许把路径撬开
 * （它是从请求路径里来的）、一场只留一份且留下的是信息最全的那一份。
 */
@DisplayName("下播报告留档")
class LiveReportArchiveTest {
    @TempDir
    Path dir;

    private LiveReportArchive archive;

    @BeforeEach
    void setUp() {
        StarBotCoreProperties properties = new StarBotCoreProperties();
        properties.getLive().setLiveDataPath(dir.resolve("data.json").toString());
        archive = new LiveReportArchive(properties);
    }

    @Test
    @DisplayName("留档后应能原样读回，文件落在数据目录的 reports/ 下")
    void storeThenRead() {
        byte[] png = png("一张图");

        archive.store("bilibili", 1001L, 1757000000000L, png, true);

        assertTrue(archive.has("bilibili", 1001L, 1757000000000L));
        assertArrayEquals(png, archive.read("bilibili", 1001L, 1757000000000L).orElseThrow());
        assertTrue(Files.exists(dir.resolve("reports").resolve("bilibili-1001-1757000000000.png")),
                "名字里要带平台、uid 与开播时刻, 少一项就分不清是谁的哪一场");
    }

    @Test
    @DisplayName("没留过的那一场读出来是空，而不是报错或空数组")
    void missingIsEmpty() {
        assertFalse(archive.has("bilibili", 1001L, 1L));
        assertTrue(archive.read("bilibili", 1001L, 1L).isEmpty());
    }

    @Test
    @DisplayName("开播时刻差一毫秒就是另一场，不许糊到同一个文件上")
    void startTimeIsPartOfTheKey() {
        archive.store("bilibili", 1001L, 1757000000000L, png("甲场"), true);
        archive.store("bilibili", 1001L, 1757000000001L, png("乙场"), true);

        assertArrayEquals(png("甲场"), archive.read("bilibili", 1001L, 1757000000000L).orElseThrow());
        assertArrayEquals(png("乙场"), archive.read("bilibili", 1001L, 1757000000001L).orElseThrow());
    }

    @Test
    @DisplayName("金额可见的那份优先，且不会被后来的不可见版本盖掉")
    void revenueVisibleCopyWins() {
        archive.store("bilibili", 1001L, 100L, png("群聊版"), false);
        archive.store("bilibili", 1001L, 100L, png("私聊版"), true);
        assertArrayEquals(png("私聊版"), archive.read("bilibili", 1001L, 100L).orElseThrow());

        archive.store("bilibili", 1001L, 100L, png("又一个群聊版"), false);
        assertArrayEquals(png("私聊版"), archive.read("bilibili", 1001L, 100L).orElseThrow(),
                "留下哪一份不该取决于通道在配置里的先后");
    }

    @Test
    @DisplayName("平台名带路径穿越时整个不留档，也读不出目录外的文件")
    void platformCannotEscapeTheDirectory() {
        byte[] png = png("不该落在外面");

        archive.store("../../etc", 1001L, 100L, png, true);
        archive.store("bili/bili", 1001L, 100L, png, true);
        archive.store("", 1001L, 100L, png, true);

        assertFalse(Files.exists(dir.resolve("reports")), "一个字节都不该写出去");
        assertTrue(archive.read("../../etc", 1001L, 100L).isEmpty());
        assertTrue(archive.read("bili/bili", 1001L, 100L).isEmpty());
        assertFalse(archive.has("../../etc", 1001L, 100L));
    }

    @Test
    @DisplayName("空图不留档：一张 0 字节的 PNG 在界面上与「图坏了」一模一样")
    void emptyImageIsNotStored() {
        archive.store("bilibili", 1001L, 100L, new byte[0], true);
        archive.store("bilibili", 1001L, 100L, null, true);

        assertFalse(archive.has("bilibili", 1001L, 100L));
    }

    @Test
    @DisplayName("留档不会在目录里留下写了一半的临时文件")
    void leavesNoPartialFile() {
        archive.store("bilibili", 1001L, 100L, png("一张图"), true);

        assertEquals(1, dir.resolve("reports").toFile().list().length,
                "半张 PNG 与「图坏了」在界面上分不出来, 所以先写临时文件再原子改名");
    }

    private byte[] png(String content) {
        return content.getBytes(StandardCharsets.UTF_8);
    }
}
