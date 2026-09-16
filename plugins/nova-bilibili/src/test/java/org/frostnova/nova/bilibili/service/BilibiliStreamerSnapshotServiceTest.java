package org.frostnova.nova.bilibili.service;

import org.frostnova.nova.bilibili.BilibiliPlatform;
import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.model.BilibiliStreamerMetric;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.model.PushUser;
import org.frostnova.nova.core.model.StreamerSnapshot;
import org.frostnova.nova.core.service.StreamerSnapshotArchive;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.TaskScheduler;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 主播基础数据采样
 * <p>
 * 三个接口各自失败互不影响，取不到的项不写进快照而不是写成 0——
 * 后者会在趋势图上留下一个假的断崖。
 */
@DisplayName("主播基础数据采样")
class BilibiliStreamerSnapshotServiceTest {
    private static final long UID = 10001L;

    private static final long ROOM = 20002L;

    @Test
    @DisplayName("一项接口失败时该项不进快照，其它项照收")
    void failedMetricIsAbsentNotZero() {
        NovaBilibiliProperties properties = new NovaBilibiliProperties();
        BilibiliApiUtil api = mock(BilibiliApiUtil.class);
        when(api.getFansCount(UID)).thenThrow(new RuntimeException("down"));
        when(api.getFansMedalCount(UID)).thenReturn(Optional.of(12));
        when(api.getGuardCount(ROOM, UID)).thenReturn(Optional.of(3));

        StreamerSnapshotArchive archive = mock(StreamerSnapshotArchive.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);

        PushUser user = new PushUser();
        user.setUid(UID);
        user.setUname("主播甲");
        user.setRoomId(ROOM);
        user.setPlatform(BilibiliPlatform.BILIBILI.id());

        AbstractDataSource dataSource = mock(AbstractDataSource.class);
        when(dataSource.getUsers(anyString())).thenReturn(List.of(user));

        BilibiliStreamerSnapshotService service = new BilibiliStreamerSnapshotService(
                api, properties, archive, scheduler);
        service.start(dataSource);

        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).scheduleAtFixedRate(task.capture(), any(Duration.class));
        task.getValue().run();

        ArgumentCaptor<StreamerSnapshot> snap = ArgumentCaptor.forClass(StreamerSnapshot.class);
        verify(archive).append(snap.capture());
        Map<String, Double> metrics = snap.getValue().metrics();
        assertEquals(2, metrics.size(), "失败那一项不该占一个键");
        assertFalse(metrics.containsKey(BilibiliStreamerMetric.FANS), "粉丝数那趟抛了，键不该出现，更不该写成 0");
        assertEquals(12.0, metrics.get(BilibiliStreamerMetric.FANS_MEDAL));
        assertEquals(3.0, metrics.get(BilibiliStreamerMetric.GUARD));
    }
}
