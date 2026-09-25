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

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
 * 数据排行榜整张长图出图
 * <p>
 * 命令自己的尺把画手当替身，量不到「真出一张图」这一段：50 行的头像、条形、脚注折行
 * 全在画手手里，而画手自己的尺只画 10 行。这里让命令打真画手，量的是端到端的这一张。
 * <p>
 * 最要紧的一条是<b>量出来的高度要等于出图高度</b>：按高度上限截行截到哪一行，
 * 全靠量高度那把尺。尺量短了就会多画几行、出图越上限，而越上限的图发得出去也刷不动。
 * 昵称用「观众01」这类假名，头像用同一张本地占位图——不蹭真实直播间，样张能反复出。
 */
@DisplayName("数据排行榜长图出图")
class BilibiliRankingLongImageTest {
    private static final String PLATFORM = "qq-onebot";

    private static final Long GROUP = 30003L;

    private static final Long STREAMER = 10001L;

    private static final int WIDTH = 760;

    /**
     * 样张落盘处：产品树根下的 scratch/，不进仓
     */
    private static final File OUT_DIR = new File("../../scratch");

    private NovaCommonPainterFactory factory;

    private BilibiliApiUtil api;

    /**
     * 命令手上的画手，做成替身只为把画进去的各行与脚注抄下来
     */
    private BilibiliDataQueryPainter painter;

    /**
     * 量高度用的画手，与替身分开：量高度要走一次真出图，
     * 而那一次不能记进替身的调用记录，否则「命令画了几行」的清点里会多出标定那一笔
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

        factory = new NovaCommonPainterFactory(new BuildProperties(buildInfo), coreProperties, fontUtil);

        BufferedImage placeholder = new BufferedImage(200, 200, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = placeholder.createGraphics();
        graphics.setColor(new Color(120, 170, 220));
        graphics.fillRect(0, 0, 200, 200);
        graphics.dispose();

        api = mock(BilibiliApiUtil.class);
        when(api.getBilibiliImage(anyString())).thenReturn(Optional.of(placeholder));
        when(api.fetchBilibiliImage(anyString())).thenReturn(Optional.of(placeholder));

        measuring = new BilibiliDataQueryPainter(factory, api);
        painter = spy(new BilibiliDataQueryPainter(factory, api));

        liveDataService = mock(LiveDataService.class);
        properties = new NovaBilibiliProperties();
    }

    @Test
    @DisplayName("满 50 名一张图列全，量出来的高度就是出图高度")
    void paintsAllFiftyInOneImage() {
        withRanking(50);
        properties.getRanking().setTopN(50);

        Rendered rendered = execute(painter);

        assertAll(
                () -> assertEquals(50, rendered.rows().size(), "50 名要一次列全"),
                () -> assertEquals(WIDTH, rendered.image().getWidth()),
                () -> assertEquals(measured(rendered), rendered.image().getHeight(),
                        "量高度那把尺要与真出图一般高，截行才截得准")
        );
        dump("样图1-50人本场弹幕榜", rendered.image());
    }

    @Test
    @DisplayName("最多列出名次调大后由高度上限说了算，出图不越上限")
    void heightLimitDecidesWhenTopNIsRaised() {
        int total = 300;
        withRanking(total);
        // 最多列出名次顶到远超上限装得下的名次，剩下的交给高度上限裁
        properties.getRanking().setTopN(total);
        int cap = 10000;
        properties.getRanking().setHeightLimit(cap);

        Rendered rendered = execute(painter);

        assertAll(
                () -> assertTrue(rendered.image().getHeight() <= cap,
                        "出图高度 " + rendered.image().getHeight() + " 超过上限 " + cap),
                () -> assertTrue(rendered.rows().size() < total, "上限没起作用，" + total + " 名全画进去了"),
                () -> assertTrue(rendered.rows().size() > 50, "上限卡得太死，比默认列的名次还少"),
                () -> assertTrue(rendered.footnote().contains("其余 " + (total - rendered.rows().size()) + " 名未列出"),
                        rendered.footnote()),
                () -> assertEquals(measured(rendered), rendered.image().getHeight(),
                        "量高度那把尺要与真出图一般高，截行才截得准")
        );
        dump("样图2-顶满高度上限", rendered.image());
    }

    @Test
    @DisplayName("冷缓存出 50 人一张图的耗时")
    void coldRenderCost() {
        // 每趟换一只新画手，也就是换一份空的头像缓存，量到的是「头像还没取过」那一趟。
        // 共用一只画手的话第二趟起头像已在缓存里，那个数是热缓存的数，答的不是这句话
        withRanking(50);
        properties.getRanking().setTopN(50);

        long first = millis(() -> run(newPainter()));
        long second = millis(() -> run(newPainter()));
        properties.getRanking().setTopN(10);
        long ten = millis(() -> run(newPainter()));

        System.out.println("冷缓存出图耗时 50 人第一趟 " + first + " ms");
        System.out.println("冷缓存出图耗时 50 人第二趟 " + second + " ms");
        System.out.println("冷缓存出图耗时 10 人 " + ten + " ms");
    }

    /**
     * 出图的结果：图、画进去的各行与画进脚注的那句话
     */
    private record Rendered(BufferedImage image, List<UserScore> rows, String footnote) {
    }

