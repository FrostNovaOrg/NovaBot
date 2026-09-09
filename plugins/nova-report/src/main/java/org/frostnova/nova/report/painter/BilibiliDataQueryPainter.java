package org.frostnova.nova.report.painter;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.model.TextWithStyle;
import org.frostnova.nova.core.model.UserScore;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.frostnova.nova.core.lang.StringUtil;
import org.frostnova.nova.report.factory.NovaCommonPainterFactory;
import org.frostnova.nova.report.util.ImageUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.awt.Color;
import java.awt.Font;
import java.awt.Point;
import java.awt.image.BufferedImage;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.DoubleFunction;

/**
 * 数据查询结果绘制器
 * <p>
 * 「我的数据」「直播间数据」「数据排行榜」共用的出图。做成图片而非文本有两个实际理由：
 * 一是数十行的排行榜在群里刷屏且难读，二是 QQ 对长文本消息有截断。
 * <p>
 * 视觉语言与下播报告一致（同一套配色、卡片与名次条），但幅面更窄：
 * 查询结果是随手一问的东西，不该像报告那样占满整个聊天窗口。
 */
@Slf4j
@NovaComponent
public class BilibiliDataQueryPainter {
    /**
     * 图片总宽度。比下播报告（900）窄，查询结果是随手看的，不必占满屏
     */
    private static final int WIDTH = 760;

    private static final int INITIAL_HEIGHT = 900;

    private static final int CANVAS_RADIUS = 25;

    private static final int MARGIN = 35;

    private static final int CONTENT_WIDTH = WIDTH - MARGIN * 2;

    private static final int AVATAR_SIZE = 88;

    private static final int CARD_COLUMNS = 3;

    private static final int CARD_GAP = 16;

    private static final int CARD_WIDTH = (CONTENT_WIDTH - CARD_GAP * (CARD_COLUMNS - 1)) / CARD_COLUMNS;

    private static final int CARD_HEIGHT = 104;

    private static final int CARD_RADIUS = 16;

    private static final int RANKING_ROW_HEIGHT = 44;

    private static final int RANKING_BAR_HEIGHT = 14;

    /**
     * 排行榜头像的直径
     */
    private static final int RANKING_AVATAR_SIZE = 30;

    /**
     * 榜单各列的左端（相对内容区）：头像、昵称、比例条
     * <p>
     * 昵称的可用宽度是 {@code BAR_X - NAME_X} 再留 12px 不贴条子，
     * 见 {@link #NAME_MAX_WIDTH}（这一处压过比例条，是看样张才发现的）
     */
    private static final int AVATAR_X = 36;

    private static final int NAME_X = 76;

    private static final int BAR_X = 330;

    /**
     * 昵称的可用宽度，单位像素
     * <p>
     * 🔴 <b>原先写的是「最多 9 个字」。</b>按字数收，安全与否取决于用户起了什么名字：
     * 9 个全角汉字比 9 个半角字母宽一倍多。改成按像素量真宽度
     */
    private static final int NAME_MAX_WIDTH = BAR_X - NAME_X - 12;

    private static final Color COLOR_NAME = new Color(251, 114, 153);

    private static final Color COLOR_TIP = new Color(153, 162, 170);

    private static final Color COLOR_TEXT = new Color(51, 51, 51);

    private static final Color COLOR_CARD = new Color(246, 247, 249);

    private final NovaCommonPainterFactory factory;

    private final BilibiliApiUtil api;

