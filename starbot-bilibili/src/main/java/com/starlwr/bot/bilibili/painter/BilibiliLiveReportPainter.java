package com.starlwr.bot.bilibili.painter;

import com.kennycason.kumo.CollisionMode;
import com.kennycason.kumo.WordCloud;
import com.kennycason.kumo.WordFrequency;
import com.kennycason.kumo.bg.RectangleBackground;
import com.kennycason.kumo.font.KumoFont;
import com.kennycason.kumo.font.scale.SqrtFontScalar;
import com.kennycason.kumo.image.AngleGenerator;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.kennycason.kumo.palette.ColorPalette;
import javax.imageio.ImageIO;
import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.bilibili.model.BilibiliLiveMetric;
import com.starlwr.bot.bilibili.model.BilibiliLiveReportOptions;
import com.starlwr.bot.bilibili.model.Room;
import com.starlwr.bot.bilibili.util.BilibiliApiUtil;
import com.starlwr.bot.bilibili.util.DurationFormatUtil;
import com.starlwr.bot.core.factory.StarBotCommonPainterFactory;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import com.starlwr.bot.core.model.TextWithStyle;
import com.starlwr.bot.core.model.UserScore;
import com.starlwr.bot.core.painter.CommonPainter;
import com.starlwr.bot.core.plugin.StarBotComponent;
import com.starlwr.bot.core.service.LiveDataService;
import com.starlwr.bot.core.util.FontUtil;
import com.starlwr.bot.core.util.ImageUtil;
import com.starlwr.bot.core.util.StringUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.DoubleFunction;

