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
import com.starlwr.bot.core.analytics.LiveHighlightFinder;
import com.starlwr.bot.core.factory.StarBotCommonPainterFactory;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import com.starlwr.bot.core.model.RoomInfoSnapshot;
import com.starlwr.bot.core.model.TextWithStyle;
import com.starlwr.bot.core.model.UserScore;
import com.starlwr.bot.core.painter.CommonPainter;
import com.starlwr.bot.core.plugin.StarBotComponent;
import com.starlwr.bot.core.service.LiveDataService;
import com.starlwr.bot.core.service.LiveRoomInfoHistory;
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
     * 卡片内文字距卡片左右两边的留白
     */
    private static final int CARD_TEXT_INSET = 20;

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
     * 排行榜昵称的可用宽度，单位像素
     * <p>
     * 昵称画在头像右侧（{@code MARGIN + 40 + 头像 32 + 10}），比例条从 {@code MARGIN + 300} 起，
     * 中间留 12px 不让字贴上条子。<b>是像素不是字数</b>——理由见 {@link #truncate}
     */
    private static final int NAME_MAX_WIDTH = 300 - (40 + RANKING_AVATAR_SIZE + 10) - 12;

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

    /**
     * 卡片底色。包内可见：版式判据要靠它认出「哪几行落在卡片带里」，
     * 否则同样的 x 区间会撞上封面横幅与排行榜的比例条
     */
    static final Color COLOR_CARD = new Color(246, 247, 249);

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
    private static final Color COLOR_CURVE_WATCHED = new Color(110, 199, 122);

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

    /**
     * 场次内的时刻只需要时分，日期由报告头部交代
     */
    private static final DateTimeFormatter CLOCK_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of("Asia/Shanghai"));

    /**
     * 最多列出几个高能时刻
     * <p>
     * 三个足够指出一场直播的骨架，再多就成了「把曲线又写了一遍」。
     */
    private static final int HIGHLIGHT_LIMIT = 3;

    /**
     * 两个高能时刻之间至少相隔多久
     */
    private static final long HIGHLIGHT_MIN_SEPARATION_MILLIS = 5 * 60_000L;

    /**
     * 高能时刻至少要达到基线的多少倍
     */
    private static final double HIGHLIGHT_MIN_RATIO = 3.0;

    /**
     * 高能时刻的绝对门槛：这一分钟至少要有这么多条弹幕
     * <p>
     * 没有这道门槛，一场总共二十条弹幕的冷场也能凑出三个「三倍于基线」的时刻，
     * 而那三分钟各自只有三条弹幕。<b>冷场就该老实说没有高能片段。</b>
     */
    private static final double HIGHLIGHT_MIN_DANMU = 15;

    /**
     * 高能时刻标题的可用宽度，单位像素
     * <p>
     * 比昵称宽松：标题从 {@code MARGIN + 180} 起，占的是到版心右边界的一整段
     */
    private static final int TITLE_MAX_WIDTH = CONTENT_WIDTH - 180;

    private final StarBotCommonPainterFactory factory;

    private final BilibiliApiUtil api;

    private final LiveDataService liveDataService;

    private final FontUtil fontUtil;

    private final StarBotBilibiliProperties properties;

    private final LiveRoomInfoHistory roomInfoHistory;

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
                                     StarBotBilibiliProperties properties, LiveRoomInfoHistory roomInfoHistory) {
        this.factory = factory;
        this.api = api;
        this.liveDataService = liveDataService;
        this.fontUtil = fontUtil;
        this.properties = properties;
        this.roomInfoHistory = roomInfoHistory;
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
     * 本场直播报告的<b>文字版</b>，画图失败时顶上
     * <p>
     * 画图这条路依赖字体、图形环境与几个外部图片，任何一处出问题此前的结果是
     * <b>整条报告消失</b>——占位符被替换成空串、消息成了空白、发送环节直接跳过，
     * 主播看到的是「这场没有报告」而不是「报告画不出来」。
     * <p>
     * 文字版<b>不追求版式对等</b>，只保证一件事：这一场的关键数字送到了。
     * 金额可见性照 {@code options} 走——降级不是放宽口径的理由，
     * 该给大群看的仍然不带金额。
     * @param platform 直播平台
     * @param source 主播信息
     * @param options 版式选项，此处只用到金额可见性
     * @return 文字版报告
     */
    public String textReport(String platform, LiveStreamerInfo source, BilibiliLiveReportOptions options) {
        Long uid = source.getUid();
        StringBuilder text = new StringBuilder();

        text.append(source.getUname()).append(" 本场直播数据");

        String duration = durationText(platform, uid);
        text.append("\n直播时长 ").append(StringUtil.isNotBlank(duration) ? duration : "未知");
        String gap = maintenanceGapText(platform, uid);
        if (!gap.isEmpty()) {
            text.append("（其中 ").append(gap).append("因维护未采集）");
        }
        // 单房断线与程序停机分两句写。合成一句就得把两个数相加，
        // 而它们会重叠——停机期间这个房间当然也是断的
        String outage = roomOutageText(platform, uid);
        if (!outage.isEmpty()) {
            text.append("（另有 ").append(outage).append("因直播间断线未采集）");
        }

        // 本场有推送的图片没送到时才出现这一行，绝大多数场次是零、不占版面
        long imageDegraded = count(platform, uid, BilibiliLiveMetric.IMAGE_DEGRADED_COUNT);
        if (imageDegraded > 0) {
            text.append("\n⚠️ 本场有 ").append(imageDegraded).append(" 条推送的图片未送达（文字已送达）");
        }

        long danmu = count(platform, uid, BilibiliLiveMetric.DANMU_COUNT);
        int danmuUsers = liveDataService.getLiveMetricUserCount(platform, uid, BilibiliLiveMetric.DANMU_USERS);
        text.append("\n弹幕 ").append(danmu).append(" 条 · ").append(danmuUsers).append(" 人参与");

        long boxes = count(platform, uid, BilibiliLiveMetric.BOX_COUNT);
        long superChats = count(platform, uid, BilibiliLiveMetric.SUPER_CHAT_COUNT);
        long guards = count(platform, uid, BilibiliLiveMetric.CAPTAIN_COUNT)
                + count(platform, uid, BilibiliLiveMetric.COMMANDER_COUNT)
                + count(platform, uid, BilibiliLiveMetric.GOVERNOR_COUNT);

        if (options.isShowRevenue()) {
            double revenue = liveDataService.getLiveMetric(platform, uid, BilibiliLiveMetric.GIFT_VALUE)
                    + liveDataService.getLiveMetric(platform, uid, BilibiliLiveMetric.SUPER_CHAT_VALUE)
                    + liveDataService.getLiveMetric(platform, uid, BilibiliLiveMetric.GUARD_VALUE);
            if (revenue > 0) {
                text.append("\n本场收益 ¥").append(yuan(revenue));
            }
        } else {
            // 不展示金额时换一种说法，热闹程度照样看得见——与图片版同一个立场
            int giftUsers = liveDataService.getLiveMetricUserCount(platform, uid, BilibiliLiveMetric.GIFT_USERS);
            if (giftUsers > 0) {
                text.append("\n礼物 ").append(giftUsers).append(" 人送出");
            }
        }

        if (superChats > 0) {
            text.append("\n醒目留言 ").append(superChats).append(" 条");
        }
        if (guards > 0) {
            text.append("\n新开通大航海 ").append(guards).append(" 人");
        }
        if (boxes > 0) {
            text.append("\n盲盒 ").append(boxes).append(" 个");
        }

        long follow = count(platform, uid, BilibiliLiveMetric.FOLLOW_COUNT);
        int enterUsers = liveDataService.getLiveMetricUserCount(platform, uid, BilibiliLiveMetric.ENTER_USERS);
        if (follow > 0 || enterUsers > 0) {
            text.append("\n新增关注 ").append(follow).append(" 人次 · 进房 ").append(enterUsers).append(" 人");
        }

        text.append("\n\n（报告图片绘制失败，本条为文字版）");
        return text.toString();
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
            drawOverview(painter, platform, source.getUid(), options);
            if (options.isCards()) {
                drawCards(painter, platform, source.getUid(), options);
            }
            if (options.isFansChange()) {
                drawFansChange(painter, platform, source);
            }
            if (options.isInteractionCurve()) {
                drawCurves(painter, platform, source.getUid(), options);
            }
            if (options.isHighlights()) {
                drawHighlights(painter, platform, source.getUid());
            }
            if (options.isTitleChanges()) {
                drawTitleChanges(painter, platform, source.getUid());
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
            painter.drawSection(unameWithin(painter, source, textX), COLOR_NAME, new Point(textX, top + COVER_HEIGHT + 4));
            painter.drawTip("直播报告 · " + timeRange(platform, source.getUid()), COLOR_TIP, new Point(textX, top + COVER_HEIGHT + 52));

            painter.setPos(MARGIN, top + COVER_HEIGHT + AVATAR_SIZE + 16);
            return;
        }

        int textX = MARGIN + AVATAR_SIZE + 25;
        if (face != null) {
            painter.drawImage(face, new Point(MARGIN, top));
        }
        painter.drawSection(unameWithin(painter, source, textX), COLOR_NAME, new Point(textX, top + 8));
        painter.drawTip("直播报告 · " + timeRange(platform, source.getUid()), COLOR_TIP, new Point(textX, top + 58));
        painter.setPos(MARGIN, top + AVATAR_SIZE + 30);
    }

    /**
     * 取主播名，并按版心剩下的宽度截断
     * <p>
     * B 站昵称<b>没有长度上限</b>，而这一行原先一个字都不截。现在没出事只是因为
     * 常见昵称都短——<b>没撞上不等于没有</b>：实测 30 个字的昵称会顶出画布 338 像素。
     * @param textX 这一行的起始 x，可用宽度是从这里到版心右边界
     */
    private String unameWithin(CommonPainter painter, LiveStreamerInfo source, int textX) {
        String uname = Optional.ofNullable(source.getUname()).orElse("未知主播");

        return painter.truncateToWidth(
                new TextWithStyle(uname, CommonPainter.SECTION_FONT_SIZE, COLOR_NAME, Font.BOLD),
                WIDTH - MARGIN - textX);
    }

    /**
     * 绘制概览行：直播时长与本场收益
     * <p>
     * 收益此前是<b>无条件</b>绘制的：即使把其余区块全部关掉，只留一张卡片，
     * 这一行照样把整场收入写在报告最显眼的位置。
     */
    private void drawOverview(CommonPainter painter, String platform, Long uid, BilibiliLiveReportOptions options) {
        String duration = Optional.of(durationText(platform, uid)).filter(StringUtil::isNotBlank).orElse("未知");

        List<TextWithStyle> line = new ArrayList<>();
        line.add(new TextWithStyle("直播时长 ", CommonPainter.TEXT_FONT_SIZE, COLOR_TIP, Font.PLAIN));
        line.add(new TextWithStyle(duration, CommonPainter.TEXT_FONT_SIZE, COLOR_TEXT, Font.BOLD));

        // 停机缺口紧跟在时长后面，而不是塞进页脚：报告上每个数字都受它影响，
        // 看到时长的人必须同时看到「这段时间里有一截没在采」
        String gap = maintenanceGapText(platform, uid);
        if (!gap.isEmpty()) {
            line.add(new TextWithStyle("（其中 " + gap + "因维护未采集）",
                    CommonPainter.TEXT_FONT_SIZE, COLOR_TIP, Font.PLAIN));
        }
        String outage = roomOutageText(platform, uid);
        if (!outage.isEmpty()) {
            line.add(new TextWithStyle("（另有 " + outage + "因直播间断线未采集）",
                    CommonPainter.TEXT_FONT_SIZE, COLOR_TIP, Font.PLAIN));
        }

        // 与停机缺口同一个道理：图片没送到是主播能感知的差异，
        // 而它只在日志里留过痕。为零时整段不出现
        long imageDegraded = count(platform, uid, BilibiliLiveMetric.IMAGE_DEGRADED_COUNT);
        if (imageDegraded > 0) {
            line.add(new TextWithStyle("    ⚠ 有 " + imageDegraded + " 条推送的图片未送达",
                    CommonPainter.TEXT_FONT_SIZE, COLOR_TIP, Font.PLAIN));
        }

        if (options.isShowRevenue()) {
            double revenue = liveDataService.getLiveMetric(platform, uid, BilibiliLiveMetric.GIFT_VALUE)
                    + liveDataService.getLiveMetric(platform, uid, BilibiliLiveMetric.SUPER_CHAT_VALUE)
                    + liveDataService.getLiveMetric(platform, uid, BilibiliLiveMetric.GUARD_VALUE);
            if (revenue > 0) {
                line.add(new TextWithStyle("    本场收益 ", CommonPainter.TEXT_FONT_SIZE, COLOR_TIP, Font.PLAIN));
                line.add(new TextWithStyle("¥" + yuan(revenue), CommonPainter.TEXT_FONT_SIZE, COLOR_NAME, Font.BOLD));
            }
        }

        painter.drawTextWithStyle(wrapAtSegments(painter, line, MARGIN), null, true, MARGIN);
        painter.movePos(0, 18);
    }

    /**
     * 让一行由若干语义完整的片段拼成的文本，在<b>片段边界</b>上换行
     *
     * <h2>为什么不直接开自动换行了事</h2>
     * {@code drawTextWithStyle} 的自动换行是<b>逐字</b>判断的，
     * 于是「本场收益 ¥55.3」会被断成「本场收益 ¥55.」和「3」——
     * 一个数字被劈成两行，比溢出还难认。
     * <p>
     * 这些片段每一个都是一句完整的话（「（其中 8 秒因维护未采集）」「本场收益 ¥55.3」），
     * 在它们之间断开才是人读得懂的断法。
     * <p>
     * 逐字的自动换行仍然要开着<b>兜底</b>：万一某一个片段自己就比一整行还长
     * （比如时长文案将来变得很啰嗦），片段边界无处可断，那时宁可断在字中间也不要画出画布。
     *
     * @param painter 绘图器，用来量宽度
     * @param segments 片段，按绘制顺序
     * <p>
     * 包内可见而不是私有：<b>「断在哪」是这段逻辑唯一的产出</b>，
     * 而右边距那把尺子只看得出「有没有画出去」，看不出「断得人读不读得懂」——
     * 逐字换行同样不溢出，同样是绿的。要钉住片段边界这件事，判据得直接看这个返回值。
     * @param startX 这一行的起始 x，也是换行后新行的起始 x
     * @return 需要换行处已插入换行符的片段列表
     */
    List<TextWithStyle> wrapAtSegments(CommonPainter painter, List<TextWithStyle> segments, int startX) {
        int maxRight = WIDTH - MARGIN;

        List<TextWithStyle> wrapped = new ArrayList<>(segments.size());
        int x = startX;
        for (TextWithStyle segment : segments) {
            int width = painter.getStringWidthAndHeight(segment).getFirst();

            if (x > startX && x + width > maxRight) {
                // 换行之后那几个用来拉开间距的前导空格就没有意义了，去掉
                String text = segment.getText().stripLeading();
                TextWithStyle broken = new TextWithStyle("\n" + text,
                        segment.getSize(), segment.getColor(), segment.getStyle());
                broken.setFont(segment.getFont());
                wrapped.add(broken);

                TextWithStyle measured = new TextWithStyle(text,
                        segment.getSize(), segment.getColor(), segment.getStyle());
                measured.setFont(segment.getFont());
                x = startX + painter.getStringWidthAndHeight(measured).getFirst();
            } else {
                wrapped.add(segment);
                x += width;
            }
        }

        return wrapped;
    }

    /**
     * 绘制数据卡片栅格，为零的卡片自动省略
     * <p>
     * 不展示金额时这些卡片不是消失，而是换一种说法：礼物讲「多少人送出」、
     * 醒目留言讲「多少条」、盲盒讲「开了多少个」。互动的热闹程度照样看得见，
     * 只是不带走具体数额——那正是想给大群看的部分。
     */
    private void drawCards(CommonPainter painter, String platform, Long uid, BilibiliLiveReportOptions options) {
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

        boolean revenue = options.isShowRevenue();

        List<Card> cards = new ArrayList<>();
        cards.add(new Card(String.valueOf(danmu), "弹幕 · " + danmuUsers + " 人参与"));
        if (giftValue > 0 || giftUsers > 0) {
            cards.add(revenue
                    ? new Card("¥" + yuan(giftValue), "礼物 · " + giftUsers + " 人送出")
                    : new Card(giftUsers + " 人", "送出礼物"));
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
            cards.add(new Card(superChat + " 条", revenue ? "醒目留言 · ¥" + yuan(superChatValue) : "醒目留言"));
        }
        if (captain > 0 || commander > 0 || governor > 0) {
            cards.add(new Card("+" + (captain + commander + governor),
                    revenue ? "大航海 · ¥" + yuan(guardValue) : "大航海"));
        }
        if (box > 0) {
            String direction = boxProfit >= 0 ? "盈利" : "亏损";
            cards.add(new Card(box + " 个",
                    revenue ? "盲盒 · " + direction + " ¥" + yuan(Math.abs(boxProfit)) : "盲盒"));
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

        // 卡片里的文字放不下时不是被画布切掉，而是<b>盖到隔壁那张卡片上</b>——那比被切还难认。
        // 实测余量只剩个位数像素：「粉丝团 · 本场 +12345」离撑破只差 9px，涨幅到六位数就出界
        int usable = CARD_WIDTH - CARD_TEXT_INSET * 2;

        TextWithStyle value = new TextWithStyle(card.value, 34, COLOR_TEXT, Font.BOLD);
        value.setText(painter.truncateToWidth(value, usable));
        painter.drawTextWithStyle(List.of(value), new Point(x + CARD_TEXT_INSET, y + 16));

        TextWithStyle label = new TextWithStyle(card.label, 22, COLOR_TIP, Font.PLAIN);
        label.setText(painter.truncateToWidth(label, usable));
        painter.drawTextWithStyle(List.of(label), new Point(x + CARD_TEXT_INSET, y + 68));
    }

    /**
     * 绘制粉丝、粉丝团与大航海的本场变化
     * <p>
     * 这三项都不在弹幕流里，只能问接口。开播时的快照由
     * {@code BilibiliRoomStatsSnapshotter} 记下，这里取一次实时值相减即得涨幅——
     * 于是直播中随时拉的实时报告与下播报告走的是同一段逻辑。
     * <p>
     * 三项各自独立降级：接口挂了或没有开播快照，就只跳过那一项。
     * <p>
     * ⚠️ <b>已登记待处理：这里的「大航海」与数据卡片里的「大航海」不同口径，而名字一样。</b>
     * 卡片上那个是<b>上舰人次</b>（{@code CAPTAIN/COMMANDER/GOVERNOR_COUNT} 之和，
     * <b>含续费</b>），这里这个是<b>大航海人数的净变化</b>（续费不改变人数，到期会减少）。
     * 于是同一张报告里可以出现「大航海 +5」与「大航海 · 本场 +2」，读的人无法对上。
     * 曲线区还有第三个「大航海」，画的是金额（{@code GUARD_VALUE}）。
     * <p>
     * 按「同一张报告里同名指标必须同口径，不同就改名或标注」这条规矩，这里要么改名
     * （如「上舰人次」/「大航海人数」）要么标注。改动涉及报告版面与既有截图的认知，
     * 不与礼物口径那批一起做，已记账。
     * <p>
     * <b>届时以改名为主，不是标注。</b>礼物那次能靠标注解决，是因为变更后只剩一个口径，
     * 那句话说的是「这个数是什么」；而这里是<b>三个不同的量共用一个名字</b>，
     * 标注得写成「这个大航海是人次，那个大航海是人数，曲线那个是金额」——
     * 读的人仍然要在三个同名的东西之间自己对号。
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
    private void drawCurves(CommonPainter painter, String platform, Long uid, BilibiliLiveReportOptions options) {
        Optional<Long> start = liveDataService.getLiveStartTime(platform, uid);
        Optional<Long> end = effectiveEndTime(platform, uid, start);
        if (start.isEmpty() || end.isEmpty() || end.get() <= start.get()) {
            return;
        }

        // 不展示金额时，礼物、醒目留言、大航海三条曲线保留形状但不标峰值。
        // 面积图按自身峰值归一化，画出来的是「什么时候热闹」，本身不含任何绝对数值——
        // 这恰好是最适合给大群看的东西，整条删掉反而丢了氛围
        DoubleFunction<String> money = options.isShowRevenue() ? peak -> "¥" + yuan(peak) + "/分" : null;

        List<Curve> curves = new ArrayList<>();
        curves.add(new Curve("弹幕", BilibiliLiveMetric.DANMU_COUNT, COLOR_CURVE_DANMU,
                peak -> Math.round(peak) + " 条/分"));
        curves.add(new Curve("礼物", BilibiliLiveMetric.GIFT_VALUE, COLOR_NAME, money));
        curves.add(new Curve("醒目留言", BilibiliLiveMetric.SUPER_CHAT_VALUE, COLOR_CURVE_SUPER_CHAT, money));
        curves.add(new Curve("盲盒", BilibiliLiveMetric.BOX_COUNT, COLOR_CURVE_BOX,
                peak -> Math.round(peak) + " 个/分"));
        curves.add(new Curve("大航海", BilibiliLiveMetric.GUARD_VALUE, COLOR_CURVE_GUARD, money));
        // 看过人数是累计值，画出来是一条只升不降的线——它的**斜率**才是「什么时候在涨人」。
        // 峰值标的是本场最终看过多少人，因此文案是「人看过」而不是「人/分」
        curves.add(new Curve("看过人数", BilibiliLiveMetric.WATCHED_COUNT, COLOR_CURVE_WATCHED,
                peak -> Math.round(peak) + " 人看过"));

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
     * 绘制高能时刻
     * <p>
     * 给出的是<b>距开播的偏移量</b>而不只是钟表时间：主播回看录播时要拖的是进度条，
     * 而进度条上的刻度正是开播后过了多久。钟表时间跟在后面备查。
     * <p>
     * 不受金额可见性影响——这里的判据是弹幕密度，一个数字都不涉及消费。
     */
    private void drawHighlights(CommonPainter painter, String platform, Long uid) {
        Optional<Long> start = liveDataService.getLiveStartTime(platform, uid);
        Optional<Long> end = effectiveEndTime(platform, uid, start);
        if (start.isEmpty() || end.isEmpty() || end.get() <= start.get()) {
            return;
        }

        List<LiveHighlightFinder.Highlight> highlights = LiveHighlightFinder.find(
                liveDataService.getLiveSeries(platform, uid, BilibiliLiveMetric.DANMU_COUNT),
                LiveDataService.SERIES_BUCKET_MILLIS, start.get(), end.get(),
                HIGHLIGHT_LIMIT, HIGHLIGHT_MIN_SEPARATION_MILLIS, HIGHLIGHT_MIN_RATIO, HIGHLIGHT_MIN_DANMU);
        if (highlights.isEmpty()) {
            return;
        }

        painter.movePos(0, 10);
        painter.drawTextWithStyle(List.of(new TextWithStyle("高能时刻", CommonPainter.TEXT_FONT_SIZE, COLOR_TIP, Font.PLAIN)));
        painter.movePos(0, 6);

        for (int i = 0; i < highlights.size(); i++) {
            drawHighlightRow(painter, i + 1, highlights.get(i), start.get());
        }
        painter.movePos(0, 8);
    }

    /**
     * 绘制高能时刻的一行：名次、距开播时长、钟表时间与弹幕密度
     */
    private void drawHighlightRow(CommonPainter painter, int rank, LiveHighlightFinder.Highlight highlight, long start) {
        int y = painter.getY();

        painter.drawTextWithStyle(List.of(new TextWithStyle(String.valueOf(rank), 24, rankColor(rank), Font.BOLD)),
                new Point(MARGIN + 4, y + 6));

        painter.drawTextWithStyle(List.of(
                        new TextWithStyle(offsetText(highlight.at(), Optional.of(start)), 24, COLOR_TEXT, Font.BOLD),
                        new TextWithStyle("  " + CLOCK_FORMAT.format(Instant.ofEpochMilli(highlight.at())), 22, COLOR_TIP, Font.PLAIN)),
                new Point(MARGIN + 40, y + 6));

        painter.drawTextWithStyle(List.of(
                        new TextWithStyle(Math.round(highlight.value()) + " 条/分", 24, COLOR_CURVE_DANMU, Font.BOLD)),
                new Point(MARGIN + CONTENT_WIDTH - 130, y + 6));

        painter.setPos(MARGIN, y + RANKING_ROW_HEIGHT);
    }

    /**
     * 绘制本场的标题变化
     * <p>
     * 只在真的改过时出现。首条记录是开播时的初始标题，不算一次改动，
     * 所以一条记录等于「全程没改过」，直接跳过整块。
     */
    private void drawTitleChanges(CommonPainter painter, String platform, Long uid) {
        List<RoomInfoSnapshot> titles = roomInfoHistory.history(platform, uid);
        if (titles.size() < 2) {
            return;
        }

        Optional<Long> start = liveDataService.getLiveStartTime(platform, uid);

        painter.movePos(0, 10);
        painter.drawTextWithStyle(List.of(new TextWithStyle(
                "标题变化 · 本场改过 " + (titles.size() - 1) + " 次", CommonPainter.TEXT_FONT_SIZE, COLOR_TIP, Font.PLAIN)));
        painter.movePos(0, 6);

        String previousArea = "";
        for (RoomInfoSnapshot title : titles) {
            int y = painter.getY();

            painter.drawTextWithStyle(List.of(new TextWithStyle(offsetText(title.at(), start), 22, COLOR_TIP, Font.PLAIN)),
                    new Point(MARGIN + 4, y + 6));

            List<TextWithStyle> line = new ArrayList<>();
            line.add(new TextWithStyle(truncateTitle(painter, title.title()), 24, COLOR_TEXT, Font.PLAIN));
            // 分区只在这一条真的换了分区时才标出来：多数场次全程一个分区，
            // 每行都跟一遍只会把真正的改动淹掉
            String area = title.area() == null ? "" : title.area();
            if (!area.isBlank() && !area.equals(previousArea)) {
                line.add(new TextWithStyle("  " + area, 22, COLOR_TIP, Font.PLAIN));
            }
            previousArea = area.isBlank() ? previousArea : area;
            painter.drawTextWithStyle(line, new Point(MARGIN + 180, y + 6));

            painter.setPos(MARGIN, y + RANKING_ROW_HEIGHT);
        }
        painter.movePos(0, 8);
    }

    /**
     * 把时刻表述为距开播多久
     * <p>
     * 开播那一刻的偏移量是 0，而时长格式化对 0 返回空字符串——直接拼就会渲染出
     * 一个后面什么都没有的「开播后」。这里单独说成「开播时」。
     * @param at 时刻（毫秒）
     * @param start 开播时刻，取不到时退回钟表时间
     * @return 可读描述
     */
    private String offsetText(long at, Optional<Long> start) {
        if (start.isEmpty()) {
            return CLOCK_FORMAT.format(Instant.ofEpochMilli(at));
        }

        String offset = DurationFormatUtil.format(Math.max(0, (at - start.get()) / 1000));
        return offset.isEmpty() ? "开播时" : "开播后 " + offset;
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

        List<TextWithStyle> title = new ArrayList<>();
        title.add(new TextWithStyle(curve.title, 24, COLOR_TEXT, Font.PLAIN));
        // peakText 为 null 表示这条曲线的峰值是金额且本会话不展示金额，只留标题
        if (curve.peakText != null) {
            title.add(new TextWithStyle("　峰值 " + curve.peakText.apply(peak), 22, COLOR_TIP, Font.PLAIN));
        }
        painter.drawTextWithStyle(title, new Point(MARGIN, top));

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
        // 金额榜整榜跳过而非只抹掉数字：这三张榜的每一行本质都是「某人花了多少钱」，
        // 留下名次仍然是在公开排消费
        int giftRanking = options.isShowRevenue() ? options.getGiftRanking() : 0;
        int superChatRanking = options.isShowRevenue() ? options.getSuperChatRanking() : 0;
        int boxProfitRanking = options.isShowRevenue() ? options.getBoxProfitRanking() : 0;

        drawRanking(painter, platform, uid, "弹幕排行", BilibiliLiveMetric.DANMU_USERS,
                options.getDanmuRanking(), score -> Math.round(score) + " 条", null);
        // 礼物榜与上面礼物卡片同口径（都是主播到手价值），标题下把口径写明：
        // 观众最容易把这张榜读成「谁花了多少钱」，而背包礼物与盲盒上那不是同一个数
        drawRanking(painter, platform, uid, "礼物排行", BilibiliLiveMetric.GIFT_USERS,
                giftRanking, score -> "¥" + yuan(score), BilibiliLiveMetric.GIFT_RANKING_NOTE);
        drawRanking(painter, platform, uid, "醒目留言排行", BilibiliLiveMetric.SUPER_CHAT_USERS,
                superChatRanking, score -> "¥" + yuan(score), null);
        drawRanking(painter, platform, uid, "盲盒排行", BilibiliLiveMetric.BOX_USERS,
                options.getBoxRanking(), score -> Math.round(score) + " 个", null);
        // 盲盒盈亏可正可负，正数补个加号，让盈亏方向一眼可辨
        drawRanking(painter, platform, uid, "盲盒盈亏排行", BilibiliLiveMetric.BOX_PROFIT_USERS,
                boxProfitRanking, score -> (score >= 0 ? "+¥" : "-¥") + yuan(Math.abs(score)), null);

        if (options.isGuardList()) {
            drawRanking(painter, platform, uid, "本场开通大航海", BilibiliLiveMetric.GUARD_USERS,
                    GUARD_LIST_LIMIT, score -> Math.round(score) + " 次", null);
        }
    }

    /**
     * 绘制一张排行榜
     * @param title 榜单标题
     * @param metric 用户计分表指标名
     * @param limit 展示前多少名，0 为不展示
     * @param scoreText 得分的展示文案
     * @param note 标题下的口径说明，无歧义的榜传 null
     */
    private void drawRanking(CommonPainter painter, String platform, Long uid, String title,
                             String metric, int limit, DoubleFunction<String> scoreText, String note) {
        if (limit <= 0) {
            return;
        }

        List<UserScore> ranking = liveDataService.getLiveUserRanking(platform, uid, metric, limit);
        if (ranking.isEmpty()) {
            return;
        }

        painter.movePos(0, 10);
        painter.drawTextWithStyle(List.of(new TextWithStyle(title, CommonPainter.TEXT_FONT_SIZE, COLOR_TIP, Font.PLAIN)));
        if (note != null) {
            // 开自动折行：这句话一行放不下，而不折行的写法会把它画到画布外面，且不报错
            painter.drawTextWithStyle(
                    List.of(new TextWithStyle(note, CommonPainter.TIP_FONT_SIZE, COLOR_TIP, Font.PLAIN)),
                    null, true, MARGIN);
        }
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
        painter.drawTextWithStyle(List.of(new TextWithStyle(truncate(painter, user.displayName()), 24, COLOR_TEXT, Font.PLAIN)),
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
     * <p>
     * 🔴 <b>按像素收，不按字数。</b>原先写的是「最多 12 个字」——
     * 12 个全角汉字比 12 个半角字母宽一倍多，而昵称里两者混着来，
     * <b>安全与否取决于用户起了什么名字</b>。顺带解决另一件事：
     * {@code substring} 会把 emoji 劈成半个代理项，而昵称里 emoji 很常见。
     */
    private String truncate(CommonPainter painter, String name) {
        return painter.truncateToWidth(new TextWithStyle(name, 24, COLOR_TEXT, Font.PLAIN), NAME_MAX_WIDTH);
    }

    /**
     * 截断过长的标题
     * <p>
     * 同上，按像素收
     */
    private String truncateTitle(CommonPainter painter, String title) {
        if (title == null) {
            return "";
        }

        return painter.truncateToWidth(new TextWithStyle(title, 24, COLOR_TEXT, Font.PLAIN), TITLE_MAX_WIDTH);
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
     * 本场之内因程序停机而没有采集的时长描述
     * <p>
     * 只算与本场重叠的部分：一段跨越开播时刻的停机，开播之前那一截不属于本场。
     * 没有缺口时返回空字符串，报告上就不会多出一句废话。
     * <p>
     * <b>刻意不是私有的</b>：报告文本降级输出要用同一份措辞，
     * 缺口这句话只能有一个出处，否则图片版与文字版迟早说出两个不同的数。
     */
    String maintenanceGapText(String platform, Long uid) {
        Optional<Long> start = liveDataService.getLiveStartTime(platform, uid);
        Optional<Long> end = effectiveEndTime(platform, uid, start);
        if (start.isEmpty() || end.isEmpty()) {
            return "";
        }
        return DurationFormatUtil.format(liveDataService.downtimeWithin(start.get(), end.get()) / 1000);
    }

    /**
     * 本场因<b>这个直播间自己断线</b>而没采到的时长描述，没有则为空串
     * <p>
     * ⚠️ <b>与 {@link #maintenanceGapText} 分开显示，不相加。</b>
     * 那一个是整个程序停了，这一个是单个房间断线重连；
     * 程序停机期间所有房间都在断，两段必然重叠，加起来就是重复计数。
     * 报告上并排写两句，读者才知道这是两回事。
     */
    String roomOutageText(String platform, Long uid) {
        Optional<Long> start = liveDataService.getLiveStartTime(platform, uid);
        Optional<Long> end = effectiveEndTime(platform, uid, start);
        if (start.isEmpty() || end.isEmpty()) {
            return "";
        }
        long seconds = liveDataService.roomOutageWithin(platform, uid, start.get(), end.get()) / 1000;
        return seconds <= 0 ? "" : DurationFormatUtil.format(seconds);
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
    /**
     * 一条互动曲线
     *
     * @param title 曲线标题
     * @param metric 指标名
     * @param color 面积配色
     * @param peakText 峰值文案，为 null 时不标峰值（金额曲线在不展示金额的会话里即为此情形）
     */
    private record Curve(String title, String metric, Color color, DoubleFunction<String> peakText) {
    }
}
