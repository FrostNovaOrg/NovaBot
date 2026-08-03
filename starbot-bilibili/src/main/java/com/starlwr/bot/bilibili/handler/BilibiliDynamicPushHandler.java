package com.starlwr.bot.bilibili.handler;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.bilibili.event.dynamic.BilibiliDynamicUpdateEvent;
import com.starlwr.bot.bilibili.painter.BilibiliDynamicPainter;
import com.starlwr.bot.bilibili.util.BilibiliApiUtil;
import com.starlwr.bot.core.event.StarBotExternalBaseEvent;
import com.starlwr.bot.core.handler.StarBotEventHandler;
import com.starlwr.bot.core.model.PushMessage;
import com.starlwr.bot.core.model.PushTarget;
import com.starlwr.bot.core.plugin.StarBotComponent;
import com.starlwr.bot.core.sender.StarBotMessageSender;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Optional;

/**
 * 动态推送处理器
 */
@Slf4j
@StarBotComponent
public class BilibiliDynamicPushHandler implements StarBotEventHandler {
    private final BilibiliApiUtil api;

    private final BilibiliDynamicPainter painter;

    private final StarBotMessageSender sender;

    @Autowired
    public BilibiliDynamicPushHandler(BilibiliApiUtil api, BilibiliDynamicPainter painter, StarBotMessageSender sender) {
        this.api = api;
        this.painter = painter;
        this.sender = sender;
    }

    @Override
    public void handle(StarBotExternalBaseEvent baseEvent, PushMessage pushMessage) {
        BilibiliDynamicUpdateEvent event = (BilibiliDynamicUpdateEvent) baseEvent;
        JSONObject params = pushMessage.getParamsJsonObject();
        PushTarget target = pushMessage.getTarget();

        if (!shouldPush(event, params)) {
            return;
        }

        String picture = painter.paint(event.getDynamic())
                .map(base64 -> "{image_base64=" + base64 + "}")
                .orElse("");

        String content = params.getString("message")
                .replace("{uname}", PushHandlerSupport.resolveUname(api, event.getSource()))
                .replace("{action}", Optional.ofNullable(event.getAction()).orElse("发布了动态"))
                .replace("{url}", Optional.ofNullable(event.getUrl()).orElse(""))
                .replace("{picture}", picture);

        PushHandlerSupport.send(sender, target, PushHandlerSupport.withAtAll(params, target, content));
    }

    /**
     * 依据黑白名单与转发过滤判断是否需要推送
     * @param event 动态更新事件
     * @param params 推送参数
     * @return 是否需要推送
     */
    private boolean shouldPush(BilibiliDynamicUpdateEvent event, JSONObject params) {
        String type = event.getDynamic().getType();

        JSONArray whiteList = params.getJSONArray("white_list");
        if (whiteList != null && !whiteList.isEmpty()) {
            if (!whiteList.contains(type)) {
                log.info("{} 的动态类型 {} 不在白名单中, 跳过推送", event.getSource().getUname(), type);
                return false;
            }
        } else {
            JSONArray blackList = params.getJSONArray("black_list");
            if (blackList != null && blackList.contains(type)) {
                log.info("{} 的动态类型 {} 在黑名单中, 跳过推送", event.getSource().getUname(), type);
                return false;
            }
        }

        // 仅推送转发自己动态的转发
        if (event.getDynamic().isForward() && params.getBooleanValue("only_self_origin")) {
            Long originUid = Optional.ofNullable(event.getDynamic().getOrigin())
                    .flatMap(origin -> origin.getAuthorUid())
                    .orElse(null);

            if (originUid == null || !originUid.equals(event.getSource().getUid())) {
                log.info("{} 转发的动态并非转发自己的动态, 跳过推送", event.getSource().getUname());
                return false;
            }
        }

        return true;
    }

    @Override
    public Class<? extends StarBotExternalBaseEvent> getEventType() {
        return BilibiliDynamicUpdateEvent.class;
    }

    @Override
    public JSONObject getDefaultParams() {
        JSONObject params = new JSONObject();
        params.put("at_all", false);
        params.put("message", "{uname} {action}\n{url}{next}{picture}");
        params.put("white_list", List.of());
        params.put("black_list", List.of());
        params.put("only_self_origin", false);
        return params;
    }
}
