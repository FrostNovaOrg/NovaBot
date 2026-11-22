package org.frostnova.nova.console.assistant;

import com.sun.jna.Pointer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.frostnova.nova.console.assistant.AssistantSqlDatabase.HEAP_BYTES;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AssistantSqlNativeTest {
    @ParameterizedTest
    @ValueSource(ints = {3_031_000, 3_032_000, 3_033_000, 3_034_001, 3_035_000})
    @DisplayName("具备所需能力的 SQLite 3.31–3.35 仍会被版本挡掉")
    void acceptsCapableOlderSqlite(int version) {
        assertNull(AssistantSqlNative.missingCapability(workingHeap(version), symbol -> {}));
    }

    @Test
    @DisplayName("硬堆上限引入前的 SQLite 版本不能用于只读查询")
    void rejectsVersionsBeforeHardHeapLimit() {
        String reason = AssistantSqlNative.missingCapability(workingHeap(3_030_001), symbol -> {});
        assertNotNull(reason);
        assertTrue(reason.contains("3.31"), reason);
    }

    @ParameterizedTest
    @ValueSource(strings = {"sqlite3_deserialize", "sqlite3_progress_handler", "sqlite3_hard_heap_limit64"})
    @DisplayName("缺少反序列化、超时或堆上限函数的库可能被启用，探测应指出缺少的函数")
    void reportsMissingRequiredSymbol(String missing) {
        String reason = AssistantSqlNative.missingCapability(workingHeap(3_036_000), symbol -> {
            if (missing.equals(symbol)) throw new UnsatisfiedLinkError(symbol);
        });
        assertNotNull(reason);
        assertTrue(reason.contains(missing), reason);
    }

    @Test
    @DisplayName("硬堆上限读不回的库可能被误认为能限制查询内存")
    void rejectsUnreadableHardHeapLimit() {
        var api = workingHeap(3_036_000);
        when(api.sqlite3_hard_heap_limit64(-1)).thenReturn(0L, 0L);
        String reason = AssistantSqlNative.missingCapability(api, symbol -> {});
        assertNotNull(reason);
        assertTrue(reason.contains("硬堆上限"), reason);
    }

    @Test
    @DisplayName("只能把临时表放到文件的库不能用于只读查询")
    void rejectsFileOnlyTempStore() {
        var api = workingHeap(3_036_000);
        when(api.sqlite3_compileoption_used("TEMP_STORE=0")).thenReturn(1);
        String reason = AssistantSqlNative.missingCapability(api, symbol -> {});
        assertNotNull(reason);
        assertTrue(reason.contains("内存临时表"), reason);
    }

    private static AssistantSqlNative.Sqlite workingHeap(int version) {
        var api = mock(AssistantSqlNative.Sqlite.class);
        when(api.sqlite3_libversion_number()).thenReturn(version);
        when(api.sqlite3_hard_heap_limit64(-1)).thenReturn(0L, (long) HEAP_BYTES);
        when(api.sqlite3_memory_used()).thenReturn(0L, 1024L);
        when(api.sqlite3_malloc64(1024)).thenReturn(Pointer.createConstant(1));
        return api;
    }
}
