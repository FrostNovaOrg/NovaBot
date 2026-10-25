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

class StreamerProfileStoreTest {
    private static final long UID = 990000000101L, TIME = 1792454400000L;
    @TempDir Path dir;
    private StreamerProfileStore store;
    @BeforeEach void setUp() { store = new StreamerProfileStore(dir); }

    @Test @DisplayName("新档案默认第一档且不暂停，平台和主播编号分别隔离")
    void defaultsAndSubjectsStaySeparate() throws Exception {
        var fresh = store.read("bilibili", UID);
        assertEquals(1, fresh.tier()); assertFalse(fresh.paused()); assertEquals(Status.READY, fresh.status());
        store.update("bilibili", UID, p -> populated());
        assertEquals(fresh, store.read("another", UID)); assertEquals(fresh, store.read("bilibili", UID + 1));
        assertNotEquals(store.path("bilibili", UID), store.path("another", UID));
        assertEquals(dir, store.path("../example", UID).getParent());
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
    @Test @DisplayName("程序调用也存不进第三档，不让后续投递读到未开放的档位")
    void unsupportedTierCannotBeStored() {
        assertThrows(IllegalArgumentException.class, () -> store.update("bilibili", UID,
                p -> new StreamerProfile(3, TIME, List.of(), List.of(), false, null, Status.READY)));
        assertEquals(1, store.read("bilibili", UID).tier());
    }
    @Test @DisplayName("磁盘写入失败须交给命令回复失败，不能返回保存成功")
    void writeFailureIsReported() throws Exception {
        Path blocked = dir.resolve("blocked"); Files.writeString(blocked, "occupied");
        var unavailable = new StreamerProfileStore(blocked);
        assertThrows(java.io.IOException.class, () -> unavailable.update("bilibili", UID,
                p -> new StreamerProfile(2, TIME, List.of(), List.of(), true, TIME, p.status())));
    }
    private static StreamerProfile populated() {
        return new StreamerProfile(2, TIME, List.of(new Concern("回访", TIME + 1)),
                List.of(new Exclusion(Pattern.slot_compare, TIME + 2)), true, TIME + 3, Status.READY);
    }
}
