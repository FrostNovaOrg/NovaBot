package com.starlwr.bot.bilibili.handler;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.bilibili.event.live.BilibiliLiveOffEvent;
import com.starlwr.bot.bilibili.model.BilibiliLiveReportOptions;
import com.starlwr.bot.bilibili.painter.BilibiliLiveReportPainter;
import com.starlwr.bot.bilibili.util.BilibiliApiUtil;
import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.event.StarBotExternalBaseEvent;
import com.starlwr.bot.core.handler.StarBotEventHandler;
import com.starlwr.bot.core.model.HandlerOption;
import com.starlwr.bot.core.model.PushMessage;
import com.starlwr.bot.core.model.PushTarget;
import com.starlwr.bot.core.plugin.StarBotComponent;
import com.starlwr.bot.core.sender.StarBotMessageSender;
import com.starlwr.bot.core.service.RevenueVisibilityService;
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

    private final RevenueVisibilityService revenueVisibility;

    @Autowired
    public BilibiliLiveReportPushHandler(BilibiliApiUtil api, StarBotMessageSender sender,
                                         BilibiliLiveReportPainter painter, RevenueVisibilityService revenueVisibility) {
        this.api = api;
        this.sender = sender;
        this.painter = painter;
        this.revenueVisibility = revenueVisibility;
    }

    @Override
    public void handle(StarBotExternalBaseEvent baseEvent, PushMessage pushMessage) {
        BilibiliLiveOffEvent event = (BilibiliLiveOffEvent) baseEvent;
        JSONObject params = pushMessage.getParamsJsonObject();
        PushTarget target = pushMessage.getTarget();

        // 版式来自推送配置，金额可见性来自会话：前者是「长什么样」，后者是「给谁看」。
        // 同一套版式推给主播私聊和推给大群，该显示的区块相同，该不该带金额则相反
        boolean showRevenue = revenueVisibility.isVisible(target.getPlatform(), target.getType(), target.getNum());

        // 绘制失败时占位符替换为空串；默认模板只含 {report}，此时消息为空白，发送环节会直接跳过
        String report = painter.paint(event.getPlatform(), event.getSource(), BilibiliLiveReportOptions.of(params, showRevenue))
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

    /**
     * 报告版式选项
     * <p>
     * <b>这里是默认值的唯一出处</b>：{@link #getDefaultParams()} 与配置界面都从它派生。
     * 若两处各写一份，改了一处忘了另一处，界面上勾的与实际生效的就会对不上——
     * 而这种不一致不会有任何报错，只会让人以为「配了没用」。
     * <p>
     * 排行榜类填的是「展示前多少名」，0 为不展示；上限 20 与
     * {@link BilibiliLiveReportOptions} 的夹取区间一致。
     */
    private static final List<HandlerOption> OPTIONS = List.of(
            HandlerOption.bool("cover", "直播间封面", "报告顶部的封面横幅", true),
            HandlerOption.bool("cards", "数据卡片", "弹幕、礼物、点赞等概览卡片", true),
            HandlerOption.bool("fans_change", "本场变化", "粉丝、粉丝团、大航海的涨幅，每次出报告要多打三个接口", true),
            HandlerOption.bool("interaction_curve", "互动曲线", "弹幕、礼物等随时间的变化曲线", true),
            HandlerOption.bool("guard_list", "大航海名单", "本场新开通大航海的观众", true),
            HandlerOption.bool("danmu_cloud", "弹幕词云", "本场弹幕的词云图", true),
            HandlerOption.integer("danmu_ranking", "弹幕排行", "展示前几名，0 为不展示", 5, 0, 20),
            HandlerOption.integer("gift_ranking", "礼物排行", "展示前几名，0 为不展示", 5, 0, 20),
            HandlerOption.integer("super_chat_ranking", "醒目留言排行", "展示前几名，0 为不展示", 5, 0, 20),
            // 盲盒两榜默认关闭：多数直播间没有盲盒数据，开着只会让报告多两块空白
            HandlerOption.integer("box_ranking", "盲盒排行", "展示前几名，0 为不展示", 0, 0, 20),
            HandlerOption.integer("box_profit_ranking", "盲盒盈亏排行", "展示前几名，0 为不展示", 0, 0, 20));

    @Override
    public JSONObject getDefaultParams() {
        JSONObject params = new JSONObject();
        params.put("at_all", false);
        params.put("message", "{report}");
        OPTIONS.forEach(option -> params.put(option.key(), option.defaultValue()));
        return params;
    }

    @Override
    public List<HandlerOption> options() {
        return OPTIONS;
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
