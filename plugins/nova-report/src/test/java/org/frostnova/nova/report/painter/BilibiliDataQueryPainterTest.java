package org.frostnova.nova.report.painter;

import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.model.TextWithStyle;
import org.frostnova.nova.core.model.UserScore;
import org.frostnova.nova.report.factory.NovaCommonPainterFactory;
import org.frostnova.nova.report.util.FontUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.io.DefaultResourceLoader;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 数据查询结果绘制测试
 * <p>
 * 与下播报告绘制测试同一套桩：内置字体、占位头像，不依赖网络与本机字体。
 * 版式代码越界只会在运行时炸，这里用真实渲染兜住。
 */
@DisplayName("数据查询结果绘制")
class BilibiliDataQueryPainterTest {
    private BilibiliDataQueryPainter painter;

    private BuildProperties build;

    private NovaCoreProperties coreProperties;

    private FontUtil fontUtil;

    private BilibiliApiUtil api;

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
        buildInfo.setProperty("group", "com." + "starlwr");
        buildInfo.setProperty("artifact", "nova-core");
        buildInfo.setProperty("name", "NovaBot");

        build = new BuildProperties(buildInfo);
        NovaCommonPainterFactory factory = new NovaCommonPainterFactory(build, coreProperties, fontUtil);

        BufferedImage placeholder = new BufferedImage(200, 200, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = placeholder.createGraphics();
        graphics.setColor(new Color(120, 170, 220));
        graphics.fillRect(0, 0, 200, 200);
        graphics.dispose();

        api = mock(BilibiliApiUtil.class);
        when(api.getBilibiliImage(anyString())).thenReturn(Optional.of(placeholder));
        when(api.fetchBilibiliImage(anyString())).thenReturn(Optional.of(placeholder));

        painter = new BilibiliDataQueryPainter(factory, api);
    }

    @Test
    @DisplayName("卡片式数据应能绘制")
    void paintsCards() {
        List<BilibiliDataQueryPainter.DataCard> cards = List.of(
                new BilibiliDataQueryPainter.DataCard("144 条", "弹幕 · 第 3 名"),
                new BilibiliDataQueryPainter.DataCard("¥52.5", "礼物 · 第 1 名"),
                new BilibiliDataQueryPainter.DataCard("¥30", "醒目留言 · 第 2 名"),
                new BilibiliDataQueryPainter.DataCard("8 个", "盲盒 · 第 5 名"),
                new BilibiliDataQueryPainter.DataCard("-¥12.4", "盲盒盈亏 · 第 9 名"));

        Optional<String> base64 = painter.paintCards(
                new BilibiliDataQueryPainter.Header("测试主播", "本场数据 · 测试主播的直播间", "https://pic.example/face.jpg"),
                cards, "直播时长 2 小时 8 分钟");

        assertTrue(base64.isPresent());
        assertFalse(base64.get().isBlank());
        dump("cards", base64.get());
    }

    @Test
    @DisplayName("没有头像时应退化为纯文字头部而非绘制失败")
    void paintsWithoutAvatar() {
        Optional<String> base64 = painter.paintCards(
                new BilibiliDataQueryPainter.Header("测试主播", "累计数据 · 历次直播合计", null),
                List.of(new BilibiliDataQueryPainter.DataCard("10241", "弹幕 · 328 人参与")), null);

        assertTrue(base64.isPresent());
        dump("cards-no-avatar", base64.get());
    }

    @Test
    @DisplayName("排行榜应能绘制")
    void paintsRankingFirstPage() {
        Optional<String> base64 = painter.paintRanking(
                new BilibiliDataQueryPainter.Header("礼物排行榜", "本场数据 · 测试主播的直播间", "https://pic.example/face.jpg"),
                ranking(1, 10), 1, score -> "¥" + Math.round(score), "第 1 / 3 页 · 共 23 人");

        assertTrue(base64.isPresent());
        dump("ranking-page1", base64.get());
    }

    @Test
    @DisplayName("名次应从给定的起始名次排起")
    void paintsRankingSecondPage() {
        Optional<String> base64 = painter.paintRanking(
                new BilibiliDataQueryPainter.Header("弹幕排行榜", "累计数据 · 测试主播的直播间", null),
                ranking(11, 20), 11, score -> Math.round(score) + " 条", "第 2 / 3 页 · 共 23 人");

        assertTrue(base64.isPresent());
        dump("ranking-page2", base64.get());
    }

