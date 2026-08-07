package com.starlwr.bot.bilibili.service;

import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.TaskScheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 全局连接放行闸门测试
 * <p>
 * 不起真实定时器：把调度时刻捕获下来直接断言间隔。
 */
@DisplayName("全局连接放行闸门")
class BilibiliConnectGateTest {
    private static final int INTERVAL = 2000;

    private StarBotBilibiliProperties properties;

    private TaskScheduler scheduler;

    private BilibiliConnectGate gate;

    @BeforeEach
    void setUp() {
        properties = new StarBotBilibiliProperties();
        properties.getLive().setLiveRoomConnectInterval(INTERVAL);
        scheduler = mock(TaskScheduler.class);
        gate = new BilibiliConnectGate(properties, scheduler);
    }

    private List<Instant> scheduledAt(int times) {
        ArgumentCaptor<Instant> at = ArgumentCaptor.forClass(Instant.class);
        verify(scheduler, times(times)).schedule(any(Runnable.class), at.capture());
        return at.getAllValues();
    }

    @Test
    @DisplayName("同时提交的多个房间按间隔依次放行")
    void serializesConcurrentSubmissions() {
        for (int i = 0; i < 3; i++) {
            gate.submit(() -> { });
        }

        List<Instant> at = scheduledAt(3);
        assertEquals(INTERVAL, Duration.between(at.get(0), at.get(1)).toMillis(),
                "第二个应比第一个晚一个间隔");
        assertEquals(INTERVAL, Duration.between(at.get(1), at.get(2)).toMillis(),
                "第三个同理，不能和前面挤在一起");
    }

    @Test
    @DisplayName("重连自己算出的退避会被尊重，不会被闸门提前")
    void honoursCallerBackoff() {
        Instant backoff = Instant.now().plusSeconds(30);
        gate.submit(() -> { }, backoff);

        Instant at = scheduledAt(1).get(0);
        assertTrue(!at.isBefore(backoff), "放行时刻不得早于调用方要求的最早时刻");
    }

    @Test
    @DisplayName("首连与重连排在同一条时间轴上，互相让路")
    void firstConnectAndReconnectShareOneTimeline() {
        // 一个重连要求 5 秒后，随后两个首连立刻提交
        Instant backoff = Instant.now().plusSeconds(5);
        gate.submit(() -> { }, backoff);
        gate.submit(() -> { });
        gate.submit(() -> { });

        List<Instant> at = scheduledAt(3);
        assertEquals(INTERVAL, Duration.between(at.get(0), at.get(1)).toMillis(),
                "首连必须排在那个重连之后一个间隔，而不是抢在它前面并发涌出");
        assertEquals(INTERVAL, Duration.between(at.get(1), at.get(2)).toMillis());
    }

    @Test
    @DisplayName("间隔配成 0 时退化为不节流")
    void zeroIntervalDisablesThrottling() {
        properties.getLive().setLiveRoomConnectInterval(0);
        gate.submit(() -> { });
        gate.submit(() -> { });

        List<Instant> at = scheduledAt(2);
        assertEquals(0, Duration.between(at.get(0), at.get(1)).toMillis());
    }
}
