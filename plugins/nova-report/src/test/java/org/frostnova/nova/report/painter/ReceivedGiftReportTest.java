package org.frostnova.nova.report.painter;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.event.live.BilibiliFreeGiftEvent;
import org.frostnova.nova.bilibili.event.live.BilibiliPaidGiftEvent;
import org.frostnova.nova.bilibili.event.live.BilibiliRandomGiftEvent;
import org.frostnova.nova.bilibili.model.BilibiliLiveReportOptions;
import org.frostnova.nova.bilibili.model.Room;
import org.frostnova.nova.bilibili.service.BilibiliLiveStatsAggregator;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.analytics.LiveDetail;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.model.GiftInfo;
import org.frostnova.nova.core.analytics.LiveGiftTotal;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import org.frostnova.nova.core.model.UserInfo;
import org.frostnova.nova.core.service.DefaultLiveDataService;
import org.frostnova.nova.core.service.LiveDetailArchive;
import org.frostnova.nova.core.service.LiveRoomInfoHistory;
import org.frostnova.nova.core.service.NovaStateStore;
import org.frostnova.nova.report.factory.NovaCommonPainterFactory;
import org.frostnova.nova.report.util.FontUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.io.DefaultResourceLoader;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 下播报告「收到的礼物」：按种类累计、落档回读、分档排版、隐藏金额时整段不画。
 */
@DisplayName("收到的礼物")
class ReceivedGiftReportTest {
    private static final String PLATFORM = "bilibili";

    private static final LiveStreamerInfo STREAMER = new LiveStreamerInfo(10001L, "主播甲", 20002L);

    private static final long START = 1_700_000_000_000L;

    @TempDir
    Path dir;

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    @Test
    @DisplayName("付费、免费、盲盒按种类累计，开播清零后为空")
    void recordsEachKindAndClearsOnNextLive() {
        NovaCoreProperties properties = properties();
        DefaultLiveDataService data = new DefaultLiveDataService(properties);
        BilibiliLiveStatsAggregator aggregator =
                new BilibiliLiveStatsAggregator(data, new LiveDetailArchive(properties));

        aggregator.onPaidGift(new BilibiliPaidGiftEvent(STREAMER, user(1L),
                gift(11L, "小电视", 124.5, 1, "https://img.example/tv.png"), 124.5));
        aggregator.onPaidGift(new BilibiliPaidGiftEvent(STREAMER, user(2L),
                gift(11L, "小电视改名", 124.5, 2, "https://img.example/tv-later.png"), 249.0));

        aggregator.onFreeGift(new BilibiliFreeGiftEvent(STREAMER, user(3L),
                gift(22L, "辣条", 0.0, 1, "https://img.example/latiao.png")));
        aggregator.onFreeGift(new BilibiliFreeGiftEvent(STREAMER, user(3L),
                gift(22L, "辣条", 0.0, 4, null)));

        aggregator.onFreeGift(new BilibiliFreeGiftEvent(STREAMER, user(4L),
                gift(null, "荧光棒", 0.0, 2, "https://img.example/stick.png")));
        aggregator.onFreeGift(new BilibiliFreeGiftEvent(STREAMER, user(4L),
                gift(null, "荧光棒", 0.0, 3, "https://img.example/stick-later.png")));

        aggregator.onPaidGift(new BilibiliPaidGiftEvent(STREAMER, user(5L),
                gift(7L, "小心心", 0.1, 1, ""), 0.1));
        aggregator.onPaidGift(new BilibiliPaidGiftEvent(STREAMER, user(5L),
                gift(7L, "小心心", 0.1, 1, "https://img.example/heart.png"), 0.1));

        GiftInfo box = gift(99L, "幸运盲盒", 10.0, 1, "https://img.example/box.png");
        GiftInfo opened = gift(33L, "摩天大楼", 450.0, 1, "https://img.example/tower.png");
        aggregator.onRandomGift(new BilibiliRandomGiftEvent(STREAMER, user(6L), box, opened, 10.0, 450.0));
        aggregator.onRandomGift(new BilibiliRandomGiftEvent(STREAMER, user(6L), box, opened, 10.0, 450.0));

        List<LiveGiftTotal> gifts = data.getLiveGifts(PLATFORM, STREAMER.getUid());
        assertEquals(5, gifts.size(), "五种礼物，盲盒本身不入表");
        assertFalse(gifts.stream().anyMatch(gift -> "幸运盲盒".equals(gift.name())), "盲盒盒子不记");

        LiveGiftTotal television = byName(gifts, "小电视");
        assertEquals(11L, television.id());
        assertEquals(124.5, television.price(), 0.0001);
        assertEquals(3, television.count());
        assertEquals("https://img.example/tv.png", television.url());

        LiveGiftTotal snack = byName(gifts, "辣条");
        assertEquals(22L, snack.id());
        assertEquals(0.0, snack.price(), 0.0001);
        assertEquals(5, snack.count());
        assertEquals("https://img.example/latiao.png", snack.url());

        LiveGiftTotal stick = byName(gifts, "荧光棒");
        assertEquals(null, stick.id());
        assertEquals(0.0, stick.price(), 0.0001);
        assertEquals(5, stick.count());
        assertEquals("https://img.example/stick.png", stick.url());

        LiveGiftTotal heart = byName(gifts, "小心心");
        assertEquals(7L, heart.id());
        assertEquals(0.1, heart.price(), 0.0001);
        assertEquals(2, heart.count());
        assertEquals("https://img.example/heart.png", heart.url());

        LiveGiftTotal tower = byName(gifts, "摩天大楼");
        assertEquals(33L, tower.id());
        assertEquals(450.0, tower.price(), 0.0001);
        assertEquals(2, tower.count());
        assertEquals("https://img.example/tower.png", tower.url());

        data.resetLiveData(PLATFORM, STREAMER.getUid());
        assertTrue(data.getLiveGifts(PLATFORM, STREAMER.getUid()).isEmpty(), "开播清零后礼物表为空");
    }

