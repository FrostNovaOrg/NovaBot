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

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 排行榜比例条与得分：长得分会压在条子右端上，短得分的榜应维持原样。
 * <p>
 * 照 {@code BilibiliLiveReportDrawnTextTest} 覆写画字，把定位文字的起点和宽度收下来；
 * 再覆写圆角矩形，把底条（卡片底色、高 14）的矩形收下来。像素认不出「这是条子还是字」。
 */
@DisplayName("排行榜比例条与得分")
class BilibiliLiveReportRankingBarTest {
    private static final String PLATFORM = "bilibili";

    private static final LiveStreamerInfo STREAMER =
            new LiveStreamerInfo(10001L, "测试主播", 20002L, "https://pic.example/face.jpg");

    /** 得分预留槽。宽不过这个数的榜，条子右端就停在这里，样子与收窄之前相同 */
    private static final int SCORE_SLOT = 150;

    /** 底条高度，与画手里排行条子的高度相同 */
    private static final int BAR_HEIGHT = 14;

    /** 字画在行顶往下 6，条子画在行顶往下 12 */
    private static final int BAR_BELOW_TEXT = 6;

    private BuildProperties build;

    private NovaCoreProperties coreProperties;

    private FontUtil fontUtil;

    private BilibiliApiUtil api;

    private final List<PlacedText> placed = new ArrayList<>();

    private final List<Bar> bars = new ArrayList<>();

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

