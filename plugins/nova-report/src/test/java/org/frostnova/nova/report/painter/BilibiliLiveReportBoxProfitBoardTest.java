package org.frostnova.nova.report.painter;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.model.BilibiliLiveMetric;
import org.frostnova.nova.bilibili.model.BilibiliLiveReportOptions;
import org.frostnova.nova.bilibili.model.Room;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import org.frostnova.nova.core.model.TextWithStyle;
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

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 盲盒盈亏榜：按每人本场盲盒盈亏排，每行只写盈亏；隐藏金额的会话里整张不出
 * <p>
 * 用户会看到的错：开得多却亏了的人排在赚了的人前面（还在按个数排），行里写的是个数；
 * 群里设了不显示金额，报告里却露出每个人盲盒赚了亏了多少。
 * 画字照 {@code BilibiliLiveReportRankingBarTest} 的挂法收下来，按行的纵坐标认谁排在前面。
 */
@DisplayName("盲盒盈亏榜")
class BilibiliLiveReportBoxProfitBoardTest {
    private static final String PLATFORM = "bilibili";

    private static final LiveStreamerInfo STREAMER =
            new LiveStreamerInfo(10001L, "测试主播", 20002L, "https://pic.example/face.jpg");

    private static final String MANY_BUT_LOST = "多开亏了";

    private static final String FEW_BUT_WON = "少开赚了";

    private BuildProperties build;

    private NovaCoreProperties coreProperties;

    private FontUtil fontUtil;

    private BilibiliApiUtil api;

    private final List<PlacedText> placed = new ArrayList<>();

    private final List<String> drawn = new ArrayList<>();

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

