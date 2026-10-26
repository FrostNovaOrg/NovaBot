package org.frostnova.nova.core.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.alibaba.fastjson2.JSON;
import org.frostnova.nova.core.util.DurableFiles;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.frostnova.nova.core.service.StreamerProfile.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StreamerProfileStoreTest {
    private static final long UID = 990000000101L, TIME = 1792454400000L;
    @TempDir Path dir;
    private StreamerProfileStore store;
    @BeforeEach void setUp() { store = new StreamerProfileStore(dir); }

    @Test @DisplayName("新档案默认第二档且不暂停，避免未选档的主播仍收到第一档")
    void defaultsAndSubjectsStaySeparate() throws Exception {
        var fresh = store.read("bilibili", UID);
        assertEquals(2, fresh.tier()); assertNull(fresh.tierSetAt());
        assertFalse(fresh.paused()); assertEquals(Status.READY, fresh.status());
        store.update("bilibili", UID, p -> populated());
        assertEquals(fresh, store.read("another", UID)); assertEquals(fresh, store.read("bilibili", UID + 1));
        assertNotEquals(store.path("bilibili", UID), store.path("another", UID));
        assertEquals(dir, store.path("../example", UID).getParent());
    }
    @ParameterizedTest @ValueSource(ints = {1, 2})
    @DisplayName("未选档的档案读为第二档且不当作坏件，避免升级后仍发第一档或停发")
    void unsetTierReadsAsDefaultWithoutQuarantine(int tier) throws Exception {
        Path file = store.path("bilibili", UID);
        String content = "{\"tier\":" + tier + ",\"tierSetAt\":null,\"concerns\":[],\"exclusions\":[],"
                + "\"paused\":false,\"pauseSetAt\":null}";
        Files.writeString(file, content);
        var loaded = store.read("bilibili", UID);
        assertEquals(Status.READY, loaded.status()); assertEquals(2, loaded.tier()); assertNull(loaded.tierSetAt());
        assertEquals(content, Files.readString(file));
        try (var files = Files.list(dir)) { assertEquals(List.of(file), files.toList()); }
    }
    @ParameterizedTest @ValueSource(ints = {1, 2})
    @DisplayName("自己选过的档位及时间跨重启保留，避免选了第一档却被默认覆盖")
    void explicitTierSurvivesRestart(int tier) throws Exception {
        var selected = new StreamerProfile(tier, 0L, List.of(), List.of(), false, null, Status.READY);
        store.update("bilibili", UID, p -> selected);
        assertEquals(selected, new StreamerProfileStore(dir).read("bilibili", UID));
    }
    @Test @DisplayName("未选档的第二档也能保存其他设置，避免只记在意条目就使档案变成坏件")
    void tierTwoWithoutSelectionTimeCanBePersisted() throws Exception {
        var profile = new StreamerProfile(2, null, List.of(new Concern("回访", TIME)), List.of(),
                false, null, Status.READY);
        store.update("bilibili", UID, p -> profile);
        assertEquals(profile, new StreamerProfileStore(dir).read("bilibili", UID));
        assertTrue(JSON.parseObject(Files.readString(store.path("bilibili", UID))).containsKey("tierSetAt"));
    }
    @Test @DisplayName("重启后全部结构化设置与时间仍在，只存条目且文件仅属主可读写")
    void roundTripPersistsOnlyStructuredFields() throws Exception {
        store.update("bilibili", UID, p -> populated());
        assertEquals(populated(), new StreamerProfileStore(dir).read("bilibili", UID));
        var file = store.path("bilibili", UID);
        var json = JSON.parseObject(Files.readString(file));
        assertEquals(java.util.Set.of("tier", "tierSetAt", "concerns", "exclusions", "paused", "pauseSetAt"), json.keySet());
        if (Files.getFileAttributeView(file, java.nio.file.attribute.PosixFileAttributeView.class) != null)
            assertEquals(DurableFiles.OWNER_ONLY, Files.getPosixFilePermissions(file));
        assertFalse(Files.exists(DurableFiles.temporary(file)));
    }
    @Test @DisplayName("档案损坏会另存原件、记告警并保留读错状态，避免重启后误发")
    void corruptionRemainsVisibleAcrossRestartsAndEdits() throws Exception {
        var file = store.path("bilibili", UID);
        String broken = "{\"paused\":true,";
        Files.writeString(file, broken);
        var logger = (Logger) LoggerFactory.getLogger(StreamerProfileStore.class);
        var appender = new ListAppender<ILoggingEvent>(); appender.start(); logger.addAppender(appender);
        try {
            var empty = store.read("bilibili", UID);
            assertEquals(StreamerProfile.empty(Status.UNREADABLE), empty);
            assertEquals(1, appender.list.stream().filter(e -> e.getLevel() == Level.WARN && e.getFormattedMessage().contains("档案读不出")).count());
            assertTrue(appender.list.stream().noneMatch(e -> e.getFormattedMessage().contains(broken)));
            assertTrue(appender.list.stream().anyMatch(e -> e.getFormattedMessage().contains("恢复")
                    && e.getFormattedMessage().contains("档案已核对")), "告警必须说明恢复途径");
            List<Path> backups;
            try (var files = Files.list(dir)) { backups = files.filter(p -> p.getFileName().toString().contains(".bad-")).toList(); }
            assertEquals(1, backups.size()); assertEquals(broken, Files.readString(backups.get(0)));
            if (Files.getFileAttributeView(file, java.nio.file.attribute.PosixFileAttributeView.class) != null)
                assertEquals(DurableFiles.OWNER_ONLY, Files.getPosixFilePermissions(backups.get(0)));
            var restarted = new StreamerProfileStore(dir);
            assertEquals(Status.UNREADABLE, restarted.read("bilibili", UID).status());
            restarted.update("bilibili", UID, p -> new StreamerProfile(2, TIME, List.of(), List.of(), false, TIME, p.status()));
            assertEquals(Status.UNREADABLE, new StreamerProfileStore(dir).read("bilibili", UID).status());
        } finally { logger.detachAppender(appender); appender.stop(); }
    }
    @ParameterizedTest
    @ValueSource(strings = {"null", "{}", "[]", "{\"tier\":3,\"tierSetAt\":null,\"concerns\":[],\"exclusions\":[],\"paused\":false,\"pauseSetAt\":null}",
            "{\"tier\":1,\"tierSetAt\":null,\"concerns\":[],\"exclusions\":[{\"type\":\"unknown\",\"setAt\":1}],\"paused\":false,\"pauseSetAt\":null}"})
    @DisplayName("能解析的错误结构也须当作坏件，不能默默恢复默认发送")
    void invalidStructureIsNotAReadyProfile(String content) throws Exception {
        Files.writeString(store.path("bilibili", UID), content);
        assertEquals(Status.UNREADABLE, store.read("bilibili", UID).status());
    }
    @Test @DisplayName("非 UTF-8 档案按坏件另存并告警，避免误判临时故障后无法恢复发送")
    void invalidUtf8IsQuarantinedAndCanRecover() throws Exception {
        Path file = store.path("bilibili", UID);
        byte[] broken = {(byte) 0xFF, (byte) 0xFE, 0x78, (byte) 0xC3};
        Files.write(file, broken);
        var logger = (Logger) LoggerFactory.getLogger(StreamerProfileStore.class);
        var appender = new ListAppender<ILoggingEvent>(); appender.start(); logger.addAppender(appender);
        try {
            assertEquals(Status.UNREADABLE, store.read("bilibili", UID).status());
            assertTrue(appender.list.stream().anyMatch(e -> e.getLevel() == Level.WARN
                    && e.getFormattedMessage().contains("档案读不出") && e.getFormattedMessage().contains("恢复")));
            assertFalse(Files.exists(file));
            List<Path> backups;
            try (var files = Files.list(dir)) { backups = files.filter(p -> p.getFileName().toString().contains(".bad-")).toList(); }
            assertEquals(1, backups.size()); assertArrayEquals(broken, Files.readAllBytes(backups.get(0)));
            var restarted = new StreamerProfileStore(dir);
            assertEquals(Status.UNREADABLE, restarted.read("bilibili", UID).status());
            assertEquals(StreamerProfile.empty(Status.READY), restarted.recover("bilibili", UID));
            assertEquals(StreamerProfile.empty(Status.READY), new StreamerProfileStore(dir).read("bilibili", UID));
        } finally { logger.detachAppender(appender); appender.stop(); }
    }
    @Test @DisplayName("程序调用也存不进第三档，不让后续投递读到未开放的档位")
    void unsupportedTierCannotBeStored() {
        assertThrows(IllegalArgumentException.class, () -> store.update("bilibili", UID,
                p -> new StreamerProfile(3, TIME, List.of(), List.of(), false, null, Status.READY)));
        assertEquals(2, store.read("bilibili", UID).tier());
    }
    @Test @DisplayName("磁盘写入失败须交给命令回复失败，不能返回保存成功")
    void writeFailureIsReported() throws Exception {
        Path blocked = dir.resolve("blocked"); Files.writeString(blocked, "occupied");
        var unavailable = new StreamerProfileStore(blocked);
        assertThrows(java.io.IOException.class, () -> unavailable.update("bilibili", UID,
                p -> new StreamerProfile(2, TIME, List.of(), List.of(), true, TIME, p.status())));
    }
    @Test @DisplayName("暂时读取失败不移动好文件或永久停发，下次读取仍保留暂停和设置")
    void temporaryReadFailureDoesNotQuarantine() throws Exception {
        store.update("bilibili", UID, p -> populated());
        Path file = store.path("bilibili", UID);
        String original = Files.readString(file);
        try (var files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            files.when(() -> Files.readString(file)).thenThrow(new java.io.IOException("temporary"));
            assertEquals("TEMPORARILY_UNREADABLE", store.read("bilibili", UID).status().name());
            assertThrows(java.io.IOException.class, () -> store.update("bilibili", UID,
                    p -> new StreamerProfile(1, TIME, List.of(), List.of(), false, TIME, p.status())));
        }
        assertEquals(original, Files.readString(file));
        try (var files = Files.list(dir)) { assertEquals(List.of(file), files.toList()); }
        assertEquals(populated(), new StreamerProfileStore(dir).read("bilibili", UID));
    }
    private static StreamerProfile populated() {
        return new StreamerProfile(2, TIME, List.of(new Concern("回访", TIME + 1)),
                List.of(new Exclusion(Pattern.slot_compare, TIME + 2)), true, TIME + 3, Status.READY);
    }
}
