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

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
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

    /** 各探针上一次检查中的异常成员标识。 */
    private final Map<String, Set<String>> lastMembers = new ConcurrentHashMap<>();

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
        Set<String> previousMembers = lastMembers.put(key, status.members().keySet());

        if (status.level() == HealthStatus.Level.OK) {
            lastMembers.remove(key);
            if (previous != null && previous != HealthStatus.Level.OK) {
                // 收敛记录按级别各留一份，恢复时得全部清掉：漏掉哪一份，
                // 恢复后再变回那个级别就会被旧记录压掉，错过第二次告警
                for (HealthStatus.Level level : HealthStatus.Level.values()) {
                    if (level != HealthStatus.Level.OK) {
                        alertService.resolve(alertKey(key, level));
                    }
                }
                alertService.alert(key + ":恢复", "NovaBot 状态恢复：" + key, status.summary());
                record(probe, status, previous);
            }
            return;
        }

        // 首次检查即为异常，或状态由正常转为异常、由降级恶化为不可用，均应告警
        if (previous == status.level()) {
            Map<String, String> added = new LinkedHashMap<>();
            status.members().forEach((id, name) -> {
                if (previousMembers == null || !previousMembers.contains(id)) {
                    added.put(memberKey(key, id), name);
                }
            });
            if (!added.isEmpty()) {
                String subject = "NovaBot 异常告警：" + key;
                alertService.alertMembers(added, subject,
                        names -> content(memberSummary(names)
                                + (status.memberCondition().isBlank() ? "" : "\n" + status.memberCondition()),
                                status.advice()));
                String summary = memberSummary(added.values());
                log.warn("{} - {}", subject, summary);
                record(probe, new HealthStatus(status.level(), summary, status.advice()), previous);
            }
            return;
        }

        if (previous == HealthStatus.Level.OK) {
            // 新一轮异常要配对新一条恢复通知，不能沿用上一轮的恢复收敛记录。
            alertService.resolve(key + ":恢复");
        }

        String subject = "NovaBot 异常告警：" + key;
        String content = content(status.summary(), status.advice());

        alertService.alert(alertKey(key, status.level()), subject, content);
        if (!status.members().isEmpty()) {
            alertService.rememberMembers(status.members().keySet().stream()
                    .map(id -> memberKey(key, id)).toList());
        }
        log.warn("{} - {}", subject, status.summary());
        record(probe, status, previous);
    }

    private static String content(String summary, String advice) {
        return summary + (advice.isBlank() ? "" : "\n处理建议：" + advice);
    }

    private static String memberKey(String probeName, String id) {
        return probeName + ":member:" + id;
    }

    private static String memberSummary(Collection<String> names) {
        String text = "新增异常成员：" + String.join("、", names.stream().limit(5).toList());
        int rest = names.size() - 5;
        return rest > 0 ? text + "，等 " + rest + " 个" : text;
    }

    /**
     * 告警用的收敛键：探针名带上级别
     * <p>
     * 不带级别时，「降级」与「不可用」共用一份收敛记录：先降级、收敛期内又恶化为不可用的
     * 那一条会被前一条降级压掉——而「彻底挂了」恰恰是最该叫到人的一条，且之后级别不再变化，
     * 收敛期过了也不会补发。带上级别后，降级与不可用各算一个问题，恶化必达；
     * 在两个级别之间来回抖时仍受收敛管束：每个级别在收敛期内只发一次。
     */
    private static String alertKey(String probeName, HealthStatus.Level level) {
        return probeName + ":" + level.name();
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
