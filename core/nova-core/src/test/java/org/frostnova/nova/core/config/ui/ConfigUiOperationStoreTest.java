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
        String julyRecord = JSONObject.of("timestamp", Instant.parse("2025-07-31T23:59:59.999Z").toEpochMilli()) + "\n";
        Files.writeString(folder.resolve("2025-07.jsonl"), julyRecord);
        store(Instant.parse("2026-07-01T00:00:00Z")).prune();
        assertFalse(Files.exists(folder.resolve("2025-06.jsonl")));
        assertTrue(Files.exists(folder.resolve("2025-07.jsonl")), "七月末的记录尚未满一年");
        assertEquals(julyRecord, Files.readString(folder.resolve("2025-07.jsonl")));
        store(Instant.parse("2026-08-01T00:00:00Z")).prune();
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
        var executor = Executors.newFixedThreadPool(20);
        try {
            var start = new CyclicBarrier(20);
            List<String> keys = java.util.stream.IntStream.range(0, 800)
                    .mapToObj(i -> "novabot.core.setting." + i + "." + "x".repeat(64)).toList();
            assertTrue(JSONObject.of("target", JSONObject.of("keys", keys)).toJSONString()
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 8192);
            List<Callable<Void>> calls = new ArrayList<>();
            for (int i = 0; i < 160; i++) {
                int id = i;
                calls.add(() -> { start.await(90, TimeUnit.SECONDS);
                    store.append(JSONObject.of("operation", id, "target", JSONObject.of("keys", keys)));
                    return null; });
            }
            for (var result : executor.invokeAll(calls, 90, TimeUnit.SECONDS)) result.get();
        } finally {
            executor.shutdown();
            assertTrue(executor.awaitTermination(90, TimeUnit.SECONDS));
        }
        Set<Integer> operations = new HashSet<>();
        var lines = Files.readAllLines(directory.resolve("console-audit/2026-07.jsonl"));
        for (String line : lines) {
            var row = JSONObject.parseObject(line);
            assertEquals(800, row.getJSONObject("target").getJSONArray("keys").size());
            assertTrue(line.endsWith("}"));
            assertTrue(operations.add(row.getInteger("operation")), "记录不能重复");
        }
        assertEquals(160, lines.size());
        assertEquals(160, operations.size());
    }
}
