package org.frostnova.nova.report.painter;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.frostnova.nova.bilibili.BilibiliPlatform;
import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.enums.GuardType;
import org.frostnova.nova.bilibili.model.BilibiliLiveMetric;
import org.frostnova.nova.bilibili.model.BilibiliLiveReportOptions;
import org.frostnova.nova.bilibili.model.GuardListFetch;
import org.frostnova.nova.bilibili.model.GuardMedal;
import org.frostnova.nova.bilibili.model.GuardMember;
import org.frostnova.nova.bilibili.model.Room;
import org.frostnova.nova.bilibili.service.GuardRosterFile;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.bilibili.util.DanmuWordCloudFrequencies;
import org.frostnova.nova.bilibili.util.DurationFormatUtil;
import org.frostnova.nova.core.analytics.LiveHighlightFinder;
import org.frostnova.nova.core.model.DanmuRecord;
import org.frostnova.nova.core.model.LiveGap;
import org.frostnova.nova.core.analytics.LiveGiftTotal;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import org.frostnova.nova.core.model.TextWithStyle;
import org.frostnova.nova.core.model.UserScore;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.frostnova.nova.core.service.LiveDataService;
import org.frostnova.nova.core.service.LiveRoomInfoHistory;
import org.frostnova.nova.core.service.StreamerNames;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.lang.StringUtil;
import org.frostnova.nova.core.service.LiveDetailArchive;
import org.frostnova.nova.report.factory.NovaCommonPainterFactory;
import org.frostnova.nova.report.util.FontUtil;
import org.frostnova.nova.report.util.ImageUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.DoubleFunction;

/**
 * 下播报告绘制器
 * <p>
 * 把本场直播累计的统计指标绘制为报告图片：直播间封面横幅、头像、时长与收益概览、
 * 数据卡片栅格与弹幕词云。为零的条目自动省略，封面或词云不可得时对应区块整体跳过，
 * 冷清场次也能得到一张干净的报告。
 *
 * <h2>🔴 为什么这里标 &#64;Primary</h2>
 * {@link BilibiliLiveReportPreviewPainter} <b>继承</b>本类，而它自己也是一个组件——
 * 于是容器里 {@code BilibiliLiveReportPainter} 这个类型有两个候选。按类型注入的地方
 * （下播报告推送、「直播报告」命令）只要一个，容器挑不出来就当场抛
 * {@code NoUniqueBeanDefinitionException}，<b>整个程序起不来</b>。
 * <p>
 * 🔴 这件事<b>整测一个字都不会说</b>：单元测试里每个画手都是自己 {@code new} 出来的，
 * 谁也不经过容器。2026-09-04 实测的形状就是「整测全绿、打出来的产物起不来」——
 * 两者之间此前没有任何一处把对方钉住。判据在 {@code ReportPainterBeanResolutionTest}，
 * 起动那一侧在 {@code tools/boot-smoke.sh}。
 * <p>
 * 🔴 标在<b>基类</b>而不是逐个注入点写 {@code @Qualifier}：{@code @Primary} 没有
 * {@code @Inherited}，不会随继承传给预览画手，因此「两个候选里只有正式的那个是首选」
 * 这句话由一行注解一次说完；而 {@code @Qualifier} 要在每一个注入点各写一遍，
 * <b>下一个注入点忘写时的表现仍然是程序起不来</b>。预览那一屏按预览画手的具体类型注入，
 * 类型上就只有一个候选，不受这行影响。
 */
@Slf4j
@Primary
@NovaComponent
public class BilibiliLiveReportPainter {
    /**
     * 图片总宽度，与动态图片一致。版面常量自 {@link ReportSharedStyle} 起，
     * 与打赏播报图共用一份，这里只是引过来
     */
    private static final int WIDTH = ReportSharedStyle.WIDTH;

    /**
     * 初始画布高度，绘制过程中按需自动扩展
     */
    private static final int INITIAL_HEIGHT = 1600;

    /**
     * 画布圆角半径
     */
    protected static final int CANVAS_RADIUS = ReportSharedStyle.CANVAS_RADIUS;

    /**
     * 内容区左右留白
     */
    private static final int MARGIN = ReportSharedStyle.MARGIN;

    /**
     * 内容区宽度
     */
    protected static final int CONTENT_WIDTH = ReportSharedStyle.CONTENT_WIDTH;

    /**
     * 封面横幅高度
     */
    protected static final int COVER_HEIGHT = 260;

    /**
     * 头像尺寸与白色描边宽度
     */
    protected static final int AVATAR_SIZE = ReportSharedStyle.AVATAR_SIZE;

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
    protected static final int RANKING_AVATAR_SIZE = 32;

    /**
     * 大航海名单最多展示的人数
     */
    private static final int GUARD_LIST_LIMIT = 10;

    /**
     * 名单昵称的字号。粉丝牌的高度与这一行相称
     */
    private static final int ROSTER_NAME_SIZE = 24;

    /**
     * 两列之间的空隙，以及牌子和昵称之间的空隙
     */
    private static final int ROSTER_COLUMN_GAP = 16;

    private static final int ROSTER_NAME_GAP = 8;

    /**
     * 名单一行的高度。标志比牌子略高，行高按标志留
     */
    private static final int ROSTER_ROW_HEIGHT = 44;

    /**
     * 大航海标志的直径。比粉丝牌略高，压在牌子左端
     */
    static final int GUARD_ICON_SIZE = 32;

    private static final int MEDAL_BAR_HEIGHT = 24;

    private static final int MEDAL_FONT_SIZE = 16;

    private static final String GUARD_ICON_GOVERNOR =
            "https://i0.hdslb.com/bfs/live/0d2b29717af2e7b1bbdc21a4fba8619636f82517.png";

    private static final String GUARD_ICON_COMMANDER =
            "https://i0.hdslb.com/bfs/live/405bffdfd78bb562e0394dd828f8bf69ea01f400.png";

    private static final String GUARD_ICON_CAPTAIN =
            "https://i0.hdslb.com/bfs/live/00749d246e2b49b2328cb981de02142fb6aeceba.png";

    private static final Color GUARD_COLOR_GOVERNOR = new Color(0xE6, 0xB4, 0x22);

    private static final Color GUARD_COLOR_COMMANDER = new Color(0xA7, 0x73, 0xF1);

    private static final Color GUARD_COLOR_CAPTAIN = new Color(0x3F, 0xB4, 0xF6);

    private static final Color GUARD_COLOR_UNKNOWN = new Color(0x9E, 0x9E, 0x9E);

    /**
     * 排行榜昵称的可用宽度，单位像素
     * <p>
     * 昵称画在头像右侧（{@code MARGIN + 40 + 头像 32 + 10}），比例条从 {@code MARGIN + 300} 起，
     * 中间留 12px 不让字贴上条子。<b>是像素不是字数</b>——理由见 {@link #truncate}
     */
    private static final int NAME_MAX_WIDTH = 300 - (40 + RANKING_AVATAR_SIZE + 10) - 12;

    /**
     * 得分文字默认占的宽度。不超过它的榜，比例条右端不动，短得分的榜看起来和以前一样。
     * 再宽就会压上条子：条子右端原先停在右边距往左这个距离处
     */
    private static final int RANKING_SCORE_SLOT = 150;

    /**
     * 得分比默认槽更宽时，条子右端与文字之间留出的空隙，与昵称和条子之间的 12px 同一档
     */
    private static final int RANKING_SCORE_GAP = 12;

    /** 排行得分的字号，量宽和绘制必须用同一个，否则预留会和真正画上去的对不齐 */
    private static final int RANKING_SCORE_FONT = 24;

    /**
     * 词云绘制的<b>最大</b>高度。实际高度按本场词数向下取档，见
     * {@link WordCloudLayout#recommendedHeight}
     * <p>
     * 🔴 这个数只是上限，<b>不是预留的位置</b>。十来个词的冷清场次排出来是一小团，
     * 仍按 380px 挪下一块的话，图上就是一小团词底下吊着二百多像素的空白
     */
    private static final int CLOUD_HEIGHT = 380;

    /**
     * 词云最多收录的词数
     */
    private static final int CLOUD_MAX_WORDS = 72;

    /**
     * 配色自 {@link ReportSharedStyle} 起，与打赏播报图共用一份，这里只是引过来
     */
    private static final Color COLOR_NAME = ReportSharedStyle.COLOR_NAME;

    private static final Color COLOR_TIP = ReportSharedStyle.COLOR_TIP;

    private static final Color COLOR_TEXT = ReportSharedStyle.COLOR_TEXT;

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
     * 在线人数折线的笔宽（像素）
     */
    private static final int CURVE_LINE_STROKE = 3;

    /**
     * 面积的最小可见高度
     * <p>
     * 一整分钟一条弹幕都没有时，面积高度算出来是 0，画出来什么都没有——
     * 而<b>「这几分钟没人说话」和「这几分钟我们没在听」必须看得出区别</b>：
     * 后者现在画成斜纹，前者就得留下一条贴着基线的细面积，否则两者在图上都是一片空白。
     */
    private static final int CURVE_MIN_AREA_HEIGHT = 2;

    /**
     * 缺口斜纹的斜线间距与线宽（像素，沿 x 方向量）
     */
    private static final int CURVE_GAP_HATCH_PERIOD = 10;

    private static final int CURVE_GAP_HATCH_WIDTH = 3;

    /**
     * 各条曲线的配色，礼物沿用主题粉（{@link #COLOR_NAME}）
     */
    private static final Color COLOR_CURVE_WATCHED = new Color(110, 199, 122);

    /**
     * 在线人数折线的配色。包内可见：像素判据要靠它认出折线而不是面积
     */
    static final Color COLOR_CURVE_ONLINE = new Color(255, 99, 132);

    /**
     * 弹幕曲线的配色。包内可见：像素判据要靠它认出「哪几列画的是面积」，
     * 而斜纹区与贴地面积区的区别正是「这一点是不是面积色」
     */
    static final Color COLOR_CURVE_DANMU = new Color(0, 174, 236);

    /**
     * 采集缺口的斜纹色与边界线色。包内可见，理由同 {@link #COLOR_CURVE_DANMU}
     * <p>
     * 刻意取中性浅灰而不取任何一条曲线的同色系：斜纹说的是「这一段没有数据」，
     * 它一旦长得像某条曲线，就会被读成那条曲线的一段取值。
     */
    static final Color COLOR_CURVE_GAP_HATCH = new Color(206, 212, 218);

