package org.frostnova.nova.interact.auto;

import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.service.NovaStateStore;
import org.frostnova.nova.interact.danmaku.DanmakuResult;
import org.frostnova.nova.interact.danmaku.LiveDanmakuService.Room;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InteractStoreTest {
    private static final Room FIRST = new Room(1, "甲", 101);
    private static final Room SECOND = new Room(2, "乙", 102);
    @TempDir Path dir;

    private NovaStateStore state() {
        var core = new NovaCoreProperties();
        core.getLive().setLiveDataPath(dir.resolve("live.json").toString());
        return new NovaStateStore(core);
    }

    @Test
    @DisplayName("试发和自动发送同时记入时顺序颠倒，翻页找不到较新的记录")
    void ordersConcurrentArrivalsBeforeFilteringAndPaging() {
        var store = new InteractStore(state());
        Instant now = Instant.now();
        for (int i = 0; i < 23; i++) store.drop(FIRST, "timed", "条" + i, "超过时限", now.minusSeconds(30 - i));
        store.drop(FIRST, "timed", "先发生，后写入", "超过时限", now.minusSeconds(60));
        store.result(FIRST, "test", new DanmakuResult(true, null, "已发出", 101, "试发", null, 0, null), now);
        var first = store.history(1, InteractStore.Outcome.DROPPED, 1, now);
        assertEquals(24, first.total());
        assertEquals("条22", first.rows().get(0).getString("text"));
        assertEquals("先发生，后写入", store.history(1, InteractStore.Outcome.DROPPED, 2, now).rows().get(3).getString("text"));
        assertEquals("test", store.history(1, null, 1, now).rows().get(0).getString("feature"));
    }

    @Test
    @DisplayName("繁忙主播的发送记录挤掉其他房间，或七天以前的记录长期留在磁盘")
    void limitsEachStreamerAndExpiresIdleHistoryOnDisk() {
        NovaStateStore state = state();
        var store = new InteractStore(state);
        Instant now = Instant.now();
        for (int i = 0; i <= 2000; i++) store.drop(FIRST, "timed", String.valueOf(i), "排队已满", now);
        store.drop(SECOND, "timed", "另一间", "没有观众发言", now);
        assertEquals(2000, store.history(1, null, 1, now).total());
        assertEquals("1", store.history(1, null, 100, now).rows().get(19).getString("text"));
        assertEquals(1, store.history(2, null, 1, now).total());
        state.save();
        assertEquals(2000, new InteractStore(state()).history(1, null, 1, now).total());
        var later = now.plus(7, ChronoUnit.DAYS);
        assertEquals(0, store.history(1, null, 1, later).total());
        store.pruneHistory(later);
        state.save();
        assertTrue(state().namespace("LiveInteractHistory").isEmpty());
    }

    @Test
    @DisplayName("保存报错后页面却显示总开关已打开，下次启动时意外启用")
    void failedWriteLeavesPreviousSettingsVisible() {
        var state = spy(state());
        doReturn(false).when(state).trySave();
        var store = new InteractStore(state);
        var enabled = new RoomSettings(true, 6, Map.of("timed", true), List.of());
        assertThrows(IllegalStateException.class, () -> store.save(1, enabled));
        assertEquals(RoomSettings.DEFAULT, store.settings(1));
    }
}