    @Test
    @DisplayName("礼物表写入明细再读回一致，旧文件没有这一项时为空表")
    void detailRoundTripKeepsGiftsAndLegacyFileIsEmpty() throws Exception {
        NovaCoreProperties properties = properties();
        LiveDetailArchive archive = new LiveDetailArchive(properties);
        List<LiveGiftTotal> gifts = List.of(
                new LiveGiftTotal(11L, "小电视", 124.5, 3, "https://img.example/tv.png"),
                new LiveGiftTotal(null, "荧光棒", 0.0, 5, null));
        archive.store(detail(gifts));

        LiveDetail read = archive.read(PLATFORM, STREAMER.getUid(), START).orElseThrow();
        assertEquals(gifts, read.gifts());

        Path file = dir.resolve("details")
                .resolve(PLATFORM + "-" + STREAMER.getUid() + "-" + START)
                .resolve("detail.json");
        JSONObject json = JSON.parseObject(Files.readString(file, StandardCharsets.UTF_8));
        json.remove("gifts");
        Files.writeString(file, json.toJSONString(), StandardCharsets.UTF_8);

        LiveDetail legacy = archive.read(PLATFORM, STREAMER.getUid(), START).orElseThrow();
        assertTrue(legacy.gifts().isEmpty(), "旧明细没有礼物表时应读成空表");
    }

