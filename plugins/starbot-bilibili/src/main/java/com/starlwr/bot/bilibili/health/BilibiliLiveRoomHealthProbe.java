package com.starlwr.bot.bilibili.health;

import com.starlwr.bot.bilibili.config.NovaBilibiliProperties;
import com.starlwr.bot.bilibili.enums.ConnectStatus;
import com.starlwr.bot.bilibili.service.BilibiliLiveRoomService;
import com.starlwr.bot.core.health.HealthProbe;
import com.starlwr.bot.core.health.HealthStatus;
import com.starlwr.bot.core.plugin.NovaComponent;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

/**
 * 直播间长连接健康探针
 */
@NovaComponent
public class BilibiliLiveRoomHealthProbe implements HealthProbe {
    private final BilibiliLiveRoomService liveRoomService;

    private final NovaBilibiliProperties properties;

    @Autowired
    public BilibiliLiveRoomHealthProbe(BilibiliLiveRoomService liveRoomService, NovaBilibiliProperties properties) {
        this.liveRoomService = liveRoomService;
        this.properties = properties;
    }

    @Override
    public String name() {
        return "直播间连接";
    }

    @Override
    public int order() {
        return 30;
    }

    @Override
    public Scope scope() {
        return Scope.PLATFORM;
    }

    @Override
    public HealthStatus check() {
        if (!properties.getLive().isEnableConnectLiveRoom()) {
            return HealthStatus.ok("已关闭长连接，仅使用备用直播推送");
        }

        int managed = liveRoomService.getManagedRoomCount();
        if (managed == 0) {
            return HealthStatus.ok("暂无需要连接的直播间");
        }

        Map<ConnectStatus, Long> counts = liveRoomService.countByStatus();
        long connected = counts.getOrDefault(ConnectStatus.CONNECTED, 0L);
        long risk = counts.getOrDefault(ConnectStatus.RISK, 0L);

        String summary = connected + "/" + managed + " 已连接";

        // 断流时长连接看似正常，业务消息却不来，需要单独指出。
        // 但只说观测、不说成因：这里分不清平台限制、解析不出、连接半死与「确实没人说话」，
        // 2026-08-10 的误报就是把最后一种当成了第一种
        if (risk > 0) {
            return HealthStatus.degraded(summary + "，其中 " + risk + " 个业务消息断流",
                    "断流的直播间暂时收不到弹幕、礼物等事件，开播下播推送仍由备用直播推送保障。"
                            + "原因未定：可能是平台限制下发、账号或 IP 受限、也可能只是当时无人发言。"
                            + "先看该直播间是否确有人在互动，再考虑稍后重试或改用其他账号");
        }

        if (connected < managed) {
            return HealthStatus.degraded(summary,
                    "部分直播间尚未连接成功，可能仍在重试中；若长时间不恢复，请检查本机到哔哩哔哩的网络");
        }

        return HealthStatus.ok(summary);
    }
}
