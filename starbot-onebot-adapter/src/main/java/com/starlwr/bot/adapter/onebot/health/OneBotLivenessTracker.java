package com.starlwr.bot.adapter.onebot.health;

import lombok.NonNull;

import java.time.Duration;
import java.time.Instant;

/**
 * OneBot Websocket 存活判定
 * <p>
 * 长连接可能在 TCP 层看似存活却已收不到任何数据，仅靠连接状态判断不出来，只能靠「多久没收到东西」。
 * 问题是「多久没收到东西」必须挑对观测对象：
 * <p>
 * 原先的判据是<b>多久没收到聊天消息</b>，而群里有没有人说话与 OneBot 实现活没活着毫无关系。
 * 半夜没人发言就会误报——线上七天内因此发出过 35 次告警，其中 20 次是同一个通宵连续触发的，
 * 而那段时间连接一直好好的。告警一旦开始说谎，真出事时就没人再看它了。
 * <p>
 * 正确的判据是<b>心跳</b>：OneBot 11 的实现会按固定间隔推送 {@code meta_event/heartbeat}，
 * 与有没有人说话无关。心跳停了才是连接真的停了。
 * <p>
 * 但心跳是可以被关掉的（NapCat 的 {@code heartInterval} 可配为 0）。关掉之后「静默」不再携带任何信息，
 * 此时这里返回 {@link State#NO_HEARTBEAT}，由调用方改为提示配置问题而不是报连接故障——
 * 拿一个无效的判据继续告警，比不告警更糟。
 */
public class OneBotLivenessTracker {
    /**
     * 判定连接失效至少要错过的心跳次数
     * <p>
     * 取 1 次会被一次网络抖动打成故障，取值过大又拖慢发现速度，三次是常见折中。
     */
    private static final int MISSED_HEARTBEATS = 3;

    /**
     * 等待首个心跳的宽限期
     * <p>
     * 刚连上时还没收到过心跳，此时分不清「这个实现不推心跳」和「第一个心跳还没到」。
     * 宽限期内一律按正常处理，避免每次重连都误报一次「实现不推心跳」。
     */
    private static final Duration FIRST_HEARTBEAT_GRACE = Duration.ofMinutes(5);

    private final Instant connectedAt;

    /**
     * 最近一次收到任何帧的时间，心跳、聊天消息、通知都算
     */
    private volatile Instant lastFrameAt;

    /**
     * 最近一次收到心跳的时间，为 null 表示从未收到过
     */
    private volatile Instant lastHeartbeatAt;

    /**
     * 实现自报的心跳间隔，单位：毫秒，取不到时为 0
     */
    private volatile long heartbeatIntervalMillis;

    public OneBotLivenessTracker(@NonNull Instant connectedAt) {
        this.connectedAt = connectedAt;
        this.lastFrameAt = connectedAt;
    }

    /**
     * 记录收到了一帧数据
     * <p>
     * 只要收到东西就说明链路是通的，内容是什么、能不能解析都不影响这个结论，
     * 因此这里刻意不区分帧的类型。
     * @param at 收到的时间
     */
    public void frameReceived(@NonNull Instant at) {
        lastFrameAt = at;
    }

    /**
     * 记录收到了一次心跳
     * @param at 收到的时间
     * @param intervalMillis 实现自报的心跳间隔，单位：毫秒，未上报时传 0
     */
    public void heartbeatReceived(@NonNull Instant at, long intervalMillis) {
        lastHeartbeatAt = at;
        if (intervalMillis > 0) {
            heartbeatIntervalMillis = intervalMillis;
        }
    }

    /**
     * 判定当前连接是否仍然存活
     * @param now 当前时间
     * @param configuredTimeout 配置的静默超时时间
     * @return 判定结果
     */
    public Verdict evaluate(@NonNull Instant now, @NonNull Duration configuredTimeout) {
        Instant heartbeat = lastHeartbeatAt;
        Duration silence = Duration.between(lastFrameAt, now);

        if (heartbeat == null) {
            if (Duration.between(connectedAt, now).compareTo(FIRST_HEARTBEAT_GRACE) <= 0) {
                return new Verdict(State.ALIVE, silence);
            }
            return new Verdict(State.NO_HEARTBEAT, silence);
        }

        return new Verdict(silence.compareTo(timeout(configuredTimeout)) > 0 ? State.SILENT : State.ALIVE, silence);
    }

    /**
     * 实际使用的静默超时时间
     * <p>
     * 以配置值为下限，同时保证不少于三个心跳周期：把超时配得比心跳间隔还短，
     * 会让一个健康的连接每隔几秒就被判一次死刑。
     */
    private Duration timeout(Duration configuredTimeout) {
        long interval = heartbeatIntervalMillis;
        if (interval <= 0) {
            return configuredTimeout;
        }

        Duration byHeartbeat = Duration.ofMillis(interval * MISSED_HEARTBEATS);
        return byHeartbeat.compareTo(configuredTimeout) > 0 ? byHeartbeat : configuredTimeout;
    }

    /**
     * 判定结果
     */
    public enum State {
        /**
         * 连接存活
         */
        ALIVE,

        /**
         * 已超时未收到任何帧，连接很可能已失效
         */
        SILENT,

        /**
         * 对端不推送心跳，无法用静默时长判断连接是否存活
         */
        NO_HEARTBEAT
    }

    /**
     * 判定结果与已静默时长
     *
     * @param state 判定结果
     * @param silence 距上一帧的时长
     */
    public record Verdict(State state, Duration silence) {
    }
}
