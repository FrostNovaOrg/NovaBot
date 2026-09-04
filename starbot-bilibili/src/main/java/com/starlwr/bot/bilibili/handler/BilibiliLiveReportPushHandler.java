package com.starlwr.bot.bilibili.handler;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.bilibili.BilibiliPlatform;
import com.starlwr.bot.bilibili.event.live.BilibiliLiveOffEvent;
import com.starlwr.bot.bilibili.model.BilibiliLiveReportOptions;
import com.starlwr.bot.bilibili.painter.BilibiliLiveReportPainter;
import com.starlwr.bot.bilibili.util.BilibiliApiUtil;
import com.starlwr.bot.core.event.StarBotExternalBaseEvent;
import com.starlwr.bot.core.handler.StarBotEventHandler;
import com.starlwr.bot.core.model.HandlerOption;
import com.starlwr.bot.core.model.PushMessage;
import com.starlwr.bot.core.model.PushTarget;
import com.starlwr.bot.core.plugin.StarBotComponent;
import com.starlwr.bot.core.sender.AtMode;
import com.starlwr.bot.core.sender.StarBotMessageSender;
import com.starlwr.bot.core.service.LiveDataService;
import com.starlwr.bot.core.service.LiveReportArchive;
import com.starlwr.bot.core.service.RevenueVisibilityService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Base64;
import java.util.List;
import java.util.Optional;

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

    /**
     * 报告图留档，供控制台的场次列表点开看
     */
    private final LiveReportArchive reports;

    /**
     * 开播时刻。留档要拿它当文件名的一部分，好让场次表里的那一行找得到自己的图
     */
    private final LiveDataService liveDataService;

    @Autowired
    public BilibiliLiveReportPushHandler(BilibiliApiUtil api, StarBotMessageSender sender,
                                         BilibiliLiveReportPainter painter, RevenueVisibilityService revenueVisibility,
                                         LiveReportArchive reports, LiveDataService liveDataService) {
        this.api = api;
        this.sender = sender;
        this.painter = painter;
        this.revenueVisibility = revenueVisibility;
        this.reports = reports;
        this.liveDataService = liveDataService;
    }

    @Override
    public void handle(StarBotExternalBaseEvent baseEvent, PushMessage pushMessage) {
        BilibiliLiveOffEvent event = (BilibiliLiveOffEvent) baseEvent;
        JSONObject params = pushMessage.getParamsJsonObject();
        PushTarget target = pushMessage.getTarget();

        // 版式来自推送配置，金额可见性来自会话：前者是「长什么样」，后者是「给谁看」。
        // 同一套版式推给主播私聊和推给大群，该显示的区块相同，该不该带金额则相反
        boolean showRevenue = revenueVisibility.isVisible(target.getPlatform(), target.getType(), target.getNum());

        // 画图失败时改发文字版，而不是什么都不发。默认模板只含 {report}，
        // 此前占位符被替换成空串会让整条消息成为空白、发送环节直接跳过——
        // 主播看到的是「这场没有报告」，而真相是「报告画不出来」
        BilibiliLiveReportOptions options = BilibiliLiveReportOptions.of(params, showRevenue);
        Optional<String> image = painter.paint(event.getPlatform(), event.getSource(), options);

        // 图既然已经画出来了，顺手留一份到本地。留档失败不影响推送，见 LiveReportArchive
        image.ifPresent(base64 -> archiveReport(event, base64, showRevenue));

        String report = image
                .map(base64 -> "{image_base64=" + base64 + "}")
                .orElseGet(() -> {
                    log.warn("{} 的下播报告图片绘制失败, 改发文字版", event.getSource().getUname());
                    return painter.textReport(event.getPlatform(), event.getSource(), options);
                });

        String template = params.getString("message");
        String content = template
                .replace("{uname}", PushHandlerSupport.resolveUname(api, event.getSource()))
                .replace("{url}", "https://live.bilibili.com/" + event.getSource().getRoomId())
                .replace("{report}", report);

        // 与下播通知同理：报告没有订阅名单这回事，订阅串给空串
        PushHandlerSupport.send(sender, target,
                PushHandlerSupport.withAtBlock(AtMode.of(params), target, template, content, ""));
    }

    /**
     * 把这一场的报告图留一份到本地
     * <p>
     * 以<b>开播时刻</b>作键，与场次归档里的 {@code startTime} 同源（都问
     * {@link LiveDataService#getLiveStartTime} 要）。两边不同源的话，场次表里的
     * 那一行会点开另一场的报告，而两张图看起来都像模像样。
     * <p>
     * 没记到开播时刻就不留档：那种情况下这一场<b>本来也不会被归档</b>
     * （见下播事件监听器），控制台上不存在这一行，留下的图永远没人点得到。
     */
    private void archiveReport(BilibiliLiveOffEvent event, String base64, boolean revenueVisible) {
        Long uid = event.getSource().getUid();
        Optional<Long> start = liveDataService.getLiveStartTime(event.getPlatform(), uid);
        if (start.isEmpty()) {
            log.debug("{} 没有记录到开播时间, 本场报告不留档", event.getSource().getUname());
            return;
        }

        try {
            reports.store(event.getPlatform(), uid, start.get(), Base64.getDecoder().decode(base64), revenueVisible);
        } catch (IllegalArgumentException e) {
            log.warn("{} 的报告图不是合法的 Base64, 本场报告不留档: {}", event.getSource().getUname(), e.getMessage());
        }
    }

    @Override
    public Class<? extends StarBotExternalBaseEvent> getEventType() {
        return BilibiliLiveOffEvent.class;
    }

    /**
     * 报告版式选项
     * <p>
     * <b>取自 {@link BilibiliLiveReportOptions#layoutOptions()}，这里不另存一份。</b>
     * 版式项的名字、默认值与取值范围原本在这里抄了一遍，而真正生效的是那个类的字段初值——
     * 两处各写一份，改了一处忘了另一处，界面上勾的与实际生效的就会对不上，
     * 而这种不一致不会有任何报错，只会让人以为「配了没用」。
     * <p>
     * {@link #getDefaultParams()}、配置界面与版式枚举接口自此读的是同一张表。
     */
    private static final List<HandlerOption> OPTIONS = BilibiliLiveReportOptions.layoutOptions();

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
        return BilibiliPlatform.BILIBILI.id();
    }

    @Override
    public List<String> placeholders() {
        return List.of("{uname}", "{report}", "{url}", "{next}", "{at=all}");
    }
}
