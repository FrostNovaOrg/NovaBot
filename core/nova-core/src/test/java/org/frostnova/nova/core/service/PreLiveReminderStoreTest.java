package org.frostnova.nova.core.service;

import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class PreLiveReminderStoreTest {
    @TempDir Path dir;
    final LocalDate today = LocalDate.of(2026, 10, 26);

    @Test @DisplayName("发送中或已发送重启均不重发，避免一天收到好几条提醒")
    void reservationSurvivesRestartAndOnlyStoresDatesAndStates() throws Exception {
        Path path = dir.resolve("deliveries.json");
        var store = new PreLiveReminderStore(path);
        assertTrue(store.reserve("synthetic-subscription", today));
        assertFalse(new PreLiveReminderStore(path).reserve("synthetic-subscription", today));
        store.result("synthetic-subscription", today, PreLiveReminderStore.Status.SENT);
        var restored = new PreLiveReminderStore(path);
        assertFalse(restored.reserve("synthetic-subscription", today));
        assertTrue(restored.reserve("synthetic-subscription", today.plusDays(1)));
        assertTrue(restored.reserve("another-subscription", today));
        var dates = JSON.parseObject(Files.readString(path)).getJSONObject("synthetic-subscription");
        assertEquals("SENT", dates.getString(today.toString()));
        assertEquals("SENDING", dates.getString(today.plusDays(1).toString()));
        assertEquals(2, dates.size());
        if (Files.getFileAttributeView(path, java.nio.file.attribute.PosixFileAttributeView.class) != null)
            assertEquals(org.frostnova.nova.core.util.DurableFiles.OWNER_ONLY, Files.getPosixFilePermissions(path));
    }

    @Test @DisplayName("跨日期补发同时占用原提醒日与实际发送日，避免同一提醒或同一天重复发")
    void lateMidnightDeliveryReservesBothDates() {
        var store = new PreLiveReminderStore(dir.resolve("deliveries.json"));
        assertTrue(store.reserve("synthetic-subscription", today, today.minusDays(1)));
        assertFalse(store.reserve("synthetic-subscription", today.minusDays(1)));
        assertFalse(store.reserve("synthetic-subscription", today));
        assertTrue(store.reserve("synthetic-subscription", today.plusDays(1)));
    }

    @Test @DisplayName("坏记录暂停投递且恢复有效备份后可继续，避免丢失历史后重复提醒")
    void corruptRecordsFailClosed() throws Exception {
        Path path = dir.resolve("deliveries.json");
        Files.writeString(path, "broken");
        assertFalse(new PreLiveReminderStore(path).reserve("synthetic-subscription", today));
        Files.delete(path);
        assertFalse(new PreLiveReminderStore(path).reserve("synthetic-subscription", today));
        Files.writeString(path, "{\"synthetic-subscription\":{\"2026-10-26\":\"SENT\"}}");
        var restored = new PreLiveReminderStore(path);
        assertFalse(restored.reserve("synthetic-subscription", today));
        assertTrue(restored.reserve("synthetic-subscription", today.plusDays(1)));
    }

    @Test @DisplayName("记录写不进去时禁止发送，避免重启后再次提醒")
    void failedPersistenceBlocksSending() throws Exception {
        Path parent = dir.resolve("not-a-directory"); Files.writeString(parent, "synthetic");
        var store = new PreLiveReminderStore(parent.resolve("deliveries.json"));
        assertFalse(store.reserve("synthetic-subscription", today));
    }
}