/**
 * 下播报告绘制器
 * <p>
 * 把本场直播累计的统计指标绘制为报告图片：直播间封面横幅、头像、时长与收益概览、
 * 数据卡片栅格与弹幕词云。为零的条目自动省略，封面或词云不可得时对应区块整体跳过，
 * 冷清场次也能得到一张干净的报告。
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
    private static final int INITIAL_HEIGHT = 1600;

    /**
     * 画布圆角半径
     */
    private static final int CANVAS_RADIUS = 25;

    /**
     * 内容区左右留白
     */
    private static final int MARGIN = 35;

    /**
     * 内容区宽度
     */
    private static final int CONTENT_WIDTH = WIDTH - MARGIN * 2;

    /**
     * 封面横幅高度
     */
    private static final int COVER_HEIGHT = 260;

    /**
     * 头像尺寸与白色描边宽度
     */
    private static final int AVATAR_SIZE = 100;

    private static final int AVATAR_RING = 5;

    /**
     * 数据卡片：每行三张
     */
    private static final int CARD_COLUMNS = 3;

    private static final int CARD_GAP = 18;

    private static final int CARD_WIDTH = (CONTENT_WIDTH - CARD_GAP * (CARD_COLUMNS - 1)) / CARD_COLUMNS;

    private static final int CARD_HEIGHT = 112;

    private static final int CARD_RADIUS = 16;

    /**
     * 排行榜每行的行高
     */
    private static final int RANKING_ROW_HEIGHT = 44;

    /**
     * 排行榜比例条的高度
     */
    private static final int RANKING_BAR_HEIGHT = 14;

    /**
     * 排行榜头像的直径。头像地址随计分一并记录，绘制时只需下载图片，不打接口
     */
    private static final int RANKING_AVATAR_SIZE = 32;

    /**
     * 大航海名单最多展示的人数
     */
    private static final int GUARD_LIST_LIMIT = 10;

    /**
     * 昵称最多展示的字符数，超出截断
     */
    private static final int MAX_NAME_LENGTH = 12;

    /**
     * 词云绘制尺寸
     */
    private static final int CLOUD_HEIGHT = 380;

    /**
     * 词云最多收录的词数
     */
    private static final int CLOUD_MAX_WORDS = 72;

    /**
     * 词云至少需要的独立词数，低于此数画出来只有零星几个词，不如不画
     */
    private static final int CLOUD_MIN_WORDS = 8;

    private static final Color COLOR_NAME = new Color(251, 114, 153);

    private static final Color COLOR_TIP = new Color(153, 162, 170);

    private static final Color COLOR_TEXT = new Color(51, 51, 51);

    private static final Color COLOR_CARD = new Color(246, 247, 249);

    /**
     * 互动曲线的高度与像素列宽
     */
    private static final int CURVE_HEIGHT = 90;

    private static final int CURVE_COLUMN_WIDTH = 2;

    /**
     * 底部标识的绘制高度，与动态图片保持一致
     */
    private static final int LOGO_HEIGHT = 45;

    /**
     * 各条曲线的配色，礼物沿用主题粉（{@link #COLOR_NAME}）
     */
    private static final Color COLOR_CURVE_DANMU = new Color(0, 174, 236);

    private static final Color COLOR_CURVE_SUPER_CHAT = new Color(255, 168, 61);

    private static final Color COLOR_CURVE_BOX = new Color(110, 199, 122);

    private static final Color COLOR_CURVE_GUARD = new Color(151, 129, 224);

    /**
     * 词云配色：哔哩哔哩粉蓝系
     */
    private static final List<Color> CLOUD_PALETTE = List.of(
            new Color(251, 114, 153),
            new Color(0, 174, 236),
            new Color(255, 168, 61),
            new Color(110, 199, 122),
            new Color(120, 120, 130)
    );

    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.of("Asia/Shanghai"));

    private final StarBotCommonPainterFactory factory;

    private final BilibiliApiUtil api;

    private final LiveDataService liveDataService;

    private final FontUtil fontUtil;

    private final StarBotBilibiliProperties properties;

    /**
     * 头像下载失败的哨兵值
     * <p>
     * Caffeine 不缓存 null，直接返回 null 会让坏地址每次绘制都重试一遍。
     * 放一张 1×1 的空图占位，靠引用相等把它与真头像区分开。
     */
    private static final BufferedImage FAILED_AVATAR = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);

    /**
     * 头像缓存，按头像地址计
     * <p>
     * 同一个人出现在多张榜、多份报告里都只下载一次。容量与时长都取得比较克制：
     * 头像是小图，但常驻内存的图片对象在小内存机器上仍值得设个上界。
     */
    private final Cache<String, BufferedImage> avatarCache = Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterWrite(Duration.ofHours(6))
            .build();

    /**
     * 底部标识只读一次盘，读过就不再重试——无论成败
     */
    private volatile boolean logoLoaded;

    private BufferedImage logo;

    @Autowired
    public BilibiliLiveReportPainter(StarBotCommonPainterFactory factory, BilibiliApiUtil api,
                                     LiveDataService liveDataService, FontUtil fontUtil,
                                     StarBotBilibiliProperties properties) {
        this.factory = factory;
        this.api = api;
        this.liveDataService = liveDataService;
        this.fontUtil = fontUtil;
        this.properties = properties;
    }

    /**
     * 绘制本场直播报告
     * @param platform 直播平台
     * @param source 主播信息
     * @return 报告图片的 Base64 编码，绘制失败时为空
     */
    public Optional<String> paint(String platform, LiveStreamerInfo source) {
        return paint(platform, source, new BilibiliLiveReportOptions());
    }

    /**
     * 按指定版式绘制本场直播报告
     * @param platform 直播平台
     * @param source 主播信息
     * @param options 版式选项，决定展示哪些区块
     * @return 报告图片的 Base64 编码，绘制失败时为空
     */
    public Optional<String> paint(String platform, LiveStreamerInfo source, BilibiliLiveReportOptions options) {
        try {
            CommonPainter painter = factory.create(WIDTH, INITIAL_HEIGHT, true);
            painter.setPos(MARGIN, MARGIN);

            drawHeader(painter, platform, source, options);
            drawOverview(painter, platform, source.getUid());
            if (options.isCards()) {
                drawCards(painter, platform, source.getUid());
            }
            if (options.isFansChange()) {
                drawFansChange(painter, platform, source);
            }
            if (options.isInteractionCurve()) {
                drawCurves(painter, platform, source.getUid());
            }
            drawRankings(painter, platform, source.getUid(), options);
            if (options.isDanmuCloud()) {
                drawWordCloud(painter, platform, source.getUid());
            }

            painter.movePos(0, 20);
            drawLogo(painter);
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
     * 绘制头部：封面横幅、压在横幅下沿的圆形头像、昵称与直播起止时间。
     * 封面不可得时退化为「头像 + 昵称」的简单头部
     */
    private void drawHeader(CommonPainter painter, String platform, LiveStreamerInfo source, BilibiliLiveReportOptions options) {
        int top = painter.getY();
        BufferedImage cover = options.isCover() ? loadCover(source) : null;

        BufferedImage face = Optional.ofNullable(resolveFace(source))
                .flatMap(url -> api.getBilibiliImage(atSize(url)))
                .map(image -> ImageUtil.maskToCircle(ImageUtil.resize(image, AVATAR_SIZE, AVATAR_SIZE)))
                .orElse(null);

        if (cover != null) {
            painter.drawImage(cover, new Point(MARGIN, top));

            // 头像叠在封面下沿，加白色描边与封面区隔
            int avatarX = MARGIN + 28;
            int avatarY = top + COVER_HEIGHT - AVATAR_SIZE / 2;
            if (face != null) {
                drawRingedAvatar(painter, face, avatarX, avatarY);
            }

            int textX = avatarX + AVATAR_SIZE + 22;
            painter.drawSection(Optional.ofNullable(source.getUname()).orElse("未知主播"), COLOR_NAME, new Point(textX, top + COVER_HEIGHT + 4));
            painter.drawTip("直播报告 · " + timeRange(platform, source.getUid()), COLOR_TIP, new Point(textX, top + COVER_HEIGHT + 52));

            painter.setPos(MARGIN, top + COVER_HEIGHT + AVATAR_SIZE + 16);
            return;
        }

        int textX = MARGIN + AVATAR_SIZE + 25;
        if (face != null) {
            painter.drawImage(face, new Point(MARGIN, top));
        }
        painter.drawSection(Optional.ofNullable(source.getUname()).orElse("未知主播"), COLOR_NAME, new Point(textX, top + 8));
        painter.drawTip("直播报告 · " + timeRange(platform, source.getUid()), COLOR_TIP, new Point(textX, top + 58));
        painter.setPos(MARGIN, top + AVATAR_SIZE + 30);
    }

    /**
     * 绘制概览行：直播时长与本场收益
     */
    private void drawOverview(CommonPainter painter, String platform, Long uid) {
        String duration = Optional.of(durationText(platform, uid)).filter(StringUtil::isNotBlank).orElse("未知");

        double revenue = liveDataService.getLiveMetric(platform, uid, BilibiliLiveMetric.GIFT_VALUE)
                + liveDataService.getLiveMetric(platform, uid, BilibiliLiveMetric.SUPER_CHAT_VALUE)
                + liveDataService.getLiveMetric(platform, uid, BilibiliLiveMetric.GUARD_VALUE);

        List<TextWithStyle> line = new ArrayList<>();
        line.add(new TextWithStyle("直播时长 ", CommonPainter.TEXT_FONT_SIZE, COLOR_TIP, Font.PLAIN));
        line.add(new TextWithStyle(duration, CommonPainter.TEXT_FONT_SIZE, COLOR_TEXT, Font.BOLD));
        if (revenue > 0) {
            line.add(new TextWithStyle("    本场收益 ", CommonPainter.TEXT_FONT_SIZE, COLOR_TIP, Font.PLAIN));
            line.add(new TextWithStyle("¥" + yuan(revenue), CommonPainter.TEXT_FONT_SIZE, COLOR_NAME, Font.BOLD));
        }

        painter.drawTextWithStyle(line);
        painter.movePos(0, 18);
    }

    /**
     * 绘制数据卡片栅格，为零的卡片自动省略
     */
    private void drawCards(CommonPainter painter, String platform, Long uid) {
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
        long share = count(platform, uid, BilibiliLiveMetric.SHARE_COUNT);

        List<Card> cards = new ArrayList<>();
        cards.add(new Card(String.valueOf(danmu), "弹幕 · " + danmuUsers + " 人参与"));
        if (giftValue > 0 || giftUsers > 0) {
            cards.add(new Card("¥" + yuan(giftValue), "礼物 · " + giftUsers + " 人送出"));
        }
        if (likeTotal > 0) {
            cards.add(new Card(String.valueOf(likeTotal), "点赞"));
        }
        if (enterUsers > 0) {
            cards.add(new Card(enterUsers + " 人", "进入直播间"));
        }
        if (follow > 0) {
            cards.add(new Card("+" + follow, "新增关注"));
        }
        if (superChat > 0) {
            cards.add(new Card(superChat + " 条", "醒目留言 · ¥" + yuan(superChatValue)));
        }
        if (captain > 0 || commander > 0 || governor > 0) {
            cards.add(new Card("+" + (captain + commander + governor), "大航海 · ¥" + yuan(guardValue)));
        }
        if (box > 0) {
            String direction = boxProfit >= 0 ? "盈利" : "亏损";
            cards.add(new Card(box + " 个", "盲盒 · " + direction + " ¥" + yuan(Math.abs(boxProfit))));
        }
        if (freeGift > 0) {
            cards.add(new Card(freeGift + " 个", "免费礼物"));
        }
        if (share > 0) {
            cards.add(new Card(share + " 次", "分享"));
        }

        int startY = painter.getY();
        for (int i = 0; i < cards.size(); i++) {
            int row = i / CARD_COLUMNS;
            int column = i % CARD_COLUMNS;
            int x = MARGIN + column * (CARD_WIDTH + CARD_GAP);
            int y = startY + row * (CARD_HEIGHT + CARD_GAP);
            drawCard(painter, cards.get(i), x, y);
        }

        int rows = (cards.size() + CARD_COLUMNS - 1) / CARD_COLUMNS;
        painter.setPos(MARGIN, startY + rows * (CARD_HEIGHT + CARD_GAP) + 8);
    }

    /**
     * 绘制单张数据卡片
     */
    private void drawCard(CommonPainter painter, Card card, int x, int y) {
        painter.drawRoundedRectangle(x, y, CARD_WIDTH, CARD_HEIGHT, CARD_RADIUS, COLOR_CARD);
        painter.drawTextWithStyle(
                List.of(new TextWithStyle(card.value, 34, COLOR_TEXT, Font.BOLD)),
                new Point(x + 20, y + 16));
        painter.drawTextWithStyle(
                List.of(new TextWithStyle(card.label, 22, COLOR_TIP, Font.PLAIN)),
                new Point(x + 20, y + 68));
    }

    /**
     * 绘制粉丝、粉丝团与大航海的本场变化
     * <p>
     * 这三项都不在弹幕流里，只能问接口。开播时的快照由
     * {@code BilibiliRoomStatsSnapshotter} 记下，这里取一次实时值相减即得涨幅——
     * 于是直播中随时拉的实时报告与下播报告走的是同一段逻辑。
     * <p>
     * 三项各自独立降级：接口挂了或没有开播快照，就只跳过那一项。
     */
    private void drawFansChange(CommonPainter painter, String platform, LiveStreamerInfo source) {
        List<Card> cards = new ArrayList<>();

        api.getFansCount(source.getUid()).ifPresent(fans ->
                cards.add(changeCard(platform, source.getUid(), fans, BilibiliLiveMetric.FANS_AT_START, "粉丝")));
        api.getFansMedalCount(source.getUid()).ifPresent(medal ->
                cards.add(changeCard(platform, source.getUid(), medal, BilibiliLiveMetric.FANS_MEDAL_AT_START, "粉丝团")));
        if (source.getRoomId() != null) {
            api.getGuardCount(source.getRoomId(), source.getUid()).ifPresent(guard ->
                    cards.add(changeCard(platform, source.getUid(), guard, BilibiliLiveMetric.GUARD_AT_START, "大航海")));
        }

        if (cards.isEmpty()) {
            return;
        }

        painter.movePos(0, 10);
        painter.drawTextWithStyle(List.of(new TextWithStyle("本场变化", CommonPainter.TEXT_FONT_SIZE, COLOR_TIP, Font.PLAIN)));
        painter.movePos(0, 6);

        int startY = painter.getY();
        for (int i = 0; i < cards.size(); i++) {
            drawCard(painter, cards.get(i), MARGIN + i * (CARD_WIDTH + CARD_GAP), startY);
        }
        painter.setPos(MARGIN, startY + CARD_HEIGHT + CARD_GAP);
    }

    /**
     * 组装一张变化卡片：主体是当前值，副标题带上本场涨幅
     * <p>
     * 没有开播快照时（如程序在直播中途才启动）只显示当前值，不显示涨幅——
     * 拿不到基准就别编一个出来。
     */
    private Card changeCard(String platform, Long uid, long current, String startMetric, String label) {
        double start = liveDataService.getLiveMetric(platform, uid, startMetric);
        if (start <= 0) {
            return new Card(String.valueOf(current), label);
        }

        long delta = current - Math.round(start);
        String sign = delta >= 0 ? "+" : "";
        return new Card(String.valueOf(current), label + " · 本场 " + sign + delta);
    }

    /**
     * 绘制互动曲线
     * <p>
     * 每项指标一条独立的面积图，各自按自身峰值缩放。<b>刻意不把它们叠在同一张图上</b>：
     * 弹幕以「条」计、礼物以「元」计，量级动辄差两个数量级，共用纵轴的结果是
     * 除了最大的那条以外全部压成一条直线。
     */
    private void drawCurves(CommonPainter painter, String platform, Long uid) {
        Optional<Long> start = liveDataService.getLiveStartTime(platform, uid);
        Optional<Long> end = effectiveEndTime(platform, uid, start);
        if (start.isEmpty() || end.isEmpty() || end.get() <= start.get()) {
            return;
        }

        List<Curve> curves = new ArrayList<>();
        curves.add(new Curve("弹幕", BilibiliLiveMetric.DANMU_COUNT, COLOR_CURVE_DANMU,
                peak -> Math.round(peak) + " 条/分"));
        curves.add(new Curve("礼物", BilibiliLiveMetric.GIFT_VALUE, COLOR_NAME,
                peak -> "¥" + yuan(peak) + "/分"));
        curves.add(new Curve("醒目留言", BilibiliLiveMetric.SUPER_CHAT_VALUE, COLOR_CURVE_SUPER_CHAT,
                peak -> "¥" + yuan(peak) + "/分"));
        curves.add(new Curve("盲盒", BilibiliLiveMetric.BOX_COUNT, COLOR_CURVE_BOX,
                peak -> Math.round(peak) + " 个/分"));
        curves.add(new Curve("大航海", BilibiliLiveMetric.GUARD_VALUE, COLOR_CURVE_GUARD,
                peak -> "¥" + yuan(peak) + "/分"));

        boolean first = true;
        for (Curve curve : curves) {
            Map<Long, Double> series = liveDataService.getLiveSeries(platform, uid, curve.metric);
            if (series.isEmpty()) {
                continue;
            }

            if (first) {
                painter.movePos(0, 10);
                painter.drawTextWithStyle(List.of(new TextWithStyle("互动曲线", CommonPainter.TEXT_FONT_SIZE, COLOR_TIP, Font.PLAIN)));
                painter.movePos(0, 6);
                first = false;
            }
            drawCurve(painter, curve, series, start.get(), end.get());
        }

        if (!first) {
            painter.movePos(0, 8);
        }
    }

    /**
     * 绘制一条面积图：标题、峰值、面积本体与基线
     */
    private void drawCurve(CommonPainter painter, Curve curve, Map<Long, Double> series, long start, long end) {
        int top = painter.getY();

        int columns = Math.max(1, CONTENT_WIDTH / CURVE_COLUMN_WIDTH);
        double[] values = resample(series, start, end, columns);

        double peak = 0;
        for (double value : values) {
            peak = Math.max(peak, Math.abs(value));
        }
        if (peak == 0) {
            return;
        }

        painter.drawTextWithStyle(List.of(
                new TextWithStyle(curve.title, 24, COLOR_TEXT, Font.PLAIN),
                new TextWithStyle("　峰值 " + curve.peakText.apply(peak), 22, COLOR_TIP, Font.PLAIN)),
                new Point(MARGIN, top));

        int chartTop = top + 34;
        int baseline = chartTop + CURVE_HEIGHT;

        // 面积多边形：左下角起，沿曲线走一遍，回到右下角闭合
        List<Point> area = new ArrayList<>(columns + 2);
        area.add(new Point(MARGIN, baseline));
        for (int i = 0; i < columns; i++) {
            int height = (int) Math.round(CURVE_HEIGHT * Math.abs(values[i]) / peak);
            area.add(new Point(MARGIN + i * CURVE_COLUMN_WIDTH, baseline - height));
        }
        area.add(new Point(MARGIN + (columns - 1) * CURVE_COLUMN_WIDTH, baseline));
        painter.drawPolygon(area, curve.color);

        // 基线压在面积下沿，给曲线一个明确的落脚点
        painter.drawRectangle(MARGIN, baseline, CONTENT_WIDTH, 2, COLOR_CARD);
        painter.setPos(MARGIN, baseline + 16);
    }

    /**
     * 把时间序列重采样到固定数量的像素列上
     * <p>
     * <b>时间格数与像素列数几乎不会相等，两个方向都要处理</b>：
     * 三小时的直播只有 180 个时间格却有四百多列，若只把有数据的格落到对应列、
     * 其余留零，画出来会是一排竖齿而不是一条曲线（这个坑真踩过）；
     * 十二小时的直播则相反，多个格挤进同一列。
     * <p>
     * 因此先补齐成逐格的稠密数组，再按列取所辖各格的**最大值**——
     * 取最大而非平均，是为了让短促的高峰不被摊平，也与标题上的「峰值 X/分」自洽。
     */
    private double[] resample(Map<Long, Double> series, long start, long end, int columns) {
        int buckets = (int) Math.max(1, (end - start) / LiveDataService.SERIES_BUCKET_MILLIS + 1);
        double[] dense = new double[buckets];
        for (Map.Entry<Long, Double> entry : series.entrySet()) {
            // 落在直播区间之外的格直接丢弃：时钟回拨或上一场残留都可能造成
            long offset = entry.getKey() - start;
            if (offset < 0) {
                continue;
            }
            int index = (int) (offset / LiveDataService.SERIES_BUCKET_MILLIS);
            if (index < buckets) {
                dense[index] += entry.getValue();
            }
        }

        double[] values = new double[columns];
        for (int i = 0; i < columns; i++) {
            int from = (int) ((long) i * buckets / columns);
            int to = (int) Math.max(from + 1L, (long) (i + 1) * buckets / columns);
            for (int j = from; j < Math.min(to, buckets); j++) {
                values[i] = Math.max(values[i], Math.abs(dense[j]));
            }
        }
        return values;
    }

    /**
     * 绘制各类排行榜与大航海名单，无数据的榜自动跳过
     */
    private void drawRankings(CommonPainter painter, String platform, Long uid, BilibiliLiveReportOptions options) {
        drawRanking(painter, platform, uid, "弹幕排行", BilibiliLiveMetric.DANMU_USERS,
                options.getDanmuRanking(), score -> Math.round(score) + " 条");
        drawRanking(painter, platform, uid, "礼物排行", BilibiliLiveMetric.GIFT_USERS,
                options.getGiftRanking(), score -> "¥" + yuan(score));
        drawRanking(painter, platform, uid, "醒目留言排行", BilibiliLiveMetric.SUPER_CHAT_USERS,
                options.getSuperChatRanking(), score -> "¥" + yuan(score));
        drawRanking(painter, platform, uid, "盲盒排行", BilibiliLiveMetric.BOX_USERS,
                options.getBoxRanking(), score -> Math.round(score) + " 个");
        // 盲盒盈亏可正可负，正数补个加号，让盈亏方向一眼可辨
        drawRanking(painter, platform, uid, "盲盒盈亏排行", BilibiliLiveMetric.BOX_PROFIT_USERS,
                options.getBoxProfitRanking(), score -> (score >= 0 ? "+¥" : "-¥") + yuan(Math.abs(score)));

        if (options.isGuardList()) {
            drawRanking(painter, platform, uid, "本场开通大航海", BilibiliLiveMetric.GUARD_USERS,
                    GUARD_LIST_LIMIT, score -> Math.round(score) + " 次");
        }
    }

    /**
     * 绘制一张排行榜
     * @param title 榜单标题
     * @param metric 用户计分表指标名
     * @param limit 展示前多少名，0 为不展示
     * @param scoreText 得分的展示文案
     */
    private void drawRanking(CommonPainter painter, String platform, Long uid, String title,
                             String metric, int limit, DoubleFunction<String> scoreText) {
        if (limit <= 0) {
            return;
        }

        List<UserScore> ranking = liveDataService.getLiveUserRanking(platform, uid, metric, limit);
        if (ranking.isEmpty()) {
            return;
        }

        painter.movePos(0, 10);
        painter.drawTextWithStyle(List.of(new TextWithStyle(title, CommonPainter.TEXT_FONT_SIZE, COLOR_TIP, Font.PLAIN)));
        painter.movePos(0, 6);

        // 条形长度按榜首归一化：榜首满格，其余按比例，一眼能看出差距
        double top = ranking.get(0).score();
        for (int i = 0; i < ranking.size(); i++) {
            drawRankingRow(painter, i + 1, ranking.get(i), top, scoreText);
        }
        painter.movePos(0, 8);
    }

    /**
     * 绘制排行榜的一行：名次、昵称、比例条与得分
     */
    private void drawRankingRow(CommonPainter painter, int rank, UserScore user, double topScore, DoubleFunction<String> scoreText) {
        int y = painter.getY();

        painter.drawTextWithStyle(List.of(new TextWithStyle(String.valueOf(rank), 24, rankColor(rank), Font.BOLD)),
                new Point(MARGIN + 4, y + 6));

        // 头像取不到就空着位置：让各行的昵称仍然左端对齐，比逐行错开好看
        int avatarX = MARGIN + 40;
        BufferedImage avatar = avatar(user.userFace());
        if (avatar != null) {
            painter.drawImage(avatar, new Point(avatarX, y + (RANKING_ROW_HEIGHT - RANKING_AVATAR_SIZE) / 2));
        }

        int nameX = avatarX + RANKING_AVATAR_SIZE + 10;
        painter.drawTextWithStyle(List.of(new TextWithStyle(truncate(user.displayName()), 24, COLOR_TEXT, Font.PLAIN)),
                new Point(nameX, y + 6));

        // 比例条画在昵称右侧的固定区域，与得分文字对齐
        int barX = MARGIN + 300;
        int barWidth = CONTENT_WIDTH - 300 - 150;
        painter.drawRoundedRectangle(barX, y + 12, barWidth, RANKING_BAR_HEIGHT, RANKING_BAR_HEIGHT / 2, COLOR_CARD);

        // 盈亏榜可能出现负分或榜首为 0 的情况，按绝对值取比例并留一段最小可见长度
        double ratio = topScore == 0 ? 0 : Math.abs(user.score()) / Math.abs(topScore);
        int filled = (int) Math.round(barWidth * Math.max(0, Math.min(1, ratio)));
        if (filled > 0) {
            painter.drawRoundedRectangle(barX, y + 12, Math.max(filled, RANKING_BAR_HEIGHT),
                    RANKING_BAR_HEIGHT, RANKING_BAR_HEIGHT / 2, rankColor(rank));
        }

        painter.drawTextWithStyle(List.of(new TextWithStyle(scoreText.apply(user.score()), 24, COLOR_TEXT, Font.PLAIN)),
                new Point(barX + barWidth + 16, y + 6));

        painter.setPos(MARGIN, y + RANKING_ROW_HEIGHT);
    }

    /**
     * 取排行榜用的圆形头像，带缓存
     * <p>
     * 缓存按头像地址而非用户：同一个人在多张榜里出现、多份报告里出现，都只下载一次。
     * 取不到时缓存一个空值占位，避免坏地址被反复重试。
     */
    private BufferedImage avatar(String url) {
        if (StringUtil.isBlank(url)) {
            return null;
        }

        BufferedImage cached = avatarCache.get(url, key -> api.getBilibiliImage(atSize(key, RANKING_AVATAR_SIZE))
                .map(image -> ImageUtil.maskToCircle(ImageUtil.resize(image, RANKING_AVATAR_SIZE, RANKING_AVATAR_SIZE)))
                .orElse(FAILED_AVATAR));
        // 用哨兵值区分「没缓存过」与「缓存了一次失败」，后者不再重试
        return cached == FAILED_AVATAR ? null : cached;
    }

    /**
     * 名次配色：前三名依次为金、银、铜，其余用主题粉
     */
    private Color rankColor(int rank) {
        return switch (rank) {
            case 1 -> new Color(240, 173, 78);
            case 2 -> new Color(160, 174, 192);
            case 3 -> new Color(205, 133, 96);
            default -> new Color(251, 168, 193);
        };
    }

    /**
     * 截断过长的昵称，避免顶到比例条
     */
    private String truncate(String name) {
        return name.length() <= MAX_NAME_LENGTH ? name : name.substring(0, MAX_NAME_LENGTH) + "…";
    }

    /**
     * 绘制弹幕词云，词数不足或渲染失败时整体跳过
     */
    private void drawWordCloud(CommonPainter painter, String platform, Long uid) {
        Map<String, Integer> frequencies = liveDataService.getLiveWordFrequencies(platform, uid);
        if (frequencies.size() < CLOUD_MIN_WORDS) {
            return;
        }

        try {
            List<WordFrequency> words = frequencies.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()))
                    .limit(CLOUD_MAX_WORDS)
                    .map(entry -> new WordFrequency(entry.getKey(), entry.getValue()))
                    .toList();

            WordCloud cloud = new WordCloud(new Dimension(CONTENT_WIDTH, CLOUD_HEIGHT), CollisionMode.PIXEL_PERFECT);
            cloud.setPadding(3);
            cloud.setBackground(new RectangleBackground(new Dimension(CONTENT_WIDTH, CLOUD_HEIGHT)));
            cloud.setBackgroundColor(new Color(0, 0, 0, 0));
            cloud.setColorPalette(new ColorPalette(CLOUD_PALETTE));
            cloud.setKumoFont(new KumoFont(fontUtil.findFontForCharacter('云')));
            cloud.setFontScalar(new SqrtFontScalar(16, 62));
            // 中文竖排可读性差，词一律横排
            cloud.setAngleGenerator(new AngleGenerator(0));
            cloud.build(new ArrayList<>(words));

            painter.movePos(0, 6);
            painter.drawTextWithStyle(List.of(new TextWithStyle("弹幕词云", CommonPainter.TEXT_FONT_SIZE, COLOR_TIP, Font.PLAIN)));
            painter.movePos(0, 8);
            painter.drawImage(ImageUtil.maskToRoundedRectangle(cloud.getBufferedImage(), CARD_RADIUS));
        } catch (Exception e) {
            log.warn("绘制弹幕词云失败, 报告将不含词云: {}", e.getMessage());
        }
    }

    /**
     * 绘制带白色描边的圆形头像
     */
    private void drawRingedAvatar(CommonPainter painter, BufferedImage face, int x, int y) {
        int ringSize = AVATAR_SIZE + AVATAR_RING * 2;
        BufferedImage ring = new BufferedImage(ringSize, ringSize, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = ring.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, ringSize, ringSize);
        graphics.dispose();
        painter.drawImage(ImageUtil.maskToCircle(ring), new Point(x - AVATAR_RING, y - AVATAR_RING));
        painter.drawImage(face, new Point(x, y));
    }

    /**
     * 获取直播间封面并裁剪为横幅：不可得时返回 null，头部退化为简单版式
     */
    private BufferedImage loadCover(LiveStreamerInfo source) {
        if (source.getRoomId() == null) {
            return null;
        }

        try {
            Room room = api.getLiveInfoByRoomId(source.getRoomId());
            if (room == null || StringUtil.isBlank(room.getCover())) {
                return null;
            }

            return api.getBilibiliImage(room.getCover())
                    .map(cover -> {
                        BufferedImage scaled = ImageUtil.resizeByWidth(cover, CONTENT_WIDTH);
                        if (scaled.getHeight() < COVER_HEIGHT) {
                            scaled = ImageUtil.resizeByHeight(cover, COVER_HEIGHT);
                        }
                        int cropX = Math.max(0, (scaled.getWidth() - CONTENT_WIDTH) / 2);
                        int cropY = Math.max(0, (scaled.getHeight() - COVER_HEIGHT) / 2);
                        BufferedImage banner = scaled.getSubimage(cropX, cropY,
                                Math.min(CONTENT_WIDTH, scaled.getWidth()), Math.min(COVER_HEIGHT, scaled.getHeight()));
                        return ImageUtil.maskToRoundedRectangle(banner, CANVAS_RADIUS - 5);
                    })
                    .orElse(null);
        } catch (Exception e) {
            log.debug("获取直播间 {} 的封面失败: {}", source.getRoomId(), e.getMessage());
            return null;
        }
    }

    /**
     * 绘制底部标识，未配置或读取失败时跳过
     */
    private void drawLogo(CommonPainter painter) {
        BufferedImage image = logo();
        if (image == null) {
            return;
        }

        int top = painter.getY();
        painter.drawImage(image, new Point(MARGIN, top));
        painter.setPos(MARGIN, top + image.getHeight() + 10);
    }

    /**
     * 读取并缓存底部标识图片
     * <p>
     * 只在首次绘制时读取一次；读取失败也标记为已加载，
     * 以免路径写错导致每份报告都重复尝试读盘并刷一条警告。
     * @return 标识图片，未配置或读取失败时为 null
     */
    private BufferedImage logo() {
        if (logoLoaded) {
            return logo;
        }

        synchronized (this) {
            if (!logoLoaded) {
                String path = properties.getLive().getReportLogoPath();
                if (StringUtil.isNotBlank(path)) {
                    try {
                        Path file = Path.of(path);
                        if (Files.isReadable(file)) {
                            logo = ImageUtil.resizeByHeight(ImageIO.read(file.toFile()), LOGO_HEIGHT);
                        } else {
                            log.warn("下播报告的标识图片 {} 不存在或不可读, 已跳过绘制", path);
                        }
                    } catch (Exception e) {
                        log.warn("读取下播报告的标识图片 {} 失败, 已跳过绘制: {}", path, e.getMessage());
                    }
                }
                logoLoaded = true;
            }
        }

        return logo;
    }

    /**
     * 读取计数类指标并取整
     */
    private long count(String platform, Long uid, String metric) {
        return Math.round(liveDataService.getLiveMetric(platform, uid, metric));
    }

    /**
     * 本场直播的时长描述。直播中（消息命令实时拉取）以当前时刻为终点
     */
    private String durationText(String platform, Long uid) {
        Optional<Long> start = liveDataService.getLiveStartTime(platform, uid);
        Optional<Long> end = effectiveEndTime(platform, uid, start);
        if (start.isEmpty() || end.isEmpty()) {
            return "";
        }
        return DurationFormatUtil.format((end.get() - start.get()) / 1000);
    }

    /**
     * 本场直播的起止时间描述。直播中显示「起点 起 · 直播中」
     */
    private String timeRange(String platform, Long uid) {
        Optional<Long> start = liveDataService.getLiveStartTime(platform, uid);
        if (start.isEmpty()) {
            return TIME_FORMATTER.format(Instant.now());
        }

        if (isLiving(platform, uid)) {
            return TIME_FORMATTER.format(Instant.ofEpochMilli(start.get())) + " 起 · 直播中";
        }

        Optional<Long> end = effectiveEndTime(platform, uid, start);
        if (end.isEmpty()) {
            return TIME_FORMATTER.format(Instant.ofEpochMilli(start.get()));
        }
        return TIME_FORMATTER.format(Instant.ofEpochMilli(start.get()))
                + " ~ " + TIME_FORMATTER.format(Instant.ofEpochMilli(end.get()));
    }

    /**
     * 本场直播的有效终点：已下播用记录的结束时间；直播中用当前时刻。
     * 上一场遗留的结束时间早于本场开始时间，视为无效
     */
    private Optional<Long> effectiveEndTime(String platform, Long uid, Optional<Long> start) {
        Optional<Long> end = liveDataService.getLiveEndTime(platform, uid);
        if (end.isPresent() && (start.isEmpty() || end.get() >= start.get())) {
            return end;
        }
        if (isLiving(platform, uid)) {
            return Optional.of(System.currentTimeMillis());
        }
        return Optional.empty();
    }

    /**
     * 是否正在直播
     */
    private boolean isLiving(String platform, Long uid) {
        return liveDataService.getLiveStatus(platform, uid).orElse(false);
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
        return atSize(url, AVATAR_SIZE);
    }

    /**
     * 为图片地址附加指定宽度的缩放参数
     * <p>
     * 排行榜头像只有 32px，下原图既慢又浪费——一场直播的榜单动辄数十人
     */
    private String atSize(String url, int size) {
        if (StringUtil.isBlank(url) || url.contains("@")) {
            return url;
        }
        return url + "@" + size + "w.webp";
    }

    /**
     * 数据卡片：取值与标签
     */
    private record Card(String value, String label) {
    }

    /**
     * 一条互动曲线的定义
     * @param title 曲线标题
     * @param metric 时间序列的指标名
     * @param color 面积配色
     * @param peakText 峰值的展示文案
     */
    private record Curve(String title, String metric, Color color, DoubleFunction<String> peakText) {
    }
}
