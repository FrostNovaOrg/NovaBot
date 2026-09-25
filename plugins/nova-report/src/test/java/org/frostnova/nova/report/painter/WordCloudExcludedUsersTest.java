package org.frostnova.nova.report.painter;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.bilibili.BilibiliPlatform;
import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.model.BilibiliLiveReportOptions;
import org.frostnova.nova.bilibili.model.Room;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.bilibili.util.DanmuWordCloudFrequencies;
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
import org.slf4j.LoggerFactory;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.io.DefaultResourceLoader;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 词云可以不计指定用户的弹幕。数据仍按原样保存，只在画图时去掉。
 */
@DisplayName("词云不计指定用户")
class WordCloudExcludedUsersTest {
    private static final String PLATFORM = BilibiliPlatform.BILIBILI.id();

    private static final long STREAMER = 19_000_000_000_301L;

    private static final long ROOM = 47_000_000_000_401L;

    private static final long BOT = 19_000_000_000_201L;

    private static final long HUMAN = 19_000_000_000_202L;

    private static final long START = 1_700_000_111_000L;

    private static final String BOT_NAME = "迎客机";

    private static final String FALLBACK_LOG = "词云没能按屏蔽名单重算, 仍按已保存的词频绘制";

    /** 进程里留着的场次上限。再多的场也不能把表撑过这个数。 */
    private static final int KEPT_READINGS = 32;

    private static final int MANY_SESSIONS = 400;

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

    @Test
    @DisplayName("名单里的机器人刷了欢迎和感谢，下播报告的词云里这些词不再是最大的")
    void endReportDropsWordsFromListedUsers() throws Exception {
        LiveDataService data = sessionData();
        floodStored(data);
        writeDanmu();
        CapturePainter painter = painter(data, List.of(Long.toString(BOT)));

        painter.paint(PLATFORM, streamer(), cloudOnly());

        Map<String, Integer> drawn = oneCloud(painter);
        assertFalse(drawn.containsKey("欢迎") || drawn.containsKey("感谢"),
                "名单里的人刷的欢迎、感谢仍画在词云里: " + drawn);
        assertEquals(3, drawn.get("唱歌"), "其他人的词被一起拿掉了: " + drawn);
        assertEquals(30, data.getLiveWordFrequencies(PLATFORM, STREAMER).get("欢迎"),
                "画图时改掉了已经保存的词频");
    }

    @Test
    @DisplayName("重画以前的一场，名单里那人的词不再出现")
    void redrawDropsWordsFromListedUsers() throws Exception {
        writeDanmu();
        Map<String, Integer> stored = storedWords();
        LiveDetail detail = new LiveDetail(LiveDetail.VERSION, PLATFORM, STREAMER, "主播甲", ROOM,
                START, START + 3_600_000L, 3_600L,
                Map.of(), Map.of(), Map.of(), Map.of(), stored,
                List.of(), List.of(), List.of(), Map.of(), List.of());
        NovaBilibiliProperties properties = properties(List.of(Long.toString(BOT)));
        CaptureReplay painter = new CaptureReplay(factory, quietApi(), fontUtil, properties,
                new LiveRoomInfoHistory(new NovaStateStore(new NovaCoreProperties())), detail,
                new ReportImageDiskCache(temp.resolve("image-cache")));

        assertTrue(painter.render(cloudOnly()).isPresent(), "重画没有画出报告");

        Map<String, Integer> drawn = oneCloud(painter);
        assertFalse(drawn.containsKey("欢迎") || drawn.containsKey("感谢"),
                "重画旧场时，名单里那人的词仍在: " + drawn);
        assertEquals(3, drawn.get("唱歌"), "重画把其他人的词也拿掉了: " + drawn);
        assertEquals(30, stored.get("欢迎"), "重画改掉了这场保存的词频");
    }

