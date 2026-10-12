package org.frostnova.nova.report.painter;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.model.BilibiliLiveMetric;
import org.frostnova.nova.bilibili.model.BilibiliLiveReportOptions;
import org.frostnova.nova.bilibili.model.Room;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import org.frostnova.nova.core.model.UserScore;
import org.frostnova.nova.core.service.DefaultLiveDataService;
import org.frostnova.nova.core.service.LiveRoomInfoHistory;
import org.frostnova.nova.core.service.NovaStateStore;
import org.frostnova.nova.report.factory.NovaCommonPainterFactory;
import org.frostnova.nova.report.util.FontUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.io.DefaultResourceLoader;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 盲盒盈亏榜的比例条：亏了的那几行画绿色，赚了的照旧按名次配色
 * <p>
 * 用户会看到的错：亏了的那行条子和赚了的同色、长短又相近，只有行尾的正负号分得出，
 * 一眼看去亏得多的人像是「排得很高」。
 * <p>
 * 四位观众：两位赚、两位亏，按盈亏排下来亏的落在第 3、4 名——第 3 名若按名次配色是铜色，
 * 所以这一格分得清「按正负配色」与「按名次配色」。
 * 条子的位置照 {@code BilibiliLiveReportRankingBarTest} 的挂法从圆角矩形里收下来
 * （卡片底色、高 14 的那条是底条，一行一条，按画的先后就是名次），
 * 颜色则到解出来的成图上取像素，量的是用户看到的那张图。
 */
@DisplayName("盲盒盈亏榜：亏了的那几行比例条是绿色")
class BoxProfitLossBarColorTest {
    private static final String PLATFORM = "bilibili";

    private static final LiveStreamerInfo STREAMER =
            new LiveStreamerInfo(10001L, "测试主播", 20002L, "https://pic.example/face.jpg");

    /** 报告里看过人数曲线的那个绿 */
    private static final Color LOSS_GREEN = new Color(110, 199, 122);

    /** 前三名金、银、铜，其余粉 */
    private static final Color GOLD = new Color(240, 173, 78);

    private static final Color SILVER = new Color(160, 174, 192);

    private static final Color CARD = new Color(246, 247, 249);

    private static final int BAR_HEIGHT = 14;

    /** 逐通道差容得下的量：条子中段是实心色，取点处不该有抗锯齿 */
    private static final int TOLERANCE = 2;

    /** 按盈亏从高到低：+18.6、+3.5、-5、-16.4 */
    private static final double[] SCORES = {18.6, 3.5, -5.0, -16.4};

    private static final Color[] EXPECTED = {GOLD, SILVER, LOSS_GREEN, LOSS_GREEN};

    private BuildProperties build;

    private NovaCoreProperties coreProperties;

    private FontUtil fontUtil;

    private final List<int[]> tracks = new ArrayList<>();

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    @BeforeEach
    void setUp() {
        coreProperties = new NovaCoreProperties();
        coreProperties.getPaint().getFonts().add("内置");
        fontUtil = new FontUtil(new DefaultResourceLoader(), coreProperties);
        fontUtil.init();

        Properties buildInfo = new Properties();
        buildInfo.setProperty("version", "4.0.0");
        buildInfo.setProperty("group", "org.frostnova.nova");
        buildInfo.setProperty("artifact", "nova-core");
        buildInfo.setProperty("name", "NovaBot");
        build = new BuildProperties(buildInfo);
        tracks.clear();
    }

    @Test
    @DisplayName("下播报告：亏了的行条子是绿色（含第 3 名），赚了的行是名次色")
    void liveReportPaintsLossBarsGreen() throws Exception {
        BilibiliApiUtil api = mock(BilibiliApiUtil.class);
        when(api.getBilibiliImage(anyString())).thenReturn(Optional.empty());
        Room room = new Room();
        room.setTitle("测试直播间");
        when(api.getLiveInfoByRoomId(anyLong())).thenReturn(room);
        when(api.getGuardList(anyLong(), anyLong())).thenReturn(Optional.of(List.of()));

        DefaultLiveDataService data = new DefaultLiveDataService(new NovaCoreProperties());
        data.setLiveStartTime(PLATFORM, STREAMER.getUid(), 1_700_000_000_000L);
        data.setLiveEndTime(PLATFORM, STREAMER.getUid(), 1_700_000_000_000L + 3600_000);
        for (int i = 0; i < SCORES.length; i++) {
            long uid = i + 1;
            data.incrementLiveUserMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.BOX_PROFIT_USERS, uid,
                    SCORES[i]);
            data.recordLiveUserName(PLATFORM, STREAMER.getUid(), uid, "观众" + uid);
        }

