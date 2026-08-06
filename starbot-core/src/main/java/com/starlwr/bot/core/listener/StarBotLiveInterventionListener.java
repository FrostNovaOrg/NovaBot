package com.starlwr.bot.core.listener;

import com.starlwr.bot.core.alert.AlertService;
import com.starlwr.bot.core.event.live.base.StarBotLiveInterventionEvent;
import com.starlwr.bot.core.event.live.common.LiveCutOffEvent;
import com.starlwr.bot.core.event.live.common.LiveWarningEvent;
import com.starlwr.bot.core.event.live.common.RoomLockEvent;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 平台干预告警监听器
 * <p>
 * <b>为什么走告警通道而不是粉丝群推送。</b>被警告、被切流、被封禁都是需要主播
 * <i>立刻</i>知道并处理的事，而粉丝群里的人帮不上忙——把「本场被平台切断了」
 * 广播给观众，除了尴尬没有别的作用。告警通道（QQ 私聊 / 邮件 / Webhook）
 * 本来就是给运维和主播本人看的，正是这类消息该去的地方。
 */
@Slf4j
@Component
public class StarBotLiveInterventionListener {
    private static final DateTimeFormatter EXPIRE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final AlertService alertService;

    @Autowired
    public StarBotLiveInterventionListener(AlertService alertService) {
        this.alertService = alertService;
    }

    /**
     * 违规警告
     * <p>
     * 警告是切流之前的最后一次机会，处理得及时就不会真被切，所以它同样值得叫醒人。
     */
    @EventListener
    public void onWarning(LiveWarningEvent event) {
        alert(event, "warning", "直播收到违规警告",
                describe(event) + " 收到平台警告：" + reasonOf(event)
                        + "\n未及时处理可能被切断直播流，请尽快按提示调整。");
    }

    /**
     * 直播流被切断
     */
    @EventListener
    public void onCutOff(LiveCutOffEvent event) {
        alert(event, "cut-off", "直播流被平台切断",
                describe(event) + " 的直播流已被平台切断：" + reasonOf(event)
                        + "\n本场将以「被平台切断」归档，时长与营收不代表正常水平。");
    }

    /**
     * 直播间被封禁
     */
    @EventListener
    public void onRoomLock(RoomLockEvent event) {
        String expire = event.getExpireAt() == null
                ? "平台未给出解封时间"
                : "解封时间 " + EXPIRE_FORMAT.format(event.getExpireAt());

        alert(event, "room-lock", "直播间被封禁",
                describe(event) + " 的直播间已被封禁：" + reasonOf(event) + "\n" + expire + "。");
    }

    private void alert(StarBotLiveInterventionEvent event, String kind, String subject, String content) {
        LiveStreamerInfo source = event.getSource();
        Long uid = source == null ? null : source.getUid();
        alertService.alert("live-intervention:" + kind + ":" + event.getPlatform() + ":" + uid, subject, content);
    }

    /**
     * 主播的可读描述
     */
    private String describe(StarBotLiveInterventionEvent event) {
        LiveStreamerInfo source = event.getSource();
        if (source == null) {
            return "未知主播";
        }
        return source.getUname() + "（直播间 " + source.getRoomIdString() + "）";
    }

    /**
     * 平台说明文案，缺失时给出中性描述而不是留空
     */
    private String reasonOf(StarBotLiveInterventionEvent event) {
        String reason = event.getReason();
        return reason == null || reason.isBlank() ? "平台未给出说明" : reason;
    }
}