    @Test
    @DisplayName("贵重的排在上面，各档每行种数与图标尺寸固定，超过三十种末行汇总")
    void laysOutExpensiveTiersFirst() {
        List<LiveGiftTotal> gifts = new ArrayList<>();
        add(gifts, 520, 1);
        add(gifts, 199, 1);
        add(gifts, 124, 1);
        add(gifts, 100, 1);
        add(gifts, 99, 1);
        add(gifts, 50, 1);
        add(gifts, 20, 1);
        add(gifts, 15, 1);
        add(gifts, 10, 1);
        add(gifts, 9.9, 1);
        add(gifts, 6, 1);
        add(gifts, 5, 1);
        add(gifts, 3, 1);
        add(gifts, 2, 1);
        add(gifts, 1, 1);
        add(gifts, 0.9, 1);
        add(gifts, 0.5, 1);
        add(gifts, 0.2, 1);
        add(gifts, 0.1, 1);
        add(gifts, 0, 5);
        add(gifts, 0, 3);
        add(gifts, 0, 1);

        List<ReceivedGiftLayout.Line> lines = ReceivedGiftLayout.layout(gifts);
        assertEquals(8, lines.size());
        expectRow(lines.get(0), 96, 3, 3);
        expectRow(lines.get(1), 96, 3, 1);
        expectRow(lines.get(2), 72, 4, 4);
        expectRow(lines.get(3), 72, 4, 1);
        expectRow(lines.get(4), 60, 5, 5);
        expectRow(lines.get(5), 60, 5, 1);
        expectRow(lines.get(6), 48, 6, 6);
        expectRow(lines.get(7), 48, 6, 1);

        double previous = Double.POSITIVE_INFINITY;
        int previousCount = Integer.MAX_VALUE;
        for (ReceivedGiftLayout.Line line : lines) {
            ReceivedGiftLayout.GiftRow row = (ReceivedGiftLayout.GiftRow) line;
            for (LiveGiftTotal gift : row.gifts()) {
                assertTrue(gift.price() < previous || (gift.price() == previous && gift.count() <= previousCount),
                        "应按单价从高到低，同价按个数从多到少");
                if (gift.price() != previous) {
                    previousCount = Integer.MAX_VALUE;
                }
                previous = gift.price();
                previousCount = gift.count();
            }
        }
        assertEquals(520, ((ReceivedGiftLayout.GiftRow) lines.get(0)).gifts().get(0).price(), 0.0001);
        assertEquals(100, ((ReceivedGiftLayout.GiftRow) lines.get(1)).gifts().get(0).price(), 0.0001);
        assertEquals(10, ((ReceivedGiftLayout.GiftRow) lines.get(3)).gifts().get(0).price(), 0.0001);
        assertEquals(1, ((ReceivedGiftLayout.GiftRow) lines.get(5)).gifts().get(0).price(), 0.0001);

        assertTrue(ReceivedGiftLayout.layout(List.of()).isEmpty());

        List<LiveGiftTotal> many = new ArrayList<>();
        for (int i = 0; i < 31; i++) {
            many.add(new LiveGiftTotal((long) i, "礼物" + i, 200, 31 - i, "u"));
        }
        List<ReceivedGiftLayout.Line> overflow = ReceivedGiftLayout.layout(many);
        assertEquals(11, overflow.size());
        assertTrue(overflow.get(overflow.size() - 1) instanceof ReceivedGiftLayout.OverflowLine);
        ReceivedGiftLayout.OverflowLine tail = (ReceivedGiftLayout.OverflowLine) overflow.get(overflow.size() - 1);
        assertEquals("另有 1 种礼物，共 1 个", tail.text());
        assertEquals(1, tail.kinds());
        assertEquals(1, tail.count());
        for (int i = 0; i < 10; i++) {
            expectRow(overflow.get(i), 96, 3, 3);
        }
    }

