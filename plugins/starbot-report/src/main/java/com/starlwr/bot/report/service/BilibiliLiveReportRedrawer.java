package com.starlwr.bot.report.service;

import com.starlwr.bot.bilibili.BilibiliPlatform;
import com.starlwr.bot.bilibili.config.NovaBilibiliProperties;
import com.starlwr.bot.bilibili.model.BilibiliLiveReportOptions;
import com.starlwr.bot.report.painter.BilibiliLiveReportReplayPainter;
import com.starlwr.bot.bilibili.util.BilibiliApiUtil;
import com.starlwr.bot.core.analytics.LiveDetail;
import com.starlwr.bot.core.plugin.NovaComponent;
import com.starlwr.bot.core.service.LiveReportRedrawer;
import com.starlwr.bot.core.service.LiveRoomInfoHistory;
import com.starlwr.bot.report.factory.NovaCommonPainterFactory;
import com.starlwr.bot.report.util.FontUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Optional;

/**
 * 从明细重画哔哩哔哩的下播报告
 * <p>
 * 控制台点开一场历史直播的报告时，若图片缓存已过期，走的就是这里。
 * <p>
 * <b>一律按默认版式、金额可见重画</b>：留档是给这台机器的主人看的，
 * 他本就看得到金额，给他一份抹掉了金额的报告等于让他看一份比自己权限更少的东西——
 * 与报告图缓存「金额可见的那份优先」是同一条立场。
 * 当时推给大群的那一份是什么版式，明细里没有记，也不该记：
 * 同一场推给三个通道就有三种版式，而这里只需要答「那一场发生了什么」。
 * <p>
 * ⚠️ 因此重画出来的<b>版式</b>可能与当时那张不同（例如当时把盲盒榜打开了），
 * 差的是显示哪几块，不是数据本身——数据面全部来自明细。
 */
@Slf4j
@NovaComponent
public class BilibiliLiveReportRedrawer implements LiveReportRedrawer {
    private final NovaCommonPainterFactory factory;

    private final BilibiliApiUtil api;

    private final FontUtil fontUtil;

    private final NovaBilibiliProperties properties;

    private final LiveRoomInfoHistory roomInfoHistory;

    @Autowired
    public BilibiliLiveReportRedrawer(NovaCommonPainterFactory factory, BilibiliApiUtil api, FontUtil fontUtil,
                                      NovaBilibiliProperties properties, LiveRoomInfoHistory roomInfoHistory) {
        this.factory = factory;
        this.api = api;
        this.fontUtil = fontUtil;
        this.properties = properties;
        this.roomInfoHistory = roomInfoHistory;
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
            BilibiliLiveReportReplayPainter painter = new BilibiliLiveReportReplayPainter(
                    factory, api, fontUtil, properties, roomInfoHistory, detail);
            return painter.render(new BilibiliLiveReportOptions());
        } catch (Exception e) {
            log.error("重新绘制 {} 的历史报告失败", detail.uname(), e);
            return Optional.empty();
        }
    }
}
