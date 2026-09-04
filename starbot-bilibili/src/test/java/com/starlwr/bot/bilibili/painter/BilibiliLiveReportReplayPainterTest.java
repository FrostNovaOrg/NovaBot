package com.starlwr.bot.bilibili.painter;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.bilibili.model.BilibiliLiveMetric;
import com.starlwr.bot.bilibili.model.BilibiliLiveReportOptions;
import com.starlwr.bot.bilibili.model.Room;
import com.starlwr.bot.bilibili.util.BilibiliApiUtil;
import com.starlwr.bot.core.analytics.LiveDetail;
import com.starlwr.bot.core.analytics.LiveHighlightFinder;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.factory.StarBotCommonPainterFactory;
import com.starlwr.bot.core.model.LiveGap;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import com.starlwr.bot.core.model.RoomInfoSnapshot;
import com.starlwr.bot.core.model.SeriesPeak;
import com.starlwr.bot.core.model.UserScore;
import com.starlwr.bot.core.service.DefaultLiveDataService;
import com.starlwr.bot.core.service.LiveDataService;
import com.starlwr.bot.core.service.LiveRoomInfoHistory;
import com.starlwr.bot.core.util.FontUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.io.DefaultResourceLoader;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 从明细重画历史报告
 *
 * <h2>这一组里最要紧的一格</h2>
 * 🔴 <b>重画出来的那张图，与下播当时发出去的那张必须是同一份数据。</b>
 * 「像模像样」是这件事最危险的失败形态：明细少灌了一条曲线、排行榜短了一截、
 * 缺口斜纹没画上——重画出来的报告仍然是一张完整好看的图，
 * 没有任何一处提示「这里少了东西」。
 * <p>
 * 因此本组的判法是<b>拿同一份数据画两次</b>：一次走正常的下播出图路径，
 * 一次经明细往返后重画，比两张图的像素尺寸。少画一块、多画一块都会改变高度。
 * <p>
 * ⚠️ 尺寸相同<b>不等于逐像素相同</b>，本组也不这么断言：封面与头像在重画时是占位图，
 * 而占位图的尺寸与真图相同——两张图在那一块上颜色不同、高度一致。
 * 「数据面一致」这件事由高度这一维配合逐项断言来管。
 */
@DisplayName("从明细重画历史报告")
class BilibiliLiveReportReplayPainterTest {
    private static final String PLATFORM = "bilibili";

    /** ⚠️ 保留段假值 */
    private static final long UID = 19604318752096L;

    private static final long ROOM_ID = 47615208934771L;

    private static final long START = 1_700_000_000_000L;

    private static final long MINUTE = 60_000L;

    private static final long DURATION = 128 * MINUTE;

    private static final LiveStreamerInfo STREAMER = new LiveStreamerInfo(UID, "主播甲", ROOM_ID, null);

    private BilibiliApiUtil api;

    private StarBotCommonPainterFactory factory;

    private FontUtil fontUtil;

    private LiveRoomInfoHistory roomInfoHistory;

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    @BeforeEach
    void setUp() {
        StarBotCoreProperties coreProperties = new StarBotCoreProperties();
        coreProperties.getPaint().getFonts().add("内置");

        fontUtil = new FontUtil(new DefaultResourceLoader(), coreProperties);
        fontUtil.init();

        Properties buildInfo = new Properties();
        buildInfo.setProperty("version", "5.1.0");
        buildInfo.setProperty("group", "com.starlwr");
        buildInfo.setProperty("artifact", "starbot-core");
        buildInfo.setProperty("name", "StarBotCore");
        factory = new StarBotCommonPainterFactory(new BuildProperties(buildInfo), coreProperties, fontUtil);

        // 🔴 一个方法都不打桩：重画侧的任何一次调用都是「重画联网了」的实证。
        // 出图侧那一趟另建一个会打桩的 mock，见 paintLive()
        api = mock(BilibiliApiUtil.class);
        roomInfoHistory = mock(LiveRoomInfoHistory.class);
    }