    /**
     * 头像下载失败的哨兵值：Caffeine 不缓存 null，坏地址否则会每次都重试
     */
    private static final BufferedImage FAILED_AVATAR = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);

    private final Cache<String, BufferedImage> avatarCache = Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterWrite(Duration.ofHours(6))
            .build();

    @Autowired
    public BilibiliDataQueryPainter(NovaCommonPainterFactory factory, BilibiliApiUtil api) {
        this.factory = factory;
        this.api = api;
    }

    /**
     * 绘制卡片式数据
     * @param header 头部信息
     * @param cards 数据卡片，为空时只出头部与脚注
     * @param footnote 脚注，可为空
     * @return 图片的 Base64 编码，绘制失败时为空
     */
    public Optional<String> paintCards(Header header, List<DataCard> cards, String footnote) {
        return render(header, footnote, painter -> drawCards(painter, cards));
    }

    /**
     * 绘制排行榜
     * @param header 头部信息
     * @param rows 榜单行，已按得分降序
     * @param startRank 首行的名次，翻页时从 11、21 起
     * @param scoreText 得分的展示文案
     * @param footnote 脚注，可为空
     * @return 图片的 Base64 编码，绘制失败时为空
     */
    public Optional<String> paintRanking(Header header, List<UserScore> rows, int startRank,
                                         DoubleFunction<String> scoreText, String footnote) {
        return render(header, footnote, painter -> drawRanking(painter, rows, startRank, scoreText));
    }

    /**
     * 出图的骨架：头部、正文、脚注、署名与背景
     */
    private Optional<String> render(Header header, String footnote, Consumer<CommonPainter> body) {
        try {
            CommonPainter painter = factory.create(WIDTH, INITIAL_HEIGHT, true);
            painter.setPos(MARGIN, MARGIN);

            drawHeader(painter, header);
            body.accept(painter);

            if (StringUtil.isNotBlank(footnote)) {
                painter.movePos(0, 6);
                // 开自动折行而不是 drawTip：drawTip 不折行，而脚注要装口径说明这类整句，
                // 一行放不下就会画到画布外面去——只是画布外，报错都不会有
                painter.drawTextWithStyle(
                        List.of(new TextWithStyle(footnote, CommonPainter.TIP_FONT_SIZE, COLOR_TIP, Font.PLAIN)),
                        null, true, MARGIN);
            }

            painter.movePos(0, 16);
            painter.drawCopyright(MARGIN);
            painter.movePos(0, 10);

            // 该调用同时把画布裁剪至实际内容高度并铺上背景，必须在全部内容绘制完毕后执行
            painter.createSolidRoundedRectangleBackground(Color.WHITE, CANVAS_RADIUS);

            return painter.base64();
        } catch (Exception e) {
            log.error("绘制数据查询结果「{}」失败", header.title(), e);
            return Optional.empty();
        }
    }

    /**
     * 绘制头部：圆形头像、标题与副标题。头像不可得时退化为纯文字头部
     */
    private void drawHeader(CommonPainter painter, Header header) {
        int top = painter.getY();
        BufferedImage face = Optional.ofNullable(header.faceUrl())
                .filter(StringUtil::isNotBlank)
                .flatMap(url -> api.getBilibiliImage(atSize(url)))
                .map(image -> ImageUtil.maskToCircle(ImageUtil.resize(image, AVATAR_SIZE, AVATAR_SIZE)))
                .orElse(null);

        int textX = MARGIN;
        if (face != null) {
            painter.drawImage(face, new Point(MARGIN, top));
            textX = MARGIN + AVATAR_SIZE + 22;
        }

        painter.drawSection(header.title(), COLOR_NAME, new Point(textX, top + 6));
        if (StringUtil.isNotBlank(header.subtitle())) {
            painter.drawTip(header.subtitle(), COLOR_TIP, new Point(textX, top + 54));
        }

        painter.setPos(MARGIN, top + (face != null ? AVATAR_SIZE : 60) + 24);
    }

    /**
     * 绘制数据卡片栅格
     */
    private void drawCards(CommonPainter painter, List<DataCard> cards) {
        if (cards.isEmpty()) {
            return;
        }

        int startY = painter.getY();
        for (int i = 0; i < cards.size(); i++) {
            DataCard card = cards.get(i);
            int x = MARGIN + (i % CARD_COLUMNS) * (CARD_WIDTH + CARD_GAP);
            int y = startY + (i / CARD_COLUMNS) * (CARD_HEIGHT + CARD_GAP);

            painter.drawRoundedRectangle(x, y, CARD_WIDTH, CARD_HEIGHT, CARD_RADIUS, COLOR_CARD);
            painter.drawTextWithStyle(List.of(new TextWithStyle(card.value(), 32, COLOR_TEXT, Font.BOLD)),
                    new Point(x + 18, y + 14));
            painter.drawTextWithStyle(List.of(new TextWithStyle(card.label(), 20, COLOR_TIP, Font.PLAIN)),
                    new Point(x + 18, y + 62));
        }

        int rows = (cards.size() + CARD_COLUMNS - 1) / CARD_COLUMNS;
        painter.setPos(MARGIN, startY + rows * (CARD_HEIGHT + CARD_GAP) + 4);
    }

    /**
     * 绘制排行榜，条形长度按本页榜首归一化
     */
    private void drawRanking(CommonPainter painter, List<UserScore> rows, int startRank, DoubleFunction<String> scoreText) {
        if (rows.isEmpty()) {
            return;
        }

        double top = rows.get(0).score();
        for (int i = 0; i < rows.size(); i++) {
            drawRankingRow(painter, startRank + i, rows.get(i), top, scoreText);
        }
        painter.movePos(0, 6);
    }

    /**
     * 绘制排行榜的一行：名次、昵称、比例条与得分
     */
    private void drawRankingRow(CommonPainter painter, int rank, UserScore user, double topScore, DoubleFunction<String> scoreText) {
        int y = painter.getY();

        painter.drawTextWithStyle(List.of(new TextWithStyle(String.valueOf(rank), 24, rankColor(rank), Font.BOLD)),
                new Point(MARGIN + 4, y + 6));

        // 头像取不到就空着位置，让各行昵称仍然左端对齐
        BufferedImage avatar = avatar(user.userFace());
        if (avatar != null) {
            painter.drawImage(avatar, new Point(MARGIN + AVATAR_X, y + (RANKING_ROW_HEIGHT - RANKING_AVATAR_SIZE) / 2));
        }

        painter.drawTextWithStyle(List.of(new TextWithStyle(truncate(painter, displayName(user)), 24, COLOR_TEXT, Font.PLAIN)),
                new Point(MARGIN + NAME_X, y + 6));

        int barX = MARGIN + BAR_X;
        int barWidth = CONTENT_WIDTH - BAR_X - 140;
        painter.drawRoundedRectangle(barX, y + 12, barWidth, RANKING_BAR_HEIGHT, RANKING_BAR_HEIGHT / 2, COLOR_CARD);

        // 盈亏榜可能出现负分或榜首为 0，按绝对值取比例并留一段最小可见长度
        double ratio = topScore == 0 ? 0 : Math.abs(user.score()) / Math.abs(topScore);
        int filled = (int) Math.round(barWidth * Math.max(0, Math.min(1, ratio)));
        if (filled > 0) {
            painter.drawRoundedRectangle(barX, y + 12, Math.max(filled, RANKING_BAR_HEIGHT),
                    RANKING_BAR_HEIGHT, RANKING_BAR_HEIGHT / 2, rankColor(rank));
        }

        painter.drawTextWithStyle(List.of(new TextWithStyle(scoreText.apply(user.score()), 24, COLOR_TEXT, Font.PLAIN)),
                new Point(barX + barWidth + 14, y + 6));

        painter.setPos(MARGIN, y + RANKING_ROW_HEIGHT);
    }

    /**
     * 取排行榜用的圆形头像，带缓存
     * <p>
     * 头像地址随计分一并记录，这里只需下载图片、不打接口。按地址缓存：
     * 同一个人在多次查询里出现只下载一次。
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
     * 榜单上的展示名：昵称未记录时退回 uid，总比留白强
     */
    private String displayName(UserScore user) {
        return StringUtil.isBlank(user.displayName()) ? String.valueOf(user.userUid()) : user.displayName();
    }

    /**
     * 截断过长的昵称，避免顶到比例条
     * <p>
     * 按像素收，理由见 {@link #NAME_MAX_WIDTH}。顺带解决 {@code substring}
     * 把 emoji 劈成半个代理项的问题——昵称里 emoji 很常见
     */
    private String truncate(CommonPainter painter, String name) {
        return painter.truncateToWidth(new TextWithStyle(name, 24, COLOR_TEXT, Font.PLAIN), NAME_MAX_WIDTH);
    }

    /**
     * 为图片地址附加缩放参数，避免下载原图
     */
    private String atSize(String url) {
        return atSize(url, AVATAR_SIZE);
    }

    /**
     * 为图片地址附加指定宽度的缩放参数。榜单头像只有 30px，下原图既慢又浪费
     */
    private String atSize(String url, int size) {
        return url.contains("@") ? url : url + "@" + size + "w.webp";
    }

    /**
     * 图片头部
     * @param title 标题，如主播昵称或查询者昵称
     * @param subtitle 副标题，如「本场数据 · 测试主播的直播间」
     * @param faceUrl 头像地址，可为空
     */
    public record Header(String title, String subtitle, String faceUrl) {
    }

    /**
     * 一张数据卡片
     * @param value 主体数值
     * @param label 下方说明
     */
    public record DataCard(String value, String label) {
    }
}
