package com.starlwr.bot.bilibili.service;

import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.core.plugin.StarBotComponent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.TaskScheduler;

import java.time.Duration;
import java.time.Instant;

/**
 * 全局连接放行闸门
 * <p>
 * <b>首连与重连排进同一条时间轴</b>，按配置的固定间隔依次放行。
 * <p>
 * 没有这道闸门时，两条时间轴是各管各的：启动时由 {@code BilibiliLiveRoomService}
 * 按房间累加延迟错开首连，而重连由每个连接器自己指数退避、彼此不知情。
 * 房间一多，同时断线就会叠成请求洪峰——2026-08-04 14:21 那场
 * 「32 秒内 96 次重连打出 -352 限流」正是这个结构缺陷的后果：
 * 单个房间自己反复重连，没有任何东西拦它。
 * <p>
 * 这不是反风控手段，是「礼貌客户端」承诺的技术兑现：任何时候我们对平台的请求密度
 * 都应当是自我约束的，不依赖平台来教我们做人。
 */
@Slf4j
@StarBotComponent
public class BilibiliConnectGate {
    private final StarBotBilibiliProperties properties;

    private final TaskScheduler scheduler;

    /**
     * 下一次允许放行的时刻。所有房间共用这一个游标，这就是"同一条时间轴"的全部含义
     */
    private Instant nextAllowedAt = Instant.EPOCH;

    @Autowired
    public BilibiliConnectGate(StarBotBilibiliProperties properties,
                               @Qualifier("bilibiliTaskScheduler") TaskScheduler scheduler) {
        this.properties = properties;
        this.scheduler = scheduler;
    }

    /**
     * 排队等待放行
     * @param task 建连任务
     * @param notBefore 最早不得早于此刻放行，用于承载调用方自己算出的退避
     * @return 实际放行时刻
     */
    public synchronized Instant submit(Runnable task, Instant notBefore) {
        long interval = Math.max(0, properties.getLive().getLiveRoomConnectInterval());
        Instant now = Instant.now();

        Instant at = now;
        if (notBefore != null && notBefore.isAfter(at)) {
            at = notBefore;
        }
        if (nextAllowedAt.isAfter(at)) {
            at = nextAllowedAt;
        }
        nextAllowedAt = at.plusMillis(interval);

        long wait = Duration.between(now, at).toMillis();
        if (wait > 0) {
            log.debug("建连请求排队 {} 毫秒后放行", wait);
        }
        scheduler.schedule(task, at);
        return at;
    }

    /**
     * 立即排队，不附加额外的最早时刻
     * @param task 建连任务
     * @return 实际放行时刻
     */
    public Instant submit(Runnable task) {
        return submit(task, null);
    }
}