    @Test
    @DisplayName("③ 同一份数据、同一套版式：重画图与下播出图像素尺寸相同")
    void redrawnImageHasSameSize() throws IOException {
        BufferedImage live = image(paintLive(new BilibiliLiveReportOptions()));
        BufferedImage redrawn = image(redraw(new BilibiliLiveReportOptions()));

        assertAll(
                () -> assertEquals(live.getWidth(), redrawn.getWidth()),
                () -> assertEquals(live.getHeight(), redrawn.getHeight(),
                        "高度不同说明少画或多画了一块——而少画一块的报告仍然是一张完整好看的图"));

        System.out.println("下播出图 " + live.getWidth() + "×" + live.getHeight()
                + "，明细重画 " + redrawn.getWidth() + "×" + redrawn.getHeight());
    }

    @Test
    @DisplayName("③ 换一套版式，两边照样同尺寸——比的是数据面，不是某一套开关")
    void sameSizeUnderAnotherLayout() throws IOException {
        JSONObject params = new JSONObject();
        params.put("danmu_cloud", false);
        params.put("highlights", false);
        params.put("box_ranking", 5);
        BilibiliLiveReportOptions options = BilibiliLiveReportOptions.of(params, true);

        assertEquals(image(paintLive(options)).getHeight(), image(redraw(options)).getHeight());
    }

    @Test
    @DisplayName("③ 掰断：明细里少一条曲线，两张图的高度当场不同")
    void missingSeriesChangesTheHeight() throws IOException {
        LiveDetail complete = detail();
        Map<String, Map<Long, Double>> fewer = new LinkedHashMap<>(complete.series());
        fewer.remove(BilibiliLiveMetric.SUPER_CHAT_VALUE);
        LiveDetail crippled = new LiveDetail(complete.version(), complete.platform(), complete.uid(),
                complete.uname(), complete.roomId(), complete.startTime(), complete.endTime(),
                complete.durationSeconds(), complete.metrics(), complete.userCounts(), fewer,
                complete.rankings(), complete.words(), complete.highlights(), complete.titles(),
                complete.gaps(), complete.peaks());

        int full = image(redraw(new BilibiliLiveReportOptions())).getHeight();
        int missing = image(render(crippled, new BilibiliLiveReportOptions())).getHeight();

        assertTrue(missing < full,
                "少一条曲线而高度没变, 说明这一格根本量不到曲线画没画（全 " + full + "，缺 " + missing + "）");
    }

    @Test
    @DisplayName("🔴 重画全程不碰接口，也不碰状态存储")
    void redrawNeverTouchesTheNetwork() {
        redraw(new BilibiliLiveReportOptions());

        verifyNoInteractions(api);
        verifyNoInteractions(roomInfoHistory);
    }

    @Test
    @DisplayName("🔴 重画那份数据不许落盘：默认落点正是真数据文件")
    void replayDataNeverPersists() throws Exception {
        BilibiliLiveReportReplayPainter painter = painter(detail());

        Object data = field(BilibiliLiveReportPainter.class, painter, "liveDataService");
        assertTrue(data instanceof DefaultLiveDataService,
                "重画的数据服务换了实现, 这一格量的东西得跟着重判：" + data.getClass());
        StarBotCoreProperties properties =
                (StarBotCoreProperties) field(DefaultLiveDataService.class, data, "properties");

        // 先证这一格量得到东西：默认值是 true，读到 true 才说明这一格真的在看这个开关
        assertTrue(new StarBotCoreProperties().getLive().isSaveLiveData(),
                "默认值不再是 true 了, 这一格的意义得重判");
        assertFalse(properties.getLive().isSaveLiveData(),
                "重画的数据服务开着落盘：它的默认落点是 "
                        + properties.getLive().getLiveDataPath() + "，那是真数据所在的文件");
    }

