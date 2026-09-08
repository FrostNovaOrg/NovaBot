package com.starlwr.bot.core.service;

import com.starlwr.bot.core.enums.LiveEndReason;
import com.starlwr.bot.core.event.live.base.StarBotLiveInterventionEvent;
import com.starlwr.bot.core.event.live.common.LiveCutOffEvent;
import com.starlwr.bot.core.event.live.common.LiveOnEvent;
import com.starlwr.bot.core.event.live.common.RoomLockEvent;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 平台干预记录
 * <p>
 * 平台切流时下发的是 {@code CUT_OFF}，紧接着才是下播消息——两条独立的消息，
 * 而「这场是不是被切的」只有把它们对上才答得出来。本类就是这个对应关系的存放处。
 * <p>
 * <b>为什么不去查接口。</b>回查直播间状态是更直觉的做法，但
 * {@code getInfoByRoom} 实测被风控挡回 -352（补齐 WBI 签名与 buvid 后依然如此），
 * 而 {@code room/v1/Room/get_info} 压根不返回封禁字段。
 * 长连接自己下发的指令不需要额外请求，也不受风控影响，是更可靠的判据。
 */
@Slf4j
@Service
public class LiveInterventionTracker {
    /**
     * 干预记录的有效期
     * <p>
     * 切流到下播通常只隔几秒，但下播还有备用轮询这条发现路径（默认 10 秒一轮），
     * 极端情况下会晚上一阵子。取 5 分钟是宁可宽松：这段时间内除了这次切流，
     * 本来也不会有别的原因让直播结束。真正防误判的是开播时清空，而不是窗口本身。
     */
    private static final Duration RETENTION = Duration.ofMinutes(5);

    private final Map<String, Intervention> interventions = new ConcurrentHashMap<>();

    /**
     * 记下一次切流
     */
    @Order(-10000)
    @EventListener
    public void onCutOff(LiveCutOffEvent event) {
        record(event, LiveEndReason.CUT_OFF);
    }

    /**
     * 记下一次封禁
     */
    @Order(-10000)
    @EventListener
    public void onRoomLock(RoomLockEvent event) {
        record(event, LiveEndReason.ROOM_LOCK);
    }

    /**
     * 开播时清空该主播的干预记录
     * <p>
     * 上一场被切、这一场重开，新的一场必须从干净状态开始，
     * 否则它一结束就会被扣上「被切断」的帽子。
     */
    @Order(-10000)
    @EventListener
    public void onLiveOn(LiveOnEvent event) {
        if (event.getSource() == null || event.getSource().getUid() == null) {
            return;
        }
        interventions.remove(key(event.getPlatform(), event.getSource().getUid()));
    }

    /**
     * 判断一场直播的结束原因
     * @param platform 直播平台
     * @param uid 主播 UID
     * @param endedAt 下播时刻
     * @return 结束原因，没有对得上的干预记录时为 {@link LiveEndReason#NORMAL}
     */
    public LiveEndReason endReason(@NonNull String platform, @NonNull Long uid, @NonNull Instant endedAt) {
        Intervention intervention = interventions.get(key(platform, uid));
        if (intervention == null) {
            return LiveEndReason.NORMAL;
        }

        // 干预发生在下播之后，说明这条记录属于别的时段，不能算在这场头上
        if (intervention.at().isAfter(endedAt)) {
            return LiveEndReason.NORMAL;
        }

        return Duration.between(intervention.at(), endedAt).compareTo(RETENTION) <= 0
                ? intervention.reason()
                : LiveEndReason.NORMAL;
    }

    /**
     * 取该主播最近一次干预说明
     * @param platform 直播平台
     * @param uid 主播 UID
     * @return 平台给出的说明文案，无记录时为空字符串
     */
    public String lastDetail(@NonNull String platform, @NonNull Long uid) {
        Intervention intervention = interventions.get(key(platform, uid));
        return intervention == null || intervention.detail() == null ? "" : intervention.detail();
    }

    private void record(StarBotLiveInterventionEvent event, LiveEndReason reason) {
        if (event.getSource() == null || event.getSource().getUid() == null) {
            return;
        }

        interventions.put(key(event.getPlatform(), event.getSource().getUid()),
                new Intervention(reason, Instant.ofEpochMilli(event.getTimestamp()), event.getReason()));
        log.warn("[{}] [{}] {}(UID: {}): {}", event.getPlatform(), reason.getDescription(),
                event.getSource().getUname(), event.getSource().getUid(), event.getReason());
    }

    private static String key(String platform, Long uid) {
        return platform + ":" + uid;
    }

    /**
     * 一次平台干预
     * @param reason 对应的结束原因
     * @param at 发生时刻
     * @param detail 平台给出的说明文案
     */
    private record Intervention(LiveEndReason reason, Instant at, String detail) {
    }
}
