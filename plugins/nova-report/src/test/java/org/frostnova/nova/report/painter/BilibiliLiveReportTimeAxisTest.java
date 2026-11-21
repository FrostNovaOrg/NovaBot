package org.frostnova.nova.report.painter;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.model.BilibiliLiveMetric;
import org.frostnova.nova.bilibili.model.BilibiliLiveReportOptions;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import org.frostnova.nova.core.model.TextWithStyle;
import org.frostnova.nova.core.service.DefaultLiveDataService;
import org.frostnova.nova.core.service.LiveRoomInfoHistory;
import org.frostnova.nova.report.factory.NovaCommonPainterFactory;
import org.frostnova.nova.report.util.FontUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.io.DefaultResourceLoader;

import java.awt.Color;
import java.awt.Font;
import java.awt.Point;
import java.awt.geom.AffineTransform;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class BilibiliLiveReportTimeAxisTest {
    private static final int LEFT = 35;
    private static final int RIGHT = 865;
    private static final int PLOT_HEIGHT = 90;
    private static final String PLATFORM = "bilibili";
    private static final long UID = 10001L;
    private static final LiveStreamerInfo STREAMER = new LiveStreamerInfo(UID, "示例主播", 20002L, null);
    private static final ReportPalette PALETTE = ReportPalette.DEFAULT;
    private static final NovaCoreProperties PROPERTIES = new NovaCoreProperties();
    private static FontUtil fonts;

    @BeforeAll
    static void loadFonts() {
        System.setProperty("java.awt.headless", "true");
        PROPERTIES.getPaint().getFonts().add("内置");
        PROPERTIES.getLive().setSaveLiveData(false);
        fonts = new FontUtil(new DefaultResourceLoader(), PROPERTIES);
        fonts.init();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            11-21T20:07:00 | 11-21T23:12:00 | 20:30,21:00,21:30,22:00,22:30,23:00
            11-21T08:00:00 | 11-21T09:30:00 | 08:00,08:30,09:00,09:30
            11-21T08:00:00 | 11-21T08:04:00 | 08:00,08:01,08:02,08:03,08:04
            11-21T08:00:05 | 11-21T08:00:55 | ''
            11-21T20:00:00 | 11-22T04:00:00 | 20:00,22:00,11-22,02:00,04:00
            11-21T20:07:00 | 11-22T22:07:00 | 11-22,06:00,12:00,18:00
            11-21T23:59:48 | 11-22T00:00:12 | 11-22
            """)
    void labelsShowBeijingTimeAndFitOnOneRow(String start, String end, String expected) {
        Capture capture = paint(start, end, start.substring(0, 11) + ":00", false, true);
        assertEquals(expected.isEmpty() ? List.of() : List.of(expected.split(",")),
                capture.labels.stream().map(Label::text).toList());
        int previousRight = LEFT - 8;
        for (Label label : capture.labels) {
            assertTrue(label.x() >= previousRight + 8, "时间文字重叠或越过左边界");
            assertTrue(label.right() <= RIGHT, "时间文字越过右边界");
            assertEquals(label.text().contains("-") ? PALETTE.text() : PALETTE.tip(), label.color());
            assertEquals(capture.labels.get(0).y(), label.y(), "时间文字应在同一行");
        }
        assertEquals(capture.labels.isEmpty() ? 0 : 1, capture.labels.stream().map(Label::y).distinct().count());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            11-21T20:07:30 | 11-21T23:12:00 | 11-21T21:00:00
            11-21T20:07:30 | 11-22T04:00:00 | 11-21T22:00:00
            """)
    void gridLineAndPeakShareAColumn(String start, String end, String peak) {
        Capture capture = paint(start, end, peak, true, true);
        assertEquals(2, capture.chartTops.size(), "只画有数据的两张图");
        int peakX = capture.peakX;
        assertTrue(peakX > LEFT && peakX < RIGHT, "合成峰值应在绘图区中间");
        assertFalse(capture.labels.isEmpty(), "缺少共用的时间刻度");
        assertTrue(capture.labels.get(0).y() >= capture.chartTops.get(1) + PLOT_HEIGHT, "时间行应在最后一张图下方");
        for (int top : capture.chartTops) {
            assertTrue(capture.rectangles.stream().anyMatch(rect -> rect.x() == peakX && rect.y() == top),
                    "峰值所在列 x=" + peakX + " 缺少竖向参考线");
            assertFalse(capture.rectangles.stream().anyMatch(rect -> rect.x() == peakX
                    && rect.y() <= top + 7 && rect.y() + rect.height() > top + 7), "参考线应有断开的线段");
        }
    }

    @Test
    void endTickAndLabelAlignWithTheFinalMinutePeakInAShortSession() {
        String end = "11-21T08:04:00";
        Capture capture = paint("11-21T08:00:00", end, end, false, true);

        Label label = capture.labels.stream().filter(candidate -> candidate.text().equals("08:04"))
                .findFirst().orElseThrow();
        int labelCenter = label.x() + (label.right() - label.x()) / 2;
        assertEquals(capture.peakX, labelCenter, "08:04 应对准最后一分钟峰值的起点");
        int top = capture.chartTops.get(0);
        assertTrue(capture.rectangles.stream().anyMatch(rect -> rect.x() == labelCenter && rect.y() == top),
                "最后一分钟的峰值起点应有竖向参考线");
    }

    @Test
    void dropsTheOuterLabelWhenWideTimeLabelsOverlapButKeepsItsGridLine() {
        FontUtil wideFonts = new FontUtil(new DefaultResourceLoader(), PROPERTIES) {
            @Override
            public Font findFontForCharacter(int codePoint) {
                Font font = super.findFontForCharacter(codePoint);
                return codePoint == ':' || Character.isDigit(codePoint)
                        ? font.deriveFont(AffineTransform.getScaleInstance(2.5, 1)) : font;
            }
        };
        wideFonts.init();
        Capture capture = paint("11-21T07:59:00", "11-21T08:27:00", "11-21T08:00:00", false, true, wideFonts);

        assertEquals(List.of("08:05", "08:10", "08:15", "08:20", "08:25"),
                capture.labels.stream().map(Label::text).toList());
        int top = capture.chartTops.get(0);
        assertTrue(capture.rectangles.stream().anyMatch(rect -> rect.x() == capture.peakX && rect.y() == top),
                "省略 08:00 文字后仍应保留该刻度的竖向参考线");
    }

    @Test
    void omitsGridLinesAtPlotEdgesAndSkipsAxisWhenCurvesAreOff() {
        String start = "11-21T08:00:00";
        String end = "11-21T09:30:00";
        Capture shown = paint(start, end, "11-21T08:30:00", false, true);
        int top = shown.chartTops.get(0);
        List<Integer> gridLines = shown.rectangles.stream().filter(rect -> rect.y() == top)
                .map(Segment::x).distinct().toList();
        assertEquals(3, gridLines.size());
        assertTrue(gridLines.stream().allMatch(x -> x > LEFT && x < RIGHT));

        Capture hidden = paint(start, end, "11-21T08:30:00", false, false);
        assertTrue(hidden.labels.isEmpty());
        assertTrue(hidden.chartTops.isEmpty());
    }

    private static Capture paint(String start, String end, String peak, boolean twoCurves, boolean interactionCurve) {
        return paint(start, end, peak, twoCurves, interactionCurve, fonts);
    }

    private static Capture paint(String start, String end, String peak, boolean twoCurves, boolean interactionCurve,
                                 FontUtil fonts) {
        DefaultLiveDataService data = new DefaultLiveDataService(PROPERTIES);
        data.setLiveStartTime(PLATFORM, UID, time(start));
        data.setLiveEndTime(PLATFORM, UID, time(end));
        data.incrementLiveSeries(PLATFORM, UID, BilibiliLiveMetric.DANMU_COUNT, time(peak), 100);
        if (twoCurves) {
            data.incrementLiveSeries(PLATFORM, UID, BilibiliLiveMetric.GIFT_VALUE, time(peak), 20);
            data.maxLiveSeries(PLATFORM, UID, BilibiliLiveMetric.ONLINE_COUNT, time(peak), 0);
        }
        Capture[] capture = new Capture[1];
        BuildProperties build = new BuildProperties(new Properties());
        NovaCommonPainterFactory factory = new NovaCommonPainterFactory(build, PROPERTIES, fonts) {
            @Override
            public CommonPainter create(int width, int height, boolean autoExpand) {
                capture[0] = new Capture(build, width, height, autoExpand, fonts);
                return capture[0];
            }
        };
        BilibiliLiveReportPainter painter = new BilibiliLiveReportPainter(factory, mock(BilibiliApiUtil.class),
                data, fonts, new NovaBilibiliProperties(), mock(LiveRoomInfoHistory.class));
        JSONObject params = JSONObject.of("interaction_curve", interactionCurve, "highlights", false, "cover", false);
        assertTrue(painter.paint(PLATFORM, STREAMER, BilibiliLiveReportOptions.of(params, true)).isPresent());
        return capture[0];
    }

    private static long time(String dateAndTime) {
        return OffsetDateTime.parse("2026-" + dateAndTime + "+08:00").toInstant().toEpochMilli();
    }

    private record Label(String text, Color color, int x, int y, int right) {}

    private record Segment(int x, int y, int height) {}

    private static final class Capture extends CommonPainter {
        final List<Label> labels = new ArrayList<>();
        final List<Segment> rectangles = new ArrayList<>();
        final List<Integer> chartTops = new ArrayList<>();
        int peakX = -1;

        Capture(BuildProperties build, int width, int height, boolean autoExpand, FontUtil fonts) {
            super(build, PROPERTIES, fonts, width, height, autoExpand);
        }

        @Override
        public CommonPainter drawTextWithStyle(List<TextWithStyle> texts, Point point,
                                               boolean autoWrap, int marginRight) {
            for (TextWithStyle text : texts) {
                if (point != null && text.getStyle() == Font.PLAIN && text.getText().matches("[0-9]{2}[:-][0-9]{2}")) {
                    labels.add(new Label(text.getText(), text.getColor(), point.x, point.y,
                            point.x + getStringWidthAndHeight(text).getFirst()));
                }
            }
            return super.drawTextWithStyle(texts, point, autoWrap, marginRight);
        }

        @Override
        public CommonPainter drawRectangle(int x, int y, int width, int height, Color color) {
            if (width == 1 && height < PLOT_HEIGHT) {
                rectangles.add(new Segment(x, y, height));
            }
            if (x == LEFT && width == RIGHT - LEFT && height == 2 && color.equals(PALETTE.card())) {
                chartTops.add(y - PLOT_HEIGHT);
            }
            return super.drawRectangle(x, y, width, height, color);
        }

        @Override
        public CommonPainter drawPolygon(List<Point> points, Color color) {
            if (color.equals(PALETTE.curveDanmu())) {
                int top = points.stream().mapToInt(point -> point.y).min().orElseThrow();
                peakX = points.stream().filter(point -> point.y == top).mapToInt(point -> point.x).min().orElseThrow();
            }
            return super.drawPolygon(points, color);
        }
    }
}
