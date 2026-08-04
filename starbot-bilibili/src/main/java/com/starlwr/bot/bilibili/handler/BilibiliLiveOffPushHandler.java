package com.starlwr.bot.bilibili.handler;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.bilibili.event.live.BilibiliLiveOffEvent;
import com.starlwr.bot.bilibili.util.BilibiliApiUtil;
import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.event.StarBotExternalBaseEvent;
import com.starlwr.bot.core.handler.StarBotEventHandler;
import com.starlwr.bot.core.model.PushMessage;
import com.starlwr.bot.core.model.PushTarget;
import com.starlwr.bot.core.plugin.StarBotComponent;
import com.starlwr.bot.core.sender.StarBotMessageSender;
import com.starlwr.bot.core.service.LiveDataService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import java.util.Optional;

/**
 * 下播推送处理器
 */
@Slf4j
@StarBotComponent
public class BilibiliLiveOffPushHandler implements StarBotEventHandler {
    private final BilibiliApiUtil api;

    private final StarBotMessageSender sender;

    private final LiveDataService liveDataService;

    @Autowired
    public BilibiliLiveOffPushHandler(BilibiliApiUtil api, StarBotMessageSender sender, LiveDataService liveDataService) {
        this.api = api;
        this.sender = sender;
        this.liveDataService = liveDataService;
    }

    @Override
    public void handle(StarBotExternalBaseEvent baseEvent, PushMessage pushMessage) {
        BilibiliLiveOffEvent event = (BilibiliLiveOffEvent) baseEvent;
        JSONObject params = pushMessage.getParamsJsonObject();
        PushTarget target = pushMessage.getTarget();

        // 时长取不到时（如程序在开播后才启动，未记录到开播时间）移除 {time} 所在分句，
        // 避免渲染出「……，本场直播时长 」这样的悬空半句
        String content = PushHandlerSupport.replaceOrDropClause(params.getString("message"), "{time}", formatDuration(event))
                .replace("{uname}", PushHandlerSupport.resolveUname(api, event.getSource()))
                .replace("{url}", "https://live.bilibili.com/" + event.getSource().getRoomId());

        PushHandlerSupport.send(sender, target, PushHandlerSupport.withAtAll(params, target, content));
    }

    /**
     * 计算本场直播时长
     * @param event 下播事件
     * @return 时长描述，无法计算时返回空字符串
     */
    private String formatDuration(BilibiliLiveOffEvent event) {
        Optional<Long> start = liveDataService.getLiveStartTime(event.getPlatform(), event.getSource().getUid());
        Optional<Long> end = liveDataService.getLiveEndTime(event.getPlatform(), event.getSource().getUid());

        if (start.isEmpty() || end.isEmpty()) {
            return "";
        }

        long duration = (end.get() - start.get()) / 1000;
        if (duration <= 0) {
            return "";
        }

        StringBuilder text = new StringBuilder();
        long hours = duration / 3600;
        long minutes = duration % 3600 / 60;
        long seconds = duration % 60;

        if (hours > 0) {
            text.append(hours).append(" 时 ");
        }
        if (minutes > 0) {
            text.append(minutes).append(" 分 ");
        }
        if (seconds > 0) {
            text.append(seconds).append(" 秒");
        }

        return text.toString().trim();
    }

    @Override
    public Class<? extends StarBotExternalBaseEvent> getEventType() {
        return BilibiliLiveOffEvent.class;
    }

    @Override
    public JSONObject getDefaultParams() {
        JSONObject params = new JSONObject();
        params.put("at_all", false);
        params.put("message", "{uname} 直播结束了，本场直播时长 {time}");
        return params;
    }

    @Override
    public String displayName() {
        return "下播通知";
    }

    @Override
    public String description() {
        return "主播结束直播时推送";
    }

    @Override
    public String platform() {
        return LivePlatform.BILIBILI.getName();
    }

    @Override
    public List<String> placeholders() {
        return List.of("{uname}", "{time}", "{url}", "{next}", "{at=all}");
    }
}
