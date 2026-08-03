package com.starlwr.bot.core.alert;

import com.starlwr.bot.core.health.HealthProbe;
import com.starlwr.bot.core.health.HealthStatus;
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
     * 各探针上一次的状态级别
     */
    private final Map<String, HealthStatus.Level> lastLevels = new ConcurrentHashMap<>();

    @Autowired
    public HealthAlertMonitor(ObjectProvider<HealthProbe> probes, AlertService alertService) {
        this.probes = probes;
        this.alertService = alertService;
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
                alertService.alert(key + ":恢复", "StarBot 状态恢复：" + key, status.summary());
            }
            return;
        }

        // 首次检查即为异常，或状态由正常转为异常、由降级恶化为不可用，均应告警
        if (previous == status.level()) {
            return;
        }

        String subject = "StarBot 异常告警：" + key;
        String content = status.summary()
                + (status.advice().isBlank() ? "" : "\n处理建议：" + status.advice());

        alertService.alert(key, subject, content);
        log.warn("{} - {}", subject, status.summary());
    }
}
