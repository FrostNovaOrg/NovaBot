package org.frostnova.nova.report.command;

import org.frostnova.nova.bilibili.command.BilibiliStreamerChoice;
import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.command.CommandContext;
import org.frostnova.nova.core.command.CommandReply;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.model.PushTarget;
import org.frostnova.nova.core.model.PushUser;
import org.frostnova.nova.core.model.UserScore;
import org.frostnova.nova.core.service.LiveDataService;
import org.frostnova.nova.core.service.NovaStateStore;
import org.frostnova.nova.core.service.RevenueVisibilityService;
import org.frostnova.nova.report.factory.NovaCommonPainterFactory;
import org.frostnova.nova.report.painter.BilibiliDataQueryPainter;
import org.frostnova.nova.report.util.FontUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.io.DefaultResourceLoader;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 数据排行榜整图高度上限
 * <p>
 * 太长的图在手机上刷不动、也常发不出去：宁可少列几名并写明「其余 N 名未列出」，
 * 也不要出一张超过高度上限的长图。上限按真实出图高度量，不按估计的行数推。
 */
@DisplayName("数据排行榜整图高度上限")
class BilibiliRankingHeightLimitTest {
    private static final String PLATFORM = "qq-onebot";

    private static final Long GROUP = 30003L;

    private static final Long STREAMER = 10001L;

    private static final int PEOPLE = 50;

    /**
     * 这次上限设成「正好画得下这么多行」
     */
    private static final int FITS = 20;

    private BilibiliDataQueryPainter painter;

    /**
     * 标定上限用的画手，与命令手上那只替身分开：
     * 标定要走一次真出图量高度，而那一次不能记进替身的调用记录，
     * 否则「命令画了几行」的清点里会多出标定那一笔
     */
    private BilibiliDataQueryPainter measuring;

    private LiveDataService liveDataService;

    private NovaBilibiliProperties properties;

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    @BeforeEach
    void setUp() {
        // 与绘制测试同一套桩：内置字体、占位头像，不依赖网络与本机字体
        NovaCoreProperties coreProperties = new NovaCoreProperties();
        coreProperties.getPaint().getFonts().add("内置");

        FontUtil fontUtil = new FontUtil(new DefaultResourceLoader(), coreProperties);
        fontUtil.init();

        Properties buildInfo = new Properties();
        buildInfo.setProperty("version", "4.0.0");
        buildInfo.setProperty("group", "com." + "starlwr");
        buildInfo.setProperty("artifact", "nova-core");
        buildInfo.setProperty("name", "NovaBot");

        NovaCommonPainterFactory factory =
                new NovaCommonPainterFactory(new BuildProperties(buildInfo), coreProperties, fontUtil);

        BufferedImage placeholder = new BufferedImage(200, 200, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = placeholder.createGraphics();
        graphics.setColor(new Color(120, 170, 220));
        graphics.fillRect(0, 0, 200, 200);
        graphics.dispose();

        BilibiliApiUtil api = mock(BilibiliApiUtil.class);
        when(api.getBilibiliImage(anyString())).thenReturn(Optional.of(placeholder));

        measuring = new BilibiliDataQueryPainter(factory, api);
        painter = spy(new BilibiliDataQueryPainter(factory, api));

        liveDataService = mock(LiveDataService.class);
        properties = new NovaBilibiliProperties();
    }

    @Test
    @DisplayName("装不下时只画装得下的名次，出图高度不越上限，其余名数写对")
    void cutsRowsToHeightLimit() throws Exception {
        withRanking(PEOPLE);
        // 上限就是「画得下这么多行」那张图的高度：用真实出图高度当尺，
        // 而不是拍一个行数去推像素
        int cap = heightOfRows(FITS);
        properties.getRanking().setHeightLimit(cap);

        BilibiliRankingCommand command = new BilibiliRankingCommand(
                dataSource(), mock(BilibiliStreamerChoice.class), liveDataService, painter,
                new RevenueVisibilityService(new NovaStateStore(new NovaCoreProperties())), properties);

        CommandReply reply = command.execute(context("弹幕"));

        ArgumentCaptor<List<UserScore>> rows = captor();
        ArgumentCaptor<String> footnote = ArgumentCaptor.forClass(String.class);
        verify(painter).paintRanking(any(), rows.capture(), eq(1), any(), footnote.capture());
        int painted = heightOfImage(reply);
        assertAll(
                () -> assertEquals(FITS, rows.getValue().size(), "只画装得下的那些名次"),
                () -> assertTrue(footnote.getValue().contains("其余 " + (PEOPLE - FITS) + " 名未列出"),
                        footnote.getValue()),
                () -> assertTrue(painted <= cap, "出图高度 " + painted + " 超过上限 " + cap)
        );
    }

    @Test
    @DisplayName("上限填得比一张最小的图还矮：按最小高度出图，并记一条 WARN")
    void belowMinimumLimitPaintsAtMinimumAndWarns() throws Exception {
        withRanking(PEOPLE);
        // 一百比「表头 + 一行名次 + 脚注」的最小高度还矮，画得下一行就到顶了
        properties.getRanking().setHeightLimit(100);

        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(BilibiliRankingCommand.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            BilibiliRankingCommand command = new BilibiliRankingCommand(
                    dataSource(), mock(BilibiliStreamerChoice.class), liveDataService, painter,
                    new RevenueVisibilityService(new NovaStateStore(new NovaCoreProperties())), properties);

            CommandReply reply = command.execute(context("弹幕"));

            ArgumentCaptor<List<UserScore>> rows = captor();
            ArgumentCaptor<String> footnote = ArgumentCaptor.forClass(String.class);
            verify(painter).paintRanking(any(), rows.capture(), eq(1), any(), footnote.capture());

            int painted = heightOfImage(reply);
            // 生效的上限是「配置值」与「一张最小的图」里高的那个，按真出图高度量
            int minimum = measuring.measureRankingHeight(header(), 1, footnote.getValue());
            List<String> warnings = appender.list.stream()
                    .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN)
                    .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                    .filter(m -> m.contains("上限"))
                    .toList();

            assertAll(
                    () -> assertTrue(rows.getValue().size() >= 1,
                            "上限再矮也得画得出名次来，空图等于没出图"),
                    () -> assertTrue(minimum > 0,
                            "最小可出图高度量不出数，这一格就没法判越没越上限"),
                    () -> assertTrue(painted <= Math.max(100, minimum),
                            "出图高度 " + painted + " 越过生效的上限（配置 100，最小可出图 " + minimum + "）"),
                    () -> assertEquals(1, warnings.size(),
                            "上限低于最小可出图高度该记一条 WARN 说明按最小高度出了，实际 " + warnings),
                    () -> assertTrue(warnings.get(0).contains("100"),
                            "WARN 要写明填的那个上限，否则使用者不知道是哪一项被抬高了: " + warnings.get(0))
            );
        } finally {
            logger.detachAppender(appender);
        }
    }

