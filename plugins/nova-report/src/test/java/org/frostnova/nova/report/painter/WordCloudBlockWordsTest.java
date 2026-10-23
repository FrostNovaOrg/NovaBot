package org.frostnova.nova.report.painter;

import com.alibaba.fastjson2.JSON;
import org.frostnova.nova.core.model.PushUser;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.bilibili.service.BilibiliLiveStatsAggregator;
import org.frostnova.nova.core.service.LiveDetailArchive;
import org.frostnova.nova.bilibili.event.live.BilibiliDanmuEvent;
import org.frostnova.nova.core.model.UserInfo;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import java.util.concurrent.atomic.AtomicReference;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.bilibili.BilibiliPlatform;
import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.model.BilibiliLiveReportOptions;
import org.frostnova.nova.bilibili.model.Room;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.bilibili.util.DanmuWordUtil;
import org.frostnova.nova.core.analytics.LiveDetail;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.model.DanmuRecord;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import org.frostnova.nova.core.service.DefaultLiveDataService;
import org.frostnova.nova.core.service.LiveDataService;
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
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 词云可以不显示指定的词：凡是包含屏蔽词的词都不画，英文不分大小写。
 * 已保存的词频不改，只在画图时按当下这张表挑掉。
 */
@DisplayName("词云不显示指定的词")
class WordCloudBlockWordsTest {
    private static final String PLATFORM = BilibiliPlatform.BILIBILI.id();

    private static final long STREAMER = 19_000_000_000_311L;

    private static final long ROOM = 47_000_000_000_411L;

    private static final long BOT = 19_000_000_000_211L;

    private static final long HUMAN = 19_000_000_000_212L;

    private static final long START = 1_700_000_222_000L;

    /** 报告里一张词云最多画的词数，与画手里的上限一致 */
    private static final int CLOUD_MAX_WORDS = 72;

    private static NovaCommonPainterFactory factory;

    private static FontUtil fontUtil;

    @TempDir
    Path temp;

    @BeforeAll
    static void headlessAndFonts() {
        System.setProperty("java.awt.headless", "true");
        NovaCoreProperties core = new NovaCoreProperties();
        core.getPaint().getFonts().add("内置");
        fontUtil = new FontUtil(new DefaultResourceLoader(), core);
        fontUtil.init();
        Properties buildInfo = new Properties();
        buildInfo.setProperty("version", "4.3.0");
        buildInfo.setProperty("group", "com.example");
        buildInfo.setProperty("artifact", "nova-core");
        buildInfo.setProperty("name", "NovaBot");
        factory = new NovaCommonPainterFactory(new BuildProperties(buildInfo), core, fontUtil);
    }

