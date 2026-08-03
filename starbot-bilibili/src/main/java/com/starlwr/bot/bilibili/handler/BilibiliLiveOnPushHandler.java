package com.starlwr.bot.bilibili.handler;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.bilibili.event.live.BilibiliLiveOnEvent;
import com.starlwr.bot.bilibili.model.Room;
import com.starlwr.bot.bilibili.util.BilibiliApiUtil;
import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.enums.PushTargetType;
import com.starlwr.bot.core.event.StarBotExternalBaseEvent;
import com.starlwr.bot.core.handler.StarBotEventHandler;
import com.starlwr.bot.core.model.Message;
import com.starlwr.bot.core.model.PushMessage;
import com.starlwr.bot.core.model.PushTarget;
import com.starlwr.bot.core.plugin.StarBotComponent;
import com.starlwr.bot.core.sender.StarBotMessageSender;
import com.starlwr.bot.core.util.StringUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

/**
 * 开播推送处理器
 */
@Slf4j
@StarBotComponent
public class BilibiliLiveOnPushHandler implements StarBotEventHandler {
    private final BilibiliApiUtil api;

    private final StarBotMessageSender sender;

    @Autowired
    public BilibiliLiveOnPushHandler(BilibiliApiUtil api, StarBotMessageSender sender) {
        this.api = api;
        this.sender = sender;
    }

    @Override
    public void handle(StarBotExternalBaseEvent baseEvent, PushMessage pushMessage) {
        BilibiliLiveOnEvent event = (BilibiliLiveOnEvent) baseEvent;
        JSONObject params = pushMessage.getParamsJsonObject();
        PushTarget target = pushMessage.getTarget();

        // 短时间内断线重连视为同一场直播，避免重复通知
        if (event.isReconnect()) {
            PushHandlerSupport.send(sender, target, params.getString("reconnect_message"));
            return;
        }

        String uname = PushHandlerSupport.resolveUname(api, event.getSource());

        String title = "";
        String cover = "";
        try {
            Room room = api.getLiveInfoByRoomId(event.getSource().getRoomId());
            title = StringUtil.isBlank(room.getTitle()) ? "" : room.getTitle();
            if (StringUtil.isNotBlank(room.getCover())) {
                cover = "{image_url=" + room.getCover() + "}";
            }
        } catch (Exception e) {
            log.error("获取直播间 {} 的标题与封面失败: {}", event.getSource().getRoomIdString(), e.getMessage());
        }

        String content = params.getString("message")
                .replace("{uname}", uname)
                .replace("{title}", title)
                .replace("{url}", "https://live.bilibili.com/" + event.getSource().getRoomId())
                .replace("{cover}", cover);

        PushHandlerSupport.send(sender, target, PushHandlerSupport.withAtAll(params, target, content));
    }

    @Override
    public Class<? extends StarBotExternalBaseEvent> getEventType() {
        return BilibiliLiveOnEvent.class;
    }

    @Override
    public JSONObject getDefaultParams() {
        JSONObject params = new JSONObject();
        params.put("at_all", false);
        params.put("message", "{uname} 正在直播 {title}\n{url}{next}{cover}");
        params.put("reconnect_message", "检测到下播后短时间内重新开播,本次开播不再重复通知");
        return params;
    }

    @Override
    public String displayName() {
        return "开播通知";
    }

    @Override
    public String description() {
        return "主播开始直播时推送";
    }

    @Override
    public String platform() {
        return LivePlatform.BILIBILI.getName();
    }

    @Override
    public List<String> placeholders() {
        return List.of("{uname}", "{title}", "{cover}", "{url}", "{next}", "{at=all}");
    }
}
