package com.starlwr.bot.adapter.onebot.health;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OneBot Websocket 存活判定测试
 * <p>
 * 这里最要紧的一条是「整夜无人发言不算掉线」：旧实现按聊天消息计时，线上七天误报 35 次，
 * 其中一个通宵连续误报 20 次。判据选错时，代码本身跑得完全正确，错的是它在测量什么。
 */
@DisplayName("OneBot Websocket 存活判定")
class OneBotLivenessTrackerTest {
    private static final Instant CONNECTED = Instant.parse("2026-08-05T20:00:00Z");

    /**
     * NapCat 的默认心跳间隔
     */
    private static final long HEARTBEAT = 30_000L;

    private static final Duration TIMEOUT = Duration.ofSeconds(120);

    @Test
    @DisplayName("整夜没人说话但心跳照常, 应判定为存活")
    void staysAliveThroughSilentNight() {
        OneBotLivenessTracker tracker = new OneBotLivenessTracker(CONNECTED);

        // 从 01:00 到 09:00 一句聊天消息都没有，只有每 30 秒一次的心跳
        Instant now = CONNECTED;
        for (int i = 0; i < 8 * 60 * 2; i++) {
            now = now.plusSeconds(30);
            tracker.frameReceived(now);
            tracker.heartbeatReceived(now, HEARTBEAT);
        }

        assertEquals(OneBotLivenessTracker.State.ALIVE, tracker.evaluate(now.plusSeconds(5), TIMEOUT).state());
    }

    @Test
    @DisplayName("心跳停止超过超时时长应判定为失效")
    void reportsSilentWhenHeartbeatStops() {
        OneBotLivenessTracker tracker = new OneBotLivenessTracker(CONNECTED);
        tracker.frameReceived(CONNECTED);
        tracker.heartbeatReceived(CONNECTED, HEARTBEAT);

        OneBotLivenessTracker.Verdict verdict = tracker.evaluate(CONNECTED.plusSeconds(121), TIMEOUT);

        assertEquals(OneBotLivenessTracker.State.SILENT, verdict.state());
        assertEquals(121, verdict.silence().toSeconds());
    }

    @Test
    @DisplayName("刚过一两个心跳周期不应判定为失效")
    void toleratesOccasionalMissedHeartbeat() {
        OneBotLivenessTracker tracker = new OneBotLivenessTracker(CONNECTED);
        tracker.frameReceived(CONNECTED);
        tracker.heartbeatReceived(CONNECTED, HEARTBEAT);

        assertEquals(OneBotLivenessTracker.State.ALIVE, tracker.evaluate(CONNECTED.plusSeconds(75), TIMEOUT).state());
    }

    @Test
    @DisplayName("超时配得比心跳间隔还短时, 仍需错过三个心跳才判定失效")
    void neverTimesOutFasterThanThreeHeartbeats() {
        OneBotLivenessTracker tracker = new OneBotLivenessTracker(CONNECTED);
        tracker.frameReceived(CONNECTED);
        tracker.heartbeatReceived(CONNECTED, HEARTBEAT);

        // 配置写了 10 秒，但心跳每 30 秒才来一次，照配置判会把健康连接判死
        Duration tooShort = Duration.ofSeconds(10);

        assertEquals(OneBotLivenessTracker.State.ALIVE, tracker.evaluate(CONNECTED.plusSeconds(89), tooShort).state());
        assertEquals(OneBotLivenessTracker.State.SILENT, tracker.evaluate(CONNECTED.plusSeconds(91), tooShort).state());
    }

    @Test
    @DisplayName("心跳间隔远大于配置值时, 以心跳间隔为准")
    void followsHeartbeatIntervalWhenLongerThanConfigured() {
        OneBotLivenessTracker tracker = new OneBotLivenessTracker(CONNECTED);
        tracker.frameReceived(CONNECTED);
        tracker.heartbeatReceived(CONNECTED, Duration.ofMinutes(5).toMillis());

        assertEquals(OneBotLivenessTracker.State.ALIVE, tracker.evaluate(CONNECTED.plusSeconds(14 * 60), TIMEOUT).state());
        assertEquals(OneBotLivenessTracker.State.SILENT, tracker.evaluate(CONNECTED.plusSeconds(16 * 60), TIMEOUT).state());
    }

    @Test
    @DisplayName("非心跳的帧同样证明链路存活")
    void anyFrameCountsAsLiveness() {
        OneBotLivenessTracker tracker = new OneBotLivenessTracker(CONNECTED);
        tracker.heartbeatReceived(CONNECTED, HEARTBEAT);

        // 心跳停了，但群里的聊天消息还在进来，说明连接是好的
        tracker.frameReceived(CONNECTED.plusSeconds(200));

        assertEquals(OneBotLivenessTracker.State.ALIVE, tracker.evaluate(CONNECTED.plusSeconds(230), TIMEOUT).state());
    }

    @Test
    @DisplayName("对端不推心跳时不按静默判定, 而是明确报告判据不可用")
    void reportsMissingHeartbeatInsteadOfFalseAlarm() {
        OneBotLivenessTracker tracker = new OneBotLivenessTracker(CONNECTED);

        // 连上八小时，一个心跳都没有：这不是掉线，是这个实现根本不推心跳
        assertEquals(OneBotLivenessTracker.State.NO_HEARTBEAT,
                tracker.evaluate(CONNECTED.plus(Duration.ofHours(8)), TIMEOUT).state());
    }

    @Test
    @DisplayName("刚连上还没收到首个心跳时不应报判据不可用")
    void waitsForFirstHeartbeatBeforeComplaining() {
        OneBotLivenessTracker tracker = new OneBotLivenessTracker(CONNECTED);

        // 检测每 30 秒跑一次，而心跳也是 30 秒一次，首个心跳没赶上第一次检查是常态
        assertEquals(OneBotLivenessTracker.State.ALIVE, tracker.evaluate(CONNECTED.plusSeconds(30), TIMEOUT).state());
    }

    @Test
    @DisplayName("未上报心跳间隔时退回使用配置值")
    void fallsBackToConfiguredTimeoutWithoutInterval() {
        OneBotLivenessTracker tracker = new OneBotLivenessTracker(CONNECTED);
        tracker.frameReceived(CONNECTED);
        tracker.heartbeatReceived(CONNECTED, 0L);

        assertEquals(OneBotLivenessTracker.State.ALIVE, tracker.evaluate(CONNECTED.plusSeconds(119), TIMEOUT).state());
        assertEquals(OneBotLivenessTracker.State.SILENT, tracker.evaluate(CONNECTED.plusSeconds(121), TIMEOUT).state());
    }
}