    @Test
    @DisplayName("一场三个推送目标，弹幕原文只重算一次")
    void threeTargetsRecountOnce() throws Exception {
        LiveDataService data = sessionData();
        floodStored(data);
        writeDanmu();
        CapturePainter painter = painter(data, List.of(Long.toString(BOT)));
        BilibiliLiveReportOptions options = cloudOnly();

        painter.paint(PLATFORM, streamer(), options);
        painter.paint(PLATFORM, streamer(), options);
        painter.paint(PLATFORM, streamer(), options);

        assertEquals(3, painter.seen.size(), "三个推送目标没有各画一次词云");
        assertTrue(painter.seen.get(0) == painter.seen.get(1) && painter.seen.get(0) == painter.seen.get(2),
                "一场三个推送目标把弹幕原文重算了三次");
        assertFalse(painter.seen.get(0).containsKey("欢迎"),
                "重算结果仍含名单里的人刷的词: " + painter.seen.get(0));
    }

    @Test
    @DisplayName("两位主播同时下播，各自的词云重算不用互相等")
    void twoStreamersRecountWithoutWaiting() throws Exception {
        long other = STREAMER + 1;
        LiveDataService data = sessionData();
        data.setLiveStartTime(PLATFORM, other, START);
        data.setLiveEndTime(PLATFORM, other, START + 3_600_000L);
        SlowDanmu painter = new SlowDanmu(data, 500L, false);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            Future<Map<String, Integer>> first = pool.submit(() -> {
                ready.countDown();
                assertTrue(go.await(5, TimeUnit.SECONDS), "没有等到同时开始");
                return painter.frequenciesForWordCloud(PLATFORM, STREAMER);
            });
            Future<Map<String, Integer>> second = pool.submit(() -> {
                ready.countDown();
                assertTrue(go.await(5, TimeUnit.SECONDS), "没有等到同时开始");
                return painter.frequenciesForWordCloud(PLATFORM, other);
            });
            assertTrue(ready.await(5, TimeUnit.SECONDS), "两个词云没有都开始");
            warmSegmenter();
            long began = System.nanoTime();
            go.countDown();
            Map<String, Integer> left = first.get(5, TimeUnit.SECONDS);
            Map<String, Integer> right = second.get(5, TimeUnit.SECONDS);
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began);
            assertEquals(2, painter.reads.get(), "两场没有各读一次弹幕原文");
            assertTrue(elapsed < 800,
                    "两位主播同时下播，词云重算排成一队，用了 " + elapsed + " 毫秒");
            assertEquals(Integer.valueOf(1), left.get("唱歌"), "第一场没有按原文重算: " + left);
            assertEquals(Integer.valueOf(1), right.get("唱歌"), "第二场没有按原文重算: " + right);
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("同一场三个推送目标同时要词云，只重算一次，拿到同一份")
    void threeTargetsAtOnceShareOneRecount() throws Exception {
        LiveDataService data = sessionData();
        SlowDanmu painter = new SlowDanmu(data, 300L, true);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            CountDownLatch ready = new CountDownLatch(3);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Map<String, Integer>>> futures = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    assertTrue(go.await(5, TimeUnit.SECONDS), "没有等到同时开始");
                    return painter.frequenciesForWordCloud(PLATFORM, STREAMER);
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS), "三个推送目标没有都开始");
            go.countDown();
            Map<String, Integer> first = futures.get(0).get(5, TimeUnit.SECONDS);
            Map<String, Integer> second = futures.get(1).get(5, TimeUnit.SECONDS);
            Map<String, Integer> third = futures.get(2).get(5, TimeUnit.SECONDS);
            assertEquals(1, painter.recounts.get(),
                    "同一场三个推送目标把词云重算了 " + painter.recounts.get() + " 次");
            assertSame(first, second, "三个推送目标没有拿到同一份词频");
            assertSame(first, third, "三个推送目标没有拿到同一份词频");
            assertEquals(Integer.valueOf(1), first.get("唱歌"), "重算结果不对: " + first);
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("名单为空时，词云用已保存的词频，逐项相同")
    void emptyListKeepsStoredWords() throws Exception {
        LiveDataService data = sessionData();
        data.incrementLiveWordFrequency(PLATFORM, STREAMER, "唱歌");
        data.incrementLiveWordFrequency(PLATFORM, STREAMER, "唱歌");
        data.incrementLiveWordFrequency(PLATFORM, STREAMER, "唱歌");
        writeDanmu();
        Map<String, Integer> stored = data.getLiveWordFrequencies(PLATFORM, STREAMER);
        CapturePainter painter = painter(data, List.of());

        painter.paint(PLATFORM, streamer(), cloudOnly());

        assertEquals(stored, oneCloud(painter), "名单为空时词云与已保存的词频不一致");
    }

    @Test
    @DisplayName("这一场没有弹幕原文时，词云退回已保存的词频")
    void missingDanmuFallsBackToStoredWords() throws Exception {
        LiveDataService data = sessionData();
        floodStored(data);
        Map<String, Integer> stored = data.getLiveWordFrequencies(PLATFORM, STREAMER);
        CapturePainter painter = painter(data, List.of(Long.toString(BOT)));

        painter.paint(PLATFORM, streamer(), cloudOnly());

        assertEquals(stored, oneCloud(painter), "没有弹幕原文时没有退回已保存的词频");
    }

    @Test
    @DisplayName("没有弹幕原文时记一行，不写观众编号和昵称，三个目标也只记一行")
    void missingDanmuLogsOnceWithoutViewerIdentity() throws Exception {
        LiveDataService data = sessionData();
        floodStored(data);
        CapturePainter painter = painter(data, List.of(Long.toString(BOT)));
        ListAppender<ILoggingEvent> appender = attachLog();
        try {
            BilibiliLiveReportOptions options = cloudOnly();
            painter.paint(PLATFORM, streamer(), options);
            painter.paint(PLATFORM, streamer(), options);
            painter.paint(PLATFORM, streamer(), options);
            List<String> lines = appender.list.stream()
                    .filter(event -> event.getLevel() == Level.WARN)
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(message -> message.contains(FALLBACK_LOG))
                    .toList();
            assertEquals(1, lines.size(), "没有弹幕原文时应记一行, 实际 " + lines);
            String line = lines.get(0);
            assertFalse(line.contains(Long.toString(BOT)), "日志带了观众编号: " + line);
            assertFalse(line.contains(BOT_NAME), "日志带了观众昵称: " + line);
        } finally {
            detachLog(appender);
        }
    }

    @Test
    @DisplayName("没有弹幕原文时，后计入的词下一次词云也画得上")
    void missingDanmuFollowsLaterStoredWords() throws Exception {
        LiveDataService data = sessionData();
        floodStored(data);
        CapturePainter painter = painter(data, List.of(Long.toString(BOT)));
        BilibiliLiveReportOptions options = cloudOnly();

        painter.paint(PLATFORM, streamer(), options);
        for (int i = 0; i < 7; i++) {
            data.incrementLiveWordFrequency(PLATFORM, STREAMER, "新词");
        }
        painter.seen.clear();
        painter.paint(PLATFORM, streamer(), options);

        Map<String, Integer> drawn = oneCloud(painter);
        Map<String, Integer> stored = data.getLiveWordFrequencies(PLATFORM, STREAMER);
        assertAll(
                () -> assertEquals(stored, drawn,
                        "没有弹幕原文时，第二次词云仍是第一次的旧词频: " + drawn),
                () -> assertEquals(Integer.valueOf(7), drawn.get("新词"),
                        "后计入的词没有画上: " + drawn));
    }

    @Test
    @DisplayName("连看很多场，重算表和人数读数不随场次越攒越多")
    void manySessionsDoNotPileUpRecountsOrGuardCounts() throws Exception {
        LiveDataService data = sessionData();
        BilibiliApiUtil api = quietApi();
        when(api.getGuardCount(anyLong(), anyLong())).thenReturn(Optional.of(42));
        BilibiliLiveReportPainter painter = new BilibiliLiveReportPainter(
                factory, api, data, fontUtil, properties(List.of(Long.toString(BOT))),
                new LiveRoomInfoHistory(new NovaStateStore(new NovaCoreProperties())),
                new ReportImageDiskCache(temp.resolve("image-cache")));
        for (int i = 0; i < MANY_SESSIONS; i++) {
            long start = START + i;
            data.setLiveStartTime(PLATFORM, STREAMER, start);
            if (i % 2 == 0) {
                writeOneDanmu(STREAMER, start);
            }
            painter.frequenciesForWordCloud(PLATFORM, STREAMER);
            painter.guardCount(ROOM, STREAMER);
        }
        int recounts = mapSize(painter, "wordCloudRecounts");
        int guards = mapSize(painter, "guardCountOnCard");
        int noted = mapSizeIfPresent(painter, "wordCloudFallbackNoted");
        assertAll(
                () -> assertTrue(recounts > 0 && recounts <= KEPT_READINGS,
                        "连续画了 " + MANY_SESSIONS + " 场，重算表留着 " + recounts + " 条，随场次一直涨"),
                () -> assertTrue(guards > 0 && guards <= KEPT_READINGS,
                        "连续画了 " + MANY_SESSIONS + " 场，人数读数留着 " + guards + " 条，随场次一直涨"),
                () -> {
                    if (noted >= 0) {
                        assertTrue(noted > 0 && noted <= KEPT_READINGS,
                                "连续画了 " + MANY_SESSIONS + " 场，没原文的提示留着 " + noted + " 条，随场次一直涨");
                    }
                });
    }

    private void writeOneDanmu(long uid, long start) throws Exception {
        Path dir = temp.resolve("details").resolve(PLATFORM + "-" + uid + "-" + start);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("danmu.jsonl"),
                danmu(HUMAN, "观众甲", "唱歌", DanmuRecord.Type.DANMU) + "\n");
    }

    @SuppressWarnings("unchecked")
    private static int mapSize(Object painter, String field) throws Exception {
        Field declared = BilibiliLiveReportPainter.class.getDeclaredField(field);
        declared.setAccessible(true);
        return ((Map<?, ?>) declared.get(painter)).size();
    }

    private static int mapSizeIfPresent(Object painter, String field) throws Exception {
        try {
            return mapSize(painter, field);
        } catch (NoSuchFieldException ignored) {
            return -1;
        }
    }

    private static Map<String, Integer> oneCloud(CapturePainter painter) {
        assertEquals(1, painter.seen.size(), "词云没有画出来");
        return painter.seen.get(0);
    }

    private static Map<String, Integer> oneCloud(CaptureReplay painter) {
        assertEquals(1, painter.seen.size(), "词云没有画出来");
        return painter.seen.get(0);
    }

    private CapturePainter painter(LiveDataService data, List<String> exclude) {
        return new CapturePainter(factory, quietApi(), data, fontUtil, properties(exclude),
                new LiveRoomInfoHistory(new NovaStateStore(new NovaCoreProperties())),
                new ReportImageDiskCache(temp.resolve("image-cache")));
    }

    private static NovaBilibiliProperties properties(List<String> exclude) {
        NovaBilibiliProperties properties = new NovaBilibiliProperties();
        properties.getLive().setWordCloudExcludeUids(new ArrayList<>(exclude));
        return properties;
    }

    private LiveDataService sessionData() {
        NovaCoreProperties properties = new NovaCoreProperties();
        properties.getLive().setSaveLiveData(false);
        DefaultLiveDataService data = new DefaultLiveDataService(properties);
        data.setLiveStartTime(PLATFORM, STREAMER, START);
        data.setLiveEndTime(PLATFORM, STREAMER, START + 3_600_000L);
        data.setLiveStatus(PLATFORM, STREAMER, false);
        return data;
    }

    private static void floodStored(LiveDataService data) {
        for (int i = 0; i < 30; i++) {
            data.incrementLiveWordFrequency(PLATFORM, STREAMER, "欢迎");
            data.incrementLiveWordFrequency(PLATFORM, STREAMER, "感谢");
        }
        for (int i = 0; i < 3; i++) {
            data.incrementLiveWordFrequency(PLATFORM, STREAMER, "唱歌");
        }
    }

    private static Map<String, Integer> storedWords() {
        Map<String, Integer> words = new LinkedHashMap<>();
        words.put("欢迎", 30);
        words.put("感谢", 30);
        words.put("唱歌", 3);
        return words;
    }

    private void writeDanmu() throws Exception {
        Path dir = temp.resolve("details").resolve(PLATFORM + "-" + STREAMER + "-" + START);
        Files.createDirectories(dir);
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            lines.add(danmu(BOT, BOT_NAME, "欢迎", DanmuRecord.Type.DANMU));
            lines.add(danmu(BOT, BOT_NAME, "感谢", DanmuRecord.Type.DANMU));
        }
        lines.add(danmu(HUMAN, "观众甲", "欢迎", DanmuRecord.Type.EMOJI));
        lines.add(danmu(HUMAN, "观众甲", "感谢", DanmuRecord.Type.SUPER_CHAT));
        for (int i = 0; i < 3; i++) {
            lines.add(danmu(HUMAN, "观众甲", "唱歌", DanmuRecord.Type.DANMU));
        }
        Files.writeString(dir.resolve("danmu.jsonl"), String.join("\n", lines) + "\n");
    }

    private static String danmu(long uid, String name, String text, DanmuRecord.Type type) {
        return "{\"at\":" + START + ",\"uid\":" + uid + ",\"uname\":\"" + name
                + "\",\"text\":\"" + text + "\",\"type\":\"" + type.name() + "\"}";
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

    private static ListAppender<ILoggingEvent> attachLog() {
        Logger logger = (Logger) LoggerFactory.getLogger(BilibiliLiveReportPainter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static void detachLog(ListAppender<ILoggingEvent> appender) {
        Logger logger = (Logger) LoggerFactory.getLogger(BilibiliLiveReportPainter.class);
        logger.detachAppender(appender);
    }

    private static final class CapturePainter extends BilibiliLiveReportPainter {
        private final List<Map<String, Integer>> seen = new ArrayList<>();

        private CapturePainter(NovaCommonPainterFactory factory, BilibiliApiUtil api, LiveDataService data,
                               FontUtil fontUtil, NovaBilibiliProperties properties,
                               LiveRoomInfoHistory history, ReportImageDiskCache images) {
            super(factory, api, data, fontUtil, properties, history, images);
        }

        @Override
        BufferedImage paintWordCloud(String platform, Long uid, Map<String, Integer> frequencies) {
            seen.add(frequencies);
            return super.paintWordCloud(platform, uid, frequencies);
        }
    }

    private final class SlowDanmu extends BilibiliLiveReportPainter {
        private final AtomicInteger reads = new AtomicInteger();

        private final AtomicInteger recounts = new AtomicInteger();

        private final long pauseMillis;

        private final boolean pauseWhileRecounting;

        private SlowDanmu(LiveDataService data, long pauseMillis, boolean pauseWhileRecounting) {
            super(factory, quietApi(), data, fontUtil, properties(List.of(Long.toString(BOT))),
                    new LiveRoomInfoHistory(new NovaStateStore(new NovaCoreProperties())),
                    new ReportImageDiskCache(temp.resolve("slow-danmu")));
            this.pauseMillis = pauseMillis;
            this.pauseWhileRecounting = pauseWhileRecounting;
        }

        @Override
        Optional<Long> wordCloudDanmuSize(String platform, long uid, long start) {
            return Optional.of(64L);
        }

        @Override
        Optional<List<DanmuRecord>> wordCloudDanmu(String platform, long uid, long start) {
            reads.incrementAndGet();
            if (!pauseWhileRecounting) {
                pauseQuiet(pauseMillis);
            }
            return Optional.of(List.of(
                    new DanmuRecord(START, HUMAN, "观众甲", "唱歌", DanmuRecord.Type.DANMU)));
        }

        @Override
        Map<String, Integer> recountWordCloud(String platform, Long uid, List<DanmuRecord> danmu,
                                              Set<Long> exclude) {
            recounts.incrementAndGet();
            if (pauseWhileRecounting) {
                pauseQuiet(pauseMillis);
            }
            return super.recountWordCloud(platform, uid, danmu, exclude);
        }
    }

    private static void warmSegmenter() {
        DanmuWordCloudFrequencies.recount(PLATFORM, STREAMER,
                List.of(new DanmuRecord(START, HUMAN, "观众甲", "唱歌", DanmuRecord.Type.DANMU)),
                Set.of(BOT));
    }

    private static void pauseQuiet(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待被打断");
        }
    }

    private static final class CaptureReplay extends BilibiliLiveReportReplayPainter {
        private final List<Map<String, Integer>> seen = new ArrayList<>();

        private CaptureReplay(NovaCommonPainterFactory factory, BilibiliApiUtil api, FontUtil fontUtil,
                              NovaBilibiliProperties properties, LiveRoomInfoHistory history, LiveDetail detail,
                              ReportImageDiskCache images) {
            super(factory, api, fontUtil, properties, history, detail, images);
        }

        @Override
        BufferedImage paintWordCloud(String platform, Long uid, Map<String, Integer> frequencies) {
            seen.add(frequencies);
            return super.paintWordCloud(platform, uid, frequencies);
        }
    }
}
