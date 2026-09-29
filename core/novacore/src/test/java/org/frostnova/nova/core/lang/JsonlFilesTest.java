package org.frostnova.nova.core.lang;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JSONL 按行读写辅助测试
 */
@DisplayName("JSONL 按行读写")
class JsonlFilesTest {
    @TempDir
    Path dir;

    @Test
    @DisplayName("一行里有半个中文字时只坏这一行，前后各行原样读出")
    void halfCharacterOnlySpoilsItsOwnLine() throws IOException {
        Path file = dir.resolve("a.jsonl");
        Files.writeString(file, "{\"n\":\"前\"}\n", StandardCharsets.UTF_8);
        // 「截」是 E6 88 AA，只写进前两个字节
        Files.write(file, new byte[]{'{', '"', (byte) 0xE6, (byte) 0x88}, StandardOpenOption.APPEND);
        JsonlFiles.separateTruncatedTail(file);
        Files.writeString(file, "{\"n\":\"后\"}\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        List<String> lines;
        try (Stream<String> stream = JsonlFiles.lines(file)) {
            lines = stream.toList();
        }

        assertEquals(3, lines.size(), "坏字节不该吞掉换行，把两行并成一行");
        assertEquals("{\"n\":\"前\"}", lines.get(0));
        assertEquals("{\"\uFFFD", lines.get(1), "坏字节换成替换符，这一行交给解析那头跳过");
        assertEquals("{\"n\":\"后\"}", lines.get(2));
    }

    @Test
    @DisplayName("文件不在时照旧抛 NoSuchFileException，调用方据此答空表")
    void missingFileStillSaysSo() {
        assertThrows(NoSuchFileException.class, () -> JsonlFiles.lines(dir.resolve("none.jsonl")).close());
    }
}