        BufferedImage placeholder = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = placeholder.createGraphics();
        graphics.setColor(new Color(120, 170, 220));
        graphics.fillRect(0, 0, 64, 64);
        graphics.dispose();
        api = mock(BilibiliApiUtil.class);
        when(api.getBilibiliImage(anyString())).thenReturn(Optional.of(placeholder));
        Room room = new Room();
        room.setTitle("测试直播间");
        when(api.getLiveInfoByRoomId(anyLong())).thenReturn(room);
        when(api.getGuardList(anyLong(), anyLong())).thenReturn(Optional.of(List.of()));
    }

    @Test
    @DisplayName("得分宽过 150 像素时，比例条右端停在得分文字左边并留出空隙；短得分的榜条宽不变")
    void longScoreStaysClearOfTheBar() {
        List<String> red = new ArrayList<>();

        paint(paramsWith("box_ranking", 5), data -> {
            data.incrementLiveUserMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.BOX_USERS, 1L, 23);
            data.incrementLiveUserMetric(PLATFORM, STREAMER.getUid(),
                    BilibiliLiveMetric.BOX_PROFIT_USERS, 1L, -135.2);
            data.recordLiveUserName(PLATFORM, STREAMER.getUid(), 1L, "甲");
            data.incrementLiveUserMetric(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.BOX_USERS, 2L, 2);
            data.incrementLiveUserMetric(PLATFORM, STREAMER.getUid(),
                    BilibiliLiveMetric.BOX_PROFIT_USERS, 2L, 1);
            data.recordLiveUserName(PLATFORM, STREAMER.getUid(), 2L, "乙");
        });

        List<Row> rows = scoreRows();
        List<Row> wide = rows.stream().filter(row -> row.score.width > SCORE_SLOT).toList();
        try {
            assertTrue(!wide.isEmpty(),
                    "没有一行得分宽过 " + SCORE_SLOT + "，这格没量到长得分。各行：" + rows);
        } catch (Throwable t) {
            red.add("① " + t.getMessage());
        }
        try {
            assertTrue(rows.size() >= 2, "长得分那张榜应有两行，实际 " + rows.size() + "：" + rows);
        } catch (Throwable t) {
            red.add("② " + t.getMessage());
        }
        if (!wide.isEmpty()) {
            try {
                List<String> overlaps = new ArrayList<>();
                for (Row row : wide) {
                    int barRight = row.track.x + row.track.width;
                    if (barRight >= row.score.x) {
                        overlaps.add(row.score.text + " 宽 " + row.score.width + "：比例条右端 " + barRight
                                + " 不在得分起点 " + row.score.x + " 之左");
                    }
                }
                assertTrue(overlaps.isEmpty(), "长得分压在比例条上：" + overlaps);
            } catch (Throwable t) {
                red.add("③ " + t.getMessage());
            }
            try {
                int width = rows.get(0).track.width;
                assertTrue(rows.stream().allMatch(row -> row.track.width == width),
                        "同一张榜各行条子应一样宽：" + rows);
            } catch (Throwable t) {
                red.add("④ " + t.getMessage());
            }
        }

        paint(paramsWith("danmu_ranking", 5), data -> {
            data.recordLiveMetricUser(PLATFORM, STREAMER.getUid(), BilibiliLiveMetric.DANMU_USERS, 3L);
            data.recordLiveUserName(PLATFORM, STREAMER.getUid(), 3L, "丙");
        });
        List<Row> shortRows = scoreRows();
        int unchanged = ReportSharedStyle.CONTENT_WIDTH - 300 - SCORE_SLOT;
        try {
            assertTrue(shortRows.size() == 1 && shortRows.get(0).score.width <= SCORE_SLOT,
                    "短得分这一榜应只有一行、且得分不超过 " + SCORE_SLOT + "：" + shortRows);
        } catch (Throwable t) {
            red.add("⑤ " + t.getMessage());
        }
        try {
            assertTrue(shortRows.stream().allMatch(row -> row.track.width == unchanged),
                    "短得分的榜条宽应仍是 " + unchanged + "：" + shortRows);
        } catch (Throwable t) {
            red.add("⑥ " + t.getMessage());
        }

        if (!red.isEmpty()) {
            fail(red.size() + " 问红：" + String.join("；", red));
        }
    }

    /**
     * 底条配上同一行最靠右的那段字。那段就是得分：名次和昵称都在它左边。
     */
    private List<Row> scoreRows() {
        int card = BilibiliLiveReportPainter.COLOR_CARD.getRGB();
        List<Row> rows = new ArrayList<>();
        for (Bar track : bars) {
            if (track.height != BAR_HEIGHT || track.rgb != card) {
                continue;
            }
            PlacedText score = null;
            for (PlacedText text : placed) {
                if (text.y + BAR_BELOW_TEXT != track.y) {
                    continue;
                }
                if (score == null || text.x > score.x) {
                    score = text;
                }
            }
            if (score != null && (score.text.contains(" 个") || score.text.contains(" 条") || score.text.contains("¥"))) {
                rows.add(new Row(track, score));
            }
        }
        return rows;
    }

    private void paint(JSONObject params, Consumer<DefaultLiveDataService> seed) {
        placed.clear();
        bars.clear();
        DefaultLiveDataService data = new DefaultLiveDataService(new NovaCoreProperties());
        data.setLiveStartTime(PLATFORM, STREAMER.getUid(), 1_700_000_000_000L);
        data.setLiveEndTime(PLATFORM, STREAMER.getUid(), 1_700_000_000_000L + 3600_000);
        seed.accept(data);

        NovaCommonPainterFactory factory = new NovaCommonPainterFactory(build, coreProperties, fontUtil) {
            @Override
            public CommonPainter create(int width, int height, boolean autoExpand) {
                return new CommonPainter(build, coreProperties, fontUtil, width, height, autoExpand) {
                    @Override
                    public CommonPainter drawTextWithStyle(List<TextWithStyle> texts, Point drawLocation,
                                                           boolean autoWrap, int marginRight) {
                        if (drawLocation != null) {
                            for (TextWithStyle text : texts) {
                                if (text.getText() != null) {
                                    placed.add(new PlacedText(text.getText(), drawLocation.x, drawLocation.y,
                                            getStringWidthAndHeight(text).getFirst()));
                                }
                            }
                        }
                        return super.drawTextWithStyle(texts, drawLocation, autoWrap, marginRight);
                    }

                    @Override
                    public CommonPainter drawRoundedRectangle(int x, int y, int width, int height, int radius,
                                                              Color color) {
                        bars.add(new Bar(x, y, width, height, color.getRGB()));
                        return super.drawRoundedRectangle(x, y, width, height, radius, color);
                    }
                };
            }
        };

        BilibiliLiveReportPainter painter = new BilibiliLiveReportPainter(factory, api, data, fontUtil,
                new NovaBilibiliProperties(), new LiveRoomInfoHistory(new NovaStateStore(new NovaCoreProperties())));
        Optional<String> image = painter.paint(PLATFORM, STREAMER, BilibiliLiveReportOptions.of(params, true));
        assertTrue(image.isPresent(), "报告没画出来，条子和得分都无从量起");
    }

    private static JSONObject paramsWith(String rankingKey, int limit) {
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
        params.put(rankingKey, limit);
        return params;
    }

    private record PlacedText(String text, int x, int y, int width) {
    }

    private record Bar(int x, int y, int width, int height, int rgb) {
    }

    private record Row(Bar track, PlacedText score) {
    }
}
