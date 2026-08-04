package com.starlwr.bot.bilibili.handler;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.bilibili.event.live.BilibiliLiveOffEvent;
import com.starlwr.bot.bilibili.painter.BilibiliLiveReportPainter;
import com.starlwr.bot.bilibili.util.BilibiliApiUtil;
import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.event.StarBotExternalBaseEvent;
import com.starlwr.bot.core.handler.StarBotEventHandler;
import com.starlwr.bot.core.model.PushMessage;
import com.starlwr.bot.core.model.PushTarget;
import com.starlwr.bot.core.plugin.StarBotComponent;
import com.starlwr.bot.core.sender.StarBotMessageSender;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

/**
 * 下播报告推送处理器
 * <p>
 * 主播结束直播时，把本场直播累计的统计数据绘制为报告图片并推送。
 * 与「下播通知」相互独立，可单独启用或同时启用。
 */
@Slf4j
@StarBotComponent
public class BilibiliLiveReportPushHandler implements StarBotEventHandler {
    private final BilibiliApiUtil api;

    private final StarBotMessageSender sender;

    private final BilibiliLiveReportPainter painter;

    @Autowired
    public BilibiliLiveReportPushHandler(BilibiliApiUtil api, StarBotMessageSender sender, BilibiliLiveReportPainter painter) {
        this.api = api;
        this.sender = sender;
        this.painter = painter;
    }

    @Override
    public void handle(StarBotExternalBaseEvent baseEvent, PushMessage pushMessage) {
        BilibiliLiveOffEvent event = (BilibiliLiveOffEvent) baseEvent;
        JSONObject params = pushMessage.getParamsJsonObject();
        PushTarget target = pushMessage.getTarget();

        // 绘制失败时占位符替换为空串；默认模板只含 {report}，此时消息为空白，发送环节会直接跳过
        String report = painter.paint(event.getPlatform(), event.getSource())
                .map(base64 -> "{image_base64=" + base64 + "}")
                .orElse("");

        String content = params.getString("message")
                .replace("{uname}", PushHandlerSupport.resolveUname(api, event.getSource()))
                .replace("{url}", "https://live.bilibili.com/" + event.getSource().getRoomId())
                .replace("{report}", report);

        PushHandlerSupport.send(sender, target, PushHandlerSupport.withAtAll(params, target, content));
    }

    @Override
    public Class<? extends StarBotExternalBaseEvent> getEventType() {
        return BilibiliLiveOffEvent.class;
    }

    @Override
    public JSONObject getDefaultParams() {
        JSONObject params = new JSONObject();
        params.put("at_all", false);
        params.put("message", "{report}");
        return params;
    }

    @Override
    public String displayName() {
        return "下播报告";
    }

    @Override
    public String description() {
        return "主播结束直播时推送本场直播数据统计图";
    }

    @Override
    public String platform() {
        return LivePlatform.BILIBILI.getName();
    }

    @Override
    public List<String> placeholders() {
        return List.of("{uname}", "{report}", "{url}", "{next}", "{at=all}");
    }
}
