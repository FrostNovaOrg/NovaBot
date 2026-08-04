package com.starlwr.bot.bilibili.painter;

import com.starlwr.bot.bilibili.model.BilibiliLiveMetric;
import com.starlwr.bot.bilibili.util.BilibiliApiUtil;
import com.starlwr.bot.bilibili.util.DurationFormatUtil;
import com.starlwr.bot.core.factory.StarBotCommonPainterFactory;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import com.starlwr.bot.core.painter.CommonPainter;
import com.starlwr.bot.core.plugin.StarBotComponent;
import com.starlwr.bot.core.service.LiveDataService;
import com.starlwr.bot.core.util.ImageUtil;
import com.starlwr.bot.core.util.StringUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.awt.Color;
import java.awt.Point;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * 下播报告绘制器
 * <p>
 * 把本场直播累计的统计指标绘制为报告图片。指标由 {@code BilibiliLiveStatsAggregator}
 * 在直播期间累计，为零的条目自动省略，不产生数据的冷清场次也能得到一张干净的报告。
 */
@Slf4j
@StarBotComponent
public class BilibiliLiveReportPainter {
    /**
     * 图片总宽度，与动态图片一致
     */
    private static final int WIDTH = 900;

    /**
     * 初始画布高度，绘制过程中按需自动扩展
     */
    private static final int INITIAL_HEIGHT = 1200;

    /**
     * 画布圆角半径
     */
    private static final int CANVAS_RADIUS = 25;

    /**
     * 内容区左右留白
     */
    private static final int MARGIN = 35;

    /**
     * 头像尺寸
     */
    private static final int AVATAR_SIZE = 100;

    /**
     * 数据行的行高
     */
    private static final int ROW_HEIGHT = 52;

    /**
     * 数据行取值列的横坐标
     */
    private static final int VALUE_X = MARGIN + 240;

    private static final Color COLOR_NAME = new Color(251, 114, 153);

    private static final Color COLOR_TIP = new Color(153, 162, 170);

