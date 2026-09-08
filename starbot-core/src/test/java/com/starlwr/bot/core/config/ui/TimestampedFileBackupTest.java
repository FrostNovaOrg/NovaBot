package com.starlwr.bot.core.config.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 带时间戳的文件备份
 */
@DisplayName("带时间戳的文件备份")
class TimestampedFileBackupTest {

    private static final Instant START = Instant.parse("2026-09-05T10:00:00Z");

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    @TempDir
    Path dir;

    /**
     * 备份要说出自己裁掉了哪几份
     * <p>
     * 这个类是个不认得 Spring 的工具类（安全模式下也在用它，那时时间线根本不在），
     * 所以它自己不写日志页，只把「删了哪几份」交出去。交不出来的话，
     * 调用方就只能靠数目录里剩几份来猜，而两次保存之间还可能有别人在动那个目录。
     */
    @Test
    @DisplayName("裁掉的是哪几份要交代出来，一份没裁时交空表")
    void tellsWhichBackupsWerePruned() throws IOException {
        Path file = dir.resolve("application.yml");
        Files.writeString(file, "seed", StandardCharsets.UTF_8);

        // 前两次都在保留份数内：一份没裁，交的就该是空表而不是「不知道」
        assertEquals(List.of(), new TimestampedFileBackup(file, clockAt(0)).backup(2));
        assertEquals(List.of(), new TimestampedFileBackup(file, clockAt(1)).backup(2));

        List<String> pruned = new TimestampedFileBackup(file, clockAt(2)).backup(2);
        assertEquals(List.of("application.yml." + STAMP.format(START) + ".bak"), pruned,
                "裁掉的该是最旧那一份, 而且得报得出名字");
        assertFalse(stampedBackupNames(file).contains(pruned.get(0)), "报了被裁掉, 盘上就不该还在");

        // 文件还不在时连备份都不做，自然也没有裁掉什么
        assertEquals(List.of(), new TimestampedFileBackup(dir.resolve("nothing.yml")).backup(2));
    }

    @Test
    @DisplayName("写 12 次只留最新 10 份，按时间戳名排序")
    void keepsTheNewestTenByStampName() throws IOException {
        Path file = dir.resolve("application.yml");
        Files.writeString(file, "seed", StandardCharsets.UTF_8);

        for (int i = 0; i < 12; i++) {
            Files.writeString(file, "v" + i, StandardCharsets.UTF_8);
            new TimestampedFileBackup(file, clockAt(i)).backup(10);
        }

        List<String> names = stampedBackupNames(file);
        assertEquals(10, names.size(), "超出保留份数的旧备份应被裁掉");

        List<String> expected = new ArrayList<>();
        for (int i = 2; i < 12; i++) {
            expected.add("application.yml." + STAMP.format(START.plusSeconds(i)) + ".bak");
        }
        assertEquals(expected, names, "留下的应是时间戳名最新的 10 份");
        assertFalse(names.contains("application.yml." + STAMP.format(START) + ".bak"));
        assertFalse(names.contains("application.yml." + STAMP.format(START.plusSeconds(1)) + ".bak"));
    }

    @Test
    @DisplayName("裁剪只动带戳备份：本体与同前缀的不合格件都不碰")
    void pruneNeverTouchesTheBackedUpFileItself() throws IOException {
        Path file = dir.resolve("application.yml");
        Files.writeString(file, "seed", StandardCharsets.UTF_8);
        Path legacy = dir.resolve("application.yml.bak");
        Files.writeString(legacy, "legacy", StandardCharsets.UTF_8);
        Path decoy = dir.resolve("application.yml.2026.bak");
        Files.writeString(decoy, "decoy", StandardCharsets.UTF_8);

        for (int i = 0; i < 11; i++) {
            Files.writeString(file, "v" + i, StandardCharsets.UTF_8);
            new TimestampedFileBackup(file, clockAt(i)).backup(10);
        }

        // 本体名是各备份名的前缀，裁剪一旦认错名单，本体总是最先陪葬的那一个
        assertTrue(Files.exists(file), "被备份的本体必须仍在盘上");
        assertEquals("v10", Files.readString(file, StandardCharsets.UTF_8), "本体内容应仍是最后一次写入");

        List<String> names = stampedBackupNames(file);
        assertEquals(10, names.size(), "写满 11 份后应留恰 10 份");
        assertFalse(names.contains("application.yml." + STAMP.format(START) + ".bak"), "最旧一份应被裁掉");
        assertTrue(Files.exists(legacy), "手搓的 application.yml.bak 不该被裁剪动到");
        assertTrue(Files.exists(decoy), "同前缀但不合格的 application.yml.2026.bak 不该被裁剪动到");
    }

