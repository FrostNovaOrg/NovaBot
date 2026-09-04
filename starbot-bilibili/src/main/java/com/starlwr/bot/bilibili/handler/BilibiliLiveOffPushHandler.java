package com.starlwr.bot.bilibili.handler;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.bilibili.BilibiliPlatform;
import com.starlwr.bot.bilibili.event.live.BilibiliLiveOffEvent;
import com.starlwr.bot.bilibili.util.BilibiliApiUtil;
import com.starlwr.bot.bilibili.util.DurationFormatUtil;
import com.starlwr.bot.core.event.StarBotExternalBaseEvent;
import com.starlwr.bot.core.handler.StarBotEventHandler;
import com.starlwr.bot.core.model.PushMessage;
import com.starlwr.bot.core.model.PushTarget;
import com.starlwr.bot.core.plugin.StarBotComponent;
import com.starlwr.bot.core.sender.AtMode;
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
        String template = params.getString("message");
        String content = PushHandlerSupport.replaceOrDropClause(template, "{time}", formatDuration(event))
                .replace("{uname}", PushHandlerSupport.resolveUname(api, event.getSource()))
                .replace("{url}", "https://live.bilibili.com/" + event.getSource().getRoomId());

        // 下播通知没有「@ 订阅的人」这回事（订阅只分开播与动态两类），因此订阅串给空串：
        // 三选一里剩下的那一档等价于旧的 at_all 开关，取值仍从旧键来
        PushHandlerSupport.send(sender, target,
                PushHandlerSupport.withAtBlock(AtMode.of(params), target, template, content, ""));
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

        return DurationFormatUtil.format((end.get() - start.get()) / 1000);
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
        return BilibiliPlatform.BILIBILI.id();
    }

    @Override
    public List<String> placeholders() {
        return List.of("{uname}", "{time}", "{url}", "{next}", "{at=all}");
    }
}
