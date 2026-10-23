package org.frostnova.nova.report.handler;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.alibaba.fastjson2.JSON;
import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.event.dynamic.BilibiliDynamicUpdateEvent;
import org.frostnova.nova.bilibili.event.live.BilibiliLiveOffEvent;
import org.frostnova.nova.bilibili.event.live.BilibiliOfflineRewardDigestEvent;
import org.frostnova.nova.bilibili.model.BilibiliLiveReportOptions;
import org.frostnova.nova.bilibili.model.Dynamic;
import org.frostnova.nova.core.service.AtSubscriptionService;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.datasource.DataSourceService;
import org.frostnova.nova.core.datasource.DataSourceServiceRegistry;
import org.frostnova.nova.core.datasource.JsonDataSource;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.event.NovaExternalBaseEvent;
import org.frostnova.nova.core.handler.NovaEventHandler;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import org.frostnova.nova.core.model.Message;
import org.frostnova.nova.core.model.PushMessage;
import org.frostnova.nova.core.model.PushTarget;
import org.frostnova.nova.core.sender.NovaMessageSender;
import org.frostnova.nova.core.service.LiveDataService;
import org.frostnova.nova.core.service.LiveRoomInfoHistory;
import org.frostnova.nova.core.service.NovaStateStore;
import org.frostnova.nova.core.service.PushTemplateDefaults;
import org.frostnova.nova.core.service.ReportPaletteSettings;
import org.frostnova.nova.core.service.RevenueVisibilityService;
import org.frostnova.nova.core.service.StreamerNames;
import org.frostnova.nova.core.timeline.TimelineWriter;
import org.frostnova.nova.report.factory.NovaCommonPainterFactory;
import org.frostnova.nova.report.painter.BilibiliDynamicPainter;
import org.frostnova.nova.report.painter.BilibiliLiveReportPreviewPainter;
import org.frostnova.nova.report.painter.BilibiliOfflineRewardDigestPainter;
import org.frostnova.nova.report.painter.BilibiliWeeklyReportPainter;
import org.frostnova.nova.report.painter.ReportPalette;
import org.frostnova.nova.report.painter.ReportPalettes;
import org.frostnova.nova.report.util.FontUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.DefaultResourceLoader;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 推送出去的报告图按通道的配色画
 * <p>
 * 用真画手出图，量的是推出去那张图的底色：配色接错了，使用者看到的就是「选了夜幕，推出来还是白底」。
 */
@DisplayName("报告图按通道的配色出图")
class ChannelReportPaletteTest {
    private static final Pattern IMAGE = Pattern.compile("\\{image_base64=([^}]+)}");

    private static final LiveStreamerInfo STREAMER = new LiveStreamerInfo(19604318752096L, "示例主播", 47615208934771L);

    private static NovaCommonPainterFactory factory;

    private static FontUtil fonts;

    @TempDir
    Path dir;

    private PushTemplateDefaults defaults;

    private ReportPalettes palettes;

    private NovaMessageSender sender;

    private RevenueVisibilityService revenue;

    private BilibiliApiUtil api;

    private final ListAppender<ILoggingEvent> logLines = new ListAppender<>();

    /**
     * 各画手真去画了几次
     */
    private final Map<String, AtomicInteger> painted = new HashMap<>();

    @BeforeAll
    static void painterFactory() {
        System.setProperty("java.awt.headless", "true");
        NovaCoreProperties properties = new NovaCoreProperties();
        properties.getPaint().getFonts().add("内置");
        fonts = new FontUtil(new DefaultResourceLoader(), properties);
        fonts.init();
        Properties build = new Properties();
        build.setProperty("version", "1.0.0");
        factory = new NovaCommonPainterFactory(new BuildProperties(build), properties, fonts);
    }