    // 抓的问题：甲的多字词被切碎挡不住，或把乙的词云也挡了。
    @Test
    void streamerPhraseSurvivesCollectionAndOnlyFiltersOwnReport() {
        String phrase = "原神启动";
        var user = JSON.parseObject(
                "{\"wordCloudBlockWords\":[\"原神启动\"]}", PushUser.class);
        var source = mock(AbstractDataSource.class);
        when(source.getUser(PLATFORM, STREAMER)).thenReturn(Optional.of(user));
        LiveDataService data = sessionData(true);
        var aggregator = new BilibiliLiveStatsAggregator(data, mock(LiveDetailArchive.class), properties(List.of(), List.of()));
        CapturePainter painter = painter(data, List.of(), List.of("全局屏蔽"));
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(AbstractDataSource.class, () -> source);
            context.refresh();
            context.getAutowireCapableBeanFactory().autowireBean(aggregator);
            context.getAutowireCapableBeanFactory().autowireBean(painter);
            aggregator.onDanmu(new BilibiliDanmuEvent(streamer(),
                    new UserInfo(HUMAN, "观众甲", null), phrase + "了 唱歌", phrase + "了 唱歌"));
            Map<String, Integer> words = data.getLiveWordFrequencies(PLATFORM, STREAMER);
            assertTrue(words.containsKey(phrase), "直播中多字屏蔽词被切碎: " + words);
            assertFalse(placed(painter.layoutWordCloud(PLATFORM, STREAMER, words)).contains(phrase));
            assertTrue(placed(painter.layoutWordCloud(PLATFORM, STREAMER + 1, words)).contains(phrase));
            Map<String, Integer> sample = Map.of(phrase, 5, "全局屏蔽", 4, "唱歌", 3);
            assertEquals(List.of("唱歌"), placed(painter.layoutWordCloud(PLATFORM, STREAMER, sample)));
            CaptureReplay replay = replay(sample, properties(List.of(), List.of("全局屏蔽")));
            context.getAutowireCapableBeanFactory().autowireBean(replay);
            assertTrue(replay.render(cloudOnly()).isPresent(), "历史报告重画失败");
            assertEquals(List.of("唱歌"), oneCloud(replay.drawn), "历史重画未叠加当前主播名单");
        }
    }

    // 抓的问题：全实例和主播同时设置多字词时，只保住一份，另一份切碎后漏进词云。
    @Test
    void collectionKeepsBothGlobalAndStreamerPhrasesWhole() {
        String global = "星穹铁道";
        String own = "原神启动";
        for (String phrase : List.of(global, own)) {
            assertFalse(DanmuWordUtil.extractWords(phrase + "了").contains(phrase), "测试词应原本会被切碎");
        }
        var source = mock(AbstractDataSource.class);
        when(source.getUser(PLATFORM, STREAMER)).thenReturn(Optional.of(JSON.parseObject(
                "{\"wordCloudBlockWords\":[\"原神启动\"]}", PushUser.class)));
        LiveDataService data = sessionData(true);
        var aggregator = new BilibiliLiveStatsAggregator(data, mock(LiveDetailArchive.class),
                properties(List.of(), List.of(global)));
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(AbstractDataSource.class, () -> source);
            context.refresh();
            context.getAutowireCapableBeanFactory().autowireBean(aggregator);
            for (long uid : List.of(STREAMER, STREAMER + 1)) {
                aggregator.onDanmu(new BilibiliDanmuEvent(new LiveStreamerInfo(uid, "主播", ROOM, null),
                        new UserInfo(HUMAN, "观众", null), global + "了 " + own + "了 唱歌",
                        global + "了 " + own + "了 唱歌"));
            }
            Map<String, Integer> first = data.getLiveWordFrequencies(PLATFORM, STREAMER);
            assertTrue(first.containsKey(global), "全实例词被切碎: " + first);
            assertTrue(first.containsKey(own), "主播词被切碎: " + first);
            Map<String, Integer> other = data.getLiveWordFrequencies(PLATFORM, STREAMER + 1);
            assertTrue(other.containsKey(global), "全实例词未用于乙: " + other);
            assertFalse(other.containsKey(own), "甲的分词名单影响乙: " + other);
        }
    }

    // 抓的问题：历史报告入口没传入推送配置，重画时仍冒出主播现在已屏蔽的词。
    @Test
    void historicalRedrawerUsesCurrentGlobalAndStreamerLists() {
        var current = new AtomicReference<>(new PushUser());
        var source = mock(AbstractDataSource.class);
        when(source.getUser(PLATFORM, STREAMER)).thenAnswer(call -> Optional.of(current.get()));
        var properties = properties(List.of(), List.of("全局屏蔽"));
        var history = new LiveRoomInfoHistory(new NovaStateStore(new NovaCoreProperties()));
        var images = new ReportImageDiskCache(temp.resolve("image-cache"));
        var detail = new LiveDetail(LiveDetail.VERSION, PLATFORM, STREAMER, "主播甲", ROOM,
                START, START + 3_600_000L, 3_600L, Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of("全局屏蔽", 4, "原神启动", 3, "唱歌", 2), List.of(), List.of(), List.of(), Map.of(), List.of());
        var redrawer = new org.frostnova.nova.report.service.BilibiliLiveReportRedrawer(
                factory, quietApi(), fontUtil, properties, history, images);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(AbstractDataSource.class, () -> source);
            context.refresh();
            context.getAutowireCapableBeanFactory().autowireBean(redrawer);
            byte[] before = redrawer.redraw(detail).orElseThrow();
            current.set(JSON.parseObject("{\"wordCloudBlockWords\":[\"原神启动\"]}", PushUser.class));
            byte[] after = redrawer.redraw(detail).orElseThrow();
            assertFalse(java.util.Arrays.equals(before, after), "当前名单变化后，历史图仍相同");
            var expected = new CaptureReplay(factory, quietApi(), fontUtil, properties, history, detail, images);
            expected.setDataSource(source);
            byte[] reference = expected.render(new BilibiliLiveReportOptions()).orElseThrow();
            assertEquals(List.of("唱歌"), oneCloud(expected.drawn), "两份当前名单都应过滤");
            org.junit.jupiter.api.Assertions.assertArrayEquals(reference, after, "历史入口画的图未采用当前两份名单");
        }
    }

    // 抓的问题：甲排除观众后报告仍含其弹幕，修改两份名单后又用了旧重算结果。
    @Test
    void streamerExclusionsAndWordsInvalidateRecountWithoutAffectingOthers() throws Exception {
        writeDanmu(List.of(danmu(BOT, "迎客机", "欢迎"), danmu(BOT, "迎客机", "欢迎"),
                danmu(HUMAN, "观众甲", "原神启动了"), danmu(HUMAN, "观众甲", "唱歌"),
                danmu(999L, "观众乙", "全局排除")));
        LiveDataService data = sessionData(false);
        stored(data, "欢迎", 2); stored(data, "唱歌", 1);
        data.setLiveStartTime(PLATFORM, STREAMER + 1, START);
        data.setLiveEndTime(PLATFORM, STREAMER + 1, START + 3_600_000L);
        Path other = temp.resolve("details").resolve(PLATFORM + "-" + (STREAMER + 1) + "-" + START);
        Files.createDirectories(other);
        Files.copy(temp.resolve("details").resolve(PLATFORM + "-" + STREAMER + "-" + START).resolve("danmu.jsonl"),
                other.resolve("danmu.jsonl"));
        var user = new AtomicReference<>(JSON.parseObject(
                "{\"wordCloudExcludeUids\":[\"" + BOT + "\"],\"wordCloudBlockWords\":[\"原神启动\"]}",
                PushUser.class));
        var source = mock(AbstractDataSource.class);
        when(source.getUser(PLATFORM, STREAMER)).thenAnswer(call -> Optional.ofNullable(user.get()));
        CapturePainter painter = painter(data, List.of("999"), List.of());
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(AbstractDataSource.class, () -> source);
            context.refresh();
            context.getAutowireCapableBeanFactory().autowireBean(painter);
            Map<String, Integer> first = painter.frequenciesForWordCloud(PLATFORM, STREAMER);
            assertFalse(first.containsKey("欢迎"), "被排除观众的弹幕仍在: " + first);
            assertFalse(first.containsKey("全局"), "主播名单覆盖了全实例排除名单");
            assertTrue(painter.paint(PLATFORM, streamer(), cloudOnly()).isPresent());
            assertEquals(List.of("唱歌"), oneCloud(painter.drawn));
            System.out.println("WORD_CLOUD_BEFORE: " + data.getLiveWordFrequencies(PLATFORM, STREAMER));
            System.out.println("WORD_CLOUD_AFTER: " + first);
            System.out.println("WORD_CLOUD_DRAWN: " + oneCloud(painter.drawn));
            assertTrue(first.containsKey("原神启动"));
            assertEquals(2, painter.frequenciesForWordCloud(PLATFORM, STREAMER + 1).get("欢迎"), "甲的排除名单影响了乙");
            user.set(JSON.parseObject("{\"wordCloudExcludeUids\":[\"" + BOT + "\"]}",
                    PushUser.class));
            Map<String, Integer> changedWords = painter.frequenciesForWordCloud(PLATFORM, STREAMER);
            assertFalse(changedWords.containsKey("原神启动"), "屏蔽词修改仍命中旧缓存");
            user.set(new PushUser());
            assertEquals(2, painter.frequenciesForWordCloud(PLATFORM, STREAMER).get("欢迎"), "排除名单修改仍命中旧缓存");
            user.set(null);
            assertEquals(2, painter.frequenciesForWordCloud(PLATFORM, STREAMER).get("欢迎"), "删除主播后应只用全实例名单");
        }
    }

    /**
     * 抓的故障：主播把「上舰」加进屏蔽表以后，下一张下播报告的词云里还有「上舰」，
     * 或者还有「求上舰」这类含它的词。直播中随时拉的实时报告走同一段，一并看。
     */
    @Test
    @DisplayName("加进屏蔽表的词和含它的词，下播报告与直播中的实时报告的词云里都不再出现")
    void reportDropsBlockedWordAndWordsContainingIt() {
        for (boolean live : new boolean[]{false, true}) {
            LiveDataService data = sessionData(live);
            stored(data, "上舰", 30);
            stored(data, "求上舰", 20);
            stored(data, "唱歌", 3);
            CapturePainter painter = painter(data, List.of(), List.of("上舰"));

            painter.paint(PLATFORM, streamer(), cloudOnly());

            List<String> drawn = oneCloud(painter.drawn);
            String which = live ? "直播中的实时报告" : "下播报告";
            assertAll(
                    () -> assertFalse(drawn.contains("上舰"), which + "里屏蔽词还在: " + drawn),
                    () -> assertFalse(drawn.contains("求上舰"), which + "里含屏蔽词的词还在: " + drawn),
                    () -> assertTrue(drawn.contains("唱歌"), which + "把别的词也拿掉了: " + drawn),
                    () -> assertEquals(Integer.valueOf(30), data.getLiveWordFrequencies(PLATFORM, STREAMER).get("上舰"),
                            "画图时改掉了已经保存的词频"));
        }
    }

    /**
     * 抓的故障：屏蔽表里写「AWSL」，词频里存的是折成小写的「awsl」，没挡住；
     * 屏蔽表写小写「yyds」，弹幕里带汉字的「Yyds好」大小写照原样存着，也没挡住。
     */
    @Test
    @DisplayName("屏蔽词与词云里的词英文大小写不同，照样挡住")
    void blockIgnoresLetterCase() {
        LiveDataService data = sessionData(false);
        stored(data, "awsl", 10);
        stored(data, "Yyds好", 5);
        stored(data, "唱歌", 3);
        CapturePainter painter = painter(data, List.of(), List.of("AWSL", "yyds"));

        painter.paint(PLATFORM, streamer(), cloudOnly());

        List<String> drawn = oneCloud(painter.drawn);
        assertEquals(List.of("唱歌"), drawn, "大小写不同的屏蔽词没挡住: " + drawn);
    }

    /**
     * 抓的故障：改了屏蔽表以后重画旧场，拿到的还是改表之前那张图。
     * 名单非空时重画会按原文重算并留下结果，这里让它走那条留结果的路，
     * 同一个画手先画一次、改表、再画一次。
     */
    @Test
    @DisplayName("改了屏蔽表以后重画旧场，词云按新表画")
    void redrawFollowsChangedBlockList() throws Exception {
        writeDanmu(List.of(
                danmu(BOT, "迎客机", "欢迎"),
                danmu(HUMAN, "观众甲", "上舰"),
                danmu(HUMAN, "观众甲", "上舰"),
                danmu(HUMAN, "观众甲", "唱歌")));
        Map<String, Integer> stored = new LinkedHashMap<>();
        stored.put("欢迎", 1);
        stored.put("上舰", 2);
        stored.put("唱歌", 1);
        NovaBilibiliProperties properties = properties(List.of(Long.toString(BOT)), List.of());
        CaptureReplay painter = replay(stored, properties);

        assertTrue(painter.render(cloudOnly()).isPresent(), "重画没有画出报告");
        assertTrue(oneCloud(painter.drawn).contains("上舰"), "改表之前「上舰」就没画上，测不出变化");

        properties.getLive().setWordCloudBlockWords(new ArrayList<>(List.of("上舰")));
        painter.drawn.clear();
        assertTrue(painter.render(cloudOnly()).isPresent(), "改表后重画没有画出报告");

        List<String> drawn = oneCloud(painter.drawn);
        assertAll(
                () -> assertFalse(drawn.contains("上舰"), "改了屏蔽表以后重画，图还是旧的: " + drawn),
                () -> assertTrue(drawn.contains("唱歌"), "重画把别的词也拿掉了: " + drawn));
    }

    /**
     * 抓的故障：按原文重算那一路（「词云不计这些用户」非空时）切词没带屏蔽表，
     * 多字屏蔽词被切成几段，每段都不含屏蔽词，照样画进词云。
     */
    @Test
    @DisplayName("按弹幕原文重算时，被切开的多字屏蔽词的碎片也不进词云")
    void recountDoesNotLeakFragmentsOfBlockedPhrase() throws Exception {
        String phrase = "原神启动";
        List<String> fragments = DanmuWordUtil.extractWords(phrase + "了");
        assertFalse(fragments.isEmpty() || fragments.contains(phrase),
                "前提不成立：分词器本来就不切这个词，换一个词测: " + fragments);
        writeDanmu(List.of(
                danmu(BOT, "迎客机", "欢迎"),
                danmu(HUMAN, "观众甲", phrase + "了"),
                danmu(HUMAN, "观众甲", "唱歌")));
        LiveDataService data = sessionData(false);
        stored(data, "唱歌", 1);
        CapturePainter painter = painter(data, List.of(Long.toString(BOT)), List.of(phrase));

        painter.paint(PLATFORM, streamer(), cloudOnly());

        List<String> drawn = oneCloud(painter.drawn);
        assertAll(
                () -> assertFalse(fragments.stream().anyMatch(drawn::contains),
                        "屏蔽词的碎片 " + fragments + " 进了词云: " + drawn),
                () -> assertTrue(drawn.contains("唱歌"), "没有按原文重算: " + drawn));
    }

    /**
     * 抓的故障：屏蔽掉的词占着前 72 名的位子，词云少了一块，后面的词没补上来。
     */
    @Test
    @DisplayName("屏蔽掉的词让出位子，由后面的词补上，词云仍是满的")
    void blockedWordsYieldTheirSlots() {
        LiveDataService data = sessionData(false);
        stored(data, "上舰", 500);
        stored(data, "求上舰", 400);
        List<String> rest = new ArrayList<>();
        for (int i = 1; i <= CLOUD_MAX_WORDS; i++) {
            String word = String.format("词%02d", i);
            rest.add(word);
            stored(data, word, 200 - i);
        }
        CapturePainter painter = painter(data, List.of(), List.of("上舰"));

        painter.paint(PLATFORM, streamer(), cloudOnly());

        assertEquals(1, painter.layouts.size(), "词云没有画出来");
        WordCloudLayout.Result layout = painter.layouts.get(0);
        List<String> drawn = oneCloud(painter.drawn);
        assertAll(
                () -> assertEquals(CLOUD_MAX_WORDS, drawn.size() + layout.dropped(),
                        "屏蔽后排进版面的词不是 " + CLOUD_MAX_WORDS + " 个: 画上 " + drawn.size()
                                + "，放不下 " + layout.dropped()),
                () -> assertFalse(drawn.contains("上舰") || drawn.contains("求上舰"), "屏蔽词还在: " + drawn),
                () -> assertTrue(drawn.contains(rest.get(rest.size() - 1)),
                        "排在后面的词没补上来: " + drawn));
    }

    private void writeDanmu(List<String> lines) throws Exception {
        Path dir = temp.resolve("details").resolve(PLATFORM + "-" + STREAMER + "-" + START);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("danmu.jsonl"), String.join("\n", lines) + "\n");
    }

    private static String danmu(long uid, String name, String text) {
        return "{\"at\":" + START + ",\"uid\":" + uid + ",\"uname\":\"" + name
                + "\",\"text\":\"" + text + "\",\"type\":\"" + DanmuRecord.Type.DANMU.name() + "\"}";
    }

    private static void stored(LiveDataService data, String word, int times) {
        for (int i = 0; i < times; i++) {
            data.incrementLiveWordFrequency(PLATFORM, STREAMER, word);
        }
    }

    private static List<String> oneCloud(List<List<String>> drawn) {
        assertEquals(1, drawn.size(), "词云没有画出来");
        return drawn.get(0);
    }

    private CapturePainter painter(LiveDataService data, List<String> exclude, List<String> block) {
        return new CapturePainter(factory, quietApi(), data, fontUtil, properties(exclude, block),
                new LiveRoomInfoHistory(new NovaStateStore(new NovaCoreProperties())),
                new ReportImageDiskCache(temp.resolve("image-cache")));
    }

    private CaptureReplay replay(Map<String, Integer> stored, NovaBilibiliProperties properties) {
        LiveDetail detail = new LiveDetail(LiveDetail.VERSION, PLATFORM, STREAMER, "主播甲", ROOM,
                START, START + 3_600_000L, 3_600L,
                Map.of(), Map.of(), Map.of(), Map.of(), stored,
                List.of(), List.of(), List.of(), Map.of(), List.of());
        return new CaptureReplay(factory, quietApi(), fontUtil, properties,
                new LiveRoomInfoHistory(new NovaStateStore(new NovaCoreProperties())), detail,
                new ReportImageDiskCache(temp.resolve("image-cache")));
    }

    private static NovaBilibiliProperties properties(List<String> exclude, List<String> block) {
        NovaBilibiliProperties properties = new NovaBilibiliProperties();
        properties.getLive().setWordCloudExcludeUids(new ArrayList<>(exclude));
        properties.getLive().setWordCloudBlockWords(new ArrayList<>(block));
        return properties;
    }

    private static LiveDataService sessionData(boolean live) {
        NovaCoreProperties properties = new NovaCoreProperties();
        properties.getLive().setSaveLiveData(false);
        DefaultLiveDataService data = new DefaultLiveDataService(properties);
        data.setLiveStartTime(PLATFORM, STREAMER, START);
        if (!live) {
            data.setLiveEndTime(PLATFORM, STREAMER, START + 3_600_000L);
        }
        data.setLiveStatus(PLATFORM, STREAMER, live);
        return data;
    }

    private static LiveStreamerInfo streamer() {
        return new LiveStreamerInfo(STREAMER, "主播甲", ROOM, "https://pic.example/face.jpg");
    }

    private static BilibiliLiveReportOptions cloudOnly() {
        JSONObject params = new JSONObject();
        params.put("cover", false);
        params.put("cards", false);
        params.put("fans_change", false);
        params.put("interaction_curve", false);
        params.put("guard_list", false);
        params.put("guard_list_all", false);
        params.put("highlights", false);
        params.put("gift_list", false);
        params.put("danmu_ranking", 0);
        params.put("gift_ranking", 0);
        params.put("super_chat_ranking", 0);
        params.put("box_ranking", 0);
        params.put("box_profit_ranking", 0);
        params.put("danmu_cloud", true);
        return BilibiliLiveReportOptions.of(params, false);
    }

    private static BilibiliApiUtil quietApi() {
        BilibiliApiUtil api = mock(BilibiliApiUtil.class);
        BufferedImage placeholder = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = placeholder.createGraphics();
        graphics.setColor(new Color(120, 170, 220));
        graphics.fillRect(0, 0, 64, 64);
        graphics.dispose();
        when(api.getBilibiliImage(anyString())).thenReturn(Optional.of(placeholder));
        Room room = new Room();
        room.setTitle("测试直播间");
        room.setCover("https://pic.example/cover.jpg");
        when(api.getLiveInfoByRoomId(anyLong())).thenReturn(room);
        when(api.getFansCount(anyLong())).thenReturn(Optional.empty());
        when(api.getFansMedalCount(anyLong())).thenReturn(Optional.empty());
        when(api.getGuardCount(anyLong(), anyLong())).thenReturn(Optional.empty());
        when(api.getGuardList(anyLong(), anyLong())).thenReturn(Optional.of(List.of()));
        return api;
    }

    /**
     * 记下每张词云真正排上版面的词。画手收到的词频是整张表，挑词在排版之前，
     * 所以要看排出来的结果，不看传进来的表。
     */
    private static List<String> placed(WordCloudLayout.Result layout) {
        return layout.placements().stream().map(WordCloudLayout.Placement::text).toList();
    }

    private static final class CapturePainter extends BilibiliLiveReportPainter {
        private final List<List<String>> drawn = new ArrayList<>();

        private final List<WordCloudLayout.Result> layouts = new ArrayList<>();

        private CapturePainter(NovaCommonPainterFactory factory, BilibiliApiUtil api, LiveDataService data,
                               FontUtil fontUtil, NovaBilibiliProperties properties,
                               LiveRoomInfoHistory history, ReportImageDiskCache images) {
            super(factory, api, data, fontUtil, properties, history, images);
        }

        @Override
        BufferedImage paintWordCloud(String platform, Long uid, Map<String, Integer> frequencies, ReportPalette palette) {
            WordCloudLayout.Result layout = layoutWordCloud(platform, uid, frequencies);
            layouts.add(layout);
            drawn.add(placed(layout));
            return super.paintWordCloud(platform, uid, frequencies, palette);
        }
    }

    private static final class CaptureReplay extends BilibiliLiveReportReplayPainter {
        private final List<List<String>> drawn = new ArrayList<>();

        private CaptureReplay(NovaCommonPainterFactory factory, BilibiliApiUtil api, FontUtil fontUtil,
                              NovaBilibiliProperties properties, LiveRoomInfoHistory history, LiveDetail detail,
                              ReportImageDiskCache images) {
            super(factory, api, fontUtil, properties, history, detail, images);
        }

        @Override
        BufferedImage paintWordCloud(String platform, Long uid, Map<String, Integer> frequencies, ReportPalette palette) {
            drawn.add(placed(layoutWordCloud(platform, uid, frequencies)));
            return super.paintWordCloud(platform, uid, frequencies, palette);
        }
    }
}