    private long millis(Runnable task) {
        long start = System.nanoTime();
        task.run();
        return (System.nanoTime() - start) / 1_000_000;
    }

    /**
     * 量高度那把尺的读数，用与画进图里的同一份行数与同一句脚注
     */
    private int measured(Rendered rendered) {
        return measuring.measureRankingHeight(header(), rendered.rows().size(), rendered.footnote());
    }

    private Rendered execute(BilibiliDataQueryPainter which) {
        CommandReply reply = run(which);

        ArgumentCaptor<List<UserScore>> rows = captor();
        ArgumentCaptor<String> footnote = ArgumentCaptor.forClass(String.class);
        verify(which).paintRanking(any(), rows.capture(), eq(1), any(), footnote.capture());
        return new Rendered(decode(reply), rows.getValue(), footnote.getValue());
    }

    private CommandReply run(BilibiliDataQueryPainter which) {
        BilibiliRankingCommand command = new BilibiliRankingCommand(
                dataSource(), mock(BilibiliStreamerChoice.class), liveDataService, which,
                new RevenueVisibilityService(new NovaStateStore(new NovaCoreProperties())), properties);
        return command.execute(context("弹幕"));
    }

    /**
     * 每只新画手自带一份空的头像缓存
     */
    private BilibiliDataQueryPainter newPainter() {
        return new BilibiliDataQueryPainter(factory, api);
    }

    private static BufferedImage decode(CommandReply reply) {
        String content = reply.content();
        int start = content.indexOf("{image_base64=");
        assertTrue(start >= 0, "回的不是图：" + content);
        String base64 = content.substring(start + "{image_base64=".length(), content.lastIndexOf('}'));
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(base64)));
            assertNotNull(image, "解不出图");
            return image;
        } catch (IOException e) {
            throw new IllegalStateException("解不出图", e);
        }
    }

    /**
     * 把样张另存为 PNG，便于人工核对版面
     */
    private void dump(String name, BufferedImage image) {
        try {
            OUT_DIR.mkdirs();
            File file = new File(OUT_DIR, name + ".png");
            ImageIO.write(image, "png", file);
            System.out.println("样张 " + file.getAbsolutePath()
                    + " 宽" + image.getWidth() + " 高" + image.getHeight() + " 字节" + file.length());
        } catch (Exception e) {
            // 仅用于人工核对，失败不影响测试结论
        }
    }

    /**
     * 命令会写进图里的那份头：榜名、口径与直播间、主播头像
     */
    private static BilibiliDataQueryPainter.Header header() {
        return new BilibiliDataQueryPainter.Header("弹幕排行榜", "本场数据 · 测试主播的直播间",
                "https://pic.example/face.jpg");
    }

    /**
     * 让排行榜接口按请求的名次数返回连号观众，得分随名次递减，头像地址也是假的
     */
    private void withRanking(int total) {
        when(liveDataService.getLiveMetricUserCount(anyString(), anyLong(), anyString())).thenReturn(total);
        when(liveDataService.getLiveUserRanking(anyString(), anyLong(), anyString(), anyInt()))
                .thenAnswer(invocation -> ranking(invocation.getArgument(3)));
    }

    private static List<UserScore> ranking(int limit) {
        List<UserScore> scores = new ArrayList<>();
        for (int i = 1; i <= limit; i++) {
            scores.add(new UserScore((long) i, "观众" + String.format("%02d", i),
                    "https://pic.example/face" + i + ".jpg", 1000 - i + 1));
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
        user.setFace("https://pic.example/face.jpg");
        user.setTargets(List.of(target));
        return user;
    }

    private static CommandContext context(String... args) {
        String typed = "数据排行榜";
        return new CommandContext(PLATFORM, PushTargetType.GROUP, GROUP, 2000000002L,
                typed, Arrays.asList(args), typed);
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<List<UserScore>> captor() {
        return ArgumentCaptor.forClass(List.class);
    }
}