    @Test
    @DisplayName("昵称缺失或超长时应仍能排版")
    void paintsRankingWithAwkwardNames() {
        List<UserScore> rows = List.of(
                new UserScore(1L, null, 100),
                new UserScore(2L, "这是一个特别特别特别长的昵称用来测试截断", 80),
                new UserScore(3L, "短", 0));

        Optional<String> base64 = painter.paintRanking(
                new BilibiliDataQueryPainter.Header("盲盒盈亏排行榜", "本场数据 · 测试主播的直播间", null),
                rows, 1, score -> (score >= 0 ? "+¥" : "-¥") + Math.abs(Math.round(score)), null);

        assertTrue(base64.isPresent());
        dump("ranking-awkward", base64.get());
    }

    /**
     * 用户会看到的错：盲盒卡大字改写金额后，位数一多就伸出卡片、压到隔壁那张卡上。
     * 「-¥12345.6」要整串画出；长到放不下的照报告卡片的办法截短，也不许出卡片。
     * <p>
     * 卡片的位置从圆角矩形里收下来（宽 219、高 104 的就是一张数据卡），
     * 大字从按坐标画的 32 号粗体字里收下来，宽度用同一支画笔量。
     */
    @Test
    @DisplayName("盲盒卡大字写长金额：-¥12345.6 整串画出，放不下的截短也不出卡片")
    void longProfitValueStaysInsideCard() {
        List<int[]> boxes = new ArrayList<>();
        List<Object[]> values = new ArrayList<>();
        NovaCommonPainterFactory recording = new NovaCommonPainterFactory(build, coreProperties, fontUtil) {
            @Override
            public CommonPainter create(int width, int height, boolean autoExpand) {
                return new CommonPainter(build, coreProperties, fontUtil, width, height, autoExpand) {
                    @Override
                    public CommonPainter drawRoundedRectangle(int x, int y, int width, int height, int radius,
                                                              Color color) {
                        if (width == 219 && height == 104) {
                            boxes.add(new int[]{x, width});
                        }
                        return super.drawRoundedRectangle(x, y, width, height, radius, color);
                    }

                    @Override
                    public CommonPainter drawTextWithStyle(List<TextWithStyle> texts, Point drawLocation) {
                        TextWithStyle first = texts.isEmpty() ? null : texts.get(0);
                        if (first != null && drawLocation != null && first.getSize() == 32
                                && first.getStyle() == Font.BOLD) {
                            values.add(new Object[]{first.getText(), drawLocation.x,
                                    getStringWidthAndHeight(first).getFirst()});
                        }
                        return super.drawTextWithStyle(texts, drawLocation);
                    }
                };
            }
        };

        Optional<String> base64 = new BilibiliDataQueryPainter(recording, api).paintCards(
                new BilibiliDataQueryPainter.Header("测试主播", "本场数据", null),
                List.of(new BilibiliDataQueryPainter.DataCard("-¥12345.6", "盲盒盈亏 · 31 个"),
                        new BilibiliDataQueryPainter.DataCard("-¥123456789.5", "盲盒盈亏 · 31 个")),
                null);

        assertTrue(base64.isPresent());
        assertEquals(2, boxes.size(), "应画两张数据卡");
        assertEquals(2, values.size(), "应画两行大字");
        List<String> red = new ArrayList<>();
        if (!"-¥12345.6".equals(values.get(0)[0])) {
            red.add("「-¥12345.6」该整串画出，实际画的是「" + values.get(0)[0] + "」");
        }
        for (int i = 0; i < 2; i++) {
            int right = (int) values.get(i)[1] + (int) values.get(i)[2];
            int cardRight = boxes.get(i)[0] + boxes.get(i)[1];
            if (right > cardRight) {
                red.add("第 " + (i + 1) + " 张卡大字「" + values.get(i)[0] + "」右缘到 x=" + right
                        + "，卡片右缘在 x=" + cardRight);
            }
        }
        if (!red.isEmpty()) {
            fail(String.join("；", red));
        }
    }

    /**
     * 造一页连号用户，得分随名次递减
     */
    private List<UserScore> ranking(int from, int to) {
        List<UserScore> rows = new ArrayList<>();
        for (int i = from; i <= to; i++) {
            rows.add(new UserScore((long) i, "用户" + i, "https://pic.example/face" + i + ".jpg", (to - i + 1) * 13.0));
        }
        return rows;
    }

    /**
     * 把绘制结果另存为 PNG，便于人工核对版面
     */
    private void dump(String name, String base64) {
        try {
            Path dir = Path.of("target", "painter-output");
            Files.createDirectories(dir);
            Files.write(dir.resolve("query-" + name + ".png"), Base64.getDecoder().decode(base64));
        } catch (Exception e) {
            // 仅用于人工核对，失败不影响测试结论
        }
    }
}
