package com.starlwr.bot.core.service;

import com.starlwr.bot.core.enums.LiveEndReason;
import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.event.live.common.LiveCutOffEvent;
import com.starlwr.bot.core.event.live.common.LiveOnEvent;
import com.starlwr.bot.core.event.live.common.RoomLockEvent;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 平台干预记录测试
 * <p>
 * 这个判断会写进场次归档，直接影响运营看到的趋势。
 * <b>错标的代价是不对称的</b>：把被切的算成正常只是少了一条注解，
 * 把正常的算成被切却会让人去追查一个不存在的事故——用例围绕这条铺开。
 */
@DisplayName("平台干预记录")
class LiveInterventionTrackerTest {
    private static final String PLATFORM = "bilibili";

    private static final Long UID = 19142561034510L;

    private static final Instant NOW = Instant.parse("2026-08-06T12:00:00Z");

    private LiveInterventionTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new LiveInterventionTracker();
    }

    private LiveStreamerInfo source() {
        return new LiveStreamerInfo(UID, "测试主播", 47121903112096L);
    }

    private void cutOff(Instant at) {
        tracker.onCutOff(new LiveCutOffEvent(PLATFORM, source(), "违反直播规范", at));
    }

    @Test
    @DisplayName("没有任何干预时应判为主动下播")
    void defaultsToNormal() {
        assertEquals(LiveEndReason.NORMAL, tracker.endReason(PLATFORM, UID, NOW));
    }

    @Test
    @DisplayName("切流后紧接着的下播应判为被切断")
    void cutOffFollowedByLiveOff() {
        cutOff(NOW.minusSeconds(5));

        assertEquals(LiveEndReason.CUT_OFF, tracker.endReason(PLATFORM, UID, NOW));
    }

    @Test
    @DisplayName("封禁后紧接着的下播应判为被封禁")
    void roomLockFollowedByLiveOff() {
        tracker.onRoomLock(new RoomLockEvent(LivePlatform.of(PLATFORM), source(), null, null, NOW.minusSeconds(3)));

        assertEquals(LiveEndReason.ROOM_LOCK, tracker.endReason(PLATFORM, UID, NOW));
    }

    @Test
    @DisplayName("隔得太久的干预不该算在这场头上")
    void staleInterventionIsIgnored() {
        cutOff(NOW.minus(Duration.ofMinutes(30)));

        assertEquals(LiveEndReason.NORMAL, tracker.endReason(PLATFORM, UID, NOW));
    }

    @Test
    @DisplayName("上一场被切、这一场重开，新的一场不该继承那顶帽子")
    void liveOnClearsPreviousIntervention() {
        cutOff(NOW.minusSeconds(5));
        tracker.onLiveOn(new LiveOnEvent(PLATFORM, source(), NOW.minusSeconds(2)));

        assertEquals(LiveEndReason.NORMAL, tracker.endReason(PLATFORM, UID, NOW));
    }

    @Test
    @DisplayName("发生在下播之后的干预属于别的时段，不该倒扣到这场")
    void interventionAfterLiveOffIsIgnored() {
        cutOff(NOW.plusSeconds(30));

        assertEquals(LiveEndReason.NORMAL, tracker.endReason(PLATFORM, UID, NOW));
    }

    @Test
    @DisplayName("一位主播的干预不应影响另一位")
    void interventionIsPerStreamer() {
        cutOff(NOW.minusSeconds(5));

        assertEquals(LiveEndReason.NORMAL, tracker.endReason(PLATFORM, 999L, NOW));
        assertEquals(LiveEndReason.NORMAL, tracker.endReason("douyu", UID, NOW));
    }

    @Test
    @DisplayName("应记下平台给出的说明，供告警与排查使用")
    void keepsDetail() {
        cutOff(NOW.minusSeconds(5));

        assertEquals("违反直播规范", tracker.lastDetail(PLATFORM, UID));
        assertEquals("", tracker.lastDetail(PLATFORM, 999L));
    }
}
