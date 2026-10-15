package org.frostnova.nova.bilibili.service;

import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.event.live.BilibiliDanmuEvent;
import org.frostnova.nova.bilibili.event.live.BilibiliLiveOffEvent;
import org.frostnova.nova.bilibili.model.BilibiliUserInfo;
import org.frostnova.nova.bilibili.model.Room;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.listener.NovaDefaultLiveOffEventListener;
import org.frostnova.nova.core.model.PushUser;
import org.frostnova.nova.core.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Duration;
import org.springframework.scheduling.TaskScheduler;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BilibiliStreamAdoptionTest {
    private static final String PLATFORM = "bilibili";
    private static final long UID = 9200000001L;
    private static final long OLD = Instant.parse("2026-10-10T01:00:00Z").toEpochMilli();
    private static final long CURRENT = OLD + 86400000;
    @TempDir Path dir;
    private DefaultLiveDataService data;
    private LiveSessionArchive sessions;
    private LiveDetailArchive details;
    private BilibiliLiveStatsAggregator stats;
    private NovaDefaultLiveOffEventListener off;
    private LiveSessionRecovery recovery;
    private PushUser user;

    @BeforeEach
    void setup() {
        var props = new NovaCoreProperties();
        props.getLive().setLiveDataPath(dir.resolve("data.json").toString());
        data = new DefaultLiveDataService(props);
        sessions = new LiveSessionArchive(props);
        details = new LiveDetailArchive(props);
        var history = new LiveRoomInfoHistory(new NovaStateStore(props));
        var archiver = new LiveSessionDetailArchiver(data, history, details);
        recovery = new LiveSessionRecovery(data, sessions, history, archiver);
        off = new NovaDefaultLiveOffEventListener(data, sessions, new LiveInterventionTracker(), history, archiver);
        stats = new BilibiliLiveStatsAggregator(data, details);
        user = new PushUser();
        user.setPlatform(PLATFORM); user.setUid(UID); user.setRoomId(9200000002L);
        user.setUname("sample"); user.setEnabled(true);
        data.setLiveStatus(PLATFORM, UID, true);
        data.setLiveStartTime(PLATFORM, UID, OLD);
        stats.onDanmu(danmu(OLD + 1000, "previous"));
    }

    private org.frostnova.nova.core.model.LiveStreamerInfo streamer() {
        return new org.frostnova.nova.core.model.LiveStreamerInfo(UID, "sample", 9200000002L);
    }

    private BilibiliDanmuEvent danmu(long at, String text) {
        return new BilibiliDanmuEvent(streamer(), new BilibiliUserInfo(9200000003L, "viewer"),
                text, text, Instant.ofEpochMilli(at));
    }

    private void adopt(long apiStart) {
        var api = mock(BilibiliApiUtil.class);
        var source = mock(AbstractDataSource.class);
        var publisher = mock(ApplicationEventPublisher.class);
        var room = new Room(); room.setLiveStatus(1); room.setLiveStartTime(apiStart / 1000);
        when(source.getUsers(PLATFORM)).thenReturn(List.of(user));
        when(api.getLiveInfoByUids(anySet())).thenReturn(Map.of(UID, room));
        var scheduler = mock(TaskScheduler.class);
        new BilibiliBackupLivePushService(api, new NovaBilibiliProperties(), publisher,
                scheduler, new BilibiliLiveStateGate(data), data, recovery).start(source);
        var poll = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).scheduleAtFixedRate(poll.capture(), any(Duration.class));
        poll.getValue().run();
        verify(api).getLiveInfoByUids(anySet());
        verifyNoInteractions(publisher);
    }

    @Test @DisplayName("重启接管新场不能续进已归档旧场的统计与归档")
    void archivedPreviousStreamMustNotBeResumed() {
        off.onLiveOffEvent(new BilibiliLiveOffEvent(streamer(), Instant.ofEpochMilli(OLD + 3600000)));
        assertEquals(Optional.of(OLD), data.getLiveStartTime(PLATFORM, UID));
        // 新建轮询服务模拟重启首轮；保留的正是上一进程已下播的账与归档。
        adopt(CURRENT);
        assertEquals(Optional.of(CURRENT), data.getLiveStartTime(PLATFORM, UID), "新场不能沿用已归档旧场起点");
        stats.onDanmu(danmu(CURRENT + 1000, "current"));
        assertFalse(stats.recoverDanmu(danmu(CURRENT + 2000, "late"), OLD, java.util.Set.of()),
                "旧场补录不能进入新场");
        assertTrue(stats.recoverDanmu(danmu(CURRENT + 2000, "recovered"), CURRENT, java.util.Set.of()));
        assertEquals(2d, data.getLiveMetric(PLATFORM, UID, "danmu_count"));
        off.onLiveOffEvent(new BilibiliLiveOffEvent(streamer(), Instant.ofEpochMilli(CURRENT + 3600000)));
        var saved = sessions.find(0, Long.MAX_VALUE);
        assertEquals(2, saved.size(), "两场不能按续场覆盖旧档");
        assertEquals(1d, saved.get(0).metrics().get("danmu_count"));
        assertEquals(2d, saved.get(1).metrics().get("danmu_count"));
        assertEquals(3600, saved.get(1).durationSeconds());
        assertEquals(Optional.of(CURRENT), data.getLiveStartTime(PLATFORM, UID), "下播报告仍需本场起点");
        assertEquals(Optional.of(CURRENT + 3600000), data.getLiveEndTime(PLATFORM, UID));
        assertEquals(2d, data.getLiveMetric(PLATFORM, UID, "danmu_count"), "下播报告仍需本场统计");
        assertEquals(List.of("previous"), details.readDanmu(PLATFORM, UID, OLD).stream().map(r -> r.text()).toList());
        assertEquals(List.of("current", "recovered"), details.readDanmu(PLATFORM, UID, CURRENT).stream().map(r -> r.text()).toList());
    }

    @ParameterizedTest(name = "{displayName}：状态完整={0}") @ValueSource(booleans = {true, false})
    @DisplayName("同一场重启续播保留起点统计与明细")
    void ongoingStreamMustKeepItsIdentity(boolean statusPresent) {
        if (!statusPresent) data.setLiveStatus(PLATFORM, UID, false);
        adopt(OLD);
        assertEquals(Optional.of(OLD), data.getLiveStartTime(PLATFORM, UID));
        stats.onDanmu(danmu(OLD + 2000, "continued"));
        off.onLiveOffEvent(new BilibiliLiveOffEvent(streamer(), Instant.ofEpochMilli(OLD + 3600000)));
        var saved = sessions.find(0, Long.MAX_VALUE);
        assertEquals(1, saved.size());
        assertEquals(OLD, saved.get(0).startTime());
        assertEquals(2d, saved.get(0).metrics().get("danmu_count"), "正常续场不能清掉重启前的统计");
        assertEquals(2, details.readDanmu(PLATFORM, UID, OLD).size());
    }
}
