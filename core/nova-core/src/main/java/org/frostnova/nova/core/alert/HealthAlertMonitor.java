package org.frostnova.nova.core.alert;

import org.frostnova.nova.core.health.HealthProbe;
import org.frostnova.nova.core.health.HealthStatus;
import org.frostnova.nova.core.timeline.TimelineEvent;
import org.frostnova.nova.core.timeline.TimelineEventType;
import org.frostnova.nova.core.timeline.TimelineWriter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 健康状况告警监控
 * <p>
 * 定期读取各探针的状态，在状态发生<b>变化</b>时告警：只在异常出现和恢复的那一刻通知，
 * 而不是每个周期重复播报同一件事。
 * <p>
 * 此前的告警只覆盖 OneBot 的两处异常，而登录失效、直播间被风控、长连接持续重连失败
 * 这些同样导致推送停摆的情况一个都没有——现在只要实现了探针就自动纳入告警。
 */
@Slf4j
@Component
public class HealthAlertMonitor {
    /**
     * 检查间隔，单位：毫秒
     */
    private static final long CHECK_INTERVAL = 60_000L;

    private final ObjectProvider<HealthProbe> probes;

    private final AlertService alertService;

    /**
     * 事件时间线
     * <p>
     * 告警是「推给人」，时间线是「留在那儿等人来看」。两者都要：
     * 告警收敛之后同一个问题一小时只推一条，而时间线上每一次变色都在。
     */
    private final TimelineWriter timeline;

    /**
     * 各探针上一次的状态级别
     */
    private final Map<String, HealthStatus.Level> lastLevels = new ConcurrentHashMap<>();

    @Autowired
    public HealthAlertMonitor(ObjectProvider<HealthProbe> probes, AlertService alertService,
                              TimelineWriter timeline) {
        this.probes = probes;
        this.alertService = alertService;
        this.timeline = timeline;
    }

    /**
     * 执行一轮检查
     */
    @Scheduled(fixedDelay = CHECK_INTERVAL, initialDelay = CHECK_INTERVAL)
    public void check() {
        probes.orderedStream().forEach(probe -> {
            try {
                evaluate(probe);
            } catch (Exception e) {
                log.debug("读取探针 {} 状态失败: {}", probe.name(), e.getMessage());
            }
        });
    }

    /**
     * 评估单个探针，必要时告警
     */
    private void evaluate(HealthProbe probe) {
        HealthStatus status = probe.check();
        String key = probe.name();
        HealthStatus.Level previous = lastLevels.put(key, status.level());

        if (status.level() == HealthStatus.Level.OK) {
            if (previous != null && previous != HealthStatus.Level.OK) {
                alertService.resolve(key);
                alertService.alert(key + ":恢复", "NovaBot 状态恢复：" + key, status.summary());
                record(probe, status, previous);
            }
            return;
        }

        // 首次检查即为异常，或状态由正常转为异常、由降级恶化为不可用，均应告警
        if (previous == status.level()) {
            return;
        }

        String subject = "NovaBot 异常告警：" + key;
        String content = status.summary()
                + (status.advice().isBlank() ? "" : "\n处理建议：" + status.advice());

        alertService.alert(key, subject, content);
        log.warn("{} - {}", subject, status.summary());
        record(probe, status, previous);
    }

    /**
     * 把一次变色记进时间线，含恢复正常那一次
     * <p>
     * 「登录态由正常转为不正常」单列一类：那是唯一一种<b>不去动手就永远不会自己好</b>的异常，
     * 混在别的变色里会被当成又一次网络抖动。<b>只有这一个方向单列</b>——
     * 登录恢复正常与别的恢复没有区别，都是「好了」。
     */
    private void record(HealthProbe probe, HealthStatus status, HealthStatus.Level previous) {
        String key = probe.name();
        boolean loginLost = probe.loginState()
                && previous == HealthStatus.Level.OK
                && status.level() != HealthStatus.Level.OK;

        String text = loginLost
                ? key + " 登录已失效：" + status.summary()
                : key + describe(status.level()) + "：" + status.summary();

        timeline.record(TimelineEvent.of(
                        loginLost ? TimelineEventType.LOGIN_LOST : TimelineEventType.PROBE_CHANGED,
                        level(status.level()))
                .text(text)
                .detail("probe", key)
                // 首次检查没有「之前」，如实写「未知」而不是编一个 OK：
                // 写成 OK 会让「一起来就是坏的」看上去像「刚刚坏掉」
                .detail("from", previous == null ? "未知" : previous.name())
                .detail("to", status.level().name())
                .detail("advice", status.advice())
                .build());
    }

    private static String describe(HealthStatus.Level level) {
        return switch (level) {
            case OK -> " 已恢复正常";
            case DEGRADED -> " 降级";
            case DOWN -> " 不可用";
        };
    }

    private static TimelineEvent.Level level(HealthStatus.Level level) {
        return switch (level) {
            case OK -> TimelineEvent.Level.INFO;
            case DEGRADED -> TimelineEvent.Level.WARN;
            case DOWN -> TimelineEvent.Level.ERROR;
        };
    }
}
