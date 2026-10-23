package org.frostnova.nova.report.painter;

import com.alibaba.fastjson2.JSON;
import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.event.live.BilibiliOfflineRewardDigestEvent;
import org.frostnova.nova.bilibili.model.BilibiliLiveReportOptions;
import org.frostnova.nova.bilibili.model.Dynamic;
import org.frostnova.nova.bilibili.model.GuardMedal;
import org.frostnova.nova.bilibili.model.GuardMember;
import java.util.Optional;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.analytics.LiveDetail;
import org.frostnova.nova.core.analytics.WeeklyReport;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import org.frostnova.nova.core.model.TextWithStyle;
import org.frostnova.nova.core.model.UserScore;
import org.frostnova.nova.core.service.LiveRoomInfoHistory;
import org.frostnova.nova.report.analytics.BilibiliLiveMetricCatalog;
import org.frostnova.nova.report.factory.NovaCommonPainterFactory;
import org.frostnova.nova.report.util.FontUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.io.DefaultResourceLoader;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class ReportPaletteTest {
    private static FontUtil fonts;
    private static NovaCommonPainterFactory factory;
    private static final Color INK = new Color(181, 43, 147);
    private static final LiveStreamerInfo STREAMER =
            new LiveStreamerInfo(19604318752096L, "示例主播", 47615208934771L, null);

    @BeforeAll
    static void setup() {
        System.setProperty("java.awt.headless", "true");
        var properties = new NovaCoreProperties();
        properties.getPaint().getFonts().add("内置");
        fonts = new FontUtil(new DefaultResourceLoader(), properties);
        fonts.init();
        var build = new Properties();
        build.setProperty("version", "1.0.0");
        factory = new NovaCommonPainterFactory(new BuildProperties(build), properties, fonts);
    }

    // A single ink exposes any element that still reads a default color.
    private static ReportPalette singleInk(Color ink) throws Exception {
        var fields = ReportPalette.class.getRecordComponents();
        Object[] values = Arrays.stream(fields)
                .map(f -> f.getType() == Color.class ? ink : java.util.Collections.nCopies(6, ink)).toArray();
        return ReportPalette.class.getDeclaredConstructor(Arrays.stream(fields).map(f -> f.getType())
                .toArray(Class<?>[]::new)).newInstance(values);
    }

    private static byte[] png(String base64) {
        return Base64.getDecoder().decode(base64);
    }

    private static void assertInk(byte[] bytes, Color ink) throws Exception {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
        assertNotNull(image);
        int visible = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int pixel = image.getRGB(x, y);
                // Rounded transparent edges may change RGB during premultiplication.
                if ((pixel >>> 24) != 255) continue;
                visible++;
                for (int shift = 0; shift <= 16; shift += 8) {
                    int delta = Math.abs(((ink.getRGB() >> shift) & 255) - ((pixel >> shift) & 255));
                    if (delta > 1) fail("pixel " + x + "," + y + " channel " + shift);
                }
            }
        }
        assertTrue(visible > 1000);
    }

    private static BilibiliLiveReportPreviewPainter preview() {
        return new BilibiliLiveReportPreviewPainter(factory, mock(BilibiliApiUtil.class), fonts,
                new NovaBilibiliProperties(), mock(LiveRoomInfoHistory.class));
    }

    @Test
    void previewPropagatesPaletteThroughEverySection() throws Exception {
        assertInk(preview().render(new BilibiliLiveReportOptions(), singleInk(INK)).orElseThrow(), INK);
    }

    @Test
    void omittedPaletteMatchesExplicitDefault() {
        var painter = preview();
        var options = new BilibiliLiveReportOptions();
        assertArrayEquals(painter.render(options).orElseThrow(),
                painter.render(options, ReportPalette.DEFAULT).orElseThrow());
    }

    @Test
    void platformMedalColorsSurviveCustomPalette() throws Exception {
        Color start = new Color(12, 210, 87);
        Color end = new Color(25, 65, 225);
        Color border = new Color(236, 173, 22);
        Color text = new Color(215, 35, 55);
        var painter = new BilibiliLiveReportPreviewPainter(factory, mock(BilibiliApiUtil.class), fonts,
                new NovaBilibiliProperties(), mock(LiveRoomInfoHistory.class)) {
            @Override
            protected Optional<List<GuardMember>> guardList(Long roomId, Long uid) {
                var medal = new GuardMedal("示例牌", 20, true, start, end, border, text, null);
                return Optional.of(List.of(new GuardMember(19604318752096L, "示例观众", 3, 1000, medal)));
            }
        };
        var options = new BilibiliLiveReportOptions();
        var defaultImage = ImageIO.read(new ByteArrayInputStream(painter.render(options).orElseThrow()));
        var customImage = ImageIO.read(new ByteArrayInputStream(
                painter.render(options, singleInk(INK)).orElseThrow()));
        // The bar interpolates between its endpoints; the outline and glyphs contain solid pixels.
        assertAll(
                () -> assertMedalColorPreserved(defaultImage, customImage, start, 32, "gradient start"),
                () -> assertMedalColorPreserved(defaultImage, customImage, end, 32, "gradient end"),
                () -> assertMedalColorPreserved(defaultImage, customImage, border, 0, "border"),
                () -> assertMedalColorPreserved(defaultImage, customImage, text, 0, "text"));
    }

    private static void assertMedalColorPreserved(BufferedImage defaultImage, BufferedImage customImage,
                                                  Color color, int tolerance, String role) {
        int defaultPixels = countColorPixels(defaultImage, color, tolerance);
        int customPixels = countColorPixels(customImage, color, tolerance);
        assertTrue(customPixels > 5, role + " pixels must remain visible: " + customPixels);
        assertEquals(defaultPixels, customPixels, role + " pixels must survive a palette change");
    }

    private static int countColorPixels(BufferedImage image, Color color, int tolerance) {
        int count = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int pixel = image.getRGB(x, y);
                if ((pixel >>> 24) == 255
                        && Math.abs(((pixel >> 16) & 255) - color.getRed()) <= tolerance
                        && Math.abs(((pixel >> 8) & 255) - color.getGreen()) <= tolerance
                        && Math.abs((pixel & 255) - color.getBlue()) <= tolerance) count++;
            }
        }
        return count;
    }

    @Test
    void concurrentRendersKeepTheirOwnPalette() throws Exception {
        var painter = preview();
        var first = singleInk(INK);
        Color other = new Color(43, 147, 181);
        var second = singleInk(other);
        var a = CompletableFuture.supplyAsync(() -> painter.render(new BilibiliLiveReportOptions(), first).orElseThrow());
        var b = CompletableFuture.supplyAsync(() -> painter.render(new BilibiliLiveReportOptions(), second).orElseThrow());
        assertInk(a.get(60, TimeUnit.SECONDS), INK);
        assertInk(b.get(60, TimeUnit.SECONDS), other);
    }

    @Test
    void queryAndForwardedDynamicUseTheSuppliedPalette() throws Exception {
        var palette = singleInk(INK);
        var api = mock(BilibiliApiUtil.class);
        var query = new BilibiliDataQueryPainter(factory, api);
        var header = new BilibiliDataQueryPainter.Header("查询", "示例", null);
        assertInk(png(query.paintCards(header, List.of(new BilibiliDataQueryPainter.DataCard("1", "弹幕")),
                "说明", palette).orElseThrow()), INK);
        assertInk(png(query.paintRanking(header, List.of(new UserScore(19604318752096L, "观众", null, -1)),
                1, String::valueOf, "说明", palette).orElseThrow()), INK);
        var dynamic = new Dynamic();
        dynamic.setType("DYNAMIC_TYPE_FORWARD");
        dynamic.setModules(JSON.parseObject("{\"module_author\":{\"name\":\"示例\",\"pub_ts\":1700000000},"
                + "\"module_dynamic\":{\"desc\":{\"text\":\"转发内容\"}}}"));
        var origin = new Dynamic();
        origin.setType("DYNAMIC_TYPE_WORD");
        origin.setModules(JSON.parseObject("{\"module_author\":{\"name\":\"原作者\"},"
                + "\"module_dynamic\":{\"desc\":{\"text\":\"原文\"}}}"));
        dynamic.setOrigin(origin);
        assertInk(png(new BilibiliDynamicPainter(factory, api, new NovaBilibiliProperties())
                .paint(dynamic, palette).orElseThrow()), INK);
    }

    @Test
    void weeklyOfflineAndReplayUseTheSuppliedPalette() throws Exception {
        var palette = singleInk(INK);
        var api = mock(BilibiliApiUtil.class);
        var properties = new NovaBilibiliProperties();
        var data = WeeklyReport.calculate("示例主播", List.of(), List.of(), Instant.parse("2026-10-12T02:00:00Z"),
                new BilibiliLiveMetricCatalog().insights(), true);
        assertInk(new BilibiliWeeklyReportPainter(factory, api, properties).render(STREAMER, data, palette), INK);
        var event = new BilibiliOfflineRewardDigestEvent(STREAMER, List.of(), Instant.parse("2026-10-12T02:00:00Z"));
        assertInk(png(new BilibiliOfflineRewardDigestPainter(factory, api, properties)
                .paint(event, true, palette).orElseThrow()), INK);
        var detail = new LiveDetail(LiveDetail.VERSION, "bilibili", STREAMER.getUid(), "示例主播", STREAMER.getRoomId(),
                1_700_000_000_000L, 1_700_003_600_000L, 3600,
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), List.of(), List.of(), List.of(), Map.of(), List.of());
        assertInk(new BilibiliLiveReportReplayPainter(factory, api, fonts, properties,
                mock(LiveRoomInfoHistory.class), detail).render(new BilibiliLiveReportOptions(), palette).orElseThrow(), INK);
    }

    @Test
    void implicitTextColorComesFromTheCanvasAndExplicitColorIsPreserved() throws Exception {
        var palette = singleInk(INK);
        var implicit = new TextWithStyle("默认文字", 30);
        assertEquals(Color.BLACK, implicit.getColor());
        var unset = new TextWithStyle();
        unset.setText("默认文字");
        unset.setSize(30);
        assertNull(unset.getColor());
        for (var text : List.of(implicit, unset)) {
            var painter = factory.create(500, 150, true, palette);
            painter.drawTextWithStyle(List.of(text));
            painter.createSolidRoundedRectangleBackground(palette.background(), 0);
            assertInk(png(painter.base64().orElseThrow()), INK);
        }
        var explicitTexts = List.of(new TextWithStyle("平台文字", 30, Color.BLUE),
                new TextWithStyle("平台文字", 30, Color.BLUE, Font.BOLD),
                new TextWithStyle("平台文字", fonts.primaryFont(), 30, Color.BLUE, Font.BOLD));
        for (var text : explicitTexts) {
            assertEquals(Color.BLUE, text.getColor());
            assertTextInk(text, palette, Color.BLUE);
        }
        assertTextInk(new TextWithStyle("平台文字", 30, Color.BLACK), palette, Color.BLACK);
        for (var text : List.of(implicit, unset, explicitTexts.get(0), explicitTexts.get(1), explicitTexts.get(2))) {
            text.setColor(Color.RED);
            assertEquals(Color.RED, text.getColor());
            assertTextInk(text, palette, Color.RED);
        }
    }

    private static void assertTextInk(TextWithStyle text, ReportPalette palette, Color color) throws Exception {
        var painter = factory.create(500, 150, true, palette);
        painter.drawTextWithStyle(List.of(text));
        painter.createSolidRoundedRectangleBackground(palette.background(), 0);
        var image = ImageIO.read(new ByteArrayInputStream(png(painter.base64().orElseThrow())));
        assertTrue(countColorPixels(image, color, 0) > 10, "explicit text color must remain visible");
    }
}
