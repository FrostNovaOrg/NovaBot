package com.starlwr.bot.bilibili.health;

import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.bilibili.enums.ConnectStatus;
import com.starlwr.bot.bilibili.service.BilibiliLiveRoomService;
import com.starlwr.bot.core.health.HealthProbe;
import com.starlwr.bot.core.health.HealthStatus;
import com.starlwr.bot.core.plugin.StarBotComponent;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

/**
 * 直播间长连接健康探针
 */
@StarBotComponent
public class BilibiliLiveRoomHealthProbe implements HealthProbe {
    private final BilibiliLiveRoomService liveRoomService;

    private final StarBotBilibiliProperties properties;

    @Autowired
    public BilibiliLiveRoomHealthProbe(BilibiliLiveRoomService liveRoomService, StarBotBilibiliProperties properties) {
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

        // 被风控时长连接看似正常，弹幕与礼物却收不到，需要单独指出
        if (risk > 0) {
            return HealthStatus.degraded(summary + "，其中 " + risk + " 个被数据风控",
                    "被风控的直播间收不到弹幕、礼物等事件，开播下播推送仍由备用直播推送保障。"
                            + "通常与账号或 IP 有关，可稍后重试或改用其他账号");
        }

        if (connected < managed) {
            return HealthStatus.degraded(summary,
                    "部分直播间尚未连接成功，可能仍在重试中；若长时间不恢复，请检查本机到哔哩哔哩的网络");
        }

        return HealthStatus.ok(summary);
    }
}