        api = mock(BilibiliApiUtil.class);
        when(api.getBilibiliImage(anyString())).thenReturn(Optional.empty());
        Room room = new Room();
        room.setTitle("测试直播间");
        when(api.getLiveInfoByRoomId(anyLong())).thenReturn(room);
        when(api.getGuardList(anyLong(), anyLong())).thenReturn(Optional.of(List.of()));
    }

    @Test
    @DisplayName("显示金额时按盈亏从高到低排、每行只写盈亏：开得少但赚了的排在开得多但亏了的前面")
    void shownSessionRanksByProfitAndWritesProfitOnly() {
        paint(true);

        List<String> red = new ArrayList<>();
        PlacedText lost = nameRow(MANY_BUT_LOST);
        PlacedText won = nameRow(FEW_BUT_WON);
        try {
            assertTrue(drawn.contains("盲盒盈亏榜"), "图上没有「盲盒盈亏榜」这个标题：" + drawn);
        } catch (Throwable t) {
            red.add("① " + t.getMessage());
        }
        try {
            assertTrue(lost != null && won != null, "两位观众没都画上榜：" + placed);
        } catch (Throwable t) {
            red.add("② " + t.getMessage());
        }
        if (lost != null && won != null) {
            try {
                assertTrue(won.y < lost.y, "赚了的应排在亏了的前面（按盈亏排，不按个数）：赚 y=" + won.y
                        + "，亏 y=" + lost.y);
            } catch (Throwable t) {
                red.add("③ " + t.getMessage());
            }
            try {
                String wonScore = scoreOf(won);
                assertTrue("+¥3.5".equals(wonScore), "赚了的那行应只写「+¥3.5」，实际：" + wonScore);
            } catch (Throwable t) {
                red.add("④ " + t.getMessage());
            }
            try {
                String lostScore = scoreOf(lost);
                assertTrue("-¥42.8".equals(lostScore), "亏了的那行应只写「-¥42.8」，实际：" + lostScore);
            } catch (Throwable t) {
                red.add("⑤ " + t.getMessage());
            }
        }
        if (!red.isEmpty()) {
            fail(red.size() + " 问红：" + String.join("；", red));
        }
    }

    @Test
    @DisplayName("隐藏金额时盲盒盈亏榜整张不出：标题、人名、个数与盈亏都不在图上")
    void hiddenSessionDropsTheWholeBoard() {
        paint(false);

        List<String> leaked = drawn.stream()
                .filter(text -> text.contains("盲盒") || text.contains(MANY_BUT_LOST) || text.contains(FEW_BUT_WON)
                        || text.endsWith(" 个") || text.contains("¥"))
                .toList();
        assertTrue(leaked.isEmpty(), "隐藏金额的会话里盲盒那一块露出来了：" + leaked);
    }

    /**
     * 喂两位：一位开了 20 个却亏 42.8 元，一位只开 2 个却赚 3.5 元。按个数排与按盈亏排正好相反
     */
    private void paint(boolean showRevenue) {
        placed.clear();
        drawn.clear();
        DefaultLiveDataService data = new DefaultLiveDataService(new NovaCoreProperties());
        data.setLiveStartTime(PLATFORM, STREAMER.getUid(), 1_700_000_000_000L);
        data.setLiveEndTime(PLATFORM, STREAMER.getUid(), 1_700_000_000_000L + 3600_000);
        data.incrementLiveUserMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.BOX_USERS, 1L, 20);
        data.incrementLiveUserMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.BOX_PROFIT_USERS, 1L, -42.8);
        data.recordLiveUserName(PLATFORM, STREAMER.getUid(), 1L, MANY_BUT_LOST);
        data.incrementLiveUserMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.BOX_USERS, 2L, 2);
        data.incrementLiveUserMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.BOX_PROFIT_USERS, 2L, 3.5);
        data.recordLiveUserName(PLATFORM, STREAMER.getUid(), 2L, FEW_BUT_WON);

        NovaCommonPainterFactory factory = new NovaCommonPainterFactory(build, coreProperties, fontUtil) {
            @Override
            public CommonPainter create(int width, int height, boolean autoExpand) {
                return new CommonPainter(build, coreProperties, fontUtil, width, height, autoExpand) {
                    @Override
                    public CommonPainter drawTextWithStyle(List<TextWithStyle> texts, Point drawLocation,
                                                           boolean autoWrap, int marginRight) {
                        for (TextWithStyle text : texts) {
                            if (text.getText() == null) {
                                continue;
                            }
                            drawn.add(text.getText());
                            if (drawLocation != null) {
                                placed.add(new PlacedText(text.getText(), drawLocation.x, drawLocation.y));
                            }
                        }
                        return super.drawTextWithStyle(texts, drawLocation, autoWrap, marginRight);
                    }
                };
            }
        };

        BilibiliLiveReportPainter painter = new BilibiliLiveReportPainter(factory, api, data, fontUtil,
                new NovaBilibiliProperties(), new LiveRoomInfoHistory(new NovaStateStore(new NovaCoreProperties())));
        Optional<String> image = painter.paint(PLATFORM, STREAMER, BilibiliLiveReportOptions.of(params(), showRevenue));
        assertTrue(image.isPresent(), "报告没画出来，榜无从量起");
    }

    private PlacedText nameRow(String name) {
        return placed.stream().filter(text -> name.equals(text.text)).findFirst().orElse(null);
    }

    /**
     * 同一行最靠右的那段字就是得分：名次和昵称都在它左边
     */
    private String scoreOf(PlacedText name) {
        PlacedText score = null;
        for (PlacedText text : placed) {
            if (text.y == name.y && text.x > name.x && (score == null || text.x > score.x)) {
                score = text;
            }
        }
        return score == null ? null : score.text;
    }

    /**
     * 只开盲盒这一榜，别的块都关掉：图上带「¥」和人名的字只可能来自它
     */
    private static JSONObject params() {
        JSONObject params = new JSONObject();
        params.put("cover", false);
        params.put("cards", false);
        params.put("fans_change", false);
        params.put("interaction_curve", false);
        params.put("highlights", false);
        params.put("danmu_cloud", false);
        params.put("gift_list", false);
        params.put("guard_list", false);
        params.put("guard_list_all", false);
        params.put("danmu_ranking", 0);
        params.put("gift_ranking", 0);
        params.put("super_chat_ranking", 0);
        params.put("box_ranking", 5);
        return params;
    }

    private record PlacedText(String text, int x, int y) {
    }
}
