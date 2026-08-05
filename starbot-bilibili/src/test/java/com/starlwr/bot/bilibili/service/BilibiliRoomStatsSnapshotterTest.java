package com.starlwr.bot.bilibili.service;

import com.starlwr.bot.bilibili.model.BilibiliLiveMetric;
import com.starlwr.bot.bilibili.util.BilibiliApiUtil;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.event.live.common.LiveOnEvent;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import com.starlwr.bot.core.service.DefaultLiveDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 开播数据快照测试
 * <p>
 * 快照是「本场涨了多少」的基准，错一次整场的涨幅都是错的，
 * 因此重点覆盖断线重连与接口失败这两种会污染基准的情形。
 */
@DisplayName("开播数据快照")
class BilibiliRoomStatsSnapshotterTest {
    private static final String PLATFORM = "bilibili";

    private static final Long UID = 3000000000000001L;

    private static final Long ROOM = 2000000001L;

    private BilibiliApiUtil api;

    private DefaultLiveDataService liveDataService;

    private BilibiliRoomStatsSnapshotter snapshotter;

    @BeforeEach
    void setUp() {
        api = mock(BilibiliApiUtil.class);
        when(api.getFansCount(anyLong())).thenReturn(Optional.of(243L));
        when(api.getFansMedalCount(anyLong())).thenReturn(Optional.of(36));
        when(api.getGuardCount(anyLong(), anyLong())).thenReturn(Optional.of(3));

        liveDataService = new DefaultLiveDataService(new StarBotCoreProperties());
        snapshotter = new BilibiliRoomStatsSnapshotter(liveDataService, api);
    }

    @Test
    @DisplayName("开播时应记下三项快照")
    void recordsSnapshot() {
        snapshotter.onLiveOn(event(false));

        assertEquals(243.0, liveDataService.getLiveMetric(PLATFORM, UID, BilibiliLiveMetric.FANS_AT_START));
        assertEquals(36.0, liveDataService.getLiveMetric(PLATFORM, UID, BilibiliLiveMetric.FANS_MEDAL_AT_START));
        assertEquals(3.0, liveDataService.getLiveMetric(PLATFORM, UID, BilibiliLiveMetric.GUARD_AT_START));
    }

    @Test
    @DisplayName("断线重连不应改写快照，否则涨幅基准会在直播中途挪位")
    void reconnectKeepsOriginalSnapshot() {
        snapshotter.onLiveOn(event(false));
        when(api.getFansCount(anyLong())).thenReturn(Optional.of(999L));

        snapshotter.onLiveOn(event(true));

        assertEquals(243.0, liveDataService.getLiveMetric(PLATFORM, UID, BilibiliLiveMetric.FANS_AT_START));
    }

    @Test
    @DisplayName("单个接口取不到时其余仍应记下")
    void partialFailureStillRecordsOthers() {
        when(api.getFansMedalCount(anyLong())).thenReturn(Optional.empty());

        snapshotter.onLiveOn(event(false));

        assertEquals(243.0, liveDataService.getLiveMetric(PLATFORM, UID, BilibiliLiveMetric.FANS_AT_START));
        assertEquals(0.0, liveDataService.getLiveMetric(PLATFORM, UID, BilibiliLiveMetric.FANS_MEDAL_AT_START));
    }

    @Test
    @DisplayName("没有房间号时不应去问大航海接口")
    void skipsGuardWithoutRoomId() {
        snapshotter.onLiveOn(new LiveOnEvent(PLATFORM, new LiveStreamerInfo(UID, "测试主播", null)));

        verify(api, never()).getGuardCount(anyLong(), anyLong());
        assertEquals(243.0, liveDataService.getLiveMetric(PLATFORM, UID, BilibiliLiveMetric.FANS_AT_START));
    }

    @Test
    @DisplayName("其他平台的开播事件不应触发哔哩哔哩接口")
    void ignoresOtherPlatforms() {
        LiveOnEvent event = new LiveOnEvent("douyu", new LiveStreamerInfo(UID, "测试主播", ROOM));

        snapshotter.onLiveOn(event);

        verify(api, never()).getFansCount(anyLong());
    }

    private LiveOnEvent event(boolean reconnect) {
        LiveOnEvent event = new LiveOnEvent(PLATFORM, new LiveStreamerInfo(UID, "测试主播", ROOM));
        event.setReconnect(reconnect);
        return event;
    }
}
