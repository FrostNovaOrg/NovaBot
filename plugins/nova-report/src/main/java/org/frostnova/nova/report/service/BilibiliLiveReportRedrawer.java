package org.frostnova.nova.report.service;

import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.bilibili.BilibiliPlatform;
import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.model.BilibiliLiveReportOptions;
import org.frostnova.nova.report.painter.BilibiliLiveReportReplayPainter;
import org.frostnova.nova.report.painter.ReportImageDiskCache;
import org.frostnova.nova.report.painter.ReportPalettes;
import org.frostnova.nova.report.painter.LiveReportRenderCache;
import org.frostnova.nova.report.handler.BilibiliLiveReportPushHandler;
import org.frostnova.nova.core.model.PushTarget;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import org.frostnova.nova.core.model.MessagePlaceholders;
import org.frostnova.nova.core.service.PushTemplateDefaults;
import org.frostnova.nova.core.service.RevenueVisibilityService;
import com.alibaba.fastjson2.JSONObject;
import java.util.Base64;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.analytics.LiveDetail;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.frostnova.nova.core.service.LiveReportRedrawer;
import org.frostnova.nova.core.service.LiveRoomInfoHistory;
import org.frostnova.nova.report.factory.NovaCommonPainterFactory;
import org.frostnova.nova.report.util.FontUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.Optional;

/**
 * 从明细重画哔哩哔哩的下播报告
 * <p>
 * 控制台点开一场历史直播的报告时，若图片缓存已过期，走的就是这里。
 * <p>
 * <b>主播页回看按默认版式、金额可见重画</b>：留档是给这台机器的主人看的，
 * 他本就看得到金额，给他一份抹掉了金额的报告等于让他看一份比自己权限更少的东西——
 * 与报告图缓存「金额可见的那份优先」是同一条立场。
 * 当时推给大群的那一份是什么版式，明细里没有记，也不该记：
 * 同一场推给三个通道就有三种版式，而这里只需要答「那一场发生了什么」。
 * <p>
 * ⚠️ 因此重画出来的<b>版式</b>可能与当时那张不同（例如当时把盲盒盈亏榜打开了），
 * 差的是显示哪几块，不是数据本身——数据面全部来自明细。
 * 通道页主动发送上一份报告时，按该通道当前的版式、金额可见与配色生成，优先复用外观相同的成品图。
 */
@Slf4j
@NovaComponent
public class BilibiliLiveReportRedrawer implements LiveReportRedrawer {
    private final NovaCommonPainterFactory factory;

    private final BilibiliApiUtil api;

    private final FontUtil fontUtil;

    private final NovaBilibiliProperties properties;

    private final LiveRoomInfoHistory roomInfoHistory;

    private final ReportImageDiskCache images;

    @Autowired(required = false)
    private AbstractDataSource dataSource;

    @Autowired(required = false)
    private ReportPalettes palettes = ReportPalettes.factory();

    @Autowired(required = false)
    private LiveReportRenderCache rendered = new LiveReportRenderCache();

    @Autowired
    private RevenueVisibilityService revenue;

    @Autowired
    private PushTemplateDefaults templates;

    @Autowired
    private BilibiliLiveReportPushHandler handler;

    @Autowired
    public BilibiliLiveReportRedrawer(NovaCommonPainterFactory factory, BilibiliApiUtil api, FontUtil fontUtil,
                                      NovaBilibiliProperties properties, LiveRoomInfoHistory roomInfoHistory,
                                      ReportImageDiskCache images) {
        this.factory = factory;
        this.api = api;
        this.fontUtil = fontUtil;
        this.properties = properties;
        this.roomInfoHistory = roomInfoHistory;
        this.images = images;
    }

    @Override
    public String platform() {
        return BilibiliPlatform.BILIBILI.id();
    }

    /**
     * 重画一场
     * <p>
     * <b>每场一个画手</b>：画手认的是「一份直播数据服务」，而每一场都是各自独立的一份。
     * 共用一个画手就要在它身上换数据，那等于给一个本来无状态的东西加上状态，
     * 而两场同时被点开时，两边会互相看到对方的数据。
     */
    @Override
    public Optional<byte[]> redraw(LiveDetail detail) {
        if (detail == null || detail.uid() == null) {
            return Optional.empty();
        }

        try {
            BilibiliLiveReportReplayPainter painter = replay(detail);
            // 全默认版式，数据卡片各张的开关不看，顺序也一样：重画的这一场不属于哪个推送目标，
            // 没有哪一份版式算它的；按某个群挑过的卡来画，看的人会以为那几项这场没有数据
            return painter.render(new BilibiliLiveReportOptions(), palettes.byDefault().palette());
        } catch (Exception e) {
            log.error("重新绘制 {} 的历史报告失败", detail.uname(), e);
            return Optional.empty();
        }
    }

    protected BilibiliLiveReportReplayPainter replay(LiveDetail detail) {
        var painter = new BilibiliLiveReportReplayPainter(factory, api, fontUtil, properties, roomInfoHistory, detail, images);
        painter.setDataSource(dataSource);
        return painter;
    }

    @Override
    public Optional<String> channelContent(LiveDetail detail, PushTarget channel) {
        JSONObject params = templates.paramsOf(handler);
        channel.getMessages().stream()
                .filter(message -> handler.getClass().getName().equals(message.handlerClassName())
                        || handler.legacyClassNames().contains(message.handlerClassName()))
                .findFirst().ifPresent(message -> {
                    if (message.getParamsJsonObject() != null) params.putAll(message.getParamsJsonObject());
                });
        var options = BilibiliLiveReportOptions.of(params, revenue.isVisible(channel));
        var palette = palettes.forChannel(channel).palette();
        var cached = rendered.get(detail.platform(), detail.uid(), detail.startTime(), options, palette);
        if (cached.isPresent()) return cached.map(image -> "{image_base64=" + image + "}");
        var painter = replay(detail);
        var image = painter.render(options, palette);
        if (image.isPresent()) {
            String base64 = Base64.getEncoder().encodeToString(image.get());
            rendered.put(detail.platform(), detail.uid(), detail.startTime(), options, palette, base64);
            return Optional.of("{image_base64=" + base64 + "}");
        }
        var streamer = new LiveStreamerInfo(detail.uid(), detail.uname(), detail.roomId());
        return Optional.of(MessagePlaceholders.escapeExternalText(painter.textReport(detail.platform(), streamer, options)));
    }
}