    private static final Color COLOR_TEXT = new Color(51, 51, 51);

    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.of("Asia/Shanghai"));

    private final StarBotCommonPainterFactory factory;

    private final BilibiliApiUtil api;

    private final LiveDataService liveDataService;

    @Autowired
    public BilibiliLiveReportPainter(StarBotCommonPainterFactory factory, BilibiliApiUtil api, LiveDataService liveDataService) {
        this.factory = factory;
        this.api = api;
        this.liveDataService = liveDataService;
    }

    /**
     * 绘制本场直播报告
     * @param platform 直播平台
     * @param source 主播信息
     * @return 报告图片的 Base64 编码，绘制失败时为空
     */
    public Optional<String> paint(String platform, LiveStreamerInfo source) {
        try {
            CommonPainter painter = factory.create(WIDTH, INITIAL_HEIGHT, true);
            painter.setPos(MARGIN, MARGIN);

            drawHeader(painter, platform, source);
            drawRows(painter, platform, source.getUid());

            painter.movePos(0, 20);
            painter.drawCopyright(MARGIN);
            painter.movePos(0, 10);

            // 该调用同时把画布裁剪至实际内容高度并铺上背景，必须在全部内容绘制完毕后执行
            painter.createSolidRoundedRectangleBackground(Color.WHITE, CANVAS_RADIUS);

            return painter.base64();
        } catch (Exception e) {
            log.error("绘制 {} 的直播报告失败", source.getUname(), e);
            return Optional.empty();
        }
    }

    /**
     * 绘制头部：头像、昵称与直播起止时间
     */
    private void drawHeader(CommonPainter painter, String platform, LiveStreamerInfo source) {
        int textX = MARGIN + AVATAR_SIZE + 25;
        int top = painter.getY();

        Optional.ofNullable(resolveFace(source))
                .flatMap(url -> api.getBilibiliImage(atSize(url)))
                .map(face -> ImageUtil.maskToCircle(ImageUtil.resize(face, AVATAR_SIZE, AVATAR_SIZE)))
                .ifPresent(face -> painter.drawImage(face, new Point(MARGIN, top)));

        painter.drawSection(Optional.ofNullable(source.getUname()).orElse("未知主播"), COLOR_NAME, new Point(textX, top + 8));
        painter.drawTip("直播报告 · " + timeRange(platform, source.getUid()), COLOR_TIP, new Point(textX, top + 58));

        painter.setPos(MARGIN, top + AVATAR_SIZE + 30);
    }

    /**
     * 绘制统计数据行
     */
    private void drawRows(CommonPainter painter, String platform, Long uid) {
        long danmu = count(platform, uid, BilibiliLiveMetric.DANMU_COUNT);
        int danmuUsers = liveDataService.getLiveMetricUserCount(platform, uid, BilibiliLiveMetric.DANMU_USERS);
        double giftValue = liveDataService.getLiveMetric(platform, uid, BilibiliLiveMetric.GIFT_VALUE);
        int giftUsers = liveDataService.getLiveMetricUserCount(platform, uid, BilibiliLiveMetric.GIFT_USERS);
        long freeGift = count(platform, uid, BilibiliLiveMetric.FREE_GIFT_COUNT);
        long box = count(platform, uid, BilibiliLiveMetric.BOX_COUNT);
        double boxProfit = liveDataService.getLiveMetric(platform, uid, BilibiliLiveMetric.BOX_PROFIT);
        long superChat = count(platform, uid, BilibiliLiveMetric.SUPER_CHAT_COUNT);
        double superChatValue = liveDataService.getLiveMetric(platform, uid, BilibiliLiveMetric.SUPER_CHAT_VALUE);
        long captain = count(platform, uid, BilibiliLiveMetric.CAPTAIN_COUNT);
        long commander = count(platform, uid, BilibiliLiveMetric.COMMANDER_COUNT);
        long governor = count(platform, uid, BilibiliLiveMetric.GOVERNOR_COUNT);
        double guardValue = liveDataService.getLiveMetric(platform, uid, BilibiliLiveMetric.GUARD_VALUE);
        long follow = count(platform, uid, BilibiliLiveMetric.FOLLOW_COUNT);
        int enterUsers = liveDataService.getLiveMetricUserCount(platform, uid, BilibiliLiveMetric.ENTER_USERS);
        long likeTotal = count(platform, uid, BilibiliLiveMetric.LIKE_TOTAL);
        int likeUsers = liveDataService.getLiveMetricUserCount(platform, uid, BilibiliLiveMetric.LIKE_USERS);
        long share = count(platform, uid, BilibiliLiveMetric.SHARE_COUNT);

        drawRow(painter, "直播时长", Optional.of(durationText(platform, uid)).filter(StringUtil::isNotBlank).orElse("未知"));
        drawRow(painter, "弹幕", danmu + " 条 · " + danmuUsers + " 人参与");

        if (giftValue > 0 || giftUsers > 0) {
            drawRow(painter, "礼物", "¥" + yuan(giftValue) + " · " + giftUsers + " 人送出");
        }
        if (freeGift > 0) {
            drawRow(painter, "免费礼物", freeGift + " 个");
        }
        if (box > 0) {
            String direction = boxProfit >= 0 ? "盈利" : "亏损";
            drawRow(painter, "盲盒", box + " 个 · " + direction + " ¥" + yuan(Math.abs(boxProfit)));
        }
        if (superChat > 0) {
            drawRow(painter, "醒目留言", superChat + " 条 · ¥" + yuan(superChatValue));
        }
        if (captain > 0 || commander > 0 || governor > 0) {
            StringBuilder guard = new StringBuilder();
            if (governor > 0) {
                guard.append("总督 +").append(governor).append("  ");
            }
            if (commander > 0) {
                guard.append("提督 +").append(commander).append("  ");
            }
            if (captain > 0) {
                guard.append("舰长 +").append(captain).append("  ");
            }
            guard.append("· ¥").append(yuan(guardValue));
            drawRow(painter, "大航海", guard.toString());
        }
        if (follow > 0) {
            drawRow(painter, "新增关注", "+" + follow);
        }
        if (enterUsers > 0) {
            drawRow(painter, "进入直播间", enterUsers + " 人");
        }
        if (likeTotal > 0 || likeUsers > 0) {
            String value = likeTotal > 0 ? likeTotal + (likeUsers > 0 ? " · " + likeUsers + " 人参与" : "") : likeUsers + " 人参与";
            drawRow(painter, "点赞", value);
        }
        if (share > 0) {
            drawRow(painter, "分享", share + " 次");
        }
    }

    /**
     * 绘制一行「标签 + 取值」
     */
    private void drawRow(CommonPainter painter, String label, String value) {
        int y = painter.getY();
        painter.drawText(label, COLOR_TIP, new Point(MARGIN, y));
        painter.drawText(value, COLOR_TEXT, new Point(VALUE_X, y));
        painter.setPos(MARGIN, y + ROW_HEIGHT);
    }

    /**
     * 读取计数类指标并取整
     */
    private long count(String platform, Long uid, String metric) {
        return Math.round(liveDataService.getLiveMetric(platform, uid, metric));
    }

    /**
     * 本场直播的时长描述
     */
    private String durationText(String platform, Long uid) {
        Optional<Long> start = liveDataService.getLiveStartTime(platform, uid);
        Optional<Long> end = liveDataService.getLiveEndTime(platform, uid);
        if (start.isEmpty() || end.isEmpty()) {
            return "";
        }
        return DurationFormatUtil.format((end.get() - start.get()) / 1000);
    }

    /**
     * 本场直播的起止时间描述
     */
    private String timeRange(String platform, Long uid) {
        Optional<Long> start = liveDataService.getLiveStartTime(platform, uid);
        Optional<Long> end = liveDataService.getLiveEndTime(platform, uid);
        if (start.isEmpty() || end.isEmpty()) {
            return TIME_FORMATTER.format(Instant.now());
        }
        return TIME_FORMATTER.format(Instant.ofEpochMilli(start.get()))
                + " ~ " + TIME_FORMATTER.format(Instant.ofEpochMilli(end.get()));
    }

    /**
     * 金额格式化：保留一位小数，整数金额省略小数位
     */
    private String yuan(double value) {
        long rounded = Math.round(value * 10);
        if (rounded % 10 == 0) {
            return String.valueOf(rounded / 10);
        }
        return String.valueOf(rounded / 10.0);
    }

    /**
     * 主播头像地址：事件中缺失时通过接口取
     */
    private String resolveFace(LiveStreamerInfo source) {
        if (StringUtil.isNotBlank(source.getFace())) {
            return source.getFace();
        }
        try {
            return api.getUpInfoByUid(source.getUid()).getFace();
        } catch (Exception e) {
            log.debug("获取 uid {} 的头像失败: {}", source.getUid(), e.getMessage());
            return null;
        }
    }

    /**
     * 为图片地址附加缩放参数，避免下载原图
     */
    private String atSize(String url) {
        if (StringUtil.isBlank(url) || url.contains("@")) {
            return url;
        }
        return url + "@" + AVATAR_SIZE + "w.webp";
    }
}