    /**
     * 按给定名次数画一张，取它的像素高度
     * <p>
     * 走 {@link #measuring} 而不是命令手上那只替身：标定是量尺本身的事，
     * 记到替身头上会让「命令画了几行」的清点多出一笔
     */
    private int heightOfRows(int shown) throws Exception {
        List<UserScore> rows = ranking(PEOPLE).subList(0, shown);
        String base64 = measuring.paintRanking(header(), rows, 1, score -> Math.round(score) + " 条",
                footnoteFor(shown)).orElseThrow();
        return heightOfImage(CommandReply.image(base64));
    }

    private String footnoteFor(int shown) {
        return "前 " + shown + " 名 · 共 " + PEOPLE + " 人\n其余 " + (PEOPLE - shown) + " 名未列出";
    }

    private BilibiliDataQueryPainter.Header header() {
        return new BilibiliDataQueryPainter.Header("弹幕排行榜", "本场数据 · 测试主播的直播间", null);
    }

    /**
     * 回复里那张图的像素高度
     */
    private static int heightOfImage(CommandReply reply) throws Exception {
        String content = reply.content();
        int start = content.indexOf("{image_base64=");
        assertTrue(start >= 0, "回的不是图：" + content);
        String base64 = content.substring(start + "{image_base64=".length(), content.lastIndexOf('}'));
        BufferedImage image = javax.imageio.ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(base64)));
        assertTrue(image != null, "解不出图");
        return image.getHeight();
    }

    /**
     * 让排行榜接口按请求的名次数返回连号用户，得分随名次递减
     */
    private void withRanking(int total) {
        when(liveDataService.getLiveMetricUserCount(anyString(), anyLong(), anyString())).thenReturn(total);
        when(liveDataService.getLiveUserRanking(anyString(), anyLong(), anyString(), anyInt()))
                .thenAnswer(invocation -> ranking(invocation.getArgument(3)));
    }

    private static List<UserScore> ranking(int limit) {
        List<UserScore> scores = new ArrayList<>();
        for (int i = 1; i <= limit; i++) {
            scores.add(new UserScore((long) i, "观众" + String.format("%02d", i), PEOPLE - i + 1));
        }
        return scores;
    }

    private AbstractDataSource dataSource() {
        AbstractDataSource dataSource = mock(AbstractDataSource.class);
        when(dataSource.getUsers("bilibili")).thenReturn(List.of(streamer()));
        return dataSource;
    }

    private static PushUser streamer() {
        PushTarget target = new PushTarget();
        target.setPlatform(PLATFORM);
        target.setType(PushTargetType.GROUP);
        target.setNum(GROUP);

        PushUser user = new PushUser();
        user.setUid(STREAMER);
        user.setUname("测试主播");
        user.setTargets(List.of(target));
        return user;
    }

    private static CommandContext context(String... args) {
        String typed = "数据排行榜";
        return new CommandContext(PLATFORM, PushTargetType.GROUP, GROUP, 2000000002L,
                typed, Arrays.asList(args), typed);
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<List<UserScore>> captor() {
        return ArgumentCaptor.forClass(List.class);
    }
}
