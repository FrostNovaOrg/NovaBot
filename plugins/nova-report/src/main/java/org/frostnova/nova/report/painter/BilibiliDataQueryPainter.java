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
import org.springframework.web.client.HttpStatusCodeException;

import java.awt.Color;
import java.awt.Font;
import java.awt.Point;
import java.awt.image.BufferedImage;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.DoubleFunction;
import java.util.stream.Collectors;

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
     * 榜单各列的左端（相对内容区）：名次、头像、昵称、比例条
     * <p>
     * 名次从 {@link #RANK_TEXT_X} 起画，头像、昵称跟着让开（见 {@link #rankingLayout}）；
     * 比例条仍从 {@link #BAR_X} 起，得分在条子右边，这两处不受名次列宽影响
     */
    private static final int RANK_TEXT_X = 4;

    /**
     * 名次与头像、头像与昵称之间各留这么多像素不贴着
     */
    private static final int RANK_COLUMN_GAP = 10;

    private static final int BAR_X = 330;

    private static final Color COLOR_NAME = new Color(251, 114, 153);

    private static final Color COLOR_TIP = new Color(153, 162, 170);

    private static final Color COLOR_TEXT = new Color(51, 51, 51);

    private static final Color COLOR_CARD = new Color(246, 247, 249);

    private final NovaCommonPainterFactory factory;

    private final BilibiliApiUtil api;

    /**
     * 整张图等头像的总时间：到点没取回的空着位置照出图
     * <p>
     * 一张图五十行，逐张同步下，源站慢时就是五十倍的等待；整图只等这么久，
     * 之后画手只认缓存里已经有的那几张
     */
    private static final long AVATAR_WAIT_MILLIS = 3000;

    /**
     * 头像下载线程数。定长而不是来一个起一个：源站慢时线程本身就是会堆积的那种东西
     */
    private static final int AVATAR_FETCH_THREADS = 8;

    /**
     * 头像下载失败的哨兵值：Caffeine 不缓存 null，坏地址否则会每次都重试
     * <p>
     * 只给「源站明说没有这张图」用。等到点没等到的、取图抛出来的那次都不算失败，不入这张缓存
     */
    private static final BufferedImage FAILED_AVATAR = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);

    private final Cache<String, BufferedImage> avatarCache = Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterWrite(Duration.ofHours(6))
            .build();

    /**
     * 同一张地址同一时间只排一趟下载，别的请求跟着那只排队的结果走
     */
    private final ConcurrentHashMap<String, CompletableFuture<BufferedImage>> avatarInFlight = new ConcurrentHashMap<>();

    /**
     * 头像下载线程：定长、守护、排队有上限
     * <p>
     * 三条都为「源站慢」这一件事：定长再加同一张地址只排一次（见 {@link #avatarInFlight}），
     * 再慢的源站也堆不出无上限的线程与排队；队列是这世上唯一可能堆东西的地方，给它个上限。
     * 守护线程——出图失败进程照样退得出，不为一张图拖住整个程序
     */
    private static final ThreadPoolExecutor AVATAR_FETCHERS = new ThreadPoolExecutor(
            AVATAR_FETCH_THREADS, AVATAR_FETCH_THREADS, 60, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(512),
            runnable -> {
                Thread thread = new Thread(runnable, "bilibili-avatar-fetch");
                thread.setDaemon(true);
                return thread;
            });

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
     * @param startRank 首行的名次，一张长图从 1 起
     * @param scoreText 得分的展示文案
     * @param footnote 脚注，可为空
     * @return 图片的 Base64 编码，绘制失败时为空
     */
    public Optional<String> paintRanking(Header header, List<UserScore> rows, int startRank,
                                         DoubleFunction<String> scoreText, String footnote) {
        // 先把头像并发取回来，整图最多等 {@link #AVATAR_WAIT_MILLIS}；到点没取到的画空位
        prefetchAvatars(rows);
        return render(header, footnote, painter -> drawRanking(painter, rows, startRank, scoreText));
    }

    /**
     * 量出「画这么多行、脚注写成这样」的整图像素高度，不出图
     * <p>
     * 图高不是行数乘行高那么直白：脚注要折几行取决于它自己写了什么，而「其余 N 名未列出」
     * 这一行正是随着列几个人变的。要按高度上限截行，就得先知道真高度。
     * <p>
     * 只排版不下图：行高是定死的，头像在不在都不改高度；头部头像给的是地址而不是图片，
     * 拿不到时真实出图只会更矮，按「地址给了就算有」来量，量出来的只会略高、不会越上限。
     *
     * @param header 头部信息
     * @param rowCount 要画的名次数
     * @param footnote 脚注，可为空
     * @return 出图的像素高度；量不出来时返回 0——那是「不截行」，宁可出一张长图，
     *         也不要因为量不出高度就静默少列一堆人
     */
    public int measureRankingHeight(Header header, int rowCount, String footnote) {
        try {
            CommonPainter painter = factory.create(WIDTH, INITIAL_HEIGHT, true);
            painter.setPos(MARGIN, MARGIN + headerBlockHeight(StringUtil.isNotBlank(header.faceUrl())));
            painter.setPos(MARGIN, painter.getY() + rankingBlockHeight(rowCount));
            drawFootnoteAndCopyright(painter, footnote);
            return painter.getY();
        } catch (Exception e) {
            log.error("量取排行榜高度失败", e);
            return 0;
        }
    }

    /**
     * 头部占多高：有头像时一行 {@link #AVATAR_SIZE}，纯文字头部 60，末尾再空 24
     * <p>
     * 出图与量高度共用这一个式子，免得两处的落点算得不一样
     */
    private static int headerBlockHeight(boolean withFace) {
        return (withFace ? AVATAR_SIZE : 60) + 24;
    }

    /**
     * 排行榜正文占多高：每行 {@link #RANKING_ROW_HEIGHT}，末尾再空 6
     */
    private static int rankingBlockHeight(int rowCount) {
        return rowCount == 0 ? 0 : rowCount * RANKING_ROW_HEIGHT + 6;
    }

    /**
     * 脚注与署名那一段。出图与量高度共用，免得两处的行距算得不一样
     */
    private void drawFootnoteAndCopyright(CommonPainter painter, String footnote) {
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
            drawFootnoteAndCopyright(painter, footnote);

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

        painter.setPos(MARGIN, top + headerBlockHeight(face != null));
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
     * 绘制排行榜，条形长度按榜首归一化
     */
    private void drawRanking(CommonPainter painter, List<UserScore> rows, int startRank, DoubleFunction<String> scoreText) {
        if (rows.isEmpty()) {
            return;
        }

        RowLayout layout = rankingLayout(painter);
        int startY = painter.getY();
        double top = rows.get(0).score();
        for (int i = 0; i < rows.size(); i++) {
            drawRankingRow(painter, startRank + i, rows.get(i), top, scoreText, layout);
        }
        painter.setPos(MARGIN, startY + rankingBlockHeight(rows.size()));
    }

    /**
     * 一行里名次、头像、昵称各落在哪，昵称还能有多宽
     * <p>
     * 名次列宽跟着三位数走，头像与昵称整体右移；昵称仍左对齐，宽度按余量收。
     * 头像与昵称之间、名次与头像之间各留 {@link #RANK_COLUMN_GAP}px
     */
    private RowLayout rankingLayout(CommonPainter painter) {
        int avatarX = RANK_TEXT_X + rankColumnWidth(painter) + RANK_COLUMN_GAP;
        int nameX = avatarX + RANKING_AVATAR_SIZE + RANK_COLUMN_GAP;
        // 昵称不贴比例条，留 12px
        return new RowLayout(avatarX, nameX, BAR_X - nameX - 12);
    }

    /**
     * 名次列占多宽：按三位数「300」的真实字宽量，用的是出图这一支自己的字体
     * <p>
     * 头像左端原先定在 36px，只按两位数留；从第 100 名起，三位数的第三位会被
     * 头像圆盖住，名次读不出来。列宽跟着最大可能的位数走
     */
    private static int rankColumnWidth(CommonPainter painter) {
        return painter.getStringWidthAndHeight(new TextWithStyle("300", 24, COLOR_TEXT, Font.BOLD)).getFirst();
    }

    /**
     * 排行榜一行的横向落点（相对内容区左端）
     */
    private record RowLayout(int avatarX, int nameX, int nameMaxWidth) {
    }

    /**
     * 绘制排行榜的一行：名次、昵称、比例条与得分
     */
    private void drawRankingRow(CommonPainter painter, int rank, UserScore user, double topScore,
                                DoubleFunction<String> scoreText, RowLayout layout) {
        int y = painter.getY();

        painter.drawTextWithStyle(List.of(new TextWithStyle(String.valueOf(rank), 24, rankColor(rank), Font.BOLD)),
                new Point(MARGIN + RANK_TEXT_X, y + 6));

        // 头像取不到就空着位置，让各行昵称仍然左端对齐
        BufferedImage avatar = avatar(user.userFace());
        if (avatar != null) {
            painter.drawImage(avatar,
                    new Point(MARGIN + layout.avatarX(), y + (RANKING_ROW_HEIGHT - RANKING_AVATAR_SIZE) / 2));
        }

        painter.drawTextWithStyle(List.of(new TextWithStyle(truncate(painter, displayName(user), layout.nameMaxWidth()),
                        24, COLOR_TEXT, Font.PLAIN)),
                new Point(MARGIN + layout.nameX(), y + 6));

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
     * 把这一批头像并发取回来，整图最多等 {@link #AVATAR_WAIT_MILLIS}
     * <p>
     * 等到点就往下画：没取回的空着位置，别让整张图跟着一张头像等下去。
     * 只等「已经排上队的」那一批，画这一路不再下载（见 {@link #avatar}）
     */
    private void prefetchAvatars(List<UserScore> rows) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(AVATAR_WAIT_MILLIS);

        Map<String, CompletableFuture<BufferedImage>> pending = new LinkedHashMap<>();
        for (UserScore row : rows) {
            String url = row.userFace();
            if (StringUtil.isBlank(url) || avatarCache.getIfPresent(url) != null) {
                continue;
            }
            pending.put(url, startAvatarFetch(url));
        }
        if (pending.isEmpty()) {
            return;
        }
        if (awaitAvatars(pending, deadline)) {
            return;
        }

        // 等到点还有没落定的：死掉的那只重排一趟，仍在飞的跟着原任务走不重复排队。
        // 被放弃的连接是一次没成，不是「这地址没图」，不重排的话它就永远空着
        Map<String, CompletableFuture<BufferedImage>> secondTry = new LinkedHashMap<>();
        for (Map.Entry<String, CompletableFuture<BufferedImage>> entry : pending.entrySet()) {
            if (entry.getValue().isCompletedExceptionally() && avatarCache.getIfPresent(entry.getKey()) == null) {
                secondTry.put(entry.getKey(), startAvatarFetch(entry.getKey()));
            }
        }
        if (!secondTry.isEmpty()) {
            awaitAvatars(secondTry, deadline);
        }
        warnAvatarsNotFetched(rows, pending, secondTry);
    }

    /**
     * 这一趟没取到的头像，整张图合起来记一条：几张、什么原因
     * <p>
     * 一张图几十张头像，每张各记一条带堆栈的 ERROR，源站一挂就是几十行，看不出是同一件事；
     * 这里合成一行。「源站明说没这张图」不算没取到——那是问出答案了的，另有失败缓存记着。
     * 原因不带异常原文：原文里裹着完整地址
     */
    private void warnAvatarsNotFetched(List<UserScore> rows,
                                       Map<String, CompletableFuture<BufferedImage>> pending,
                                       Map<String, CompletableFuture<BufferedImage>> secondTry) {
        Map<String, Integer> reasons = new LinkedHashMap<>();
        Set<String> counted = new HashSet<>();
        for (UserScore row : rows) {
            String url = row.userFace();
            if (StringUtil.isBlank(url) || !counted.add(url) || avatarCache.getIfPresent(url) != null) {
                continue;
            }
            CompletableFuture<BufferedImage> attempt = secondTry.getOrDefault(url, pending.get(url));
            reasons.merge(missReason(attempt), 1, Integer::sum);
        }
        if (reasons.isEmpty()) {
            return;
        }
        String breakdown = reasons.entrySet().stream()
                .map(entry -> entry.getKey() + "×" + entry.getValue())
                .collect(Collectors.joining("、"));
        int missed = reasons.values().stream().mapToInt(Integer::intValue).sum();
        log.warn("名次行有 {} 张头像这次没取到（{}），位置留空、不记失败，源站好了下一张图照常再取", missed, breakdown);
    }

    /**
     * 这次没取到的原因，写进一行汇总
     */
    private static String missReason(CompletableFuture<BufferedImage> attempt) {
        if (attempt == null) {
            return "排不进队";
        }
        if (!attempt.isDone()) {
            return "到点还没回";
        }
        Throwable failure = attempt.handle((image, error) -> error).getNow(null);
        if (failure instanceof HttpStatusCodeException status) {
            return "HTTP " + status.getStatusCode().value();
        }
        return failure == null ? "没拿到图" : failure.getClass().getSimpleName();
    }

    /**
     * 等到齐回 true；等到点、或有哪只死了，回 false
     */
    private static boolean awaitAvatars(Map<String, CompletableFuture<BufferedImage>> pending, long deadlineNanos) {
        long remainMillis = Math.max(0, TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()));
        try {
            CompletableFuture.allOf(pending.values().toArray(new CompletableFuture[0]))
                    .get(remainMillis, TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException | ExecutionException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 排一趟头像下载；同一张地址在飞时跟着那只走，死掉的那只当场让位
     * <p>
     * 排不进队的那条<b>当场从在飞表拿掉</b>：留着它，源站一直挂时这张表跟着每个新地址一直涨、
     * 没有上界——表里只留排上队与正在取的，条数至多是线程数＋队列长
     */
    private CompletableFuture<BufferedImage> startAvatarFetch(String url) {
        CompletableFuture<BufferedImage> future = avatarInFlight.compute(url, (key, existing) -> {
            if (existing != null && !existing.isCompletedExceptionally()) {
                return existing;
            }
            CompletableFuture<BufferedImage> fresh = new CompletableFuture<>();
            try {
                AVATAR_FETCHERS.execute(() -> downloadAvatar(key, fresh));
            } catch (RejectedExecutionException e) {
                // 排不进去就当没取到，出图不受影响；不落失败缓存，下一趟能排上就照常取
                fresh.completeExceptionally(e);
            }
            return fresh;
        });
        if (future.isCompletedExceptionally()) {
            avatarInFlight.remove(url, future);
        }
        return future;
    }

    /**
     * 下载一张头像并裁圆，结果写进缓存后交差
     * <p>
     * 取到就缓存；源站明说没有这张图也缓存成哨兵值——这两种都是「问过了」。
     * <b>取图抛出来的那次不写任何缓存</b>：连接被放弃、超时、断线都是「这次没取成」，
     * 记 6 小时会把源站刚好不好的那几分钟记成接下来几小时都没头像。
     * 分界在 {@link BilibiliApiUtil#fetchBilibiliImage(String)}：回空＝源站明说没图，抛回＝这次没取成
     */
    private void downloadAvatar(String url, CompletableFuture<BufferedImage> future) {
        try {
            BufferedImage ready = api.fetchBilibiliImage(atSize(url, RANKING_AVATAR_SIZE))
                    .map(image -> ImageUtil.maskToCircle(
                            ImageUtil.resize(image, RANKING_AVATAR_SIZE, RANKING_AVATAR_SIZE)))
                    .orElse(null);
            avatarCache.put(url, ready == null ? FAILED_AVATAR : ready);
            future.complete(ready);
        } catch (RuntimeException e) {
            future.completeExceptionally(e);
        } finally {
            avatarInFlight.remove(url, future);
        }
    }

    /**
     * 取排行榜用的圆形头像，只读缓存
     * <p>
     * 下载在 {@link #prefetchAvatars} 里排队做，这里只认缓存里已经有的：
     * 出图这一路上不许再出现一次同步下载，否则源站一慢整张图就跟着慢
     */
    private BufferedImage avatar(String url) {
        if (StringUtil.isBlank(url)) {
            return null;
        }
        BufferedImage cached = avatarCache.getIfPresent(url);
        // 哨兵值是「源站明说没有」，画成空位；没缓存过（等到点没等到）也是空位
        return cached == null || cached == FAILED_AVATAR ? null : cached;
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
     * 按像素收，宽度由这一行的落点算出来（见 {@link #rankingLayout}）：昵称跟名次列
     * 一起右移后余量是变的，写死一个宽度就会在名次变宽那侧顶到比例条。
     * 顺带解决 {@code substring} 把 emoji 劈成半个代理项的问题——昵称里 emoji 很常见
     */
    private String truncate(CommonPainter painter, String name, int nameMaxWidth) {
        return painter.truncateToWidth(new TextWithStyle(name, 24, COLOR_TEXT, Font.PLAIN), nameMaxWidth);
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
