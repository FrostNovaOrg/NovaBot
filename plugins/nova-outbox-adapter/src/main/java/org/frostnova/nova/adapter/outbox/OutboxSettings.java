package org.frostnova.nova.adapter.outbox;

import jakarta.annotation.PostConstruct;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.frostnova.nova.core.model.Message.PushKind;
import org.frostnova.nova.core.properties.ConfigEffect;
import org.frostnova.nova.core.properties.ConfigLabel;
import org.frostnova.nova.core.properties.ConfigLevel;
import org.frostnova.nova.core.properties.ConfigSummary;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

@Getter
@Setter
@ConfigurationProperties(OutboxSettings.PREFIX)
public class OutboxSettings {
    static final String PREFIX = "novabot.adapter.outbox";
    static final int DEFAULT_PICKUP_ALERT_MINUTES = 5;

    /** 等待过久的待发通知可触发告警，默认关闭。 */
    @ConfigLabel("待发通知久未取走时告警")
    @ConfigSummary(value = "待发通知等到设定时长时提醒管理员，告警不会发往外发会话。", details = false)
    @ConfigLevel(ConfigLevel.Level.COMMON)
    @ConfigEffect(ConfigEffect.Effect.IMMEDIATE)
    private volatile boolean pickupAlertEnabled;

    /** 待发通知触发告警的等待时长。单位：分钟。 */
    @ConfigLabel("等多久算久未取走")
    @ConfigSummary(value = "请填写 1 至 60 分钟，默认 5 分钟。", details = false)
    @ConfigLevel(ConfigLevel.Level.COMMON)
    @ConfigEffect(ConfigEffect.Effect.IMMEDIATE)
    private int pickupAlertMinutes = DEFAULT_PICKUP_ALERT_MINUTES;

    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private volatile long pickupAlertActivation;

    /** 开播通知入箱后可以认领的时长，默认 10 分钟。 */
    @ConfigLabel("外发箱 · 开播通知有效期")
    @ConfigEffect(ConfigEffect.Effect.RESTART)
    private Duration liveOnTtl = Duration.ofMinutes(10);

    /** 下播通知入箱后可以认领的时长，默认 30 分钟。 */
    @ConfigLabel("外发箱 · 下播通知有效期")
    @ConfigEffect(ConfigEffect.Effect.RESTART)
    private Duration liveOffTtl = Duration.ofMinutes(30);

    /** 动态入箱后可以认领的时长，默认 60 分钟。 */
    @ConfigLabel("外发箱 · 动态有效期")
    @ConfigEffect(ConfigEffect.Effect.RESTART)
    private Duration dynamicTtl = Duration.ofMinutes(60);

    /** 直播报告入箱后可以认领的时长，默认 6 小时。 */
    @ConfigLabel("外发箱 · 直播报告有效期")
    @ConfigEffect(ConfigEffect.Effect.RESTART)
    private Duration liveReportTtl = Duration.ofHours(6);

    /** 下播打赏播报入箱后可以认领的时长，默认 6 小时。 */
    @ConfigLabel("外发箱 · 下播打赏播报有效期")
    @ConfigEffect(ConfigEffect.Effect.RESTART)
    private Duration rewardDigestTtl = Duration.ofHours(6);

    /** 其他通知入箱后可以认领的时长，默认 60 分钟。 */
    @ConfigLabel("外发箱 · 其他推送有效期")
    @ConfigEffect(ConfigEffect.Effect.RESTART)
    private Duration defaultTtl = Duration.ofMinutes(60);

    /** 认领后默认 15 分钟未收到结果，先显示「结果不明」。该次认领的回报仍可更新状态，重新放回待发后旧回报失效。 */
    @ConfigLabel("外发箱 · 认领超时")
    @ConfigEffect(ConfigEffect.Effect.RESTART)
    private Duration claimTimeout = Duration.ofMinutes(15);

    /** 从入箱起保留记录和图片的时长，默认 14 天。 */
    @ConfigLabel("外发箱 · 记录保留时间")
    @ConfigEffect(ConfigEffect.Effect.RESTART)
    private Duration retention = Duration.ofDays(14);

    public synchronized void setPickupAlertEnabled(boolean enabled) {
        if (pickupAlertEnabled && !enabled) pickupAlertActivation++;
        pickupAlertEnabled = enabled;
    }

    public synchronized void setPickupAlertMinutes(int minutes) {
        pickupAlertMinutes = requirePickupAlertMinutes(minutes);
    }

    static int requirePickupAlertMinutes(int minutes) {
        if (minutes < 1 || minutes > 60) throw new IllegalArgumentException("请填写 1 至 60 分钟的整数");
        return minutes;
    }

    long pickupAlertActivation() {
        return pickupAlertActivation;
    }

    synchronized PickupAlert pickupAlert() {
        return new PickupAlert(isPickupAlertEnabled(), getPickupAlertMinutes(), pickupAlertActivation);
    }

    record PickupAlert(boolean enabled, int minutes, long activation) {}

    @PostConstruct
    void validate() {
        for (Duration value : List.of(liveOnTtl, liveOffTtl, dynamicTtl, liveReportTtl,
                rewardDigestTtl, defaultTtl, claimTimeout, retention)) {
            if (value.toMillis() <= 0) throw new IllegalArgumentException("外发箱时间设置至少为 1 毫秒");
        }
    }

    Duration ttl(PushKind kind) {
        return switch (kind) {
            case LIVE_ON -> getLiveOnTtl();
            case LIVE_OFF -> getLiveOffTtl();
            case DYNAMIC -> getDynamicTtl();
            case LIVE_REPORT -> getLiveReportTtl();
            case REWARD_DIGEST -> getRewardDigestTtl();
            default -> getDefaultTtl();
        };
    }
}