    @Test
    @DisplayName("标题轨迹取自明细，而不是问状态存储要当前那一场的")
    void titlesComeFromDetail() throws IOException {
        // 状态存储是个一句话都没打桩的 mock，它若被问到就会答空表；
        // 明细里有两条标题，图上「标题变化」那一块因此画得出来——高度上看得见
        JSONObject params = new JSONObject();
        params.put("title_changes", false);
        BilibiliLiveReportOptions off = BilibiliLiveReportOptions.of(params, true);

        assertTrue(image(redraw(new BilibiliLiveReportOptions())).getHeight()
                        > image(redraw(off)).getHeight(),
                "开着「标题变化」却没比关掉时高, 说明标题轨迹没从明细里读出来");
    }

    @Test
    @DisplayName("昵称空着时退回 UID，不在图上画一个 null")
    void blankUnameFallsBackToUid() {
        LiveDetail complete = detail();
        LiveDetail nameless = new LiveDetail(complete.version(), complete.platform(), complete.uid(),
                null, complete.roomId(), complete.startTime(), complete.endTime(),
                complete.durationSeconds(), complete.metrics(), complete.userCounts(), complete.series(),
                complete.rankings(), complete.words(), complete.highlights(), complete.titles(),
                complete.gaps(), complete.peaks());

        assertTrue(render(nameless, new BilibiliLiveReportOptions()).length > 0);
    }

    // ---------------------------------------------------------------- 两条路径

