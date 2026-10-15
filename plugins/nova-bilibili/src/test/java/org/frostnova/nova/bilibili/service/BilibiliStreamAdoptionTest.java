package org.frostnova.nova.bilibili.service;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.event.live.BilibiliDanmuEvent;
import org.frostnova.nova.bilibili.event.live.BilibiliLiveOffEvent;
import org.frostnova.nova.bilibili.model.BilibiliUserInfo;
import org.frostnova.nova.bilibili.model.Room;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.enums.LiveEndReason;
import org.frostnova.nova.core.listener.NovaDefaultLiveOffEventListener;
import org.frostnova.nova.core.model.PushUser;
import org.frostnova.nova.core.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import java.time.Duration;
import org.springframework.scheduling.TaskScheduler;

import java.nio.file.Path;
import java.nio.file.Files;
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
    private NovaCoreProperties props;
    private DefaultLiveDataService data;
    private LiveDataService liveData;
    private double mergedDanmu;
    private LiveSessionArchive sessions;
    private LiveDetailArchive details;
    private BilibiliLiveStatsAggregator stats;
    private NovaDefaultLiveOffEventListener off;
    private LiveSessionRecovery recovery;
    private PushUser user;
    private Room polledRoom;
    private Runnable nextPoll;

    @BeforeEach
    void setup() {
        props = new NovaCoreProperties();
        props.getLive().setLiveDataPath(dir.resolve("data.json").toString());
        props.getLive().setAutoSaveLiveDataInterval(3600);
        data = new DefaultLiveDataService(props);
        wireServices();
        user = new PushUser();
        user.setPlatform(PLATFORM); user.setUid(UID); user.setRoomId(9200000002L);
        user.setUname("sample"); user.setEnabled(true);
        data.setLiveStatus(PLATFORM, UID, true);
        data.setLiveStartTime(PLATFORM, UID, OLD);
        stats.onDanmu(danmu(OLD + 1000, "previous"));
    }

    private void wireServices() {
        var store = mock(RedisTotalDataStore.class);
        doAnswer(inv -> {
            RedisTotalDataStore.LiveSnapshot snapshot = inv.getArgument(2);
            mergedDanmu += snapshot.metrics().getOrDefault("danmu_count", 0d);
            return true;
        }).when(store).merge(any(), any(), any());
        var total = mock(TotalDataStorage.class);
        when(total.active()).thenReturn(store);
        liveData = new CompositeLiveDataService(data, total);
        sessions = new LiveSessionArchive(props);
        details = new LiveDetailArchive(props);
        var history = new LiveRoomInfoHistory(new NovaStateStore(props));
        var archiver = new LiveSessionDetailArchiver(liveData, history, details);
        recovery = new LiveSessionRecovery(liveData, sessions, history, archiver, props);
        off = new NovaDefaultLiveOffEventListener(liveData, sessions, new LiveInterventionTracker(), history, archiver);
        stats = new BilibiliLiveStatsAggregator(liveData, details);
    }

    @AfterEach
    void close() {
        data.onContextClosedEvent();
    }

    private void restart(Long stoppedAt) throws Exception {
        data.onContextClosedEvent();
        Path path = Path.of(props.getLive().getLiveDataPath());
        var saved = JSONObject.parseObject(Files.readString(path));
        // 只移动保存时刻，模拟停机；状态、计数、累计水位与归档均从真实文件恢复。
        if (stoppedAt == null) saved.remove("LastSaveTime");
        else saved.put("LastSaveTime", stoppedAt);
        Files.writeString(path, saved.toJSONString());
        data = new DefaultLiveDataService(props);
        data.onApplicationReadyEvent();
        wireServices();
        recovery.onApplicationReadyEvent();
        assertEquals(Optional.ofNullable(stoppedAt), data.getLastSaveTime());
    }

    private org.frostnova.nova.core.model.LiveStreamerInfo streamer() {
        return new org.frostnova.nova.core.model.LiveStreamerInfo(UID, "sample", 9200000002L);
    }

    private BilibiliDanmuEvent danmu(long at, String text) {
        return new BilibiliDanmuEvent(streamer(), new BilibiliUserInfo(9200000003L, "viewer"),
                text, text, Instant.ofEpochMilli(at));
    }

    private void adopt(long apiStart) {
        adopt(apiStart, false);
    }

    private void adopt(long apiStart, boolean closesPrevious) {
        var api = mock(BilibiliApiUtil.class);
        var source = mock(AbstractDataSource.class);
        var publisher = mock(ApplicationEventPublisher.class);
        doAnswer(inv -> {
            off.onLiveOffEvent(inv.getArgument(0));
            return null;
        }).when(publisher).publishEvent(any(BilibiliLiveOffEvent.class));
        var room = new Room(); room.setLiveStatus(1); room.setLiveStartTime(apiStart / 1000);
        polledRoom = room;
        when(source.getUsers(PLATFORM)).thenReturn(List.of(user));
        when(api.getLiveInfoByUids(anySet())).thenReturn(Map.of(UID, room));
        var scheduler = mock(TaskScheduler.class);
        new BilibiliBackupLivePushService(api, new NovaBilibiliProperties(), publisher,
                scheduler, new BilibiliLiveStateGate(liveData), liveData, recovery).start(source);
        var poll = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).scheduleAtFixedRate(poll.capture(), any(Duration.class));
        nextPoll = poll.getValue();
        nextPoll.run();
        verify(api).getLiveInfoByUids(anySet());
        if (closesPrevious) {
            var event = ArgumentCaptor.forClass(BilibiliLiveOffEvent.class);
            verify(publisher).publishEvent(event.capture());
            assertEquals(data.getLastSaveTime().orElseThrow(), event.getValue().getTimestamp());
            verify(publisher, never()).publishEvent(any(org.frostnova.nova.bilibili.event.live.BilibiliLiveOnEvent.class));
        } else {
            verifyNoInteractions(publisher);
        }
    }

    @Test @DisplayName("重启接管新场不能续进已归档旧场的统计与归档")
    void archivedPreviousStreamMustNotBeResumed() throws Exception {
        off.onLiveOffEvent(new BilibiliLiveOffEvent(streamer(), Instant.ofEpochMilli(OLD + 3600000)));
        restart(OLD + 3601000);
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

    @Test @DisplayName("平台仍报原起点时重启续场不能覆盖前半场计数与原文")
    void archivedSameStartMustResume() throws Exception {
        off.onLiveOffEvent(new BilibiliLiveOffEvent(streamer(), Instant.ofEpochMilli(OLD + 3600000)));
        restart(OLD + 3601000);
        adopt(OLD);
        finishResumedStream(OLD + 3660000);
    }

    @ParameterizedTest(name = "{displayName}：间隔={0}秒") @ValueSource(ints = {60, 300})
    @DisplayName("重连窗口内重启不能把前后半场拆开")
    void archivedWithinWindowMustResume(int seconds) throws Exception {
        long end = OLD + 3600000;
        off.onLiveOffEvent(new BilibiliLiveOffEvent(streamer(), Instant.ofEpochMilli(end)));
        restart(end + 1000);
        adopt(end + seconds * 1000L);
        assertEquals(Optional.of(OLD), data.getLiveStartTime(PLATFORM, UID), "窗口内续场不能另起一场");
        finishResumedStream(end + seconds * 1000L);
    }

    @Test @DisplayName("重启续场判断遵守配置的重连窗口")
    void archivedReconnectUsesConfiguredWindow() throws Exception {
        props.getLive().setReconnectInterval(60);
        long end = OLD + 3600000;
        off.onLiveOffEvent(new BilibiliLiveOffEvent(streamer(), Instant.ofEpochMilli(end)));
        restart(end + 1000);
        long apiStart = end + 61000;
        adopt(apiStart);
        assertEquals(Optional.of(apiStart), data.getLiveStartTime(PLATFORM, UID), "超出配置窗口必须另起新场");
        stats.onDanmu(danmu(apiStart + 1000, "current"));
        off.onLiveOffEvent(new BilibiliLiveOffEvent(streamer(), Instant.ofEpochMilli(apiStart + 3600000)));
        var saved = sessions.find(0, Long.MAX_VALUE);
        assertEquals(2, saved.size());
        assertEquals(1d, saved.get(0).metrics().get("danmu_count"));
        assertEquals(1d, saved.get(1).metrics().get("danmu_count"));
        assertEquals(2d, mergedDanmu);
    }

    private void finishResumedStream(long continuedAt) {
        stats.onDanmu(danmu(continuedAt + 1000, "continued"));
        stats.onDanmu(danmu(continuedAt + 2000, "continued again"));
        off.onLiveOffEvent(new BilibiliLiveOffEvent(streamer(), Instant.ofEpochMilli(continuedAt + 3600000)));
        var saved = sessions.find(0, Long.MAX_VALUE);
        assertEquals(1, saved.size(), "续场最终只留整场归档");
        assertEquals(3d, saved.get(0).metrics().get("danmu_count"), "续场不能覆盖前半场计数");
        assertEquals(3d, mergedDanmu, "续场累计不能重复或丢失");
        assertEquals(List.of("previous", "continued", "continued again"),
                details.readDanmu(PLATFORM, UID, OLD).stream().map(r -> r.text()).toList(), "续场原文必须重新开放采集");
    }

    @ParameterizedTest(name = "{displayName}：间隔={0}秒，短停={1}")
    @CsvSource({"60,false", "300,false", "301,false", "301,true"})
    @DisplayName("停机期间重新开播按重连窗口保留或分开统计归档")
    void unarchivedRestartUsesReconnectWindow(int seconds, boolean recent) throws Exception {
        long stoppedAt = recent ? (System.currentTimeMillis() / 1000 - 400) * 1000 : OLD + 3600000;
        restart(stoppedAt);
        assertEquals(Optional.of(true), data.getLiveStatus(PLATFORM, UID));
        assertTrue(sessions.find(0, Long.MAX_VALUE).isEmpty());
        long apiStart = stoppedAt + seconds * 1000L;
        boolean newStream = seconds > props.getLive().getReconnectInterval();
        adopt(apiStart, recent && newStream);
        assertEquals(Optional.of(newStream ? apiStart : OLD), data.getLiveStartTime(PLATFORM, UID),
                "停机后新场不能续进窗口外旧场，窗口内也不能拆场");
        stats.onDanmu(danmu(apiStart + 1000, "continued"));
        if (recent) {
            polledRoom.setLiveStatus(0);
            nextPoll.run();
            assertEquals(Optional.of(false), data.getLiveStatus(PLATFORM, UID), "接管后真下播不能被状态闸门拦住");
        } else {
            off.onLiveOffEvent(new BilibiliLiveOffEvent(streamer(), Instant.ofEpochMilli(apiStart + 3600000)));
        }
        var saved = sessions.find(0, Long.MAX_VALUE);
        assertEquals(newStream ? 2 : 1, saved.size());
        assertEquals(newStream ? 1d : 2d, saved.get(0).metrics().get("danmu_count"));
        if (newStream) {
            assertEquals(stoppedAt, saved.get(0).endTime());
            assertEquals(recent ? LiveEndReason.NORMAL : LiveEndReason.UNCLOSED, saved.get(0).endReason());
            assertEquals(1d, saved.get(1).metrics().get("danmu_count"));
            assertEquals(List.of("previous"), details.readDanmu(PLATFORM, UID, OLD).stream().map(r -> r.text()).toList());
            assertEquals(List.of("continued"), details.readDanmu(PLATFORM, UID, apiStart).stream().map(r -> r.text()).toList());
        } else {
            assertEquals(2, details.readDanmu(PLATFORM, UID, OLD).size());
        }
        assertEquals(2d, mergedDanmu);
    }

    @Test @DisplayName("停机起点缺失且接口起点改变时不能把新场混进旧场")
    void missingDowntimeStartSeparatesStreams() throws Exception {
        restart(null);
        adopt(CURRENT);
        assertEquals(Optional.of(CURRENT), data.getLiveStartTime(PLATFORM, UID), "无法判断重连窗口时不能沿用旧场");
        stats.onDanmu(danmu(CURRENT + 1000, "current"));
        off.onLiveOffEvent(new BilibiliLiveOffEvent(streamer(), Instant.ofEpochMilli(CURRENT + 3600000)));
        var saved = sessions.find(0, Long.MAX_VALUE);
        assertEquals(2, saved.size());
        assertEquals(LiveEndReason.UNCLOSED, saved.get(0).endReason());
        assertEquals(OLD, saved.get(0).endTime());
        assertEquals(1d, saved.get(0).metrics().get("danmu_count"));
        assertEquals(1d, saved.get(1).metrics().get("danmu_count"));
        assertEquals(2d, mergedDanmu);
    }

    @ParameterizedTest(name = "{displayName}：接口起点={0}") @ValueSource(longs = {0, -1000})
    @DisplayName("平台未给有效起点时不能从1970年计算直播时长")
    void nonPositiveApiStartUsesNow(long apiStart) {
        off.onLiveOffEvent(new BilibiliLiveOffEvent(streamer(), Instant.ofEpochMilli(OLD + 3600000)));
        long before = System.currentTimeMillis();
        adopt(apiStart);
        long after = System.currentTimeMillis();
        long start = data.getLiveStartTime(PLATFORM, UID).orElseThrow();
        assertTrue(start >= before && start <= after, "无效起点必须使用当前时刻，不能落在1970年");
        off.onLiveOffEvent(new BilibiliLiveOffEvent(streamer(), Instant.ofEpochMilli(after + 1000)));
        assertEquals(1, sessions.find(start, start + 1).get(0).durationSeconds());
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