    static final Color COLOR_CURVE_GAP_EDGE = new Color(168, 178, 188);

    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.of("Asia/Shanghai"));

    /**
     * 场次内的时刻只需要时分，日期由报告头部交代
     */
    private static final DateTimeFormatter CLOCK_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of("Asia/Shanghai"));

    private final NovaCommonPainterFactory factory;

    private final BilibiliApiUtil api;

    private final LiveDataService liveDataService;

    private final FontUtil fontUtil;

    private final NovaBilibiliProperties properties;

    private final LiveRoomInfoHistory roomInfoHistory;

    /**
     * 词云绘制器，首次画词云时建；带着一份字体查找缓存，别每张报告重建一个
     */
    private WordCloudRenderer cloudRenderer;

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
     * 头像不写入本机缓存，仍只留在这里。
     */
    private final Cache<String, BufferedImage> avatarCache = Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterWrite(Duration.ofHours(6))
            .build();

    /**
     * 大航海标志缓存，按图片地址计。容量与时长与头像缓存相同。
     * 取到的图另按请求地址留一份在本机，见 {@link #imageDisk}
     */
    private final Cache<String, BufferedImage> guardIconCache = Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterWrite(Duration.ofHours(6))
            .build();

    /**
     * 礼物图标缓存，按图片地址计。容量与时长与头像缓存相同，取不到就记住这次失败。
     * 取到的图另按请求地址留一份在本机，见 {@link #imageDisk}
     */
    private final Cache<String, BufferedImage> giftIconCache = Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterWrite(Duration.ofHours(6))
            .build();

    /**
     * 礼物图标与大航海标志的本机缓存。头像不进这里
     */
    private final ReportImageDiskCache imageDisk;

    /** 进程里按场留下的读数上限。场次再多也只留最近这些。 */
    private static final int KEPT_SESSION_READINGS = 32;

    /**
     * 人数卡片已经问到的总数，按这一场记。
     * 写名单时直接用这个数，不再为人数另打一次接口。
     * 下一场没有再问到人数时，不沿用这里的旧数。
     * 只留最近若干场。
     */
    private final Map<String, Integer> guardCountOnCard = new KeptSessions<>();

    /**
     * 一场词云按原文重算成功的结果。同一场、同一份名单、原文没变，只算一次。
     * 没有原文时不放进来，免得停在当时的词频。只留最近若干场。
     */
    private final Map<String, WordCloudRecount> wordCloudRecounts = new KeptSessions<>();

    /**
     * 没有原文、已经提示过的场次。同一场只提示一次，只留最近若干场。
     */
    private final Map<String, Boolean> wordCloudFallbackNoted = new KeptSessions<>();

    /**
     * 正在按原文重算的场次。只在这一次重算期间留着，算完就去掉。
     */
    private final ConcurrentHashMap<String, Object> wordCloudGates = new ConcurrentHashMap<>();

    /**
     * 没有原文可重算时记的那一行。不带观众编号和昵称。
     */
    private static final String WORD_CLOUD_FALLBACK =
            "词云没能按屏蔽名单重算, 仍按已保存的词频绘制";

    private record WordCloudRecount(long stamp, Map<String, Integer> words) {
    }

    /**
     * 按最近使用留下若干场。超出的丢掉最久没用的。
     */
    private static final class KeptSessions<V> extends LinkedHashMap<String, V> {
        private KeptSessions() {
            super(64, 0.75f, true);
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
            return size() > KEPT_SESSION_READINGS;
        }
    }

    /**
     * 底部标识图片只读一次盘，读过的结论本场与下一场共用，实现见 {@link ReportSharedStyle.Logo}
     */
    private final ReportSharedStyle.Logo logoDrawer = new ReportSharedStyle.Logo();

    private final StreamerNames names;

    /**
     * 不落盘的构造，给测试和版式预览。预览覆写了取图口，本来也不写这份缓存
     */
    public BilibiliLiveReportPainter(NovaCommonPainterFactory factory, BilibiliApiUtil api,
                                     LiveDataService liveDataService, FontUtil fontUtil,
                                     NovaBilibiliProperties properties, LiveRoomInfoHistory roomInfoHistory) {
        this(factory, api, liveDataService, fontUtil, properties, roomInfoHistory, ReportImageDiskCache.none());
    }

    /**
     * @param imageDisk 礼物图标与大航海标志的本机缓存
     */
    public BilibiliLiveReportPainter(NovaCommonPainterFactory factory, BilibiliApiUtil api,
                                     LiveDataService liveDataService, FontUtil fontUtil,
                                     NovaBilibiliProperties properties, LiveRoomInfoHistory roomInfoHistory,
                                     ReportImageDiskCache imageDisk) {
        this(factory, api, liveDataService, fontUtil, properties, roomInfoHistory, imageDisk, StreamerNames.none());
    }

    /**
     * @param imageDisk 礼物图标与大航海标志的本机缓存
     * @param names 起动时没查到昵称时，从最近一场归档里取主播名
     */
    @Autowired
    public BilibiliLiveReportPainter(NovaCommonPainterFactory factory, BilibiliApiUtil api,
                                     LiveDataService liveDataService, FontUtil fontUtil,
                                     NovaBilibiliProperties properties, LiveRoomInfoHistory roomInfoHistory,
                                     ReportImageDiskCache imageDisk, StreamerNames names) {
        this.names = names;
        this.factory = factory;
        this.api = api;
        this.liveDataService = liveDataService;
        this.fontUtil = fontUtil;
        this.properties = properties;
        this.roomInfoHistory = roomInfoHistory;
        this.imageDisk = imageDisk == null ? ReportImageDiskCache.none() : imageDisk;
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

        text.append(unameOf(platform, source)).append(" 本场直播数据");

        String duration = durationText(platform, uid);
        text.append("\n直播时长 ").append(StringUtil.isNotBlank(duration) ? duration : "未知");
        // 与图片版共用同一句：缺口这句话只能有一个出处，
        // 否则图片版与文字版迟早说出两个不同的数
        String gap = collectionGapText(platform, uid);
        if (!gap.isEmpty()) {
            text.append("（").append(gap).append("）");
        }

        // 本场有推送的图片没送到时才出现这一行，绝大多数场次是零、不占版面
        long imageDegraded = count(platform, uid, BilibiliLiveMetric.IMAGE_DEGRADED_COUNT);
        if (imageDegraded > 0) {
            text.append("\n⚠️ 本场有 ").append(imageDegraded).append(" 条推送的图片未送达（文字已送达）");
        }

        long danmu = count(platform, uid, BilibiliLiveMetric.DANMU_COUNT);
        int danmuUsers = liveDataService.getLiveMetricUserCount(platform, uid, BilibiliLiveMetric.DANMU_USERS);
        text.append("\n弹幕 ").append(danmu).append(" 条").append(danmuUsersSuffix(danmu, danmuUsers));

        long boxes = count(platform, uid, BilibiliLiveMetric.BOX_COUNT);
        long superChats = count(platform, uid, BilibiliLiveMetric.SUPER_CHAT_COUNT);
        long guards = guardPurchaseCount(platform, uid);

        // 流水行与图片版流水卡同一个数、同一句话式（文字版只保证数字送到，措辞跟着图片走）
        double revenueTotal = revenueTotal(platform, uid);
        int revenueUsers = revenueUserCount(platform, uid);
        if (options.isShowRevenue()) {
            if (revenueTotal > 0) {
                text.append("\n流水 ¥").append(yuan(revenueTotal)).append(" · ").append(revenueUsers).append(" 人");
            }
        } else if (revenueUsers > 0) {
            // 不展示金额时换一种说法，热闹程度照样看得见——与图片版同一个立场
            text.append("\n付费互动 ").append(revenueUsers).append(" 人");
        }

        if (superChats > 0) {
            text.append("\n醒目留言 ").append(superChats).append(" 条");
        }
        if (guards > 0) {
            // 与图片版大航海卡同一句式：只写「大航海」不写「开通」（数的是人次，续费也算），
            // 新开与续费对得上人次时拆开写，对不上就只写次数
            long opens = count(platform, uid, BilibiliLiveMetric.GUARD_OPEN_COUNT);
            long renews = count(platform, uid, BilibiliLiveMetric.GUARD_RENEW_COUNT);
            text.append("\n大航海 ").append(guards).append(" 次");
            if (opens + renews == guards) {
                text.append(" · 新开 ").append(opens).append(" · 续费 ").append(renews);
            }
        }
        if (boxes > 0) {
            text.append("\n盲盒 ").append(boxes).append(" 个");
        }

        appendGuardRoster(text, platform, source, options);

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
            drawOverview(painter, platform, source.getUid());
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
            if (options.isGiftList() && options.isShowRevenue()) {
                drawReceivedGifts(painter, platform, source.getUid());
            }
            drawRankings(painter, platform, source.getUid(), options);
            if (options.isGuardListAll()) {
                drawGuardRoster(painter, platform, source, options);
            }
            if (options.isDanmuCloud()) {
                drawWordCloud(painter, platform, source.getUid());
            }

            painter.movePos(0, 20);
            logoDrawer.draw(painter, properties);
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
     * 封面不可得时退化为「头像 + 昵称」的简单头部——那一版式与打赏播报图共用，
     * 见 {@link ReportSharedStyle#drawSimpleHeader}
     */
    private void drawHeader(CommonPainter painter, String platform, LiveStreamerInfo source, BilibiliLiveReportOptions options) {
        int top = painter.getY();
        BufferedImage cover = options.isCover() ? loadCover(source) : null;

        BufferedImage face = faceImage(source);

        if (cover != null) {
            painter.drawImage(cover, new Point(MARGIN, top));

            // 头像叠在封面下沿，加白色描边与封面区隔
            int avatarX = MARGIN + 28;
            int avatarY = top + COVER_HEIGHT - AVATAR_SIZE / 2;
            if (face != null) {
                drawRingedAvatar(painter, face, avatarX, avatarY);
            }

            int textX = avatarX + AVATAR_SIZE + 22;
            painter.drawSection(unameWithin(painter, unameOf(platform, source), textX), COLOR_NAME,
                    new Point(textX, top + COVER_HEIGHT + 4));
            painter.drawTip("直播报告 · " + timeRange(platform, source.getUid()), COLOR_TIP, new Point(textX, top + COVER_HEIGHT + 52));

            painter.setPos(MARGIN, top + COVER_HEIGHT + AVATAR_SIZE + 16);
            return;
        }

        ReportSharedStyle.drawSimpleHeader(painter, unameOf(platform, source), face,
                "直播报告 · " + timeRange(platform, source.getUid()));
    }

    /**
     * 取主播名并按版心剩下的宽度截断，实现见 {@link ReportSharedStyle#unameWithin}
     */
    private String unameWithin(CommonPainter painter, String uname, int textX) {
        return ReportSharedStyle.unameWithin(painter, uname, textX);
    }

    /**
     * 主播名：事件里没有时退回最近一场归档里的昵称，与控制台一致
     */
    private String unameOf(String platform, LiveStreamerInfo source) {
        return names.uname(platform, source.getUid(), source.getUname());
    }

    /**
     * 绘制概览行：直播时长与采集缺口
     * <p>
     * 收益那一截已挪进「流水」卡片：概览行写「本场赚了多少」与卡片写「流水」是同一件事的两个说法，
     * 同一张报告里出现两遍，读的人只会以为它们数的是两样东西。
     */
    private void drawOverview(CommonPainter painter, String platform, Long uid) {
        String duration = Optional.of(durationText(platform, uid)).filter(StringUtil::isNotBlank).orElse("未知");

        List<TextWithStyle> line = new ArrayList<>();
        line.add(new TextWithStyle("直播时长 ", CommonPainter.TEXT_FONT_SIZE, COLOR_TIP, Font.PLAIN));
        line.add(new TextWithStyle(duration, CommonPainter.TEXT_FONT_SIZE, COLOR_TEXT, Font.BOLD));

        // 采集缺口紧跟在时长后面，而不是塞进页脚：报告上每个数字都受它影响，
        // 看到时长的人必须同时看到「这段时间里有一截没在采」，以及那一截是怎么来的
        String gap = collectionGapText(platform, uid);
        if (!gap.isEmpty()) {
            line.add(new TextWithStyle("（" + gap + "）", CommonPainter.TEXT_FONT_SIZE, COLOR_TIP, Font.PLAIN));
        }

        // 与停机缺口同一个道理：图片没送到是主播能感知的差异，
        // 而它只在日志里留过痕。为零时整段不出现
        long imageDegraded = count(platform, uid, BilibiliLiveMetric.IMAGE_DEGRADED_COUNT);
        if (imageDegraded > 0) {
            line.add(new TextWithStyle("    ⚠ 有 " + imageDegraded + " 条推送的图片未送达",
                    CommonPainter.TEXT_FONT_SIZE, COLOR_TIP, Font.PLAIN));
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
     * 这些片段每一个都是一句完整的话（「（采集缺口 共 8 秒：维护 8 秒）」「本场收益 ¥55.3」），
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
     * 不展示金额时这些卡片不是消失，而是换一种说法：流水讲「多少人付费互动」、
     * 醒目留言讲「多少条」、盲盒讲「开了多少个」。互动的热闹程度照样看得见，
     * 只是不带走具体数额——那正是想给大群看的部分。
     */
    private void drawCards(CommonPainter painter, String platform, Long uid, BilibiliLiveReportOptions options) {
        List<Card> cards = buildCards(painter, platform, uid, options);

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
     * 本场数据卡片列表。为零的条目不入列。
     * <p>
     * 要画坊进来是因为大航海卡副行要量宽度决定舍不舍金额（见 {@link #guardPurchaseCard}）。
     */
    List<Card> buildCards(CommonPainter painter, String platform, Long uid, BilibiliLiveReportOptions options) {
        long danmu = count(platform, uid, BilibiliLiveMetric.DANMU_COUNT);
        int danmuUsers = liveDataService.getLiveMetricUserCount(platform, uid, BilibiliLiveMetric.DANMU_USERS);
        double revenueTotal = revenueTotal(platform, uid);
        int revenueUsers = revenueUserCount(platform, uid);
        long freeGift = count(platform, uid, BilibiliLiveMetric.FREE_GIFT_COUNT);
        long box = count(platform, uid, BilibiliLiveMetric.BOX_COUNT);
        double boxProfit = liveDataService.getLiveMetric(platform, uid, BilibiliLiveMetric.BOX_PROFIT);
        long superChat = count(platform, uid, BilibiliLiveMetric.SUPER_CHAT_COUNT);
        double superChatValue = liveDataService.getLiveMetric(platform, uid, BilibiliLiveMetric.SUPER_CHAT_VALUE);
        long guardPurchases = guardPurchaseCount(platform, uid);
        long follow = count(platform, uid, BilibiliLiveMetric.FOLLOW_COUNT);
        int enterUsers = liveDataService.getLiveMetricUserCount(platform, uid, BilibiliLiveMetric.ENTER_USERS);
        long likeTotal = count(platform, uid, BilibiliLiveMetric.LIKE_TOTAL);
        long share = count(platform, uid, BilibiliLiveMetric.SHARE_COUNT);

        boolean revenue = options.isShowRevenue();

        List<Card> cards = new ArrayList<>();
        // 流水放第一格：整场报告最要紧的一个数，第一眼要落在它上；隐藏金额的
        // 「付费互动」那形也是同一个位置——想给人看的热闹不因为不露金额而挪后
        if (revenueTotal > 0 || revenueUsers > 0) {
            cards.add(revenue
                    ? new Card("¥" + yuan(revenueTotal), "流水 · " + revenueUsers + " 人")
                    : new Card(revenueUsers + " 人", "付费互动"));
        }
        cards.add(new Card(String.valueOf(danmu), "弹幕" + danmuUsersSuffix(danmu, danmuUsers)));
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
        if (guardPurchases > 0) {
            cards.add(guardPurchaseCard(painter, platform, uid, guardPurchases, revenue));
        }
        if (box > 0) {
            cards.add(new Card(box + " 个",
                    revenue ? "盲盒 · " + boxText(boxProfit) : "盲盒"));
        }
        if (freeGift > 0) {
            cards.add(new Card(freeGift + " 个", "免费礼物"));
        }
        if (share > 0) {
            cards.add(new Card(share + " 次", "分享"));
        }
        return cards;
    }

    /**
     * 流水总额：礼物（主播到手价值，含背包礼物与盲盒开出物）＋醒目留言＋大航海。
     * <p>
     * 只做三张总量相加，<b>不另记一份总额</b>——两本账没有互相钉住的东西，迟早对不上。
     */
    double revenueTotal(String platform, Long uid) {
        return liveDataService.getLiveMetric(platform, uid, BilibiliLiveMetric.GIFT_VALUE)
                + liveDataService.getLiveMetric(platform, uid, BilibiliLiveMetric.SUPER_CHAT_VALUE)
                + liveDataService.getLiveMetric(platform, uid, BilibiliLiveMetric.GUARD_VALUE);
    }

    /**
     * 本场付费互动的去重人数：送过付费礼物、开过盲盒、发过醒目留言或上过舰的人
     * <p>
     * 新表（分人流水）记到就是它；升级前的场次没有这张表，回落到三张旧分表的参与者并集——
     * 人数问的是「有几个人」，并集天然去重，匿名的不在分表里，自然不计。
     */
    int revenueUserCount(String platform, Long uid) {
        int counted = liveDataService.getLiveMetricUserCount(platform, uid, BilibiliLiveMetric.REVENUE_USERS);
        if (counted > 0) {
            return counted;
        }
        Set<Long> union = new java.util.HashSet<>();
        Map<String, List<Long>> sets = liveDataService.getLiveMetricUserSets(platform, uid);
        for (String metric : List.of(BilibiliLiveMetric.GIFT_USERS,
                BilibiliLiveMetric.SUPER_CHAT_USERS, BilibiliLiveMetric.GUARD_USERS)) {
            List<Long> users = sets.get(metric);
            if (users != null) {
                union.addAll(users);
            }
        }
        return union.size();
    }

    /**
     * 上舰人次（舰长＋提督＋总督，含续费）。卡片、收到的礼物与大航海卡用的是同一个数
     */
    long guardPurchaseCount(String platform, Long uid) {
        return count(platform, uid, BilibiliLiveMetric.CAPTAIN_COUNT)
                + count(platform, uid, BilibiliLiveMetric.COMMANDER_COUNT)
                + count(platform, uid, BilibiliLiveMetric.GOVERNOR_COUNT);
    }

    /**
     * 大航海卡：写人次，分得清时随行写新开与续费。
     * <p>
     * 「三处的说法」之一：这里数的是<b>人次</b>（新开与续费都算一次），
     * 「本场变化」那张数的是<b>在舰人数</b>，全名单是<b>此刻在舰的人</b>——各说各的，互不冒充。
     * <p>
     * 副行标签只写「大航海」不写「开通」：数的是人次，写「开通」读者会把续费也读成新开。
     * 新开与续费比金额要紧——金额已在流水卡里，副行放不下时先舍金额，不许反过来把
     * 新开／续费截掉；真到拆分本身都放不下的地步，才轮到 {@code drawCard} 的兜底截断。
     * <p>
     * 量宽度要画坊，所以这一张建卡时就把取舍做完，而不是画的时候截。
     */
    Card guardPurchaseCard(CommonPainter painter, String platform, Long uid, long purchases, boolean showRevenue) {
        long opens = count(platform, uid, BilibiliLiveMetric.GUARD_OPEN_COUNT);
        long renews = count(platform, uid, BilibiliLiveMetric.GUARD_RENEW_COUNT);
        // 新开＋续费对不上人次的那一场分不清，只写次数：拆开写就是把「不知道」说成了知道
        String breakdown = opens + renews == purchases
                ? " · 新开 " + opens + " · 续费 " + renews : "";
        if (showRevenue) {
            double guardValue = liveDataService.getLiveMetric(platform, uid, BilibiliLiveMetric.GUARD_VALUE);
            String withAmount = "大航海 · ¥" + yuan(guardValue);
            if (breakdown.isEmpty() || cardLabelFits(painter, withAmount + breakdown)) {
                return new Card("+" + purchases + " 次", withAmount + breakdown);
            }
        }
        return new Card("+" + purchases + " 次", "大航海" + breakdown);
    }

    /**
     * 一句卡片副行文案放不放得下：按副行同款字号量，宽度是卡片内宽减两侧留白
     */
    private boolean cardLabelFits(CommonPainter painter, String label) {
        int usable = CARD_WIDTH - CARD_TEXT_INSET * 2;
        return painter.getStringWidthAndHeight(new TextWithStyle(label, 22, COLOR_TIP, Font.PLAIN)).getFirst() <= usable;
    }

    /**
     * 盲盒盈亏文案：正盈利、负亏损、零持平（不带金额）。
     */
    private String boxText(double boxProfit) {
        if (boxProfit > 0) {
            return "盈利 ¥" + yuan(boxProfit);
        }
        if (boxProfit < 0) {
            return "亏损 ¥" + yuan(Math.abs(boxProfit));
        }
        return "持平";
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
     * 绘制粉丝、粉丝团与大航海人数的本场变化
     * <p>
     * 这三项都不在弹幕流里，只能问接口。开播时的快照由
     * {@code BilibiliRoomStatsSnapshotter} 记下，这里取一次实时值相减即得涨幅——
     * 于是直播中随时拉的实时报告与下播报告走的是同一段逻辑。
     * <p>
     * 三项各自独立降级：接口挂了或没有开播快照，就只跳过那一项。
     * <p>
     * 同一张报告里有三处带「大航海」字样，各数各的、说法已分开：
     * 数据卡与收到的礼物数<b>人次</b>（写「N 次」「×N」），这里数<b>此刻在舰人数</b>
     * （写「在舰 X 人 · 较开播 ±Y」），全名单列的是<b>此刻在舰的人</b>——
     * 名字撞在一起时，读的人靠说法就知道各数的是什么。
     */
    private void drawFansChange(CommonPainter painter, String platform, LiveStreamerInfo source) {
        List<Card> cards = new ArrayList<>();

        fansCount(source.getUid()).ifPresent(fans ->
                cards.add(changeCard(platform, source.getUid(), fans, BilibiliLiveMetric.FANS_AT_START, "粉丝")));
        fansMedalCount(source.getUid()).ifPresent(medal ->
                cards.add(changeCard(platform, source.getUid(), medal, BilibiliLiveMetric.FANS_MEDAL_AT_START, "粉丝团")));
        if (source.getRoomId() != null) {
            guardCount(source.getRoomId(), source.getUid()).ifPresent(guard ->
                    cards.add(guardChangeCard(platform, source.getUid(), guard)));
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
    Card changeCard(String platform, Long uid, long current, String startMetric, String label) {
        double start = liveDataService.getLiveMetric(platform, uid, startMetric);
        if (start <= 0) {
            return new Card(String.valueOf(current), label);
        }

        long delta = current - Math.round(start);
        return new Card(String.valueOf(current), label + " · 本场 " + deltaLabel(delta));
    }

    /**
     * 大航海那张变化卡片：写「在舰 X 人」，涨幅写作「较开播 ±Y」
     * <p>
     * 与另两张变化卡（粉丝、粉丝团）分开写：那两个数的是<b>存量</b>，「本场 +N」是自然说法；
     * 这里数的是<b>此刻在舰的人数</b>，「在舰」两个字把存量说出来，涨幅对着「开播那一刻」，
     * 与数据卡那个<b>人次</b>（「+N 次」）从说法上就分得开。
     */
    Card guardChangeCard(String platform, Long uid, long current) {
        double start = liveDataService.getLiveMetric(platform, uid, BilibiliLiveMetric.GUARD_AT_START);
        if (start <= 0) {
            return new Card("在舰 " + current + " 人", "大航海");
        }

        long delta = current - Math.round(start);
        return new Card("在舰 " + current + " 人", "大航海 · 较开播 " + deltaLabel(delta));
    }

    /**
     * 本场净变化：正数带加号、负数自带减号、零写持平。
     */
    static String deltaLabel(long delta) {
        if (delta > 0) {
            return "+" + delta;
        }
        if (delta < 0) {
            return String.valueOf(delta);
        }
        return "持平";
    }

    /**
     * 绘制互动曲线
     * <p>
     * 每项指标一条独立的图，各自按自身峰值缩放。在线人数走折线，其余走面积。
     * <b>刻意不把它们叠在同一张图上</b>：弹幕以「条」计、流水以「元」计，量级动辄差两个数量级，
     * 共用纵轴的结果是除了最大的那条以外全部压成一条直线。
     */
    private void drawCurves(CommonPainter painter, String platform, Long uid, BilibiliLiveReportOptions options) {
        Optional<Long> start = liveDataService.getLiveStartTime(platform, uid);
        Optional<Long> end = effectiveEndTime(platform, uid, start);
        if (start.isEmpty() || end.isEmpty() || end.get() <= start.get()) {
            return;
        }

        List<Curve> curves = buildCurves(platform, uid, options);

        // 缺口表整段算一次：各条曲线共用同一条时间轴，缺口落在哪几列对它们是同一个答案
        List<LiveGap> gaps = collectionGaps(platform, uid, start.get(), end.get());

        boolean first = true;
        for (Curve curve : curves) {
            // 流水那条的时序是合成出来的（三张金额时序逐分钟相加），buildCurves 已连曲线一起给
            Map<Long, Double> series = curve.series != null
                    ? curve.series
                    : liveDataService.getLiveSeries(platform, uid, curve.metric);
            if (series.isEmpty()) {
                continue;
            }

            if (first) {
                painter.movePos(0, 10);
                painter.drawTextWithStyle(List.of(new TextWithStyle("互动曲线", CommonPainter.TEXT_FONT_SIZE, COLOR_TIP, Font.PLAIN)));
                painter.movePos(0, 6);
                first = false;
            }
            drawCurve(painter, curve, series, start.get(), end.get(), gaps);
        }

        if (!first) {
            painter.movePos(0, 8);
        }
    }

    /**
     * 曲线清单：弹幕、流水、看过、在线四条
     * <p>
     * 原先的礼物、醒目留言、盲盒、大航海四条金额曲线并成一条「流水」——每分钟三张金额时序相加，
     * 单色、不分成分、不另打标记；盲盒的钱记在礼物那张时序里（按开出物价值），不必另加。
     * 弹幕、看过、在线三条不动。
     * <p>
     * 包内可见：曲线的构成（几条、各叫什么、峰值怎么标）是版式判据要量的东西，
     * 从成品图上反推不出「峰值文案里有没有金额」。
     */
    List<Curve> buildCurves(String platform, Long uid, BilibiliLiveReportOptions options) {
        // 不展示金额时，流水曲线保留形状但不标峰值。
        // 面积图按自身峰值归一化，画出来的是「什么时候热闹」，本身不含任何绝对数值——
        // 这恰好是最适合给大群看的东西，整条删掉反而丢了氛围
        DoubleFunction<String> money = options.isShowRevenue() ? peak -> "¥" + yuan(peak) + "/分" : null;

        List<Curve> curves = new ArrayList<>();
        curves.add(new Curve("弹幕", BilibiliLiveMetric.DANMU_COUNT, COLOR_CURVE_DANMU,
                peak -> Math.round(peak) + " 条/分"));
        curves.add(new Curve("流水", null, COLOR_NAME, money, revenueSeries(platform, uid)));
        // 看过人数是累计值，画出来是一条只升不降的线——它的**斜率**才是「什么时候在涨人」。
        // 峰值标的是本场最终看过多少人，因此文案是「人看过」而不是「人/分」
        curves.add(new Curve("看过人数", BilibiliLiveMetric.WATCHED_COUNT, COLOR_CURVE_WATCHED,
                peak -> Math.round(peak) + " 人看过"));
        curves.add(new Curve("在线人数", BilibiliLiveMetric.ONLINE_COUNT, COLOR_CURVE_ONLINE,
                peak -> Math.round(peak) + " 人", true, "登录观众数，哔哩哔哩高能榜口径"));
        return curves;
    }

    /**
     * 流水的逐分钟时序：礼物＋醒目留言＋大航海三张金额时序按同一分钟相加
     * <p>
     * 包内可见：每分钟之和等于三张总量相加的那件事，判据要直接量这张合成表，
     * 从画出来的面积图上量不出「这一格是几块钱」。
     */
    Map<Long, Double> revenueSeries(String platform, Long uid) {
        Map<Long, Double> merged = new LinkedHashMap<>();
        for (String metric : List.of(BilibiliLiveMetric.GIFT_VALUE,
                BilibiliLiveMetric.SUPER_CHAT_VALUE, BilibiliLiveMetric.GUARD_VALUE)) {
            liveDataService.getLiveSeries(platform, uid, metric)
                    .forEach((at, value) -> merged.merge(at, value, Double::sum));
        }
        return merged;
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

        // 判据（几个、隔多远、几倍、最少几条）取自核心的那一份默认值，此处不另抄一套：
        // 明细留档里的高能时刻走的是同一组数，两边各写一份就会挑出两组不同的时刻
        List<LiveHighlightFinder.Highlight> highlights = LiveHighlightFinder.find(
                liveDataService.getLiveSeries(platform, uid, BilibiliLiveMetric.DANMU_COUNT),
                LiveDataService.SERIES_BUCKET_MILLIS, start.get(), end.get());
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
     * <p>
     * 落在缺口里的那几列<b>不画面积改画斜纹</b>：那几分钟的数字不是 0，是没有——
     * 画成贴地的面积等于替它答了「没人来」，而真相可能是那会儿最热闹。
     * @param gaps 本场的采集缺口，已裁剪到 {@code [start, end)} 之内
     */
    private void drawCurve(CommonPainter painter, Curve curve, Map<Long, Double> series,
                           long start, long end, List<LiveGap> gaps) {
        int top = painter.getY();

        int columns = Math.max(1, CONTENT_WIDTH / CURVE_COLUMN_WIDTH);
        int buckets = bucketCount(start, end);
        double[] values = curveColumnValues(curve.metric(), series, start, end, columns, gaps);
        boolean[] missing = gapColumns(gaps, start, buckets, columns);
        boolean[] sampled = sampledColumns(series, start, end, columns);

        double peak = 0;
        for (int i = 0; i < columns; i++) {
            // 峰值只认真样本：缺口里的列是没采到的一段，补出来的列是估的——
            // 拿它们去定纵轴，等于让没观测到的时间决定别处的高度
            if (sampled[i] && !missing[i]) {
                peak = Math.max(peak, Math.abs(values[i]));
            }
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

        int heading = 34;
        if (curve.caption() != null && !curve.caption().isBlank()) {
            painter.drawTextWithStyle(
                    List.of(new TextWithStyle(curve.caption(), 20, COLOR_TIP, Font.PLAIN)),
                    new Point(MARGIN, top + 28));
            heading = 52;
        }

        int chartTop = top + heading;
        int baseline = chartTop + CURVE_HEIGHT;

        // 按「有没有采到」把列切成一段一段：采到的画面积或折线，没采到的画斜纹
        int from = 0;
        while (from < columns) {
            int to = from;
            while (to + 1 < columns && missing[to + 1] == missing[from]) {
                to++;
            }
            if (missing[from]) {
                drawGapHatch(painter, columnX(from), columnX(to + 1), chartTop, baseline);
            } else if (curve.polyline()) {
                drawLineRun(painter, values, peak, from, to, baseline, curve.color);
            } else {
                drawAreaRun(painter, values, peak, from, to, baseline, curve.color);
            }
            from = to + 1;
        }

        // 基线压在面积下沿，给曲线一个明确的落脚点
        painter.drawRectangle(MARGIN, baseline, CONTENT_WIDTH, 2, COLOR_CARD);
        painter.setPos(MARGIN, baseline + 16);
    }

    /**
     * 第 column 列左边缘的 x
     */
    private static int columnX(int column) {
        return MARGIN + column * CURVE_COLUMN_WIDTH;
    }

    /**
     * 画一段面积：从第 from 列到第 to 列（含），下沿落在基线上
     */
    private void drawAreaRun(CommonPainter painter, double[] values, double peak,
                             int from, int to, int baseline, Color color) {
        if (from == to) {
            // 只剩一列时多边形退化成一条没有宽度的线，什么都画不出来，改用矩形
            int height = areaHeight(values[from], peak);
            painter.drawRectangle(columnX(from), baseline - height, CURVE_COLUMN_WIDTH, height, color);
            return;
        }

        // 面积多边形：左下角起，沿曲线走一遍，回到右下角闭合
        List<Point> area = new ArrayList<>(to - from + 3);
        area.add(new Point(columnX(from), baseline));
        for (int i = from; i <= to; i++) {
            area.add(new Point(columnX(i), baseline - areaHeight(values[i], peak)));
        }
        area.add(new Point(columnX(to), baseline));
        painter.drawPolygon(area, color);
    }

    /**
     * 画一段折线：从第 from 列到第 to 列（含），不填充
     * <p>
     * 缺口段仍走斜纹，与面积曲线同一套「没采到」的说法。
     */
    private void drawLineRun(CommonPainter painter, double[] values, double peak,
                             int from, int to, int baseline, Color color) {
        List<Point> points = new ArrayList<>(Math.max(2, to - from + 1));
        for (int i = from; i <= to; i++) {
            points.add(new Point(columnX(i), baseline - areaHeight(values[i], peak)));
        }
        if (points.size() == 1) {
            int x = points.get(0).x;
            int y = points.get(0).y;
            painter.drawPolyline(List.of(new Point(x, y), new Point(x + CURVE_COLUMN_WIDTH, y)),
                    color, CURVE_LINE_STROKE);
            return;
        }
        painter.drawPolyline(points, color, CURVE_LINE_STROKE);
    }

    /**
     * 某一列的面积高度，至少留 {@link #CURVE_MIN_AREA_HEIGHT} 像素
     * <p>
     * 取值为 0 的那几列本来一个像素都不画，于是「没人说话」在图上是一片空白，
     * 与画着斜纹的缺口段<b>并排放着也分得出，单看却分不出</b>。留一条贴地的细面积，
     * 「采集在，只是没互动」这句话才有个看得见的说法。
     */
    private static int areaHeight(double value, double peak) {
        return Math.max(CURVE_MIN_AREA_HEIGHT, (int) Math.round(CURVE_HEIGHT * Math.abs(value) / peak));
    }

    /**
     * 把一段缺口画成 45° 浅色斜纹，两侧各压一条细边界线
     * <p>
     * 用斜纹而不是灰底：灰底像一个取值为某个高度的区块，斜纹是公认的「此处无数据」。
     * 斜线按<b>整幅图的坐标</b>起线而不是从本段左端起线——相邻两段缺口的斜线因此是同一套，
     * 不会因为段的宽窄不同而各排各的。
     * @param left 左边缘 x（含）
     * @param right 右边缘 x（不含）
     */
    private void drawGapHatch(CommonPainter painter, int left, int right, int top, int bottom) {
        int height = bottom - top;
        if (right - left <= 0 || height <= 0) {
            return;
        }

        // 斜线自左下向右上，写成「x + y = 常数」的一族；常数按整幅图取网格
        int firstLine = Math.floorDiv(left + top, CURVE_GAP_HATCH_PERIOD) * CURVE_GAP_HATCH_PERIOD;
        for (int c = firstLine; c <= right + bottom; c += CURVE_GAP_HATCH_PERIOD) {
            List<Point> stripe = List.of(
                    new Point(c - top, top),
                    new Point(c - top + CURVE_GAP_HATCH_WIDTH, top),
                    new Point(c - bottom + CURVE_GAP_HATCH_WIDTH, bottom),
                    new Point(c - bottom, bottom));
            List<Point> clipped = clipToColumns(stripe, left, right);
            if (clipped.size() >= 3) {
                painter.drawPolygon(clipped, COLOR_CURVE_GAP_HATCH);
            }
        }

        // 边界线最后画，压在斜纹上：缺口起止于何时，比斜纹本身更该看得清
        painter.drawRectangle(left, top, 1, height, COLOR_CURVE_GAP_EDGE);
        painter.drawRectangle(right - 1, top, 1, height, COLOR_CURVE_GAP_EDGE);
    }

    /**
     * 把一个凸多边形裁到 {@code [left, right)} 这条竖直带子里
     * <p>
     * 斜纹得停在缺口段的边上，而绘图器只会整个填多边形、不认裁剪区，
     * 所以裁剪自己算。只需裁两条竖边，逐边取交点即可。
     */
    private static List<Point> clipToColumns(List<Point> polygon, int left, int right) {
        List<Point> afterLeft = clipToHalfPlane(polygon, left, true);
        return clipToHalfPlane(afterLeft, right - 1, false);
    }

    /**
     * 把多边形裁到某条竖直半平面内
     * @param bound 边界 x
     * @param keepRight true 保留 {@code x >= bound} 的一侧，false 保留 {@code x <= bound} 的一侧
     */
    private static List<Point> clipToHalfPlane(List<Point> polygon, int bound, boolean keepRight) {
        List<Point> result = new ArrayList<>(polygon.size() + 2);
        for (int i = 0; i < polygon.size(); i++) {
            Point current = polygon.get(i);
            Point previous = polygon.get((i + polygon.size() - 1) % polygon.size());
            boolean currentIn = keepRight ? current.x >= bound : current.x <= bound;
            boolean previousIn = keepRight ? previous.x >= bound : previous.x <= bound;

            if (currentIn != previousIn) {
                // 两点跨过边界，取边界上的交点。斜线是 45°，y 随 x 等量变化
                int dx = current.x - previous.x;
                int dy = current.y - previous.y;
                int y = dx == 0 ? previous.y : previous.y + Math.round((float) dy * (bound - previous.x) / dx);
                result.add(new Point(bound, y));
            }
            if (currentIn) {
                result.add(current);
            }
        }
        return result;
    }

    /**
     * 开播时刻所在的那个绝对分钟格的起点
     * <p>
     * 采集端（{@code DefaultLiveDataService#incrementLiveSeries}）把每分钟的数据记在
     * <b>绝对分钟格</b>上——12:00:00 到 12:00:59 的任何一次采样都落在键 12:00:00；
     * 高能时刻（{@link LiveHighlightFinder}）读的也是同一套键。曲线这边若从开播时刻
     * 本身起算自己的格，开播落在半分上时（如 12:00:30）采下来的第一格对不上原点被丢弃、
     * 之后每一格都错一位。下面三处用格的入口（数格、取值、缺口落列）都从这里取原点，
     * 与采集端<b>同一个对齐法</b>。
     */
    private static long gridStart(long start) {
        return start / LiveDataService.SERIES_BUCKET_MILLIS * LiveDataService.SERIES_BUCKET_MILLIS;
    }

    /**
     * 本场时间轴分成多少个时间格
     * <p>
     * 原点取 {@link #gridStart}：格数数的是「从开播那一分钟到下播的那一分钟」
     * 共几个绝对分钟格，而不是「从开播时刻起每满一分钟一格」。
     */
    static int bucketCount(long start, long end) {
        return (int) Math.max(1, (end - gridStart(start)) / LiveDataService.SERIES_BUCKET_MILLIS + 1);
    }

    /**
     * 第 column 列覆盖第几到第几个时间格（左闭右开）
     * <p>
     * 分列法只写这一处：重采样按它取值，缺口按它落列，两边错开一格就会出现
     * 「斜纹压着有数据的那一列」或者「缺口的边上漏出一截面积」。
     */
    private static int[] bucketRange(int column, int buckets, int columns) {
        int from = (int) ((long) column * buckets / columns);
        int to = (int) Math.max(from + 1L, (long) (column + 1) * buckets / columns);
        return new int[]{from, Math.min(to, buckets)};
    }

    /**
     * 逐列判断这一列是不是落在缺口里
     * <p>
     * 只要这一列覆盖的时段与缺口<b>有一点交集</b>就算缺口列。宁可多标一列，
     * 也不让一段真实的缺口因为不足一列宽而在图上整个消失——
     * 「缺口如实标注」的方向是让它看得见，不是让它凑整。
     */
    static boolean[] gapColumns(List<LiveGap> gaps, long start, int buckets, int columns) {
        boolean[] missing = new boolean[columns];
        if (gaps.isEmpty()) {
            return missing;
        }

        long origin = gridStart(start);
        for (int i = 0; i < columns; i++) {
            int[] range = bucketRange(i, buckets, columns);
            long from = origin + range[0] * LiveDataService.SERIES_BUCKET_MILLIS;
            long to = origin + range[1] * LiveDataService.SERIES_BUCKET_MILLIS;
            for (LiveGap gap : gaps) {
                if (gap.from() < to && gap.to() > from) {
                    missing[i] = true;
                    break;
                }
            }
        }
        return missing;
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
     * <p>
     * 序列的键是采集端记的<b>绝对分钟格</b>，认列按 {@link #gridStart} 的原点：
     * 开播落在半分上时（如 12:00:30），开播那一分钟（键 12:00:00）仍是第 0 格。
     */
    static double[] resample(Map<Long, Double> series, long start, long end, int columns) {
        long origin = gridStart(start);
        int buckets = bucketCount(start, end);
        double[] dense = new double[buckets];
        for (Map.Entry<Long, Double> entry : series.entrySet()) {
            // 落在直播区间之外的格直接丢弃：时钟回拨或上一场残留都可能造成
            long offset = entry.getKey() - origin;
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
            int[] range = bucketRange(i, buckets, columns);
            for (int j = range[0]; j < range[1]; j++) {
                values[i] = Math.max(values[i], Math.abs(dense[j]));
            }
        }
        return values;
    }

    /**
     * 逐列判断这一列覆盖的时间格里有没有真的采到样本
     * <p>
     * 「有没有样本」看的是键在不在，不是取值是不是 0：瞬时量真采到 0 也是样本，
     * 补值时那一列要当锚点用，不能跟「没推过来」混为一谈。
     */
    static boolean[] sampledColumns(Map<Long, Double> series, long start, long end, int columns) {
        long origin = gridStart(start);
        int buckets = bucketCount(start, end);
        boolean[] dense = new boolean[buckets];
        for (Long key : series.keySet()) {
            long offset = key - origin;
            if (offset < 0) {
                continue;
            }
            int index = (int) (offset / LiveDataService.SERIES_BUCKET_MILLIS);
            if (index < buckets) {
                dense[index] = true;
            }
        }

        boolean[] sampled = new boolean[columns];
        for (int i = 0; i < columns; i++) {
            int[] range = bucketRange(i, buckets, columns);
            for (int j = range[0]; j < range[1]; j++) {
                if (dense[j]) {
                    sampled[i] = true;
                    break;
                }
            }
        }
        return sampled;
    }

    /**
     * 按曲线指标出各列取值，含缺样本补值
     * <p>
     * 「哪条曲线开补值」由 {@link #fillsMissingSamples} 决定，调用方只给指标名。
     */
    static double[] curveColumnValues(String metric, Map<Long, Double> series,
                                      long start, long end, int columns, List<LiveGap> gaps) {
        int buckets = bucketCount(start, end);
        double[] values = resample(series, start, end, columns);
        boolean[] missing = gapColumns(gaps, start, buckets, columns);
        boolean[] sampled = sampledColumns(series, start, end, columns);
        fillMissingSamples(values, sampled, missing, fillsMissingSamples(metric));
        return values;
    }

    /**
     * 这条曲线要不要给没采到的列补值
     * <p>
     * 瞬时量（在线人数）与「推一条记一条」的累计量（看过人数）没推的那一分钟
     * 不是 0，画成 0 会让曲线跌到地板上。按分钟累加的量（弹幕、礼物等）
     * 没消息就是真 0，不补。
     */
    static boolean fillsMissingSamples(String metric) {
        return BilibiliLiveMetric.WATCHED_COUNT.equals(metric)
                || BilibiliLiveMetric.ONLINE_COUNT.equals(metric);
    }

    /**
     * 把没采到样本的列补上，只给瞬时量曲线用
     * <p>
     * 累加量（弹幕、礼物等）没消息就是真 0，补值会把冷场画热闹，因此
     * {@code instantaneous} 为 false 时原样不动。瞬时量（在线人数、看过人数）反过来：
     * 没推送的那一分钟不是 0 人，画成 0 会让折线跌到地板上。
     * <p>
     * 补值只在同一段连续采集里做，<b>缺口里的列不碰</b>——那一段是真没采到，
     * 左右两边可能隔了一次下播，拿它们插值等于编一个不存在的人数。
     * 同一段里第一个样本之前、最后一个样本之后的列取最近那个样本的值：
     * 边上没有另一侧的锚点，外推比守恒更容易编出离谱的数。
     *
     * @param values 各列取值，就地改写
     * @param sampled 各列有没有真样本
     * @param missing 各列是不是落在采集缺口里
     * @param instantaneous true 时补值，false 时原样不动
     */
    static void fillMissingSamples(double[] values, boolean[] sampled, boolean[] missing, boolean instantaneous) {
        if (!instantaneous) {
            return;
        }
        int columns = values.length;
        int from = 0;
        while (from < columns) {
            if (missing[from]) {
                from++;
                continue;
            }
            int to = from;
            while (to + 1 < columns && !missing[to + 1]) {
                to++;
            }
            fillRun(values, sampled, from, to);
            from = to + 1;
        }
    }

    /**
     * 补一段连续非缺口的列
     * <p>
     * 段里一个样本都没有时整段留 0：缺口两边可能隔了一次下播，
     * 隔着已知缺口编数比画成 0 更糟。
     */
    private static void fillRun(double[] values, boolean[] sampled, int from, int to) {
        int first = -1;
        int last = -1;
        for (int i = from; i <= to; i++) {
            if (sampled[i]) {
                if (first < 0) {
                    first = i;
                }
                last = i;
            }
        }
        if (first < 0) {
            return;
        }
        for (int i = from; i < first; i++) {
            values[i] = values[first];
        }
        int left = first;
        while (left < last) {
            int right = left + 1;
            while (right <= last && !sampled[right]) {
                right++;
            }
            for (int i = left + 1; i < right; i++) {
                double t = (double) (i - left) / (right - left);
                values[i] = values[left] + t * (values[right] - values[left]);
            }
            left = right;
        }
        for (int i = last + 1; i <= to; i++) {
            values[i] = values[last];
        }
    }

    /**
     * 礼物名与「×个数」的字号
     */
    private static final int GIFT_NAME_SIZE = 22;

    private static final int GIFT_COUNT_SIZE = 20;

    /**
     * 同一排里相邻两种礼物的间距
     */
    private static final int GIFT_CELL_GAP = 12;

    /**
     * 下载礼物图标时按最大一档的边长取，画的时候再缩到该档的尺寸
     */
    private static final int GIFT_ICON_FETCH = 96;

    /**
     * 绘制「收到的礼物」。没有礼物也没有大航海时整段不画。
     * <p>
     * 最上面单独放大航海（总督、提督、舰长，只放本场有人开的那几档）：
     * 不论单价，一律排在所有礼物前面——这不是「谁送得贵」的排序，是「身份在礼物之上」的排序。
     */
    private void drawReceivedGifts(CommonPainter painter, String platform, Long uid) {
        List<GuardTierCell> tiers = guardTierCells(platform, uid);
        List<ReceivedGiftLayout.Line> lines = ReceivedGiftLayout.layout(liveDataService.getLiveGifts(platform, uid));
        if (tiers.isEmpty() && lines.isEmpty()) {
            return;
        }

        painter.movePos(0, 10);
        painter.drawTextWithStyle(List.of(
                new TextWithStyle("收到的礼物", CommonPainter.TEXT_FONT_SIZE, COLOR_TIP, Font.PLAIN)));
        painter.movePos(0, 8);

        int y = painter.getY();
        if (!tiers.isEmpty()) {
            y = drawGuardTierRow(painter, tiers, y);
        }
        for (ReceivedGiftLayout.Line line : lines) {
            if (line instanceof ReceivedGiftLayout.OverflowLine overflow) {
                painter.setPos(MARGIN, y);
                painter.drawTextWithStyle(List.of(
                        new TextWithStyle(overflow.text(), CommonPainter.TEXT_FONT_SIZE, COLOR_TIP, Font.PLAIN)));
                y = painter.getY();
                continue;
            }
            y = drawGiftRow(painter, (ReceivedGiftLayout.GiftRow) line, y);
        }
        painter.setPos(MARGIN, y + 8);
    }

    /**
     * 本场有人开的那几档大航海，按总督、提督、舰长的次序
     * <p>
     * 次数与大航海卡、本场开通名单是同一个数（舰长／提督／总督三个人次之和按档拆开）。
     */
    private List<GuardTierCell> guardTierCells(String platform, Long uid) {
        List<GuardTierCell> cells = new ArrayList<>();
        long governor = count(platform, uid, BilibiliLiveMetric.GOVERNOR_COUNT);
        long commander = count(platform, uid, BilibiliLiveMetric.COMMANDER_COUNT);
        long captain = count(platform, uid, BilibiliLiveMetric.CAPTAIN_COUNT);
        if (governor > 0) {
            cells.add(new GuardTierCell(1, "总督", governor));
        }
        if (commander > 0) {
            cells.add(new GuardTierCell(2, "提督", commander));
        }
        if (captain > 0) {
            cells.add(new GuardTierCell(3, "舰长", captain));
        }
        return cells;
    }

    /**
     * 画一排大航海格，返回下一排的起始 y。不满一行时整排在版心里居中（与礼物不满行同一条规矩）
     */
    private int drawGuardTierRow(CommonPainter painter, List<GuardTierCell> tiers, int y) {
        int columns = Math.max(tiers.size(), 1);
        int cellWidth = (CONTENT_WIDTH - GIFT_CELL_GAP * (columns - 1)) / columns;
        int nameHeight = painter.getStringWidthAndHeight(
                new TextWithStyle("礼物", GIFT_NAME_SIZE, COLOR_TEXT, Font.PLAIN)).getSecond();
        int countHeight = painter.getStringWidthAndHeight(
                new TextWithStyle("×1", GIFT_COUNT_SIZE, COLOR_TIP, Font.PLAIN)).getSecond();
        int x = MARGIN;
        if (tiers.size() < columns) {
            x += (CONTENT_WIDTH - cellWidth * tiers.size() - GIFT_CELL_GAP * (tiers.size() - 1)) / 2;
        }
        for (GuardTierCell tier : tiers) {
            String url = defaultGuardIconUrl(tier.level());
            BufferedImage icon = url == null ? null : guardIcon(url, GUARD_GIFT_ICON_SIZE);
            if (icon == null) {
                icon = solidCircle(guardColor(tier.level()), GUARD_GIFT_ICON_SIZE);
            }
            drawIconCell(painter, x, y, cellWidth, GUARD_GIFT_ICON_SIZE, icon,
                    tier.name(), "×" + tier.count(), nameHeight, countHeight);
            x += cellWidth + GIFT_CELL_GAP;
        }
        return y + GUARD_GIFT_ICON_SIZE + 6 + nameHeight + 2 + countHeight + 16;
    }

    /**
     * 画一格：图标居中在上，名字与「×个数」居中在下。礼物格与大航海格共用，
     * 两者的差别只在图标从哪来、长什么形状
     */
    private void drawIconCell(CommonPainter painter, int x, int y, int cellWidth, int iconSize,
                              BufferedImage icon, String rawName, String countText,
                              int nameHeight, int countHeight) {
        painter.drawImage(icon, new Point(x + Math.max(0, (cellWidth - iconSize) / 2), y));

        String name = painter.truncateToWidth(
                new TextWithStyle(rawName == null ? "" : rawName, GIFT_NAME_SIZE, COLOR_TEXT, Font.PLAIN),
                Math.max(1, cellWidth - 4));
        int nameWidth = painter.getStringWidthAndHeight(
                new TextWithStyle(name, GIFT_NAME_SIZE, COLOR_TEXT, Font.PLAIN)).getFirst();
        int nameY = y + iconSize + 6;
        painter.drawTextWithStyle(List.of(new TextWithStyle(name, GIFT_NAME_SIZE, COLOR_TEXT, Font.PLAIN)),
                new Point(x + Math.max(0, (cellWidth - nameWidth) / 2), nameY));

        int countWidth = painter.getStringWidthAndHeight(
                new TextWithStyle(countText, GIFT_COUNT_SIZE, COLOR_TIP, Font.PLAIN)).getFirst();
        painter.drawTextWithStyle(List.of(new TextWithStyle(countText, GIFT_COUNT_SIZE, COLOR_TIP, Font.PLAIN)),
                new Point(x + Math.max(0, (cellWidth - countWidth) / 2), nameY + nameHeight + 2));
    }

    /**
     * 收到的礼物里大航海格的图标边长。与礼物最高档同档（96px）：
     * 名单用的 32px 源图放大到这个尺寸会糊，按这个宽度另取一张清楚的
     */
    private static final int GUARD_GIFT_ICON_SIZE = 96;

    /**
     * 一档大航海格：档位、名字与本次数
     */
    private record GuardTierCell(int level, String name, long count) {
    }

    /**
     * 画一排礼物，返回下一排的起始 y
     * <p>
     * 满行照旧从左边距起逐格往右摆。不满一行时整排在版心里居中：各档格宽不同，
     * 靠左排会让头一个图标的中线一档比一档往左移，排成逐行缩小的台阶。
     */
    private int drawGiftRow(CommonPainter painter, ReceivedGiftLayout.GiftRow row, int y) {
        int iconSize = row.iconSize();
        int cellWidth = (CONTENT_WIDTH - GIFT_CELL_GAP * (row.columns() - 1)) / row.columns();
        int nameHeight = painter.getStringWidthAndHeight(
                new TextWithStyle("礼物", GIFT_NAME_SIZE, COLOR_TEXT, Font.PLAIN)).getSecond();
        int countHeight = painter.getStringWidthAndHeight(
                new TextWithStyle("×1", GIFT_COUNT_SIZE, COLOR_TIP, Font.PLAIN)).getSecond();
        int shown = row.gifts().size();
        int x = MARGIN;
        if (shown > 0 && shown < row.columns()) {
            x += (CONTENT_WIDTH - cellWidth * shown - GIFT_CELL_GAP * (shown - 1)) / 2;
        }
        for (LiveGiftTotal gift : row.gifts()) {
            BufferedImage icon = giftPicture(gift, iconSize);
            drawIconCell(painter, x, y, cellWidth, iconSize, icon,
                    gift.name(), "×" + gift.count(), nameHeight, countHeight);
            x += cellWidth + GIFT_CELL_GAP;
        }
        return y + iconSize + 6 + nameHeight + 2 + countHeight + 16;
    }

    /**
     * 礼物图标。取不到时画圆角方块，里面是礼物名的第一个字。
     */
    private BufferedImage giftPicture(LiveGiftTotal gift, int size) {
        BufferedImage fetched = giftIcon(gift.url());
        if (fetched == null) {
            return giftPlaceholder(gift.name(), size);
        }
        BufferedImage scaled = ImageUtil.resize(fetched, size, size);
        return ImageUtil.maskToRoundedRectangle(scaled, Math.max(8, size / 5));
    }

    /**
     * 取礼物图标。先查内存，再查本机，都没有才按实际请求的地址去取。
     * 取到后写入本机；取失败只记在内存里，不写本机。地址空、或这一次没取到，返回 null。
     * <p>
     * 预览与历史重画覆写这一口。预览始终画占位；重画只读本机，不向外取。
     */
    protected BufferedImage giftIcon(String url) {
        if (StringUtil.isBlank(url)) {
            return null;
        }
        BufferedImage cached = giftIconCache.get(url, key -> loadIcon(giftFetchUrl(key), GIFT_ICON_FETCH, false));
        return cached == FAILED_AVATAR ? null : cached;
    }

    /**
     * 礼物图标实际请求的地址，含缩放后缀。本机缓存按这个地址当键
     */
    protected String giftFetchUrl(String url) {
        return atSize(url, GIFT_ICON_FETCH);
    }

    /**
     * 大航海标志实际请求的地址，含缩放后缀
     */
    protected String guardFetchUrl(String url) {
        return guardFetchUrl(url, GUARD_ICON_SIZE);
    }

    /**
     * 大航海标志按指定宽度请求的地址。不同宽度是不同地址，本机缓存也各存各的
     */
    protected String guardFetchUrl(String url, int size) {
        return atSize(url, size);
    }

    /**
     * 本机上有这张图就返回它，没有返回 null
     */
    protected BufferedImage readDiskImage(String requestUrl) {
        return imageDisk.read(requestUrl).orElse(null);
    }

    /**
     * 先查本机，没有再去取。取到的按绘制尺寸缩好再写入本机；要圆形的再切一刀。
     * 取失败返回哨兵，调用方据此不再重试，且不会把这次失败写进本机
     */
    private BufferedImage loadIcon(String requestUrl, int size, boolean circle) {
        BufferedImage stored = readDiskImage(requestUrl);
        if (stored != null) {
            return stored;
        }
        Optional<BufferedImage> fetched = api.getBilibiliImage(requestUrl)
                .map(image -> ImageUtil.resize(image, size, size));
        if (circle) {
            fetched = fetched.map(ImageUtil::maskToCircle);
        }
        if (fetched.isEmpty()) {
            return FAILED_AVATAR;
        }
        imageDisk.store(requestUrl, fetched.get());
        return fetched.get();
    }

    /**
     * 圆角方块占位，正中写礼物名的第一个字
     */
    private BufferedImage giftPlaceholder(String name, int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setColor(new Color(255, 236, 242));
        graphics.fillRoundRect(0, 0, size, size, Math.max(8, size / 5), Math.max(8, size / 5));
        String letter = firstCharacter(name);
        if (!letter.isEmpty()) {
            graphics.setColor(COLOR_NAME);
            graphics.setFont(fontUtil.findFontForCharacter(letter.codePointAt(0))
                    .deriveFont(Font.BOLD, size * 0.42f));
            FontMetrics metrics = graphics.getFontMetrics();
            int textX = (size - metrics.stringWidth(letter)) / 2;
            int textY = (size - metrics.getHeight()) / 2 + metrics.getAscent();
            graphics.drawString(letter, textX, textY);
        }
        graphics.dispose();
        return image;
    }

    private static String firstCharacter(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        String trimmed = name.strip();
        if (trimmed.isEmpty()) {
            return "";
        }
        return new String(Character.toChars(trimmed.codePointAt(0)));
    }

    /**
     * 绘制各类排行榜和名单，无数据的榜自动跳过
     */
    private void drawRankings(CommonPainter painter, String platform, Long uid, BilibiliLiveReportOptions options) {
        // 金额榜整榜跳过而非只抹掉数字：这几张榜的每一行本质都是「某人花了多少钱」，
        // 留下名次仍然是在公开排消费。盲盒榜例外：隐藏金额时只写个数——个数不是消费额，
        // 而它是隐藏金额的会话里仅剩的几张榜之一
        int revenueRankingLimit = options.isShowRevenue() ? options.getGiftRanking() : 0;
        int superChatListLimit = options.isShowRevenue() ? options.getSuperChatRanking() : 0;

        drawRanking(painter, platform, uid, "弹幕排行", BilibiliLiveMetric.DANMU_USERS,
                options.getDanmuRanking(), score -> Math.round(score) + " 条", null);
        drawRevenueRanking(painter, platform, uid, revenueRankingLimit);
        drawSuperChatList(painter, platform, uid, superChatListLimit);
        drawBoxBoard(painter, platform, uid, options.getBoxRanking(), options.isShowRevenue());

        // 本场开通大航海：只有隐藏金额的会话还画这张名单——那里没有金额可露，
        // 名单正是氛围；显示金额的会话里不画，全名单上的「本场」小标承担同一件事
        if (options.isGuardList() && !options.isShowRevenue()) {
            drawRanking(painter, platform, uid, "本场开通大航海", BilibiliLiveMetric.GUARD_USERS,
                    GUARD_LIST_LIMIT, score -> Math.round(score) + " 次", null);
        }
    }

    /**
     * 流水排行。有分人流水表按它排；没有的旧场次（回放、补出报告）按礼物＋醒目留言的
     * 分人数据排，并把口径写明——上舰那一截旧数据里没有，硬编一个数进来才是出错。
     * 合成那份在 {@link RevenueRankings}，与排行榜命令共用
     */
    private void drawRevenueRanking(CommonPainter painter, String platform, Long uid, int limit) {
        if (limit <= 0) {
            return;
        }
        List<UserScore> ranking = revenueRanking(platform, uid, limit);
        String note = hasRevenueUserData(platform, uid)
                ? BilibiliLiveMetric.REVENUE_RANKING_NOTE
                : BilibiliLiveMetric.REVENUE_RANKING_FALLBACK_NOTE;
        drawRankingRows(painter, "流水排行", ranking, score -> "¥" + yuan(score), note);
    }

    /**
     * 流水排行的取数：新表优先，旧表回落
     * <p>
     * 包内可见：回落那一支「按已有的数算」算得对不对，判据要直接量这份名单
     */
    List<UserScore> revenueRanking(String platform, Long uid, int limit) {
        List<UserScore> primary = liveDataService.getLiveUserRanking(platform, uid,
                BilibiliLiveMetric.REVENUE_USERS, limit);
        if (!primary.isEmpty()) {
            return primary;
        }
        return RevenueRankings.legacyFallback(liveDataService, platform, uid, limit);
    }

    /**
     * 这一场有没有分人流水表的数据。没有＝旧场次，各处回落按它认
     */
    boolean hasRevenueUserData(String platform, Long uid) {
        return liveDataService.getLiveMetricUserCount(platform, uid, BilibiliLiveMetric.REVENUE_USERS) > 0;
    }

    /**
     * 绘制一张排行榜（按指标取数的那一支）
     */
    private void drawRanking(CommonPainter painter, String platform, Long uid, String title,
                             String metric, int limit, DoubleFunction<String> scoreText, String note) {
        if (limit <= 0) {
            return;
        }
        drawRankingRows(painter, title,
                liveDataService.getLiveUserRanking(platform, uid, metric, limit), scoreText, note);
    }

    /**
     * 绘制一张排行榜（行已取好的那一支）
     */
    private void drawRankingRows(CommonPainter painter, String title, List<UserScore> ranking,
                                 DoubleFunction<String> scoreText, String note) {
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

        // 条形长度按榜首归一化：榜首满格，其余按比例，一眼能看出差距。
        // 条子多宽按这一榜最宽的得分算，整榜共用，各行才比得了
        List<String> labels = new ArrayList<>(ranking.size());
        for (UserScore user : ranking) {
            labels.add(scoreText.apply(user.score()));
        }
        int scoreSlot = rankingScoreSlot(painter, labels);
        double top = ranking.get(0).score();
        for (int i = 0; i < ranking.size(); i++) {
            drawRankingRow(painter, i + 1, ranking.get(i), top, labels.get(i), scoreSlot);
        }
        painter.movePos(0, 8);
    }

    /**
     * 盲盒榜：开了几个与盈亏多少合在一榜，每人一行。按个数排序——个数是活动量，
     * 盈亏是运气，条形按个数走，盈亏跟着各人行显示（正负号照 profitLabel）。
     * 隐藏金额时盈亏那一截不写，榜照出、只写个数：个数不是消费额
     */
    private void drawBoxBoard(CommonPainter painter, String platform, Long uid, int limit, boolean showRevenue) {
        if (limit <= 0) {
            return;
        }
        List<UserScore> ranking = liveDataService.getLiveUserRanking(platform, uid, BilibiliLiveMetric.BOX_USERS, limit);
        if (ranking.isEmpty()) {
            return;
        }

        painter.movePos(0, 10);
        painter.drawTextWithStyle(List.of(new TextWithStyle("盲盒榜", CommonPainter.TEXT_FONT_SIZE, COLOR_TIP, Font.PLAIN)));
        painter.movePos(0, 6);

        List<String> labels = new ArrayList<>(ranking.size());
        for (UserScore user : ranking) {
            String label = Math.round(user.score()) + " 个";
            if (showRevenue) {
                double profit = liveDataService.getLiveUserMetric(platform, uid,
                        BilibiliLiveMetric.BOX_PROFIT_USERS, user.userUid());
                label += " · " + profitLabel(profit);
            }
            labels.add(label);
        }
        int scoreSlot = rankingScoreSlot(painter, labels);
        double top = ranking.get(0).score();
        for (int i = 0; i < ranking.size(); i++) {
            drawRankingRow(painter, i + 1, ranking.get(i), top, labels.get(i), scoreSlot);
        }
        painter.movePos(0, 8);
    }

    /**
     * 醒目留言名单：发过的每人一行，写昵称、合计金额、几条；有原文的下面带原文。
     * <p>
     * 与榜的区别：不画名次与比例条——它交代的是「谁说了什么」，不是「谁花得多」，
     * 比例条会把名单又变回一张消费榜。
     */
    private void drawSuperChatList(CommonPainter painter, String platform, Long uid, int limit) {
        List<SuperChatEntry> entries = superChatList(platform, uid, limit);
        if (entries.isEmpty()) {
            return;
        }

        painter.movePos(0, 10);
        painter.drawTextWithStyle(List.of(new TextWithStyle("醒目留言名单", CommonPainter.TEXT_FONT_SIZE, COLOR_TIP, Font.PLAIN)));
        painter.movePos(0, 6);

        for (SuperChatEntry entry : entries) {
            int y = painter.getY();

            painter.drawTextWithStyle(List.of(
                            new TextWithStyle(truncate(painter, entry.uname()), 24, COLOR_TEXT, Font.PLAIN)),
                    new Point(MARGIN, y + 6));

            String label = "¥" + yuan(entry.total()) + " · " + entry.count() + " 条";
            int labelWidth = painter.getStringWidthAndHeight(
                    new TextWithStyle(label, 24, COLOR_TEXT, Font.PLAIN)).getFirst();
            painter.drawTextWithStyle(List.of(new TextWithStyle(label, 24, COLOR_TEXT, Font.PLAIN)),
                    new Point(Math.max(MARGIN, WIDTH - MARGIN - labelWidth), y + 6));

            int height = RANKING_ROW_HEIGHT;
            if (!entry.joinedText().isBlank()) {
                // 一行放不下就截断：原文是氛围不是证据，截断比换行成小作文更像一张名单
                TextWithStyle text = new TextWithStyle(entry.joinedText(), 20, COLOR_TIP, Font.PLAIN);
                text.setText(painter.truncateToWidth(text, CONTENT_WIDTH));
                painter.drawTextWithStyle(List.of(text), new Point(MARGIN, y + RANKING_ROW_HEIGHT - 8));
                height = RANKING_ROW_HEIGHT + 30;
            }
            painter.setPos(MARGIN, y + height);
        }
        painter.movePos(0, 8);
    }

    /**
     * 醒目留言名单的一行：谁、合计多少、几条、原文接成的一句
     * <p>
     * 包内可见：名单的构成（金额、条数、按时间接起来的原文）是判据要量的东西
     */
    record SuperChatEntry(Long uid, String uname, double total, long count, String joinedText) {
    }

    /**
     * 醒目留言名单的取数
     * <p>
     * 条数优先用计数表；旧场次没有计数表时用原文记录数出来的个数——
     * 原文从记档那天起就在记，两个来源数的是同一件事
     */
    List<SuperChatEntry> superChatList(String platform, Long uid, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        List<UserScore> ranking = liveDataService.getLiveUserRanking(platform, uid,
                BilibiliLiveMetric.SUPER_CHAT_USERS, limit);
        if (ranking.isEmpty()) {
            return List.of();
        }

        Map<Long, List<DanmuRecord>> byUser = new LinkedHashMap<>();
        for (DanmuRecord record : superChatRecords(platform, uid)) {
            // 取用处再滤一道类型：口子给的应当只是醒目留言，混进来的普通弹幕不许上名单
            if (record.uid() != null && record.type() == DanmuRecord.Type.SUPER_CHAT) {
                byUser.computeIfAbsent(record.uid(), key -> new ArrayList<>()).add(record);
            }
        }

        List<SuperChatEntry> entries = new ArrayList<>();
        for (UserScore user : ranking) {
            List<DanmuRecord> own = byUser.getOrDefault(user.userUid(), List.of());
            long count = Math.round(liveDataService.getLiveUserMetric(platform, uid,
                    BilibiliLiveMetric.SUPER_CHAT_USERS_COUNT, user.userUid()));
            if (count == 0) {
                count = own.size();
            }
            // 多条按时间接起来（原文记录本身就是按时间追加的），句与句之间用「／」隔开
            String joined = own.stream()
                    .map(DanmuRecord::text)
                    .filter(text -> text != null && !text.isBlank())
                    .reduce((left, right) -> left + "／" + right)
                    .orElse("");
            entries.add(new SuperChatEntry(user.userUid(), user.displayName(), user.score(), count, joined));
        }
        return entries;
    }

    /**
     * 绘制当前全部大航海。拉不到时写一行说明，不让整张报告失败
     * <p>
     * 本场开通或续费过的人名字旁带一个「本场」小标：全名单是「此刻在舰的人」，
     * 哪些是这一场新来的，正是读名单的人最想马上知道的那件事。
     */
    private void drawGuardRoster(CommonPainter painter, String platform, LiveStreamerInfo source, BilibiliLiveReportOptions options) {
        Optional<List<GuardMember>> fetched = guardList(source.getRoomId(), source.getUid());
        if (fetched.isPresent() && fetched.get().isEmpty()) {
            return;
        }

        painter.movePos(0, 10);
        painter.drawTextWithStyle(List.of(new TextWithStyle("大航海名单（全部）",
                CommonPainter.TEXT_FONT_SIZE, COLOR_TIP, Font.PLAIN)));
        if (fetched.isEmpty()) {
            painter.drawTextWithStyle(
                    List.of(new TextWithStyle("名单暂时拉不到", 24, COLOR_TEXT, Font.PLAIN)),
                    null, true, MARGIN);
            painter.movePos(0, 8);
            return;
        }

        painter.movePos(0, 6);
        Set<Long> thisSession = thisSessionGuardBuyers(platform, source.getUid());
        List<GuardMember> members = sortAndLimit(fetched.get(), options.getGuardListLimit());
        int columnWidth = (CONTENT_WIDTH - ROSTER_COLUMN_GAP) / 2;
        int y = painter.getY();
        int column = 0;
        for (GuardMember member : members) {
            boolean marked = member.uid() > 0 && thisSession.contains(member.uid());
            int natural = rosterEntryWidth(painter, member, marked);
            // 半栏放得下「牌子 + 完整昵称（带小标）」才并进这一列；放不下就独占整行
            boolean ownRow = natural > columnWidth;
            if (ownRow && column == 1) {
                y += ROSTER_ROW_HEIGHT;
                column = 0;
            }
            int slot = ownRow ? CONTENT_WIDTH : columnWidth;
            int x = MARGIN + (ownRow ? 0 : column * (columnWidth + ROSTER_COLUMN_GAP));
            drawRosterEntry(painter, member, x, y, slot, marked);
            if (ownRow || column == 1) {
                y += ROSTER_ROW_HEIGHT;
                column = 0;
            } else {
                column = 1;
            }
        }
        if (column == 1) {
            y += ROSTER_ROW_HEIGHT;
        }
        painter.setPos(MARGIN, y + 8);
    }

    /**
     * 本场开通或续费过的观众：分人大航海表里有分的人。图上的小标与文字版的标记都按它认
     */
    private Set<Long> thisSessionGuardBuyers(String platform, Long uid) {
        List<Long> buyers = liveDataService.getLiveMetricUserSets(platform, uid)
                .get(BilibiliLiveMetric.GUARD_USERS);
        return buyers == null ? Set.of() : new java.util.HashSet<>(buyers);
    }

    /**
     * 「牌子（或只有标志）+ 完整昵称」占多宽；带「本场」小标的那位再让出小标的位置。
     * 用来决定这位要不要独占一行
     */
    private int rosterEntryWidth(CommonPainter painter, GuardMember member, boolean marked) {
        return badgeWidth(painter, member) + ROSTER_NAME_GAP
                + nicknameWidth(painter, member.name()) + (marked ? ROSTER_TAG_GAP + ROSTER_TAG_WIDTH : 0);
    }

    private int nicknameWidth(CommonPainter painter, String name) {
        return painter.getStringWidthAndHeight(
                new TextWithStyle(name == null ? "" : name, ROSTER_NAME_SIZE, COLOR_TEXT, Font.PLAIN)).getFirst();
    }

    private int badgeWidth(CommonPainter painter, GuardMember member) {
        GuardMedal medal = member.medal();
        if (medal == null) {
            return GUARD_ICON_SIZE;
        }
        int nameWidth = medalTextWidth(painter, medal.name(), Font.PLAIN);
        int levelWidth = medalTextWidth(painter, Integer.toString(medal.level()), Font.BOLD);
        return GUARD_ICON_SIZE + 2 + nameWidth + 4 + levelWidth + 8;
    }

    private int medalTextWidth(CommonPainter painter, String text, int style) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return painter.getStringWidthAndHeight(
                new TextWithStyle(text, MEDAL_FONT_SIZE, COLOR_TEXT, style)).getFirst();
    }

    /**
     * 画一位：左侧粉丝牌（没有牌子就只画标志），右侧昵称，本场的带小标。整行放不下才截断昵称
     */
    private void drawRosterEntry(CommonPainter painter, GuardMember member, int x, int y, int slotWidth, boolean marked) {
        BufferedImage badge = rosterBadge(painter, member);
        int badgeY = y + Math.max(0, (ROSTER_ROW_HEIGHT - badge.getHeight()) / 2);
        painter.drawImage(badge, new Point(x, badgeY));

        int nameMax = slotWidth - badge.getWidth() - ROSTER_NAME_GAP
                - (marked ? ROSTER_TAG_GAP + ROSTER_TAG_WIDTH : 0);
        TextWithStyle name = new TextWithStyle(
                member.name() == null ? "" : member.name(), ROSTER_NAME_SIZE, COLOR_TEXT, Font.PLAIN);
        name.setText(painter.truncateToWidth(name, Math.max(0, nameMax)));
        painter.drawTextWithStyle(List.of(name), new Point(x + badge.getWidth() + ROSTER_NAME_GAP, y + 6));

        if (marked) {
            int nameWidth = painter.getStringWidthAndHeight(name).getFirst();
            drawRosterTag(painter,
                    x + badge.getWidth() + ROSTER_NAME_GAP + nameWidth + ROSTER_TAG_GAP,
                    y + (ROSTER_ROW_HEIGHT - ROSTER_TAG_HEIGHT) / 2);
        }
    }

    /**
     * 「本场」小标：一小枚圆角牌子。宽度按「本场」两个字的实测字宽留边，
     * 不写死像素——换了字体它也还包得住
     */
    private void drawRosterTag(CommonPainter painter, int x, int y) {
        TextWithStyle text = new TextWithStyle("本场", ROSTER_TAG_FONT_SIZE, COLOR_NAME, Font.PLAIN);
        int textWidth = painter.getStringWidthAndHeight(text).getFirst();
        int padding = 8;
        int width = textWidth + padding * 2;
        painter.drawRoundedRectangle(x, y, width, ROSTER_TAG_HEIGHT,
                ROSTER_TAG_HEIGHT / 2, new Color(255, 236, 242));
        painter.drawTextWithStyle(List.of(text), new Point(x + padding, y + 2));
    }

    /** 小标「本场」的字号与高，宽度按字宽现算（见 drawRosterTag） */
    private static final int ROSTER_TAG_FONT_SIZE = 16;

    private static final int ROSTER_TAG_HEIGHT = 24;

    /** 按「本场」16 号字两侧各留 8 算出的占位宽，排版量行宽时用同一份 */
    private static final int ROSTER_TAG_WIDTH = 16 * 2 + 8 * 2;

    private static final int ROSTER_TAG_GAP = 6;

    private BufferedImage rosterBadge(CommonPainter painter, GuardMember member) {
        BufferedImage icon = guardIconImage(member);
        if (icon == null) {
            icon = solidCircle(guardColor(member.level()), GUARD_ICON_SIZE);
        }
        GuardMedal medal = member.medal();
        if (medal == null) {
            return icon;
        }
        BufferedImage badge = composeMedal(painter, medal, icon);
        return medal.lit() ? badge : grayscale(badge);
    }

    private BufferedImage guardIconImage(GuardMember member) {
        String url = null;
        if (member.medal() != null && StringUtil.isNotBlank(member.medal().guardIcon())) {
            url = member.medal().guardIcon();
        } else {
            url = defaultGuardIconUrl(member.level());
        }
        return url == null ? null : guardIcon(url);
    }

    private static String defaultGuardIconUrl(int level) {
        if (level == 1) {
            return GUARD_ICON_GOVERNOR;
        }
        if (level == 2) {
            return GUARD_ICON_COMMANDER;
        }
        if (level == 3) {
            return GUARD_ICON_CAPTAIN;
        }
        return null;
    }

    private static Color guardColor(int level) {
        if (level == 1) {
            return GUARD_COLOR_GOVERNOR;
        }
        if (level == 2) {
            return GUARD_COLOR_COMMANDER;
        }
        if (level == 3) {
            return GUARD_COLOR_CAPTAIN;
        }
        return GUARD_COLOR_UNKNOWN;
    }

    /**
     * 一条圆角长条：45° 渐变底、一圈细边，从左到右是标志、牌名、等级。等级没有单独的格子
     */
    private BufferedImage composeMedal(CommonPainter painter, GuardMedal medal, BufferedImage icon) {
        int width = badgeWidth(painter, new GuardMember(0L, "", 0, 0L, medal));
        int height = Math.max(GUARD_ICON_SIZE, MEDAL_BAR_HEIGHT);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        int barX = GUARD_ICON_SIZE / 2;
        int barY = (height - MEDAL_BAR_HEIGHT) / 2;
        int barWidth = width - barX;
        RoundRectangle2D bar = new RoundRectangle2D.Float(barX, barY, barWidth, MEDAL_BAR_HEIGHT,
                MEDAL_BAR_HEIGHT, MEDAL_BAR_HEIGHT);
        graphics.setPaint(gradient45(barX, barY, barWidth, MEDAL_BAR_HEIGHT, medal.start(), medal.end()));
        graphics.fill(bar);
        graphics.setStroke(new BasicStroke(1f));
        graphics.setColor(medal.border());
        graphics.draw(bar);

        int textX = GUARD_ICON_SIZE + 2;
        textX = drawMedalText(graphics, medal.name(), textX, barY, Font.PLAIN, medal.text());
        drawMedalText(graphics, Integer.toString(medal.level()), textX + 4, barY, Font.BOLD, medal.text());

        int iconY = (height - GUARD_ICON_SIZE) / 2;
        graphics.drawImage(icon, 0, iconY, GUARD_ICON_SIZE, GUARD_ICON_SIZE, null);
        graphics.dispose();
        return image;
    }

    private int drawMedalText(Graphics2D graphics, String text, int x, int barY, int style, Color color) {
        if (text == null || text.isEmpty()) {
            return x;
        }
        graphics.setColor(color);
        Font baselineFont = fontUtil.primaryFont().deriveFont(style, (float) MEDAL_FONT_SIZE);
        graphics.setFont(baselineFont);
        FontMetrics baseline = graphics.getFontMetrics();
        int baselineY = barY + (MEDAL_BAR_HEIGHT + baseline.getAscent() - baseline.getDescent()) / 2;
        for (int codePoint : text.codePoints().toArray()) {
            // 变体选择符不占位也不出墨
            if (FontUtil.isVariantSelector(codePoint)) {
                continue;
            }

            String character = new String(Character.toChars(codePoint));
            Font font = fontUtil.findFontForCharacter(codePoint).deriveFont(style, (float) MEDAL_FONT_SIZE);
            graphics.setFont(font);
            graphics.drawString(character, x, baselineY);
            x += graphics.getFontMetrics().stringWidth(character);
        }
        return x;
    }

    /**
     * 45° 渐变：从左下到右上，与网页牌子的底色同一方向
     */
    private static LinearGradientPaint gradient45(float x, float y, float width, float height, Color from, Color to) {
        float startX = x;
        float startY = y + height;
        float endX = x + width;
        float endY = y;
        if (startX == endX && startY == endY) {
            endX = startX + 1f;
        }
        return new LinearGradientPaint(startX, startY, endX, endY, new float[] {0f, 1f}, new Color[] {from, to});
    }

    private static BufferedImage solidCircle(Color color, int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setColor(color);
        graphics.fillOval(0, 0, size - 1, size - 1);
        graphics.dispose();
        return image;
    }

    private static BufferedImage grayscale(BufferedImage source) {
        BufferedImage gray = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                int argb = source.getRGB(x, y);
                int alpha = (argb >>> 24) & 0xFF;
                int red = (argb >>> 16) & 0xFF;
                int green = (argb >>> 8) & 0xFF;
                int blue = argb & 0xFF;
                int lum = (int) Math.round(0.299 * red + 0.587 * green + 0.114 * blue);
                gray.setRGB(x, y, (alpha << 24) | (lum << 16) | (lum << 8) | lum);
            }
        }
        return gray;
    }

    private void appendGuardRoster(StringBuilder text, String platform, LiveStreamerInfo source, BilibiliLiveReportOptions options) {
        if (!options.isGuardListAll()) {
            return;
        }
        Optional<List<GuardMember>> fetched = guardList(source.getRoomId(), source.getUid());
        if (fetched.isPresent() && fetched.get().isEmpty()) {
            return;
        }
        text.append("\n大航海名单（全部）");
        if (fetched.isEmpty()) {
            text.append("\n名单暂时拉不到");
            return;
        }
        Set<Long> thisSession = thisSessionGuardBuyers(platform, source.getUid());
        for (GuardMember member : sortAndLimit(fetched.get(), options.getGuardListLimit())) {
            text.append('\n').append(guardRankName(member.level())).append("  ").append(member.name());
            if (member.uid() > 0 && thisSession.contains(member.uid())) {
                text.append(" · 本场");
            }
        }
    }

    private static List<GuardMember> sortAndLimit(List<GuardMember> members, int limit) {
        List<GuardMember> sorted = new ArrayList<>(members);
        sorted.sort(Comparator
                .comparingInt((GuardMember member) -> member.level() <= 0 ? 99 : member.level())
                .thenComparing(Comparator.comparingLong(GuardMember::score).reversed()));
        if (limit > 0 && sorted.size() > limit) {
            return new ArrayList<>(sorted.subList(0, limit));
        }
        return sorted;
    }

    private static String guardRankName(int level) {
        return GuardType.of(level).getName();
    }

    /**
     * 这一榜的得分要占多宽。按最宽的一行算，整榜共用，各行条子才一样长。
     * 最宽的不超过默认槽时原样返回，短得分的榜不收窄。
     */
    private static int rankingScoreSlot(CommonPainter painter, List<String> labels) {
        int widest = 0;
        for (String label : labels) {
            int width = painter.getStringWidthAndHeight(
                    new TextWithStyle(label, RANKING_SCORE_FONT, COLOR_TEXT, Font.PLAIN)).getFirst();
            widest = Math.max(widest, width);
        }
        if (widest <= RANKING_SCORE_SLOT) {
            return RANKING_SCORE_SLOT;
        }
        return widest + RANKING_SCORE_GAP;
    }

    /**
     * 绘制排行榜的一行：名次、昵称、比例条与得分
     * <p>
     * 得分文案由调用方按行算好传入：盲盒榜那种「一行两个数」的榜拿不到一行一个函数的口子。
     * {@code scoreSlot} 是这一榜共用的得分占位，各行条子因此一样宽。
     */
    private void drawRankingRow(CommonPainter painter, int rank, UserScore user, double topScore,
                                 String scoreLabel, int scoreSlot) {
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

        // 比例条从昵称右侧的固定起点画到得分左边。槽宽由这一榜最宽的得分决定，各行相同。
        // 不超过默认槽时不收窄；再宽就按字宽留出空隙，长文案才不会压在条子右端上
        int barX = MARGIN + 300;
        int barWidth = Math.max(RANKING_BAR_HEIGHT, CONTENT_WIDTH - 300 - scoreSlot);
        painter.drawRoundedRectangle(barX, y + 12, barWidth, RANKING_BAR_HEIGHT, RANKING_BAR_HEIGHT / 2, COLOR_CARD);

        // 盈亏榜可能出现负分或榜首为 0 的情况，按绝对值取比例并留一段最小可见长度
        double ratio = topScore == 0 ? 0 : Math.abs(user.score()) / Math.abs(topScore);
        int filled = (int) Math.round(barWidth * Math.max(0, Math.min(1, ratio)));
        if (filled > 0) {
            painter.drawRoundedRectangle(barX, y + 12, Math.max(filled, RANKING_BAR_HEIGHT),
                    RANKING_BAR_HEIGHT, RANKING_BAR_HEIGHT / 2, rankColor(rank));
        }

        // 得分文字量宽度后靠右边距放，而不是从条形右端固定起点：盲盒榜那种「一行两个数」
        // 的长文案会画出右边距外（数再长一两位就被画布截掉）。与醒目留言名单的合计文案同一写法
        int scoreWidth = painter.getStringWidthAndHeight(
                new TextWithStyle(scoreLabel, RANKING_SCORE_FONT, COLOR_TEXT, Font.PLAIN)).getFirst();
        painter.drawTextWithStyle(List.of(new TextWithStyle(scoreLabel, RANKING_SCORE_FONT, COLOR_TEXT, Font.PLAIN)),
                new Point(WIDTH - MARGIN - scoreWidth, y + 6));

        painter.setPos(MARGIN, y + RANKING_ROW_HEIGHT);
    }

    /**
     * 取排行榜用的圆形头像，带缓存
     * <p>
     * 缓存按头像地址而非用户：同一个人在多张榜里出现、多份报告里出现，都只下载一次。
     * 取不到时缓存一个空值占位，避免坏地址被反复重试。
     * <p>
     * 它与 {@link #loadCover} 同属「向外部要资料的口子」那一族（见该段的说明），
     * 位置留在各自的上下文里没搬走——搬过去只会让读画法的人多跳一次。
     */
    protected BufferedImage avatar(String url) {
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
     * 取大航海标志（名单里那一档的尺寸）。先查内存，再查本机，都没有才按实际请求的地址去取。
     * 地址空、或这一次没取到，返回 null，调用方改画色块。
     * <p>
     * 与 {@link #giftIcon} 同一路：取不到就记住这次失败，失败不写本机。
     * 预览与历史重画覆写这一口，不向外取图。
     */
    protected BufferedImage guardIcon(String url) {
        return guardIcon(url, GUARD_ICON_SIZE);
    }

    /**
     * 取大航海标志，按绘制尺寸另取一张。
     * <p>
     * 「收到的礼物」那一格要 96px：把名单用的 32px 源图放大到 96 会糊，
     * 而地址本身支持按宽度取图（与礼物图标同一条机制），按目标尺寸另取就是清楚的。
     * 预览与历史重画覆写这一口，不向外取图。
     */
    protected BufferedImage guardIcon(String url, int size) {
        if (StringUtil.isBlank(url)) {
            return null;
        }
        BufferedImage cached = guardIconCache.get(url + "@" + size,
                key -> loadIcon(guardFetchUrl(url, size), size, true));
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
     * 这一张词云用的词频。名单为空时直接用已保存的表，不读弹幕原文。
     * 名单非空时按原文剔掉这些人后重算；没有原文或读失败则退回已保存的表，并记一行。
     * 退回去的那一份不留下，下一次仍读当前已保存的表。
     * 同一场、同一份名单、原文大小没变，几个推送目标共用这一次重算成功的结果。
     * 留下的场次有上限。
     * 不同的场各算各的，一场在读原文时，别的场不用跟着等。
     */
    Map<String, Integer> frequenciesForWordCloud(String platform, Long uid) {
        Map<String, Integer> stored = liveDataService.getLiveWordFrequencies(platform, uid);
        Set<Long> exclude = excludedWordCloudUids();
        if (exclude.isEmpty() || platform == null || uid == null) {
            return stored;
        }
        Optional<Long> start = liveStart(platform, uid);
        long startValue = start.orElse(-1L);
        // 重算时切词要把屏蔽词整个留下，切法随屏蔽表变，所以表也进键：改了表不拿旧的那一份
        String key = platform + "\0" + uid + "\0" + startValue + "\0" + exclude + "\0" + wordCloudBlockWords();
        // 先拿这场的门，再碰留下的表。反过来两头会互等。
        // 读原文和重算放在表锁外面，别的场不用跟着等。
        Object gate = wordCloudGates.computeIfAbsent(key, ignored -> new Object());
        try {
            synchronized (gate) {
                long stamp = -1L;
                if (start.isPresent()) {
                    stamp = wordCloudDanmuSize(platform, uid, start.get()).orElse(-1L);
                }
                synchronized (wordCloudRecounts) {
                    WordCloudRecount cached = wordCloudRecounts.get(key);
                    if (cached != null && cached.stamp() == stamp) {
                        return cached.words();
                    }
                }
                Map<String, Integer> words = stored;
                boolean recounted = false;
                if (start.isPresent() && stamp >= 0) {
                    Optional<List<DanmuRecord>> danmu = wordCloudDanmu(platform, uid, start.get());
                    if (danmu.isPresent()) {
                        words = recountWordCloud(platform, uid, danmu.get(), exclude);
                        recounted = true;
                    }
                }
                synchronized (wordCloudRecounts) {
                    if (recounted) {
                        wordCloudRecounts.put(key, new WordCloudRecount(stamp, words));
                    } else {
                        String noted = platform + "\0" + uid + "\0" + startValue;
                        if (wordCloudFallbackNoted.put(noted, Boolean.TRUE) == null) {
                            log.warn(WORD_CLOUD_FALLBACK);
                        }
                    }
                }
                return words;
            }
        } finally {
            wordCloudGates.remove(key, gate);
        }
    }

    private Set<Long> excludedWordCloudUids() {
        if (properties == null || properties.getLive() == null) {
            return Set.of();
        }
        return DanmuWordCloudFrequencies.parseUids(properties.getLive().getWordCloudExcludeUids());
    }

    /**
     * 当下这张词云屏蔽词表，已折好大小写。每次画都现读，保存后下一张就按新表
     */
    private List<String> wordCloudBlockWords() {
        if (properties == null || properties.getLive() == null) {
            return List.of();
        }
        return DanmuWordCloudFrequencies.parseBlockWords(properties.getLive().getWordCloudBlockWords());
    }

    /**
     * 按弹幕原文重算这一场的词频。屏蔽词在切词时整个留下，不切出碎片
     */
    Map<String, Integer> recountWordCloud(String platform, Long uid, List<DanmuRecord> danmu,
                                          Set<Long> exclude) {
        return DanmuWordCloudFrequencies.recount(platform, uid, danmu, exclude, wordCloudBlockWords());
    }

    /**
     * 这一场弹幕原文有多少字节。没有这份文件时为空。
     */
    Optional<Long> wordCloudDanmuSize(String platform, long uid, long start) {
        return rosterArchive().flatMap(archive -> archive.danmuFileSize(platform, uid, start));
    }

    /**
     * 这一场的醒目留言原文，按发生先后。只读本机明细档，不联网。
     * <p>
     * 属「向外部要资料的口子」：预览与演示覆写这一口喂夹具原文，
     * 否则预览那一屏拿不到任何一条原文（夹具不落明细档），名单的下半行永远空着。
     */
    protected List<DanmuRecord> superChatRecords(String platform, Long uid) {
        return liveStart(platform, uid)
                .flatMap(start -> wordCloudDanmu(platform, uid, start))
                .map(records -> records.stream()
                        .filter(record -> record.type() == DanmuRecord.Type.SUPER_CHAT)
                        .toList())
                .orElse(List.of());
    }

    /**
     * 读这一场的弹幕原文。没有这份文件或读失败时为空。
     */
    Optional<List<DanmuRecord>> wordCloudDanmu(String platform, long uid, long start) {
        return rosterArchive().flatMap(archive -> archive.readDanmuPresent(platform, uid, start));
    }

    /**
     * 绘制弹幕词云，渲染失败时整体跳过
     * <p>
     * 🔴 <b>不再按词数决定画不画。</b>此前少于 5 个词整块跳过：冷清场次的报告里
     * 连「本场没什么人说话」都看不出来，与「词云画崩了」在图上长得一模一样。
     * 现在词少排成一小团、一个词都没有画成空态，块高随词量走
     */
    private void drawWordCloud(CommonPainter painter, String platform, Long uid) {
        Map<String, Integer> frequencies = frequenciesForWordCloud(platform, uid);

        try {
            BufferedImage cloud = paintWordCloud(platform, uid, frequencies);

            painter.movePos(0, 6);
            painter.drawTextWithStyle(List.of(new TextWithStyle("弹幕词云", CommonPainter.TEXT_FONT_SIZE, COLOR_TIP, Font.PLAIN)));
            painter.movePos(0, 8);
            painter.drawImage(ImageUtil.maskToRoundedRectangle(cloud, CARD_RADIUS));
        } catch (Exception e) {
            log.warn("绘制弹幕词云失败, 报告将不含词云: {}", e.getMessage());
        }
    }

    /**
     * 把词频排成一张透明底的词云图
     * <p>
     * 包内可见：判据要拿到这张图与它背后的排版结果，而整份报告里词云只占一块，
     * 从成品图上反推「哪几个像素是哪个词」做不到
     */
    BufferedImage paintWordCloud(String platform, Long uid, Map<String, Integer> frequencies) {
        WordCloud cloud = composeWordCloud(platform, uid, frequencies);

        // 放不下的词被静默丢掉，报告看上去仍然完整——「这一场少了几十个词」只有日志说得出来。
        // 恰一行：每丢一个词打一行会把日志刷爆，一行不打就是静默
        if (cloud.layout().dropped() > 0) {
            int total = cloud.layout().placements().size() + cloud.layout().dropped();
            log.warn("绘制 {} {} 的弹幕词云: 本场取词 {} 个, 版面放不下 {} 个",
                    platform, uid, total, cloud.layout().dropped());
        }

        return cloudRenderer().render(cloud.layout(), CONTENT_WIDTH, cloud.height());
    }

    /**
     * 排一次词云版式
     */
    WordCloudLayout.Result layoutWordCloud(String platform, Long uid, Map<String, Integer> frequencies) {
        return composeWordCloud(platform, uid, frequencies).layout();
    }

    /**
     * 一块词云：实际高度与按这个高度排出来的版式
     * <p>
     * 🔴 高度与版式<b>必须同源</b>。分成两处各算一遍的话，排版按一个高度摆字、
     * 出图按另一个高度裁画布，越界的词被裁掉半截而两边都不报错
     *
     * @param height 这一块实际占多高，报告里下一块按它挪
     */
    private record WordCloud(int height, WordCloudLayout.Result layout) {
    }

    /**
     * 收词、定高、排版，三件事一趟做完
     */
    private WordCloud composeWordCloud(String platform, Long uid, Map<String, Integer> frequencies) {
        // 🔴 词频相同时按词本身排，且排序要落在 limit 之前：只按词频排的话，
        // 尾部同频的那一批里究竟哪几个进得了前 72 名，由 Map 的遍历顺序决定。
        // 屏蔽词也在 limit 之前挑掉，让出的位子由后面的词补上。
        // 旧场重画拿的是当年存下的词频、不再切词，这里只能按存下的词挑
        List<String> blockWords = wordCloudBlockWords();
        List<WordCloudLayout.Word> words = frequencies.entrySet().stream()
                .filter(entry -> entry.getKey() != null && !entry.getKey().isBlank()
                        && entry.getValue() != null && entry.getValue() > 0)
                .filter(entry -> !DanmuWordCloudFrequencies.blocked(entry.getKey(), blockWords))
                .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(CLOUD_MAX_WORDS)
                .map(entry -> new WordCloudLayout.Word(entry.getKey(), entry.getValue()))
                .toList();

        int height = WordCloudLayout.recommendedHeight(words.size(), CLOUD_HEIGHT);
        return new WordCloud(height, WordCloudLayout.layout(words, CONTENT_WIDTH, height,
                cloudSeed(platform, uid), cloudRenderer()));
    }

    /**
     * 词云的随机种子取本场直播的标识：平台 + 主播 + 本场开播时刻
     * <p>
     * 🔴 <b>种子必须逐场固定，而不是每次绘制现取。</b>同一场报告可能被重发
     * （推送失败重试、或推给好几个群），随机撒点的话每一次出来的是另一张图，
     * 收到两张的人会以为是两场直播。开播时刻取不到时退回平台与主播，
     * 至少同一场之内是稳的
     */
    private long cloudSeed(String platform, Long uid) {
        long start = liveDataService.getLiveStartTime(platform, uid).orElse(0L);
        return ((long) (platform + "#" + uid).hashCode() << 32) ^ start;
    }

    /**
     * 词云的字体测量与绘制器。字体表在运行期不变，一份够用
     */
    private synchronized WordCloudRenderer cloudRenderer() {
        if (cloudRenderer == null) {
            cloudRenderer = new WordCloudRenderer(fontUtil);
        }
        return cloudRenderer;
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
     * <p>
     * 属「向外部要资料的口子」那一族，见该段的说明。
     */
    protected BufferedImage loadCover(LiveStreamerInfo source) {
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
     * 读取计数类指标并取整
     */
    private long count(String platform, Long uid, String metric) {
        return Math.round(liveDataService.getLiveMetric(platform, uid, metric));
    }

    /**
     * 弹幕条数后面的「· N 人参与」后缀。
     * <p>
     * 匿名模式下发送者 uid 全是 0、不计人数：条数在而人数为 0 时不写「0 人参与」，
     * 免得有弹幕却读起来像没人说话。图片卡与文字版共用这一处，免得两处说法分叉。
     */
    private String danmuUsersSuffix(long danmu, int danmuUsers) {
        if (danmu > 0 && danmuUsers == 0) {
            return "";
        }
        return " · " + danmuUsers + " 人参与";
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
     * 本场的全部采集缺口，按时间先后排列
     * <p>
     * 取不到起止时刻时为空表：算不出时间轴，也就谈不上哪一段落在轴内。
     */
    private List<LiveGap> collectionGaps(String platform, Long uid) {
        Optional<Long> start = liveDataService.getLiveStartTime(platform, uid);
        Optional<Long> end = effectiveEndTime(platform, uid, start);
        if (start.isEmpty() || end.isEmpty() || end.get() <= start.get()) {
            return List.of();
        }
        return collectionGaps(platform, uid, start.get(), end.get());
    }

    /**
     * 把程序停机与本房断线合成一份<b>互不重叠</b>的缺口表
     * <p>
     * ⚠️ <b>两份必然重叠，所以不能相加。</b>程序停机期间这个房间当然也是断的，
     * 相加就是把同一秒数两遍，能算出比整场时长还长的缺口。这里让停机<b>优先占位</b>：
     * 那一秒既然整个程序都没在跑，说成「程序停了」比说成「这个房间断流」更接近成因。
     * <p>
     * 让它们互不重叠还买到一件事：各成因时长之和恰好等于总时长，
     * 概览那一行的「共 X：维护 a／重启 b／断流 c」才是一个真的分栏，而不是三个能互相重叠的数。
     */
    private List<LiveGap> collectionGaps(String platform, Long uid, long start, long end) {
        return LiveGap.merge(List.of(
                liveDataService.downtimeIntervals(start, end),
                liveDataService.roomOutageIntervals(platform, uid, start, end)));
    }

    /**
     * 本场采集缺口的那一句话：共缺了多久，各成因各占多久
     * <p>
     * 没有缺口时返回空字符串，报告上就不会多出一句废话。
     * <p>
     * <b>刻意不是私有的</b>：报告文本降级输出要用同一份措辞，
     * 缺口这句话只能有一个出处，否则图片版与文字版迟早说出两个不同的数。
     * <p>
     * 总时长由各成因逐项相加得出，<b>不另算一遍</b>：各项与总数各自取整的话，
     * 「共 12 分 34 秒」旁边挂着几项加起来是 12 分 33 秒，读的人只会以为哪里少算了一段。
     * <p>
     * 只有一个成因时不再重复那个数（「共 8 秒·维护」而不是「共 8 秒：维护 8 秒」）：
     * 这一句是跟在时长后面的一个片段，<b>版心只有那么宽</b>，
     * 而同一个数写两遍既占地方又不多说明任何事情。
     */
    String collectionGapText(String platform, Long uid) {
        List<LiveGap> gaps = collectionGaps(platform, uid);
        if (gaps.isEmpty()) {
            return "";
        }

        // 分栏按枚举声明顺序出，只出非零的那几栏——「断流 0 秒」这种栏位是噪音
        List<LiveGap.Reason> reasons = new ArrayList<>();
        List<String> parts = new ArrayList<>();
        long totalSeconds = 0;
        for (LiveGap.Reason reason : LiveGap.Reason.values()) {
            long seconds = gaps.stream()
                    .filter(gap -> gap.reason() == reason)
                    .mapToLong(LiveGap::durationMillis)
                    .sum() / 1000;
            if (seconds > 0) {
                totalSeconds += seconds;
                reasons.add(reason);
                parts.add(reason.getDescription() + " " + DurationFormatUtil.format(seconds));
            }
        }
        if (parts.isEmpty()) {
            return "";
        }

        String total = "采集缺口 共 " + DurationFormatUtil.format(totalSeconds);
        return reasons.size() == 1
                ? total + "·" + reasons.get(0).getDescription()
                : total + "：" + String.join("／", parts);
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
     * 盲盒盈亏排行金额：正数带加号、负数带减号、零无方向不带号。
     */
    static String profitLabel(double score) {
        if (score == 0) {
            return "¥0";
        }
        return (score > 0 ? "+¥" : "-¥") + yuan(Math.abs(score));
    }

    /**
     * 金额格式化：保留一位小数，整数金额省略小数位
     */
    private static String yuan(double value) {
        long rounded = Math.round(value * 10);
        if (rounded % 10 == 0) {
            return String.valueOf(rounded / 10);
        }
        return String.valueOf(rounded / 10.0);
    }

    // ================ 向外部要资料的口子 ================
    // 画一张报告要的东西有两类：一类在本场数据里（走 liveDataService，构造时给什么就是什么），
    // 另一类要向 B 站或状态存储现取——**那一类全部收在这一段**，各是一个可覆写的方法。
    //
    // 🔴 收在一处，是为了让「预览不联网」这件事有个落点：
    // BilibiliLiveReportPreviewPainter 覆写下面每一个，用本地夹具顶上。
    // 🔴 往下加新口子时必须也加在这一段，否则预览会安静地联网——
    // **一次真的去打了接口的预览，和一次用夹具画出来的预览，在图上长得一样。**
    // 这一条不指望自觉：BilibiliLiveReportPreviewPainterTest 断言预览全程与接口零交互，
    // 漏覆写的那一个口子会在那里当场红。

    /**
     * 主播头像：取地址、下图、裁成圆形，实现见 {@link ReportSharedStyle#faceImage}
     */
    protected BufferedImage faceImage(LiveStreamerInfo source) {
        return ReportSharedStyle.faceImage(api, source);
    }

    /**
     * 当前粉丝数，取不到时为空
     */
    protected Optional<Long> fansCount(Long uid) {
        return api.getFansCount(uid);
    }

    /**
     * 当前粉丝团人数，取不到时为空
     */
    protected Optional<Integer> fansMedalCount(Long uid) {
        return api.getFansMedalCount(uid);
    }

    /**
     * 当前大航海人数，取不到时为空
     */
    protected Optional<Integer> guardCount(Long roomId, Long uid) {
        Optional<Integer> count = api.getGuardCount(roomId, uid);
        String key = guardCountSessionKey(roomId, uid);
        if (key != null) {
            synchronized (guardCountOnCard) {
                if (count.isPresent() && count.get() > 0) {
                    guardCountOnCard.put(key, count.get());
                } else {
                    guardCountOnCard.remove(key);
                }
            }
        }
        return count;
    }

    /**
     * 这一场人数卡片的键。还没有开播时刻时为空，免得用上一场的数。
     */
    private String guardCountSessionKey(Long roomId, Long uid) {
        if (roomId == null || uid == null) {
            return null;
        }
        Optional<Long> start = liveStart(BilibiliPlatform.BILIBILI.id(), uid);
        if (start.isEmpty()) {
            return null;
        }
        return roomId + ":" + uid + ":" + start.get();
    }

    /**
     * 留下的人数：这一场的卡片已经问过就用那个，否则用名单首页报的总数，再没有才用名单长度
     */
    private int totalForRoster(Long roomId, Long uid, List<GuardMember> members) {
        String key = guardCountSessionKey(roomId, uid);
        Integer onCard = null;
        if (key != null) {
            synchronized (guardCountOnCard) {
                onCard = guardCountOnCard.get(key);
            }
        }
        if (onCard != null && onCard > 0) {
            return onCard;
        }
        return GuardListFetch.reportedTotal(members).orElse(members.size());
    }

    /**
     * 这位主播当前的大航海名单，取不到时为空
     * <p>
     * 这份名单是现拉的「此刻在舰的人」，不是本场新开通的那张计分表。
     * 直播中的实时报告每次向平台要，不读、也不写留下的那一份，
     * 免得直播中先看一次就把名单定格，下播时反倒不是当时的人。
     * 下播之后才读这场已经留下的，没有才向平台要，要到了就留下。
     * 几个推送目标画同一场时，后面的目标读这一份，不再各要一遍。
     * 人数用卡片已经问到的总数；没有卡片时用名单首页自带的总数；
     * 都没有才用实际取到的人数。不为人数再打一次接口。
     * 名单只存实际取到的那些人。
     * 预览与历史重画必须覆写这一口，否则会拿夹具或去年的场次去打今天的接口，
     * 预览那一支还不能把夹具写进真场次的目录。
     */
    protected Optional<List<GuardMember>> guardList(Long roomId, Long uid) {
        if (roomId == null || uid == null) {
            return Optional.empty();
        }
        String platform = BilibiliPlatform.BILIBILI.id();
        Optional<Long> start = liveStart(platform, uid);
        boolean retain = start.isPresent() && !liveNow(platform, uid);
        if (retain) {
            Optional<GuardRosterFile.Parsed> saved = savedGuardRoster(platform, uid, start.get());
            if (saved.isPresent() && !saved.get().members().isEmpty()) {
                return Optional.of(saved.get().members());
            }
        }
        try {
            Optional<List<GuardMember>> fetched = api.getGuardList(roomId, uid);
            if (fetched == null || fetched.isEmpty()) {
                return Optional.empty();
            }
            if (retain && !fetched.get().isEmpty()) {
                keepGuardRoster(platform, uid, start.get(), totalForRoster(roomId, uid, fetched.get()), fetched.get());
            }
            return fetched;
        } catch (RuntimeException e) {
            log.debug("获取直播间 {} 的大航海名单失败: {}", roomId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 此刻是否还在播。状态拿不到时按已下播，下播报告仍会留下名单
     */
    private boolean liveNow(String platform, Long uid) {
        try {
            Optional<Boolean> status = liveDataService.getLiveStatus(platform, uid);
            return status != null && status.orElse(false);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * 这场已经留下的大航海名单。没有这份缓存目录时为空，避免写到真数据旁边
     */
    protected Optional<GuardRosterFile.Parsed> savedGuardRoster(String platform, long uid, long startTime) {
        if (platform == null) {
            return Optional.empty();
        }
        return rosterArchive()
                .flatMap(archive -> archive.readText(platform, uid, startTime, GuardRosterFile.NAME))
                .flatMap(GuardRosterFile::parse);
    }

    /**
     * 留下这场的名单。已经有了就不覆盖；空名单不写，免得以后重画成没人上舰
     */
    protected void keepGuardRoster(String platform, long uid, long startTime, int total, List<GuardMember> members) {
        if (platform == null || members == null || members.isEmpty() || total <= 0) {
            return;
        }
        rosterArchive().ifPresent(archive -> archive.writeOnce(platform, uid, startTime, GuardRosterFile.NAME,
                GuardRosterFile.toJson(System.currentTimeMillis(), total, members)));
    }

    private Optional<Long> liveStart(String platform, Long uid) {
        try {
            Optional<Long> start = liveDataService.getLiveStartTime(platform, uid);
            return start == null ? Optional.empty() : start;
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * 图标缓存和明细在同一个目录下。缓存不落盘时，名单也不落盘
     */
    private Optional<LiveDetailArchive> rosterArchive() {
        Path cacheDir = imageDisk.directory();
        if (cacheDir == null) {
            return Optional.empty();
        }
        Path parent = cacheDir.getParent();
        Path liveData = parent == null ? Path.of("data.json") : parent.resolve("data.json");
        NovaCoreProperties core = new NovaCoreProperties();
        core.getLive().setSaveLiveData(false);
        core.getLive().setLiveDataPath(liveData.toString());
        return Optional.of(new LiveDetailArchive(core));
    }

    /**
     * 为图片地址附加指定宽度的缩放参数，实现见 {@link ReportSharedStyle#atSize}
     * <p>
     * 排行榜头像只有 32px，下原图既慢又浪费——一场直播的榜单动辄数十人
     */
    private String atSize(String url, int size) {
        return ReportSharedStyle.atSize(url, size);
    }

    /**
     * 数据卡片：取值与标签
     */
    record Card(String value, String label) {
    }

    /**
     * 一条互动曲线
     *
     * @param title 曲线标题
     * @param metric 指标名；合成曲线（流水）不落在哪张指标时序上，为 null
     * @param color 面积或折线配色
     * @param peakText 峰值文案，为 null 时不标峰值（金额曲线在不展示金额的会话里即为此情形）
     * @param polyline true 时画折线（不填充），false 时画面积
     * @param caption 标题下一行小字，null 则不画
     * @param series 合成曲线自带的时序；按指标取数的那几条为 null
     */
    record Curve(String title, String metric, Color color, DoubleFunction<String> peakText,
                 boolean polyline, String caption, Map<Long, Double> series) {
        private Curve(String title, String metric, Color color, DoubleFunction<String> peakText) {
            this(title, metric, color, peakText, false, null, null);
        }

        private Curve(String title, String metric, Color color, DoubleFunction<String> peakText,
                      boolean polyline, String caption) {
            this(title, metric, color, peakText, polyline, caption, null);
        }

        private Curve(String title, String metric, Color color, DoubleFunction<String> peakText,
                      Map<Long, Double> series) {
            this(title, metric, color, peakText, false, null, series);
        }
    }
}