    @Test
    @DisplayName("同一秒连备两次：两份都在，内容各是各的")
    void sameSecondBackupGetsASequenceSuffixInsteadOfOverwriting() throws IOException {
        Path file = dir.resolve("application.yml");
        Files.writeString(file, "before-first", StandardCharsets.UTF_8);
        TimestampedFileBackup backup = new TimestampedFileBackup(file, Clock.fixed(START, ZoneOffset.UTC));

        backup.backup(10);
        Files.writeString(file, "before-second", StandardCharsets.UTF_8);
        backup.backup(10);

        List<String> names = stampedAndNumberedBackupNames(file);
        assertEquals(2, names.size(), "同一秒的第二份备份应加序号另存，而不是覆盖第一份");
        assertTrue(names.contains("application.yml." + STAMP.format(START) + ".bak"), "第一份不带序号");
        assertTrue(names.contains("application.yml." + STAMP.format(START) + "-2.bak"), "同秒第二份带序号 2");
        assertEquals("before-first",
                Files.readString(dir.resolve("application.yml." + STAMP.format(START) + ".bak"), StandardCharsets.UTF_8),
                "第一份应是第一次备份前的内容");
        assertEquals("before-second",
                Files.readString(dir.resolve("application.yml." + STAMP.format(START) + "-2.bak"), StandardCharsets.UTF_8),
                "第二份应是第二次备份前的内容");
    }

    @Test
    @DisplayName("手搓的单份 .bak 不认作带时间戳备份，裁剪时不动")
    void doesNotDeleteLegacySingleBak() throws IOException {
        Path file = dir.resolve("application.yml");
        Files.writeString(file, "current", StandardCharsets.UTF_8);
        Path legacy = dir.resolve("application.yml.bak");
        Files.writeString(legacy, "legacy", StandardCharsets.UTF_8);

        for (int i = 0; i < 3; i++) {
            new TimestampedFileBackup(file, clockAt(i)).backup(2);
        }

        assertTrue(Files.exists(legacy), "旧的单份 .bak 不应被裁掉");
        assertEquals("legacy", Files.readString(legacy, StandardCharsets.UTF_8),
                "旧的单份 .bak 也不该被覆盖改写");
    }

    private static Clock clockAt(int seconds) {
        return Clock.fixed(START.plusSeconds(seconds), ZoneOffset.UTC);
    }

    private static List<String> stampedBackupNames(Path file) throws IOException {
        String prefix = file.getFileName() + ".";
        try (Stream<Path> files = Files.list(file.getParent())) {
            return files.map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith(prefix) && name.endsWith(".bak"))
                    .filter(name -> name.length() > prefix.length() + ".bak".length())
                    .filter(name -> name.substring(prefix.length(), name.length() - ".bak".length())
                            .matches("\\d{8}-\\d{6}"))
                    .sorted()
                    .toList();
        }
    }

    /**
     * 连带序号变体（同一秒的第二份起）一起数的版本
     */
    private static List<String> stampedAndNumberedBackupNames(Path file) throws IOException {
        String prefix = file.getFileName() + ".";
        try (Stream<Path> files = Files.list(file.getParent())) {
            return files.map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith(prefix) && name.endsWith(".bak"))
                    .filter(name -> name.length() > prefix.length() + ".bak".length())
                    .filter(name -> name.substring(prefix.length(), name.length() - ".bak".length())
                            .matches("\\d{8}-\\d{6}(-\\d+)?"))
                    .sorted()
                    .toList();
        }
    }
}
