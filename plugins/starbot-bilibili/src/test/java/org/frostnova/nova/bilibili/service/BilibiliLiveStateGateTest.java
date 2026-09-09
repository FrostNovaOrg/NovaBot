package org.frostnova.nova.bilibili.service;

import org.frostnova.nova.bilibili.BilibiliPlatform;
import org.frostnova.nova.core.service.LiveDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 直播状态闸门测试
 * <p>
 * 开播与下播有长连接与备用轮询两条独立的发现路径，同一次状态变化被推送两次时，
 * 群里会收到两条一模一样的通知——真机上已经发生过。用例覆盖两条路径的两种先后顺序。
 */
@DisplayName("直播状态闸门")
class BilibiliLiveStateGateTest {
    private static final long UID = 19142561034510L;

    private LiveDataService liveDataService;

    private BilibiliLiveStateGate gate;

    @BeforeEach
    void setUp() {
        liveDataService = mock(LiveDataService.class);
        when(liveDataService.getLiveStatus(anyString(), anyLong())).thenReturn(Optional.empty());
        gate = new BilibiliLiveStateGate(liveDataService);
    }

    @Test
    @DisplayName("首次遇到某个状态应放行")
    void shouldAdmitFirstObservation() {
        assertTrue(gate.admit(UID, true));
    }

    @Test
    @DisplayName("同一次状态变化只放行第一个调用者")
    void shouldAdmitOnlyOnce() {
        assertTrue(gate.admit(UID, false), "长连接先到，应放行");
        assertFalse(gate.admit(UID, false), "备用轮询随后到达同一状态，应拦下");
    }

    @Test
    @DisplayName("两条路径顺序颠倒时同样只放行一次")
    void shouldAdmitOnlyOnceRegardlessOfOrder() {
        // 轮询每 10 秒一轮、落点随机，完全可能早于长连接先看到状态变化
        assertTrue(gate.admit(UID, false), "备用轮询先到，应放行");
        assertFalse(gate.admit(UID, false), "长连接随后到达，应拦下");
    }

    @Test
    @DisplayName("状态真正发生变化时应放行")
    void shouldAdmitRealStateChange() {
        assertTrue(gate.admit(UID, true));
        assertFalse(gate.admit(UID, true));
        assertTrue(gate.admit(UID, false), "开播后下播是新的变化, 应放行");
        assertTrue(gate.admit(UID, true), "再次开播同样应放行");
    }

    @Test
    @DisplayName("不同主播互不影响")
    void shouldIsolateBetweenStreamers() {
        assertTrue(gate.admit(UID, false));
        assertTrue(gate.admit(UID + 1, false), "另一位主播的相同状态不该被前者拦下");
    }

    @Test
    @DisplayName("进程重启后以持久化状态为准, 不重复推送启动前的变化")
    void shouldFallBackToPersistedStatus() {
        when(liveDataService.getLiveStatus(BilibiliPlatform.BILIBILI.id(), UID)).thenReturn(Optional.of(false));

        assertFalse(gate.admit(UID, false), "持久化状态已是下播, 不应再推一次");
        assertTrue(gate.admit(UID, true), "开播是相对持久化状态的真实变化, 应放行");
    }

    @Test
    @DisplayName("uid 为空时直接拒绝")
    void shouldRejectNullUid() {
        assertFalse(gate.admit(null, true));
        verifyNoInteractions(liveDataService);
    }
}
