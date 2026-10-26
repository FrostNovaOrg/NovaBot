package org.frostnova.nova.core.service;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.analytics.SessionRecapRenderer.History;
import org.frostnova.nova.core.analytics.SessionRecapRenderer.SpokenPattern;
import org.frostnova.nova.core.util.DurableFiles;
import org.frostnova.nova.core.service.SessionRecapDeliveryStore.Status;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.*;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class SessionRecapDeliveryStoreTest {
    @TempDir Path dir;
    static final String KEY = "test:910000001:qq-onebot:FRIEND:930000001";
    static final Instant NOW = Instant.parse("2026-10-01T02:00:00Z");
    Path path() { return dir.resolve("session-recap-deliveries.json"); }
    SessionRecapDeliveryStore seeded() {
        var store = new SessionRecapDeliveryStore(path());
        assertTrue(store.observe(Set.of(KEY), NOW));
        assertTrue(store.enroll(KEY, NOW.minusSeconds(5400).toEpochMilli(), NOW.toEpochMilli(), NOW));
        return store;
    }
    @Test @DisplayName("仅保存规律且重启保留订阅时刻，避免昵称积存和旧场补发")
    void roundTripsMinimalRecordAndEnrollment() throws Exception {
        var store = seeded(); var record = store.records().get(0);
        var history = new History(record.startTime(), List.of(new SpokenPattern("slot_compare", Map.of(
                "slotA", "上午", "slotB", "晚上", "sessionsA", 6, "sessionsB", 6, "watchedA", 200, "watchedB", 100))));
        assertTrue(store.update(record, Status.SENT, "", NOW, history));
        var reopened = new SessionRecapDeliveryStore(path());
        assertEquals(NOW.toEpochMilli(), reopened.subscribedAt(KEY));
        assertEquals(Status.SENT, reopened.records().get(0).status());
        assertEquals("slot_compare", reopened.records().get(0).history().patterns().get(0).key());
        var json = JSONObject.parseObject(Files.readString(path())).getJSONArray("records").getJSONObject(0);
        assertEquals(Set.of("subscription", "startTime", "dueAt", "status", "reason", "updatedAt", "history"), json.keySet());
    }
    @Test @DisplayName("90 天后清理发送记录，避免无限积存")
    void retainsNinetyDays() {
        var store = seeded(); store.observe(Set.of(KEY), NOW.plus(Duration.ofDays(90)));
        assertEquals(1, store.records().size());
        store.observe(Set.of(KEY), NOW.plus(Duration.ofDays(90)).plusMillis(1)); assertTrue(store.records().isEmpty());
    }
    @Test @DisplayName("重复检查不能重置订阅开启时间，取消后重开从当时算，避免停机漏发或旧场补发")
    void enrollmentIsDurableAndRenewedOnlyAfterRemoval() {
        var store = seeded(); store.observe(Set.of(KEY), NOW.plusSeconds(300));
        assertEquals(NOW.toEpochMilli(), store.subscribedAt(KEY));
        store.observe(Set.of(), NOW.plusSeconds(600)); store.observe(Set.of(KEY), NOW.plusSeconds(900));
        assertEquals(NOW.plusSeconds(900).toEpochMilli(), store.subscribedAt(KEY));
    }
    @ParameterizedTest @ValueSource(strings = {"{broken", "{}", "null", "{\"subscriptions\":{},\"records\":[{}]}"})
    @DisplayName("坏件另存且重启后仍暂停，避免丢失发送历史后重复发")
    void corruptFileStaysPausedAcrossRestart(String broken) throws Exception {
        Files.writeString(path(), broken); var store = new SessionRecapDeliveryStore(path());
        assertFalse(store.ensureSaved()); assertFalse(store.warning().isBlank());
        try (var paths = Files.list(dir)) {
            var backup = paths.filter(p -> p.getFileName().toString().contains(".bad-")).findFirst().orElseThrow();
            assertEquals(broken, Files.readString(backup));
        }
        assertFalse(new SessionRecapDeliveryStore(path()).ensureSaved());
        Files.writeString(path(), "{\"subscriptions\":{},\"records\":[]}");
        assertTrue(new SessionRecapDeliveryStore(path()).ensureSaved());
    }
    @Test @DisplayName("记录和坏件仅属主可读写，避免其他系统用户看到私聊投递历史")
    void restrictsExistingFilesAndBackups() throws Exception {
        Assumptions.assumeTrue(Files.getFileAttributeView(dir, PosixFileAttributeView.class) != null,
                "文件系统不支持 POSIX 权限");
        var store = seeded(); Files.setPosixFilePermissions(path(), PosixFilePermissions.fromString("rw-r--r--"));
        store.observe(Set.of(KEY), NOW); assertEquals(DurableFiles.OWNER_ONLY, Files.getPosixFilePermissions(path()));
        Files.writeString(path(), "broken"); Files.setPosixFilePermissions(path(), PosixFilePermissions.fromString("rw-r--r--"));
        new SessionRecapDeliveryStore(path());
        try (var paths = Files.list(dir)) {
            Path backup = paths.filter(p -> p.getFileName().toString().contains(".bad-")).findFirst().orElseThrow();
            assertEquals(DurableFiles.OWNER_ONLY, Files.getPosixFilePermissions(backup));
        }
    }
    @Test @DisplayName("发送意图落盘失败保留待发，避免没记住就发送以及恢复后漏发")
    void failedSendingSaveIsRetryable() throws Exception {
        var store = seeded(); var record = store.records().get(0);
        Files.move(path(), dir.resolve("previous.json")); Files.createDirectory(path());
        Files.writeString(path().resolve("occupied"), "synthetic");
        assertFalse(store.update(record, Status.SENDING, "", NOW));
        assertEquals(Status.READY, store.records().get(0).status()); assertFalse(store.ensureSaved());
        Files.move(path(), dir.resolve("blocked"));
        assertTrue(store.ensureSaved()); assertEquals(Status.READY, new SessionRecapDeliveryStore(path()).records().get(0).status());
    }
}
