package org.frostnova.nova.core.config.ui;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ConfigUiOperationStoreTest {
    @TempDir Path directory;

    private ConfigUiOperationStore store(Instant time) {
        var properties = new NovaCoreProperties();
        properties.getLive().setLiveDataPath(directory.resolve("live.json").toString());
        return new ConfigUiOperationStore(properties, Clock.fixed(time, ZoneOffset.UTC));
    }

    @Test @DisplayName("跨月后查不到或覆盖旧记录：按 UTC 月份分文件并只追加")
    void appendsAndRotatesByUtcMonth() throws Exception {
        var june = store(Instant.parse("2026-06-30T23:59:59.999Z"));
        june.append(JSONObject.of("action", "LOGIN"));
        june.append(JSONObject.of("action", "LOGOUT"));
        var july = store(Instant.parse("2026-07-01T00:00:00Z"));
        july.append(JSONObject.of("action", "LOGIN"));
        var lines = Files.readAllLines(directory.resolve("console-audit/2026-06.jsonl"));
        assertEquals(2, lines.size());
        assertEquals("LOGIN", JSONObject.parseObject(lines.get(0)).getString("action"));
        assertEquals("LOGOUT", JSONObject.parseObject(lines.get(1)).getString("action"));
        assertEquals(1782863999999L, JSONObject.parseObject(lines.get(0)).getLongValue("timestamp"));
        assertEquals(1, Files.readAllLines(directory.resolve("console-audit/2026-07.jsonl")).size());
    }

    @Test @DisplayName("记录无限增长或误删一年内数据：只删除过期整月并保留其他文件")
    void expiresOnlyOwnedWholeMonths() throws Exception {
        Path folder = Files.createDirectory(directory.resolve("console-audit"));
        for (String name : List.of("2025-06.jsonl", "2025-07.jsonl", "2025-08.jsonl", "2026-07.jsonl",
                "2026-08.jsonl", "2025-13.jsonl", "notes.txt", "2025-01.jsonl.backup"))
            Files.writeString(folder.resolve(name), "old\n");
        store(Instant.parse("2026-07-01T00:00:00Z")).prune();
        assertFalse(Files.exists(folder.resolve("2025-06.jsonl")));
        assertFalse(Files.exists(folder.resolve("2025-07.jsonl")));
        assertEquals("old\n", Files.readString(folder.resolve("2025-08.jsonl")));
        assertTrue(Files.exists(folder.resolve("2026-07.jsonl")));
        assertTrue(Files.exists(folder.resolve("2026-08.jsonl")));
        assertTrue(Files.exists(folder.resolve("2025-13.jsonl")));
        assertTrue(Files.exists(folder.resolve("notes.txt")));
        assertTrue(Files.exists(folder.resolve("2025-01.jsonl.backup")));
    }

    @Test @DisplayName("多人同时操作导致记录粘行或缺行：并发追加后每行都是完整对象")
    void concurrentOperationsRemainSeparateLines() throws Exception {
        var store = store(Instant.parse("2026-07-01T00:00:00Z"));
        var executor = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Void>> calls = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                int id = i;
                calls.add(() -> { store.append(JSONObject.of("operation", id)); return null; });
            }
            for (var result : executor.invokeAll(calls, 30, TimeUnit.SECONDS)) result.get();
        } finally {
            executor.shutdown();
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
        }
        Set<Integer> operations = new HashSet<>();
        var lines = Files.readAllLines(directory.resolve("console-audit/2026-07.jsonl"));
        for (String line : lines) operations.add(JSONObject.parseObject(line).getInteger("operation"));
        assertEquals(40, lines.size());
        assertEquals(40, operations.size());
    }
}