    @BeforeEach
    void setUp() {
        NovaCoreProperties properties = new NovaCoreProperties();
        properties.getDatasource().setJsonPath(dir.resolve("datasource.json").toString());
        properties.getLive().setLiveDataPath(dir.resolve("data.json").toString());
        defaults = new PushTemplateDefaults(properties);
        palettes = new ReportPalettes(new ReportPaletteSettings(defaults));
        revenue = new RevenueVisibilityService(new NovaStateStore(properties));
        sender = mock(NovaMessageSender.class);
        api = mock(BilibiliApiUtil.class);
        when(api.getUpInfoByUid(anyLong())).thenThrow(new RuntimeException("接口不可用"));

        Logger logger = (Logger) LoggerFactory.getLogger(ReportPalettes.class);
        logger.setLevel(Level.INFO);
        logLines.start();
        logger.addAppender(logLines);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(ReportPalettes.class)).detachAppender(logLines);
    }

    // 用户故障：通道里选了夜幕，推出来还是白底
    @Test
    @DisplayName("通道选了夜幕：下播报告、动态、下播打赏播报推出去都是夜幕的底色")
    void chosenPaletteReachesEveryPushedImage() throws Exception {
        for (Kind kind : Kind.values()) {
            Kind.Run run = kind.start(this);
            run.handle(channel(30001L, "night"));
            assertEquals(ReportPalette.NIGHT.background(), dominant(lastImage()), kind.label);
        }
    }

    // 用户故障：升级后没选过的通道还是旧样子
    @Test
    @DisplayName("通道没选、默认模板没改：出粉蓝")
    void unchosenChannelGetsPinkBlueAfterUpgrade() throws Exception {
        for (Kind kind : Kind.values()) {
            kind.start(this).handle(channel(30001L, null));
            assertEquals(ReportPalette.PINK_BLUE.background(), dominant(lastImage()), kind.label);
        }
    }

    // 用户故障：改了默认模板，另选过的通道也被改掉
    @Test
    @DisplayName("默认模板改成经典：没选的通道出经典，另选了暖阳的通道仍出暖阳")
    void defaultTemplateMovesOnlyUnchosenChannels() throws Exception {
        assertTrue(new ReportPaletteSettings(defaults).saveDefault("classic").isEmpty());
        for (Kind kind : Kind.values()) {
            Kind.Run run = kind.start(this);
            run.handle(channel(30001L, null));
            assertEquals(ReportPalette.CLASSIC.background(), dominant(lastImage()), kind.label + " 没选的通道");
            run.handle(channel(30002L, "warm"));
            assertEquals(ReportPalette.WARM.background(), dominant(lastImage()), kind.label + " 选了暖阳的通道");
        }
    }

    @Test
    @DisplayName("不认识的 id：照默认模板的配色出图，日志只记一句")
    void unknownIdFallsBackToDefaultAndLogsOnce() throws Exception {
        assertTrue(new ReportPaletteSettings(defaults).saveDefault("warm").isEmpty());
        Kind.Run run = Kind.LIVE_REPORT.start(this);
        run.handle(channel(30001L, "sakura"));
        assertEquals(ReportPalette.WARM.background(), dominant(lastImage()));
        run.handle(channel(30002L, "sakura"));
        Kind.DYNAMIC.start(this).handle(channel(30003L, "sakura"));

        List<String> lines = logLines.list.stream().map(ILoggingEvent::getFormattedMessage)
                .filter(line -> line.contains("sakura")).toList();
        assertEquals(1, lines.size(), "同一个 id 只记一次: " + lines);
        assertTrue(lines.get(0).contains("暖阳"), "说清按哪一套出的图: " + lines.get(0));
        assertEquals(Level.WARN, logLines.list.get(0).getLevel());
    }

    @Test
    @DisplayName("周报画手答「实际按哪一套画」：认得的原样，认不出的答默认模板那一套")
    void weeklyPainterNamesThePaletteItActuallyUses() {
        assertTrue(new ReportPaletteSettings(defaults).saveDefault("night").isEmpty());
        BilibiliWeeklyReportPainter weekly = new BilibiliWeeklyReportPainter(factory, api,
                new NovaBilibiliProperties(), palettes);
        assertEquals("warm", weekly.paletteId("warm"));
        assertEquals("night", weekly.paletteId("sakura"));
        assertEquals("night", weekly.paletteId(null));
    }

    @Test
    @DisplayName("同一份报告推到同一套配色的三个通道只画一次，另一套配色的再画一次")
    void sameReportSamePaletteIsPaintedOnce() throws Exception {
        for (Kind kind : Kind.values()) {
            Kind.Run run = kind.start(this);
            run.handle(channel(30001L, "night"));
            run.handle(channel(30002L, "night"));
            run.handle(channel(30003L, "night"));
            int afterSame = painted(kind);
            run.handle(channel(30004L, "warm"));
            int afterOther = painted(kind);
            System.out.println("PAINTED " + kind + " three-night=" + afterSame + " plus-warm=" + afterOther);
            assertEquals(1, afterSame, kind.label + "：同一套配色的三个通道");
            assertEquals(2, afterOther, kind.label + "：另一套配色");

            List<String> images = images();
            assertEquals(4, images.size(), kind.label + "：四个通道各收到一张");
            assertEquals(images.get(0), images.get(2), kind.label + "：同一套配色的通道收到同一张");
            assertFalse(images.get(0).equals(images.get(3)), kind.label + "：另一套配色是另一张");
        }
    }

    // 用户故障：同一套配色的两个群，藏金额的那个群收到了带金额的图
    @Test
    @DisplayName("配色相同而金额可见性不同的通道各画各的，藏金额的群拿不到带金额的那张")
    void revenueVisibilityIsPartOfWhatMakesTheImage() throws Exception {
        for (Kind kind : List.of(Kind.LIVE_REPORT, Kind.REWARD_DIGEST)) {
            Kind.Run run = kind.start(this);
            PushTarget shown = channel(30001L, "night");
            shown.setRevenueVisible(true);
            run.handle(shown);
            run.handle(channel(30002L, "night"));
            assertEquals(2, painted(kind), kind.label);
            List<String> images = images();
            assertFalse(images.get(0).equals(images.get(1)), kind.label + "：两张不该是同一张");
        }
    }

    // 用户故障：前后两场共用了一张图，这一场推出去的是上一场的报告
    @Test
    @DisplayName("同一个处理器接着处理下一件事件：照样重画，不拿上一件的图")
    void nextEventOnTheSameHandlerIsPaintedAgain() throws Exception {
        for (Kind kind : Kind.values()) {
            Kind.Run first = kind.start(this);
            first.handle(channel(30001L, "night"));
            first.next().handle(channel(30001L, "night"));
            System.out.println("NEXT_EVENT " + kind + " painted=" + painted(kind));
            assertEquals(2, painted(kind), kind.label + "：两件事件各画一次");
        }
    }

    // 用户故障：两个群选了不同的版式，推出来却是同一张图
    @Test
    @DisplayName("配色相同而版式不同的通道各画各的")
    void layoutIsPartOfWhatMakesTheImage() throws Exception {
        Kind.Run live = Kind.LIVE_REPORT.start(this);
        live.handle(channel(30001L, "night"));
        PushTarget compact = channel(30002L, "night");
        PushMessage withoutCards = message(live.handler(), compact);
        withoutCards.getParamsJsonObject().put("cards", false);
        withoutCards.getParamsJsonObject().put("cover", false);
        live.handle(compact, withoutCards);
        System.out.println("LAYOUT LIVE_REPORT painted=" + painted(Kind.LIVE_REPORT));
        assertEquals(2, painted(Kind.LIVE_REPORT), "下播报告：两种版式各画一次");

        Kind.Run digest = Kind.REWARD_DIGEST.start(this);
        digest.handle(channel(30001L, "night"));
        PushTarget higher = channel(30002L, "night");
        PushMessage thresholdOne = message(digest.handler(), higher);
        thresholdOne.getParamsJsonObject().put("reward_threshold_yuan", 1);
        digest.handle(higher, thresholdOne);
        System.out.println("LAYOUT REWARD_DIGEST painted=" + painted(Kind.REWARD_DIGEST));
        assertEquals(2, painted(Kind.REWARD_DIGEST), "下播打赏播报：两种门槛各画一次");
    }

    // 用户故障：通道里选了夜幕，推出来还是粉蓝（存进推送配置文件的选择，载入、重载后都丢了）
    @Test
    @DisplayName("推送配置文件里存的通道配色：载入、重载之后，三种推送图都按它画")
    void savedChoicesSurviveLoadAndReload() throws Exception {
        assertTrue(new ReportPaletteSettings(defaults).saveDefault("warm").isEmpty());
        Path file = dir.resolve("datasource.json");
        writeDatasource(file, "night");
        JsonDataSource source = jsonDataSource(file);
        try {
            source.load();
            for (Kind kind : Kind.values()) {
                kind.start(this).handle(loaded(source, 30001L));
                Color actual = dominant(lastImage());
                System.out.println("JSON_LOAD " + kind + " configured=night actual=" + hex(actual));
                assertEquals(ReportPalette.NIGHT.background(), actual, kind.label + " 载入后");
            }

            writeDatasource(file, "classic");
            source.reload();
            for (Kind kind : Kind.values()) {
                kind.start(this).handle(loaded(source, 30001L));
                Color actual = dominant(lastImage());
                System.out.println("JSON_RELOAD " + kind + " configured=classic actual=" + hex(actual));
                assertEquals(ReportPalette.CLASSIC.background(), actual, kind.label + " 重载后");
            }
        } finally {
            source.shutdown();
        }
    }

    @Test
    @DisplayName("推送配置文件里存了不认识的 id：载入、重载后照默认模板的配色出图，日志只记一句")
    void unknownIdFromTheFileFallsBackAndLogsOnce() throws Exception {
        assertTrue(new ReportPaletteSettings(defaults).saveDefault("warm").isEmpty());
        Path file = dir.resolve("datasource.json");
        writeDatasource(file, "sakura");
        JsonDataSource source = jsonDataSource(file);
        try {
            source.load();
            Kind.LIVE_REPORT.start(this).handle(loaded(source, 30001L));
            assertEquals(ReportPalette.WARM.background(), dominant(lastImage()));
            Kind.DYNAMIC.start(this).handle(loaded(source, 30001L));
            assertEquals(ReportPalette.WARM.background(), dominant(lastImage()));

            // 改一处无关的设置让这位主播被换成新读出的一份，配色照旧是那个不认识的 id
            writeDatasource(file, "sakura", false);
            source.reload();
            Kind.REWARD_DIGEST.start(this).handle(loaded(source, 30001L));
            assertEquals(ReportPalette.WARM.background(), dominant(lastImage()));
        } finally {
            source.shutdown();
        }

        List<String> lines = logLines.list.stream().map(ILoggingEvent::getFormattedMessage)
                .filter(line -> line.contains("sakura")).toList();
        System.out.println("JSON_UNKNOWN warning-lines=" + lines.size());
        assertEquals(1, lines.size(), "同一个 id 只记一次: " + lines);
        assertTrue(lines.get(0).contains("暖阳"), lines.get(0));
    }

    // 用户故障：想要原来的样子选了经典，出来的图却和升级前不一样
    @Test
    @DisplayName("选经典的通道推出去的图，与不带配色的旧入口逐像素相同")
    void classicChannelMatchesTheOldEntry() throws Exception {
        BilibiliLiveReportPreviewPainter painter = livePainter();
        BilibiliLiveReportPushHandler handler = new BilibiliLiveReportPushHandler(api, sender, painter, revenue,
                StreamerNames.none(), palettes());
        PushMessage message = message(handler, channel(30001L, "classic"));
        handler.handle(new BilibiliLiveOffEvent(STREAMER), message);

        BilibiliLiveReportOptions options = BilibiliLiveReportOptions.of(message.getParamsJsonObject(),
                revenue.isVisible(message.getTarget()));
        String old = painter.paint("bilibili", STREAMER, options).orElseThrow();
        long differing = differing(decode(old), decode(lastImage()));
        System.out.println("CLASSIC_VS_OLD_ENTRY live-report differing=" + differing);
        assertEquals(0, differing);
    }

    /**
     * 三种推送图，各自装出处理器、事件与计数
     */
    private enum Kind {
        LIVE_REPORT("下播报告"),
        DYNAMIC("动态推送"),
        REWARD_DIGEST("下播打赏播报");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        /**
         * 一件事件，与处理它的处理器
         */
        record Run(ChannelReportPaletteTest test, Kind kind, NovaEventHandler handler, NovaExternalBaseEvent event) {
            void handle(PushTarget channel) {
                handle(channel, test.message(handler, channel));
            }

            void handle(PushTarget channel, PushMessage message) {
                handler.handle(event, message);
            }

            /**
             * 同一个处理器接着处理下一件事件
             */
            Run next() {
                return new Run(test, kind, handler, kind.event(test));
            }
        }

        Run start(ChannelReportPaletteTest test) {
            NovaEventHandler handler = switch (this) {
                case LIVE_REPORT -> new BilibiliLiveReportPushHandler(test.api, test.sender,
                        test.livePainter(), test.revenue, StreamerNames.none(), test.palettes());
                case DYNAMIC -> test.dynamicHandler();
                case REWARD_DIGEST -> new BilibiliOfflineRewardDigestPushHandler(test.api, test.sender,
                        test.revenue, test.digestPainter(), StreamerNames.none(), test.palettes());
            };
            return new Run(test, this, handler, event(test));
        }

        NovaExternalBaseEvent event(ChannelReportPaletteTest test) {
            return switch (this) {
                case LIVE_REPORT -> new BilibiliLiveOffEvent(STREAMER);
                case DYNAMIC -> test.dynamicEvent();
                case REWARD_DIGEST -> test.digestEvent();
            };
        }
    }

    /**
     * 与生产同一种装法：几个处理器共用容器里那一份
     */
    private ReportPalettes palettes() {
        return palettes;
    }

    private AtomicInteger counter(Kind kind) {
        return painted.computeIfAbsent(kind.name(), ignored -> new AtomicInteger());
    }

    private int painted(Kind kind) {
        return counter(kind).get();
    }

    private BilibiliLiveReportPreviewPainter livePainter() {
        AtomicInteger count = counter(Kind.LIVE_REPORT);
        return new BilibiliLiveReportPreviewPainter(factory, mock(BilibiliApiUtil.class), fonts,
                new NovaBilibiliProperties(), mock(LiveRoomInfoHistory.class)) {
            @Override
            public Optional<String> paint(String platform, LiveStreamerInfo source, BilibiliLiveReportOptions options,
                                          ReportPalette palette) {
                count.incrementAndGet();
                return super.paint(platform, source, options, palette);
            }
        };
    }

    private BilibiliDynamicPushHandler dynamicHandler() {
        AtomicInteger count = counter(Kind.DYNAMIC);
        BilibiliDynamicPainter painter = new BilibiliDynamicPainter(factory, api, new NovaBilibiliProperties()) {
            @Override
            public Optional<String> paint(Dynamic dynamic, ReportPalette palette) {
                count.incrementAndGet();
                return super.paint(dynamic, palette);
            }
        };
        AtSubscriptionService subscriptions = mock(AtSubscriptionService.class);
        when(subscriptions.list(anyString(), anyLong(), anyLong(), anyString())).thenReturn(List.of());
        return new BilibiliDynamicPushHandler(api, painter, sender, subscriptions, mock(LiveDataService.class),
                new NovaBilibiliProperties(), TimelineWriter.NONE, StreamerNames.none(), palettes());
    }

    private BilibiliDynamicUpdateEvent dynamicEvent() {
        Dynamic dynamic = new Dynamic();
        dynamic.setId("1");
        dynamic.setType("DYNAMIC_TYPE_WORD");
        dynamic.setModules(JSON.parseObject("{\"module_author\":{\"name\":\"示例主播\",\"pub_ts\":1700000000},"
                + "\"module_dynamic\":{\"desc\":{\"text\":\"今晚八点见\"}}}"));
        return new BilibiliDynamicUpdateEvent(STREAMER, dynamic, "发布了动态", "https://t.example/1");
    }

    private BilibiliOfflineRewardDigestPainter digestPainter() {
        AtomicInteger count = counter(Kind.REWARD_DIGEST);
        return new BilibiliOfflineRewardDigestPainter(factory, api, new NovaBilibiliProperties()) {
            @Override
            public Optional<String> paint(BilibiliOfflineRewardDigestEvent event, boolean showRevenue,
                                          ReportPalette palette) {
                count.incrementAndGet();
                return super.paint(event, showRevenue, palette);
            }
        };
    }

    private BilibiliOfflineRewardDigestEvent digestEvent() {
        BilibiliOfflineRewardDigestEvent.Contribution person = new BilibiliOfflineRewardDigestEvent.Contribution();
        person.setUid(19338207415562L);
        person.setUname("观众甲");
        person.setGiftAmountFen(10_000L);
        person.setGifts(List.of(new BilibiliOfflineRewardDigestEvent.GiftLine("小花花", 2, 10_000L)));
        return new BilibiliOfflineRewardDigestEvent(STREAMER, List.of(person));
    }

    /**
     * 一个群通道
     * @param palette 通道另选的配色，null 为没选
     */
    private static PushTarget channel(long group, String palette) {
        PushTarget target = new PushTarget();
        target.setPlatform("qq-onebot");
        target.setType(PushTargetType.GROUP);
        target.setNum(group);
        target.setReportPalette(palette);
        return target;
    }

    private static void writeDatasource(Path file, String palette) throws Exception {
        writeDatasource(file, palette, true);
    }

    /**
     * 一位主播、一个群通道的推送配置文件，与使用者手上那份同一种写法
     */
    private static void writeDatasource(Path file, String palette, boolean revenueVisible) throws Exception {
        Files.writeString(file, "[{\"uid\":10001,\"platform\":\"bilibili\",\"targets\":[{"
                + "\"platform\":\"qq-onebot\",\"type\":1,\"num\":30001,\"revenueVisible\":" + revenueVisible
                + ",\"reportPalette\":\"" + palette + "\"}]}]", StandardCharsets.UTF_8);
    }

    private static JsonDataSource jsonDataSource(Path file) {
        NovaCoreProperties properties = new NovaCoreProperties();
        properties.getDatasource().setJsonPath(file.toString());
        properties.getDatasource().setJsonAutoReload(false);
        DataSourceServiceRegistry registry = mock(DataSourceServiceRegistry.class);
        when(registry.getDataSourceService("bilibili")).thenReturn(Optional.of(mock(DataSourceService.class)));
        return new JsonDataSource(mock(ApplicationEventPublisher.class), registry, null, properties.getDatasource());
    }

    /**
     * 数据源此刻手上的那个通道
     */
    private static PushTarget loaded(JsonDataSource source, long group) {
        return source.getUser("bilibili", 10001L).orElseThrow().getTargets().stream()
                .filter(target -> target.getNum() == group).findFirst().orElseThrow();
    }

    private static String hex(Color color) {
        return String.format("#%06X", color.getRGB() & 0xFFFFFF);
    }

    private PushMessage message(NovaEventHandler handler, PushTarget channel) {
        PushMessage message = new PushMessage();
        message.setTarget(channel);
        message.setParamsJsonObject(handler.getDefaultParams());
        return message;
    }

    private List<String> images() {
        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(sender, atLeastOnce()).send(captor.capture());
        List<String> images = new ArrayList<>();
        for (Message message : captor.getAllValues()) {
            Matcher matcher = IMAGE.matcher(message.getContent());
            if (matcher.find()) {
                images.add(matcher.group(1));
            }
        }
        // 处理器手里拿的是同一个替身：读过的清掉，下一次只读新发的
        clearInvocations(sender);
        return images;
    }

    private String lastImage() {
        List<String> images = images();
        assertFalse(images.isEmpty(), "没有推出图片");
        return images.get(images.size() - 1);
    }

    private static BufferedImage decode(String base64) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(base64)));
    }

    /**
     * 整张图里最多的那种不透明颜色，即底色
     */
    private static Color dominant(String base64) throws Exception {
        BufferedImage image = decode(base64);
        Map<Integer, Integer> counts = new HashMap<>();
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int pixel = image.getRGB(x, y);
                if ((pixel >>> 24) == 255) {
                    counts.merge(pixel & 0xFFFFFF, 1, Integer::sum);
                }
            }
        }
        int top = counts.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
        return new Color(top);
    }

    private static long differing(BufferedImage expected, BufferedImage actual) {
        assertEquals(expected.getWidth(), actual.getWidth());
        assertEquals(expected.getHeight(), actual.getHeight());
        long differing = 0;
        for (int y = 0; y < expected.getHeight(); y++) {
            for (int x = 0; x < expected.getWidth(); x++) {
                if (expected.getRGB(x, y) != actual.getRGB(x, y)) differing++;
            }
        }
        return differing;
    }
}
