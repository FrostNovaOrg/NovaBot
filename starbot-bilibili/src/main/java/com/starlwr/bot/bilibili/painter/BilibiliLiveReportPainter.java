package com.starlwr.bot.bilibili.painter;

import com.kennycason.kumo.CollisionMode;
import com.kennycason.kumo.WordCloud;
import com.kennycason.kumo.WordFrequency;
import com.kennycason.kumo.bg.RectangleBackground;
import com.kennycason.kumo.font.KumoFont;
import com.kennycason.kumo.font.scale.SqrtFontScalar;
import com.kennycason.kumo.image.AngleGenerator;
import com.kennycason.kumo.palette.ColorPalette;
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

    @Autowired
    public BilibiliLiveReportPainter(StarBotCommonPainterFactory factory, BilibiliApiUtil api,
                                     LiveDataService liveDataService, FontUtil fontUtil) {
        this.factory = factory;
        this.api = api;
        this.liveDataService = liveDataService;
        this.fontUtil = fontUtil;
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
            drawRankings(painter, platform, source.getUid(), options);
            if (options.isDanmuCloud()) {
                drawWordCloud(painter, platform, source.getUid());
            }

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

        int nameX = MARGIN + 44;
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
        if (StringUtil.isBlank(url) || url.contains("@")) {
            return url;
        }
        return url + "@" + AVATAR_SIZE + "w.webp";
    }

    /**
     * 数据卡片：取值与标签
     */
    private record Card(String value, String label) {
    }
}