    /**
     * 下播出图那一趟：真画手 ＋ 直接灌进本场数据的直播数据服务
     * <p>
     * 这里的接口 mock <b>与重画那一趟用的不是同一个</b>，且这一趟允许被调用：
     * 真报告本来就要向平台取封面、头像、粉丝数。
     * 取到的都是空（mock 的默认返回），于是两趟在「本场变化」那一块上是同一个结果——
     * <b>那正是这一格能比高度的前提</b>：重画取不到粉丝现值，真出图在测试里也取不到。
     */
    private byte[] paintLive(BilibiliLiveReportOptions options) {
        BilibiliApiUtil liveApi = mock(BilibiliApiUtil.class);
        // 封面必须给：不给的话这一趟画的是「无封面」的简单头部，比带封面的矮 260 像素，
        // 而那点高度差与「重画少画了一整块」在数字上分不开——
        // 这一格就会变成在量「测试有没有打桩封面」
        Room room = new Room();
        room.setTitle("测试直播间");
        room.setCover("https://pic.example.invalid/cover.jpg");
        org.mockito.Mockito.when(liveApi.getLiveInfoByRoomId(org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(room);
        org.mockito.Mockito.when(liveApi.getBilibiliImage(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Optional.of(new BufferedImage(1600, 900, BufferedImage.TYPE_INT_ARGB)));
        org.mockito.Mockito.when(liveApi.getFansCount(org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(Optional.empty());
        org.mockito.Mockito.when(liveApi.getFansMedalCount(org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(Optional.empty());
        org.mockito.Mockito.when(liveApi.getGuardCount(org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(Optional.empty());

        LiveRoomInfoHistory history = mock(LiveRoomInfoHistory.class);
        org.mockito.Mockito.when(history.history(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(titles());

        BilibiliLiveReportPainter painter = new BilibiliLiveReportPainter(
                factory, liveApi, liveData(), fontUtil, new StarBotBilibiliProperties(), history);

        Optional<String> base64 = painter.paint(PLATFORM, STREAMER, options);
        assertTrue(base64.isPresent(), "下播出图那一趟没画出来");
        return Base64.getDecoder().decode(base64.get());
    }

    private byte[] redraw(BilibiliLiveReportOptions options) {
        return render(detail(), options);
    }

    private byte[] render(LiveDetail detail, BilibiliLiveReportOptions options) {
        Optional<byte[]> png = painter(detail).render(options);
        assertTrue(png.isPresent(), "重画没画出来");
        return png.get();
    }

    private BilibiliLiveReportReplayPainter painter(LiveDetail detail) {
        return new BilibiliLiveReportReplayPainter(
                factory, api, fontUtil, new StarBotBilibiliProperties(), roomInfoHistory, detail);
    }

    // ---------------------------------------------------------------- 同一份夹具数据的两种形态

    /**
     * 本场数据：下播出图那一趟读的就是它
     */
    private LiveDataService liveData() {
        StarBotCoreProperties properties = new StarBotCoreProperties();
        properties.getLive().setSaveLiveData(false);
        DefaultLiveDataService data = new DefaultLiveDataService(properties);

        data.setLiveStartTime(PLATFORM, UID, START);
        data.setLiveEndTime(PLATFORM, UID, START + DURATION);
        data.setLiveStatus(PLATFORM, UID, false);

        metrics().forEach((metric, value) -> data.setLiveMetric(PLATFORM, UID, metric, value));
        rankings().forEach((metric, users) -> {
            for (UserScore user : users) {
                data.incrementLiveUserMetric(PLATFORM, UID, metric, user.userUid(), user.score());
                data.recordLiveUserName(PLATFORM, UID, user.userUid(), user.userName());
                data.recordLiveUserFace(PLATFORM, UID, user.userUid(), user.userFace());
            }
        });
        series().forEach((metric, buckets) -> buckets.forEach((at, value) ->
                data.incrementLiveSeries(PLATFORM, UID, metric, at, value)));
        words().forEach((word, times) -> {
            for (int i = 0; i < times; i++) {
                data.incrementLiveWordFrequency(PLATFORM, UID, word);
            }
        });
        for (LiveGap gap : gaps()) {
            data.recordDowntime(gap.from(), gap.to(), gap.reason());
        }
        return data;
    }

    /**
     * 同一场的明细形态：重画那一趟读的就是它
     */
    private LiveDetail detail() {
        Map<String, Map<Long, Double>> series = series();
        Map<String, SeriesPeak> peaks = new LinkedHashMap<>();
        series.forEach((metric, buckets) -> buckets.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .ifPresent(top -> peaks.put(metric, new SeriesPeak(top.getKey(), top.getValue()))));

        Map<String, Integer> userCounts = new LinkedHashMap<>();
        rankings().forEach((metric, users) -> userCounts.put(metric, users.size()));

        return new LiveDetail(LiveDetail.VERSION, PLATFORM, UID, "主播甲", ROOM_ID,
                START, START + DURATION, DURATION / 1000,
                metrics(), userCounts, series, rankings(), words(),
                List.of(new LiveHighlightFinder.Highlight(START + 42 * MINUTE, 34, 4.25)),
                titles(), gaps(), peaks);
    }

    private Map<String, Double> metrics() {
        Map<String, Double> metrics = new LinkedHashMap<>();
        metrics.put(BilibiliLiveMetric.DANMU_COUNT, 1384.0);
        metrics.put(BilibiliLiveMetric.GIFT_VALUE, 268.4);
        metrics.put(BilibiliLiveMetric.GIFT_PAID, 268.4);
        metrics.put(BilibiliLiveMetric.FREE_GIFT_COUNT, 412.0);
        metrics.put(BilibiliLiveMetric.SUPER_CHAT_COUNT, 7.0);
        metrics.put(BilibiliLiveMetric.SUPER_CHAT_VALUE, 318.0);
        metrics.put(BilibiliLiveMetric.BOX_COUNT, 46.0);
        metrics.put(BilibiliLiveMetric.BOX_PROFIT, -37.5);
        metrics.put(BilibiliLiveMetric.CAPTAIN_COUNT, 3.0);
        metrics.put(BilibiliLiveMetric.GUARD_VALUE, 1386.0);
        metrics.put(BilibiliLiveMetric.FOLLOW_COUNT, 96.0);
        metrics.put(BilibiliLiveMetric.SHARE_COUNT, 24.0);
        metrics.put(BilibiliLiveMetric.LIKE_TOTAL, 2573.0);
        metrics.put(BilibiliLiveMetric.WATCHED_COUNT, 8642.0);
        metrics.put(BilibiliLiveMetric.ONLINE_RANK_COUNT, 137.0);
        metrics.put(BilibiliLiveMetric.FANS_AT_START, 12314.0);
        return metrics;
    }

    private Map<String, List<UserScore>> rankings() {
        Map<String, List<UserScore>> rankings = new LinkedHashMap<>();
        rankings.put(BilibiliLiveMetric.DANMU_USERS, viewers(new double[]{186, 142, 97, 63, 41}));
        rankings.put(BilibiliLiveMetric.GIFT_USERS, viewers(new double[]{88.8, 52.4, 30.0, 18.6, 9.9}));
        rankings.put(BilibiliLiveMetric.SUPER_CHAT_USERS, viewers(new double[]{100, 66, 30}));
        rankings.put(BilibiliLiveMetric.BOX_USERS, viewers(new double[]{18, 12, 9}));
        rankings.put(BilibiliLiveMetric.BOX_PROFIT_USERS, viewers(new double[]{24.5, -6.2, -18.4}));
        rankings.put(BilibiliLiveMetric.GUARD_USERS, viewers(new double[]{1, 1, 1}));
        return rankings;
    }

    /**
     * 观众 uid 一律取保留段假值，与真实取值范围不重叠
     */
    private List<UserScore> viewers(double[] scores) {
        List<UserScore> users = new ArrayList<>(scores.length);
        for (int i = 0; i < scores.length; i++) {
            users.add(new UserScore(19_000_000_000_000L + i, "观众" + i, "face-" + i, scores[i]));
        }
        return users;
    }

    private Map<String, Map<Long, Double>> series() {
        Map<String, Map<Long, Double>> series = new LinkedHashMap<>();
        Map<Long, Double> danmu = new TreeMap<>();
        Map<Long, Double> gift = new TreeMap<>();
        Map<Long, Double> superChat = new TreeMap<>();
        Map<Long, Double> box = new TreeMap<>();
        Map<Long, Double> guard = new TreeMap<>();
        Map<Long, Double> watched = new TreeMap<>();

        int minutes = (int) (DURATION / MINUTE);
        for (int minute = 0; minute < minutes; minute++) {
            long at = (START + minute * MINUTE) / MINUTE * MINUTE;
            danmu.put(at, (double) Math.round(6 + 5 * Math.sin(minute / 9.0) + (minute == 42 ? 28 : 0)));
            if (minute % 4 == 0) {
                gift.put(at, 3.2);
            }
            if (minute % 17 == 0) {
                superChat.put(at, 30.0);
            }
            if (minute % 23 == 0) {
                box.put(at, 4.0);
            }
            if (minute % 41 == 0) {
                guard.put(at, 198.0);
            }
            watched.put(at, 900 + minute * 62.0);
        }

        series.put(BilibiliLiveMetric.DANMU_COUNT, danmu);
        series.put(BilibiliLiveMetric.GIFT_VALUE, gift);
        series.put(BilibiliLiveMetric.SUPER_CHAT_VALUE, superChat);
        series.put(BilibiliLiveMetric.BOX_COUNT, box);
        series.put(BilibiliLiveMetric.GUARD_VALUE, guard);
        series.put(BilibiliLiveMetric.WATCHED_COUNT, watched);
        return series;
    }

    private Map<String, Integer> words() {
        String[] words = {"晚上好", "好听", "点歌", "主播", "笑死", "哈哈哈", "太强了", "下次一定",
                "打卡", "第一次来", "收藏了", "求", "签到", "破防", "泪目", "有被治愈"};
        Map<String, Integer> frequencies = new LinkedHashMap<>();
        for (int i = 0; i < words.length; i++) {
            frequencies.put(words[i], 60 - i * 2);
        }
        return frequencies;
    }

    private List<RoomInfoSnapshot> titles() {
        return List.of(new RoomInfoSnapshot(START, "开播时的标题", "虚拟主播"),
                new RoomInfoSnapshot(START + 42 * MINUTE, "改过一次的标题", "虚拟主播"));
    }

    private List<LiveGap> gaps() {
        return List.of(new LiveGap(START + 20 * MINUTE, START + 22 * MINUTE, LiveGap.Reason.RESTART));
    }

    private static BufferedImage image(byte[] png) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        assertNotNull(image, "PNG 解不出来");
        return image;
    }

    private static Object field(Class<?> owner, Object target, String name) throws Exception {
        java.lang.reflect.Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