    @Test
    @DisplayName("同名同价合成一格，同名不同价仍分开，末行按合成后的种数算")
    void mergesSameNameAndPriceIntoOneCell() {
        List<ReceivedGiftLayout.Line> same = ReceivedGiftLayout.layout(List.of(
                new LiveGiftTotal(33988L, "人气票", 1.0, 3, "https://img.example/ticket.png"),
                new LiveGiftTotal(34003L, "人气票", 1.0, 5, null)));
        List<LiveGiftTotal> ticket = named(same, "人气票");
        assertEquals(1, ticket.size(), "同名同价应合成一格");
        assertEquals(8, ticket.get(0).count(), "个数相加");
        assertEquals(1.0, ticket.get(0).price(), 0.0001);
        assertEquals("https://img.example/ticket.png", ticket.get(0).url(), "图标取先有的那张");

        List<ReceivedGiftLayout.Line> laterIcon = ReceivedGiftLayout.layout(List.of(
                new LiveGiftTotal(31164L, "粉丝团灯牌", 2.0, 2, ""),
                new LiveGiftTotal(34358L, "粉丝团灯牌", 2.0, 4, "https://img.example/lamp.png")));
        List<LiveGiftTotal> lamp = named(laterIcon, "粉丝团灯牌");
        assertEquals(1, lamp.size(), "同名同价应合成一格");
        assertEquals(6, lamp.get(0).count(), "个数相加");
        assertEquals("https://img.example/lamp.png", lamp.get(0).url(), "先没有图标时用后来的那张");

        List<ReceivedGiftLayout.Line> split = ReceivedGiftLayout.layout(List.of(
                new LiveGiftTotal(1L, "小电视", 124.5, 1, "https://img.example/tv.png"),
                new LiveGiftTotal(2L, "小电视", 200.0, 2, "https://img.example/tv-dear.png")));
        List<LiveGiftTotal> television = named(split, "小电视");
        assertEquals(2, television.size(), "同名不同价仍分开");
        assertEquals(200.0, television.get(0).price(), 0.0001);
        assertEquals(2, television.get(0).count());
        assertEquals(124.5, television.get(1).price(), 0.0001);
        assertEquals(1, television.get(1).count());

        List<LiveGiftTotal> many = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            many.add(new LiveGiftTotal(1000L + i, "礼物" + i, 500, 10, "u"));
        }
        many.add(new LiveGiftTotal(33988L, "人气票", 0.1, 2, "https://img.example/ticket.png"));
        many.add(new LiveGiftTotal(34003L, "人气票", 0.1, 3, null));
        List<ReceivedGiftLayout.Line> overflow = ReceivedGiftLayout.layout(many);
        assertTrue(overflow.get(overflow.size() - 1) instanceof ReceivedGiftLayout.OverflowLine);
        ReceivedGiftLayout.OverflowLine tail = (ReceivedGiftLayout.OverflowLine) overflow.get(overflow.size() - 1);
        assertEquals("另有 1 种礼物，共 5 个", tail.text());
        assertEquals(1, tail.kinds(), "末行种数按合成后算");
        assertEquals(5, tail.count(), "末行个数按合成后算");
    }

    @Test
    @DisplayName("隐藏金额时礼物列表整段不画，没收到礼物时也不画")
    void hidesGiftListWhenRevenueHidden() throws Exception {
        NovaCoreProperties coreProperties = new NovaCoreProperties();
        coreProperties.getPaint().getFonts().add("内置");
        coreProperties.getLive().setSaveLiveData(false);
        FontUtil fontUtil = new FontUtil(new DefaultResourceLoader(), coreProperties);
        fontUtil.init();

        Properties buildInfo = new Properties();
        buildInfo.setProperty("version", "4.0.0");
        buildInfo.setProperty("group", "org.frostnova.nova");
        buildInfo.setProperty("artifact", "nova-core");
        buildInfo.setProperty("name", "NovaBot");
        NovaCommonPainterFactory factory =
                new NovaCommonPainterFactory(new BuildProperties(buildInfo), coreProperties, fontUtil);

        BufferedImage placeholder = new BufferedImage(96, 96, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = placeholder.createGraphics();
        graphics.setColor(new Color(251, 114, 153));
        graphics.fillRect(0, 0, 96, 96);
        graphics.dispose();
        BilibiliApiUtil api = org.mockito.Mockito.mock(BilibiliApiUtil.class);
        org.mockito.Mockito.when(api.getBilibiliImage(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Optional.of(placeholder));
        Room room = new Room();
        room.setCover("https://pic.example/cover.jpg");
        org.mockito.Mockito.when(api.getLiveInfoByRoomId(org.mockito.ArgumentMatchers.anyLong())).thenReturn(room);
        org.mockito.Mockito.when(api.getGuardList(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong())).thenReturn(Optional.of(List.of()));

        DefaultLiveDataService data = new DefaultLiveDataService(coreProperties);
        data.recordLiveGift(PLATFORM, STREAMER.getUid(), 11L, "小电视", 124.5, 2, "https://img.example/tv.png");
        data.recordLiveGift(PLATFORM, STREAMER.getUid(), 22L, "辣条", 0.0, 5, "https://img.example/latiao.png");
        BilibiliLiveReportPainter painter = new BilibiliLiveReportPainter(
                factory, api, data, fontUtil, new NovaBilibiliProperties(),
                new LiveRoomInfoHistory(new NovaStateStore(coreProperties)));

        JSONObject params = quietParams();
        int shown = heightOf(painter.paint(PLATFORM, STREAMER, BilibiliLiveReportOptions.of(params, true)).orElseThrow());
        int hidden = heightOf(painter.paint(PLATFORM, STREAMER, BilibiliLiveReportOptions.of(params, false)).orElseThrow());
        data.resetLiveData(PLATFORM, STREAMER.getUid());
        int empty = heightOf(painter.paint(PLATFORM, STREAMER, BilibiliLiveReportOptions.of(params, true)).orElseThrow());

        assertTrue(hidden < shown, "隐藏金额时礼物列表不出，图应更矮：" + hidden + " vs " + shown);
        assertTrue(empty < shown, "没收到礼物时整段不画：" + empty + " vs " + shown);
        assertEquals(empty, hidden, "隐藏金额与没有礼物都应少掉同一段");
    }

    private NovaCoreProperties properties() {
        NovaCoreProperties properties = new NovaCoreProperties();
        properties.getLive().setSaveLiveData(false);
        properties.getLive().setLiveDataPath(dir.resolve("data.json").toString());
        return properties;
    }

    private static LiveDetail detail(List<LiveGiftTotal> gifts) {
        return new LiveDetail(LiveDetail.VERSION, PLATFORM, STREAMER.getUid(), "主播甲", 20002L,
                START, START + 3600_000, 3600,
                java.util.Map.of(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of(),
                List.of(), List.of(), List.of(), java.util.Map.of(), gifts);
    }

    private static JSONObject quietParams() {
        JSONObject params = new JSONObject();
        params.put("cover", false);
        params.put("cards", false);
        params.put("fans_change", false);
        params.put("interaction_curve", false);
        params.put("highlights", false);
        params.put("danmu_cloud", false);
        params.put("guard_list", false);
        params.put("guard_list_all", false);
        params.put("danmu_ranking", 0);
        params.put("gift_ranking", 0);
        params.put("super_chat_ranking", 0);
        params.put("box_ranking", 0);
        params.put("box_profit_ranking", 0);
        return params;
    }

    private static void expectRow(ReceivedGiftLayout.Line line, int iconSize, int columns, int count) {
        assertTrue(line instanceof ReceivedGiftLayout.GiftRow, "这一行应是礼物格");
        ReceivedGiftLayout.GiftRow row = (ReceivedGiftLayout.GiftRow) line;
        assertEquals(iconSize, row.iconSize());
        assertEquals(columns, row.columns());
        assertEquals(count, row.gifts().size());
    }

    private static void add(List<LiveGiftTotal> gifts, double price, int count) {
        gifts.add(new LiveGiftTotal((long) gifts.size() + 1, "礼" + gifts.size(), price, count, "u"));
    }

    private static LiveGiftTotal byName(List<LiveGiftTotal> gifts, String name) {
        return gifts.stream().filter(gift -> name.equals(gift.name())).findFirst().orElseThrow();
    }

    private static List<LiveGiftTotal> named(List<ReceivedGiftLayout.Line> lines, String name) {
        List<LiveGiftTotal> found = new ArrayList<>();
        for (ReceivedGiftLayout.Line line : lines) {
            if (line instanceof ReceivedGiftLayout.GiftRow) {
                for (LiveGiftTotal gift : ((ReceivedGiftLayout.GiftRow) line).gifts()) {
                    if (name.equals(gift.name())) {
                        found.add(gift);
                    }
                }
            }
        }
        return found;
    }

    private static GiftInfo gift(Long id, String name, double price, int count, String url) {
        return new GiftInfo(id, name, price, count, url);
    }

    private static UserInfo user(long uid) {
        return new UserInfo(uid, "用户" + uid, null);
    }

    private static int heightOf(String base64) throws Exception {
        byte[] png = Base64.getDecoder().decode(base64);
        return ImageIO.read(new ByteArrayInputStream(png)).getHeight();
    }
}
