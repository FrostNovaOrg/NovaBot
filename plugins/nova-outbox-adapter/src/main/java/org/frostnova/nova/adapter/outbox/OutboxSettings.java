package org.frostnova.nova.adapter.outbox;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.frostnova.nova.core.model.Message.PushKind;
import org.frostnova.nova.core.properties.ConfigEffect;
import org.frostnova.nova.core.properties.ConfigLabel;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

@Getter
@Setter
@ConfigurationProperties(OutboxSettings.PREFIX)
public class OutboxSettings {
    static final String PREFIX = "novabot.adapter.outbox";

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

    /** 认领后等待发送结果的时长，默认 15 分钟，超时记为结果不明。 */
    @ConfigLabel("外发箱 · 认领超时")
    @ConfigEffect(ConfigEffect.Effect.RESTART)
    private Duration claimTimeout = Duration.ofMinutes(15);

    /** 从入箱起保留记录和图片的时长，默认 14 天。 */
    @ConfigLabel("外发箱 · 记录保留时间")
    @ConfigEffect(ConfigEffect.Effect.RESTART)
    private Duration retention = Duration.ofDays(14);

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
