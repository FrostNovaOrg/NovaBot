package com.starlwr.bot.core.listener;

import com.starlwr.bot.core.analytics.LiveDetail;
import com.starlwr.bot.core.enums.LiveEndReason;
import com.starlwr.bot.core.event.live.common.LiveOffEvent;
import com.starlwr.bot.core.model.LiveSession;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import com.starlwr.bot.core.service.LiveDataService;
import com.starlwr.bot.core.service.LiveDetailArchive;
import com.starlwr.bot.core.service.LiveInterventionTracker;
import com.starlwr.bot.core.service.LiveRoomInfoHistory;
import com.starlwr.bot.core.service.LiveSessionArchive;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 正常下播归档主路
 * <p>
 * {@code LiveOffArchiveTest}（真依赖）量的是<b>归档内容</b>算得对不对；
 * 本台架把五个依赖全换成假的，量的是监听器这一层<b>主路的编排</b>：
 * 下播事实（状态、终点、并入累计）按什么顺序落、归档在什么条件下跳过。
 * 两把尺互补：真依赖那边换掉实现时这里不红，这里编排变动时那边也不红。
 */
@DisplayName("下播监听：正常下播主路")
class StarBotDefaultLiveOffEventListenerTest {
    private static final String PLATFORM = "bilibili";
    private static final long UID = 42L;
    private static final long ROOM_ID = 1001L;
    private static final long START = 1_700_000_000_000L;
    private static final long END = START + 3_600_000L;

    private LiveDataService liveDataService;
    private LiveSessionArchive archive;
    private LiveInterventionTracker interventionTracker;
    private LiveRoomInfoHistory roomInfoHistory;
    private LiveDetailArchive details;
    private StarBotDefaultLiveOffEventListener listener;

    @BeforeEach
    void setUp() {
        liveDataService = mock(LiveDataService.class);
        archive = mock(LiveSessionArchive.class);
        interventionTracker = mock(LiveInterventionTracker.class);
        roomInfoHistory = mock(LiveRoomInfoHistory.class);
        details = mock(LiveDetailArchive.class);
        listener = new StarBotDefaultLiveOffEventListener(
                liveDataService, archive, interventionTracker, roomInfoHistory, details);
    }

    private static LiveOffEvent liveOffAt(long at) {
        return new LiveOffEvent(PLATFORM, new LiveStreamerInfo(UID, "主播甲", ROOM_ID), Instant.ofEpochMilli(at));
    }

    /**
     * 阳：正常下播把三件事实与两份归档都落全
     * <p>
     * 状态翻掉、终点写下、本场并入累计，这三件是「下播发生了」的事实；
     * 场次归档与明细留档各留一份，起止时长同一套。归档里的字段逐项对值，
     * 换算错位（毫秒当秒、起止倒置）在这一步就会现形。
     */
    @Test
    @DisplayName("阳：状态/终点/并入三件事实落全，场次与明细两份归档对值")
    void normalLiveOffRecordsFactsAndArchivesBothCopies() {
        when(liveDataService.getLiveStartTime(PLATFORM, UID)).thenReturn(Optional.of(START));
        when(interventionTracker.endReason(eq(PLATFORM), eq(UID), any(Instant.class))).thenReturn(LiveEndReason.NORMAL);

        listener.onLiveOffEvent(liveOffAt(END));

        verify(liveDataService).setLiveStatus(PLATFORM, UID, false);
        verify(liveDataService).setLiveEndTime(PLATFORM, UID, END);
        verify(liveDataService).mergeLiveDataIntoTotal(PLATFORM, UID);

        ArgumentCaptor<LiveSession> sessions = ArgumentCaptor.forClass(LiveSession.class);
        verify(archive).append(sessions.capture());
        LiveSession session = sessions.getValue();
        assertEquals(PLATFORM, session.platform());
        assertEquals(UID, session.uid());
        assertEquals("主播甲", session.uname());
        assertEquals(ROOM_ID, session.roomId());
        assertEquals(START, session.startTime());
        assertEquals(END, session.endTime());
        assertEquals(3600, session.durationSeconds());
        assertEquals(LiveEndReason.NORMAL, session.endReason());

        ArgumentCaptor<LiveDetail> detailCaptor = ArgumentCaptor.forClass(LiveDetail.class);
        verify(details).store(detailCaptor.capture());
        LiveDetail detail = detailCaptor.getValue();
        assertEquals(LiveDetail.VERSION, detail.version());
        assertEquals(PLATFORM, detail.platform());
        assertEquals(UID, detail.uid());
        assertEquals(START, detail.startTime());
        assertEquals(END, detail.endTime());
        assertEquals(3600, detail.durationSeconds());
    }

    /**
     * 阴：没有开播时刻就不归档，但下播事实照落
     * <p>
     * 程序在直播中途才启动时算不出时长，归档只会污染统计——场次与明细两份都不写。
     * 但「已经下播了」是独立的事实：状态照翻、终点照记、已采到的那点数据照并入，
     * 否则这一场的数据要等到下一次开播才会被清掉，期间报告读到的是僵尸在播状态。
     */
    @Test
    @DisplayName("阴：无开播时刻不归档，状态/终点/并入照落")
    void noStartTimeSkipsArchivesButStillRecordsTheLiveOff() {
        when(liveDataService.getLiveStartTime(PLATFORM, UID)).thenReturn(Optional.empty());

        listener.onLiveOffEvent(liveOffAt(END));

        verify(archive, never()).append(any());
        verify(details, never()).store(any());

        verify(liveDataService).setLiveStatus(PLATFORM, UID, false);
        verify(liveDataService).setLiveEndTime(PLATFORM, UID, END);
        verify(liveDataService).mergeLiveDataIntoTotal(PLATFORM, UID);
    }
}
