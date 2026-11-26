package org.frostnova.nova.interact.speaker;

import org.frostnova.nova.core.health.HealthProbe;
import org.frostnova.nova.core.health.HealthStatus;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;

/**
 * 发言账号登录态的自检项
 * <p>
 * 从没登录过发言账号不算故障：这一项那时不出现，也不告警——不用这个功能的人不该被它打扰。
 * 登录过、后来失效了才报出来，口径与监听账号的登录探针一致：登录态由正常变为不正常时，
 * 时间线记一条「登录已失效」，告警按既有通道发。
 * <p>
 * 它驱动自己那张连接卡（{@link #card()}），不进首页「直播平台」那一站：发言账号失效不影响监听，
 * 那一站变红会让人以为监听坏了。
 * <p>
 * 只读内存里的状态，不发请求：实际的核验由发言账号自己的例行复检完成。
 */
@NovaComponent
public class BilibiliSpeakerLoginHealthProbe implements HealthProbe {
    /**
     * 复检结果被视为过期的宽限倍数，与监听账号的探针同一个数
     */
    private static final int STALE_FACTOR = 2;

    /**
     * 失效时的指引
     */
    static final String ADVICE_EXPIRED = "发言功能已停用；监听账号、推送与直播报告不受影响。"
            + "请在连接页的「" + BilibiliSpeakerAccount.DISPLAY_NAME + "」卡上重新扫码登录";

    private final BilibiliSpeakerAccount speaker;

    @Autowired
    public BilibiliSpeakerLoginHealthProbe(BilibiliSpeakerAccount speaker) {
        this.speaker = speaker;
    }

    @Override
    public String name() {
        return "哔哩哔哩发言账号登录";
    }

    @Override
    public int order() {
        return 11;
    }

    @Override
    public Scope scope() {
        return Scope.PLATFORM;
    }

    @Override
    public boolean loginState() {
        return true;
    }

    @Override
    public String card() {
        return BilibiliSpeakerAccount.ID;
    }

    @Override
    public boolean active() {
        return speaker.hasCredentials();
    }

    @Override
    public HealthStatus check() {
        if (!speaker.isLoggedIn()) {
            return HealthStatus.down(speaker.isLoggingIn() ? "等待扫码登录" : "凭据已失效，需要重新扫码", ADVICE_EXPIRED);
        }

        Long uid = speaker.uid();
        if (uid == null) {
            return HealthStatus.degraded("登录凭据暂未确认",
                    "启动时没能向哔哩哔哩确认发言账号的登录态，多半是网络不通。确认之前不用它发言，网络恢复后会自动确认");
        }

        if (speaker.sameAsListener()) {
            return HealthStatus.down("与监听账号是同一个号", BilibiliSpeakerAccount.SAME_AS_LISTENER_NOW);
        }

        String summary = "正常（uid " + uid + "）";
        if (isStale()) {
            return HealthStatus.degraded(summary + "，但登录态已长时间未能成功复检",
                    "通常是本机到哔哩哔哩的网络不通，此处显示的登录态可能已不准确，请检查网络");
        }
        if (speaker.settings().isAutoRefreshCookie() && !speaker.isRefreshable()) {
            return new HealthStatus(HealthStatus.Level.OK, summary + "，但未取得刷新口令，无法自动续期",
                    "本次登录时服务端未下发刷新口令，凭据到期后需要重新扫码");
        }
        return HealthStatus.ok(summary);
    }

    private boolean isStale() {
        int interval = speaker.settings().getVerifyInterval();
        if (interval <= 0) {
            return false;
        }
        Instant last = speaker.lastVerifiedAt();
        return last != null && Duration.between(last, Instant.now()).getSeconds() > (long) interval * STALE_FACTOR;
    }
}
