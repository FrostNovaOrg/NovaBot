package org.frostnova.nova.bilibili.health;

import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.enums.ConnectStatus;
import org.frostnova.nova.bilibili.service.BilibiliLiveRoomService;
import org.frostnova.nova.core.health.HealthProbe;
import org.frostnova.nova.core.health.HealthStatus;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

/**
 * 直播间长连接健康探针
 */
@NovaComponent
public class BilibiliLiveRoomHealthProbe implements HealthProbe {
    /**
     * 摘要里点名的上限。再多就只写余下有几个。
     */
    private static final int NAMED_LIMIT = 5;

    /**
     * 处理建议：先去直播间看，确有人发而这里不恢复再重启，仍不行再查登录账号。
     */
    private static final String ADVICE = "连续若干分钟没收到弹幕、礼物，进房消息照常。"
            + "可能是房间里没人说话，也可能是主播画面卡住，或是平台没把弹幕礼物发下来。"
            + "先去直播间看一眼是否确有人在发弹幕；确有人发而这里一直没恢复，再重启试；仍不行再查登录账号。";

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

        String summary = connected + "/" + managed + " 已连接";

        // 只说观测、不说成因：这里分不清没人说话、画面卡住与平台没下发。
        List<LiveStreamerInfo> quiet = liveRoomService.sourcesWithStatus(ConnectStatus.RISK);
        if (!quiet.isEmpty()) {
            return HealthStatus.degraded(summary + "，其中 " + quiet.size()
                    + " 间连续多分钟没收到弹幕礼物、进房照常：" + nameList(quiet), ADVICE);
        }

        if (connected < managed) {
            return HealthStatus.degraded(summary,
                    "部分直播间尚未连接成功，可能仍在重试中；若长时间不恢复，请检查本机到哔哩哔哩的网络");
        }

        return HealthStatus.ok(summary);
    }

    /**
     * 点出主播名和房间号。超过五个只列前五个，余下写还有几个。
     */
    private static String nameList(List<LiveStreamerInfo> rooms) {
        int shown = Math.min(NAMED_LIMIT, rooms.size());
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < shown; i++) {
            if (i > 0) {
                text.append('、');
            }
            LiveStreamerInfo one = rooms.get(i);
            String name = one.getUname();
            if (name == null || name.isBlank()) {
                name = "未命名";
            }
            String room = one.getRoomId() == null ? "未知房间" : Long.toString(one.getRoomId());
            text.append(name).append('（').append(room).append('）');
        }
        int rest = rooms.size() - shown;
        if (rest > 0) {
            text.append("，等 ").append(rest).append(" 个");
        }
        return text.toString();
    }
}
