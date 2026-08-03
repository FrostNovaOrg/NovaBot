package com.starlwr.bot.bilibili.health;

import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.bilibili.service.BilibiliAccountService;
import com.starlwr.bot.core.health.HealthProbe;
import com.starlwr.bot.core.health.HealthStatus;
import com.starlwr.bot.core.plugin.StarBotComponent;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;

/**
 * 哔哩哔哩登录态健康探针
 * <p>
 * 只读取账号服务中已有的内存状态，不发起网络请求：实际的探测由定期复检任务完成。
 */
@StarBotComponent
public class BilibiliLoginHealthProbe implements HealthProbe {
    /**
     * 复检结果被视为「过期」的宽限倍数
     * <p>
     * 网络故障时复检会维持原状态而不更新时间戳，若长时间未能成功复检，展示的登录态便不再可信，
     * 此时应提示使用者而不是继续显示「正常」。取两倍间隔以容忍偶发失败。
     */
    private static final int STALE_FACTOR = 2;

    private final BilibiliAccountService accountService;

    private final StarBotBilibiliProperties properties;

    @Autowired
    public BilibiliLoginHealthProbe(BilibiliAccountService accountService, StarBotBilibiliProperties properties) {
        this.accountService = accountService;
        this.properties = properties;
    }

    @Override
    public String name() {
        return "哔哩哔哩登录";
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    public Scope scope() {
        return Scope.PLATFORM;
    }

    @Override
    public HealthStatus check() {
        if (!accountService.isLoggedIn()) {
            return HealthStatus.down(
                    accountService.getPendingQrCodeContent() != null ? "等待扫码登录" : "未登录",
                    "动态推送与自动关注不可用，直播推送不受影响。请扫描启动日志中的二维码完成登录"
            );
        }

        String summary = "正常（uid " + accountService.getLoginUid() + "）";

        if (isStale()) {
            return HealthStatus.degraded(
                    summary + "，但登录态已长时间未能成功复检",
                    "通常是本机到哔哩哔哩的网络不通，此处显示的登录态可能已不准确，请检查网络"
            );
        }

        // 缺少刷新口令时自动续期会一直静默跳过，凭据到期后表现为「某天突然掉登录」。
        // 实测确有登录响应把 refresh_token 返回为空串的情况，因此这件事必须让使用者看得见，
        // 但它不影响当前推送，故仍记为正常，只在描述里说明
        if (properties.getAccount().isAutoRefreshCookie() && !accountService.isRefreshable()) {
            return new HealthStatus(HealthStatus.Level.OK,
                    summary + "，但未取得刷新口令，无法自动续期",
                    "本次登录时服务端未下发刷新口令，凭据到期后需要重新扫码。当前推送不受影响");
        }

        return HealthStatus.ok(summary);
    }

    /**
     * 判断上次成功复检是否已过期
     * @return 是否已过期
     */
    private boolean isStale() {
        int interval = properties.getAccount().getVerifyInterval();
        if (interval <= 0) {
            // 复检已关闭，不存在过期一说
            return false;
        }

        Instant last = accountService.getLastVerifiedAt();
        if (last == null) {
            // 启动后尚未到首个复检周期，属正常情况
            return false;
        }

        return Duration.between(last, Instant.now()).getSeconds() > (long) interval * STALE_FACTOR;
    }
}
