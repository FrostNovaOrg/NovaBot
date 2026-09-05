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
}