        BilibiliLiveReportPainter painter = new BilibiliLiveReportPainter(recordingFactory(), api, data, fontUtil,
                new NovaBilibiliProperties(), new LiveRoomInfoHistory(new NovaStateStore(new NovaCoreProperties())));
        String base64 = painter.paint(PLATFORM, STREAMER, BilibiliLiveReportOptions.of(boxOnly(), true))
                .orElseThrow(() -> new AssertionError("报告没画出来"));

        assertBarColors(decode(base64));
    }

    @Test
    @DisplayName("群里查榜「盲盒盈亏」：亏了的行条子是绿色（含第 3 名），赚了的行是名次色")
    void queryRankingPaintsLossBarsGreen() throws Exception {
        BilibiliApiUtil api = mock(BilibiliApiUtil.class);
        when(api.getBilibiliImage(anyString())).thenReturn(Optional.empty());
        when(api.fetchBilibiliImage(anyString())).thenReturn(Optional.empty());

        List<UserScore> rows = new ArrayList<>();
        for (int i = 0; i < SCORES.length; i++) {
            rows.add(new UserScore(i + 1L, "观众" + (i + 1), SCORES[i]));
        }

        BilibiliDataQueryPainter painter = new BilibiliDataQueryPainter(recordingFactory(), api);
        String base64 = painter.paintRanking(
                        new BilibiliDataQueryPainter.Header("盲盒盈亏排行榜", "本场数据 · 测试主播的直播间", null),
                        rows, 1, score -> (score >= 0 ? "+¥" : "-¥") + Math.abs(score), null)
                .orElseThrow(() -> new AssertionError("查榜图没画出来"));

        assertBarColors(decode(base64));
    }

    /**
     * 每条底条左端往里一个条高的半径处取一点：实心段至少有一个条高长，这一点总落在实心段里
     */
    private void assertBarColors(BufferedImage image) {
        assertEquals(SCORES.length, tracks.size(), "底条条数应等于榜上人数");
        List<String> red = new ArrayList<>();
        for (int i = 0; i < SCORES.length; i++) {
            int[] track = tracks.get(i);
            int x = track[0] + BAR_HEIGHT / 2;
            int y = track[1] + BAR_HEIGHT / 2;
            int rgb = image.getRGB(x, y);
            int distance = LinkColorPixels.distance(rgb, EXPECTED[i]);
            if (distance > TOLERANCE) {
                red.add("第 " + (i + 1) + " 名（" + (SCORES[i] < 0 ? "亏" : "赚") + " " + SCORES[i] + "）在 ("
                        + x + "," + y + ") 的条子色是 " + hex(rgb) + "，应为 " + hex(EXPECTED[i].getRGB()));
            }
        }
        if (!red.isEmpty()) {
            fail(red.size() + " 行条子配色不对：" + String.join("；", red));
        }
    }

    /**
     * 把底条的位置收下来：卡片底色、高 14 的圆角矩形就是排行榜一行的底条
     */
    private NovaCommonPainterFactory recordingFactory() {
        return new NovaCommonPainterFactory(build, coreProperties, fontUtil) {
            @Override
            public CommonPainter create(int width, int height, boolean autoExpand) {
                return new CommonPainter(build, coreProperties, fontUtil, width, height, autoExpand) {
                    @Override
                    public CommonPainter drawRoundedRectangle(int x, int y, int width, int height, int radius,
                                                              Color color) {
                        if (height == BAR_HEIGHT && color.getRGB() == CARD.getRGB()) {
                            tracks.add(new int[]{x, y});
                        }
                        return super.drawRoundedRectangle(x, y, width, height, radius, color);
                    }
                };
            }
        };
    }

    private static JSONObject boxOnly() {
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
        params.put("box_ranking", 10);
        return params;
    }

    private static BufferedImage decode(String base64) throws Exception {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(base64)));
        assertNotNull(image, "解不出图");
        assertTrue(image.getWidth() > 0 && image.getHeight() > 0, "图是空的");
        return image;
    }

    private static String hex(int rgb) {
        return String.format("#%06X", rgb & 0xFFFFFF);
    }
}
