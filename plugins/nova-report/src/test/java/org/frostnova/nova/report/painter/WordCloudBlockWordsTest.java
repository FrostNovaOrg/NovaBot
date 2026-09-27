package org.frostnova.nova.report.painter;

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
        BufferedImage paintWordCloud(String platform, Long uid, Map<String, Integer> frequencies) {
            WordCloudLayout.Result layout = layoutWordCloud(platform, uid, frequencies);
            layouts.add(layout);
            drawn.add(placed(layout));
            return super.paintWordCloud(platform, uid, frequencies);
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
        BufferedImage paintWordCloud(String platform, Long uid, Map<String, Integer> frequencies) {
            drawn.add(placed(layoutWordCloud(platform, uid, frequencies)));
            return super.paintWordCloud(platform, uid, frequencies);
        }
    }
}
