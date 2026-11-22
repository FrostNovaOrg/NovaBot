package org.frostnova.nova.report.painter;

import org.frostnova.nova.core.assistant.AssistantChartRenderer.*;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.model.TextWithStyle;
import org.frostnova.nova.report.factory.NovaCommonPainterFactory;
import org.frostnova.nova.report.util.FontUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.io.DefaultResourceLoader;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Properties;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

class BilibiliAssistantChartPainterTest {
    private static BilibiliAssistantChartPainter painter;
    private static NovaCommonPainterFactory factory;
    private static FontUtil fonts;

    @BeforeAll
    static void setUp() {
        System.setProperty("java.awt.headless", "true");
        var properties = new NovaCoreProperties();
        properties.getPaint().getFonts().add("Dialog");
        fonts = new FontUtil(new DefaultResourceLoader(), properties);
        fonts.init();
        factory = new NovaCommonPainterFactory(new BuildProperties(new Properties()), properties, fonts);
        painter = new BilibiliAssistantChartPainter(factory, fonts);
    }

    @Test
    @DisplayName("近十场流水的柱状图会给整数金额补零，最高值和全部场次仍应有标注")
    void tenBarsShowEveryValueAndCategoryIncludingThePeak() {
        var amounts = List.of("800", "1040.51", "1044.18", "980.28", "989.36", "1180.52", "1199.99",
                "512.34", "680.52", "930.71");
        var rows = IntStream.range(0, amounts.size()).mapToObj(i -> new Row("11-" + (10 + i) + " 20:00",
                List.of(new Value(amounts.get(i), new BigDecimal(amounts.get(i)))))).toList();
        var chart = new Chart(Type.BAR, "近十场直播流水", "场次", "金额（元）", List.of("流水"), rows, null, "合成归档", rows.size());
        var drawn = record(chart).text().stream().map(DrawnText::text).toList();
        var categoriesDrawn = drawn.stream().filter(label -> label.startsWith("11-")).toList();
        assertAll(
                () -> assertTrue(drawn.contains("1199.99"), "最高一根应标出 1199.99"),
                () -> assertEquals(10, categoriesDrawn.size(), () -> "画出的场次：" + categoriesDrawn),
                () -> assertTrue(drawn.contains("800") && !drawn.contains("800.00"), drawn::toString),
                () -> assertTrue(drawn.containsAll(amounts.subList(1, amounts.size())), "每根柱都应有数值标注"));
    }

    @Test
    @DisplayName("出图时折线的平均值和金额会重现浮点尾巴，整数被补上小数位")
    void pngChartsWriteEachShortNumericLabel() {
        assertAll(
                () -> assertDrawnNumbers("平均在线", List.of(new Value("189.2", new BigDecimal("189.16666666666670")),
                        new Value("44", new BigDecimal("44.02503293807642")), new Value("45", new BigDecimal("45.0")))),
                () -> assertDrawnNumbers("金额（元）", List.of(new Value("10463.1", new BigDecimal("10463.10000000000004")),
                        new Value("120000", new BigDecimal("120000.00")), new Value("15113.7", new BigDecimal("15113.70000000000014")))));
    }

    @Test
    @DisplayName("每日流水图的参考线会显示一长串浮点小数")
    void referenceLineUsesShortAmountLabel() {
        var rows = List.of(row("11-01", 9000), row("11-02", 11600));
        var target = new ReferenceLine(new BigDecimal("10463.10000000000004"), "流水目标");
        var chart = new Chart(Type.LINE, "每日流水", "日期", "流水（元）", List.of("流水（元）"),
                rows, target, "合成归档", rows.size());
        var drawn = record(chart).text().stream().map(DrawnText::text).toList();
        assertTrue(drawn.contains("10463.1"), drawn::toString);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("lineSamples")
    @DisplayName("每日和钟点折线的标注框会遮住其他点的圆心或盖住折线")
    void lineLabelBoxesClearAllMarkersAndStrokes(String title, String series, List<Row> rows) {
        var drawing = record(new Chart(Type.LINE, title, "时间", series, List.of(series), rows, null, "合成归档", rows.size()));
        assertEquals(rows.size(), drawing.markers().size(), "每个数据点应画出标记");
        assertFalse(drawing.labels().isEmpty(), "折线仍应有数值标注");
        assertEquals(1, drawing.strokes().size(), "完整数据应画成一条连续的线");
        var covered = IntStream.range(0, drawing.markers().size())
                .filter(i -> drawing.labels().stream().anyMatch(box -> box.contains(drawing.markers().get(i))))
                .boxed().toList();
        var coveredStrokes = drawing.labels().stream()
                .filter(box -> drawing.strokes().stream().anyMatch(stroke -> stroke.intersects(box))).toList();
        assertAll(
                () -> assertTrue(covered.isEmpty(), () -> "被标注框遮住圆心的数据点：" + covered),
                () -> assertTrue(coveredStrokes.isEmpty(), () -> "盖住折线的标注框：" + coveredStrokes),
                () -> {
                    for (int i = 0; i < drawing.labels().size(); i++) {
                        for (var other : drawing.labels().subList(0, i)) {
                            assertFalse(other.intersects(drawing.labels().get(i)), "数值标注框互相重叠");
                        }
                    }
                });
    }

    private static List<Arguments> lineSamples() {
        int[] daily = {120000, 117000, 119000, 126000, 128000, 120000, 110000, 120000, 124000, 128000,
                149058, 120000, 110000, 120000, 116000, 114000, 119000, 120000, 110000, 120000,
                118000, 112000, 114000, 120000, 110000, 120000, 118000, 110000, 10463, 115000};
        int[] hourly = {72, 69, 68, 67, 66, 58, 52, 44, 98, 101, 98, 100, 108, 120, 148, 189, 130, 118, 108, 98, 91, 81, 79, 78};
        return List.of(Arguments.of("每日流水", "流水（元）", sampleRows(daily)),
                Arguments.of("钟点平均", "平均在线", sampleRows(hourly)));
    }

    private static List<Row> sampleRows(int[] values) {
        return IntStream.range(0, values.length).mapToObj(i -> row("时段 " + (i + 1), values[i])).toList();
    }

    private static void assertDrawnNumbers(String series, List<Value> values) {
        var rows = IntStream.range(0, values.size()).mapToObj(i -> new Row("时段 " + (i + 1), List.of(values.get(i)))).toList();
        var labels = values.stream().map(Value::text).sorted().toList();
        for (var type : Type.values()) {
            var drawing = record(new Chart(type, "直播数据", "时间", series, List.of(series), rows, null, "合成归档", rows.size()));
            assertEquals(labels, drawing.text().stream().filter(entry -> type == Type.TABLE || drawing.labels().stream()
                            .anyMatch(box -> box.y == entry.position().y && box.contains(entry.position()))).map(DrawnText::text)
                    .filter(text -> text.matches("\\d+(?:\\.\\d+)?")).sorted().toList(), type::name);
        }
    }

    private record DrawnText(String text, Point position) { }
    private record Drawing(List<DrawnText> text, List<Point> markers, List<Shape> strokes, List<Rectangle> labels) { }

    private static Drawing record(Chart chart) {
        int height = chart.type() == Type.TABLE ? 816 : 560;
        var canvas = spy(factory.create(760, height));
        var palette = canvas.palette();
        var drawing = new Drawing(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        doAnswer(call -> {
            List<TextWithStyle> runs = call.getArgument(0);
            Point position = call.getArgument(1);
            drawing.text().add(new DrawnText(String.join("", runs.stream().map(TextWithStyle::getText).toList()), new Point(position)));
            return call.callRealMethod();
        }).when(canvas).drawTextWithStyle(anyList(), any(Point.class));
        doAnswer(call -> {
            int x = call.getArgument(0), y = call.getArgument(1), width = call.getArgument(2), heightDrawn = call.getArgument(3);
            int radius = call.getArgument(4);
            Color color = call.getArgument(5);
            if (width == 8 && heightDrawn == 8 && radius == 8) drawing.markers().add(new Point(x + 4, y + 4));
            if (radius == 5 && color.equals(palette.card())) drawing.labels().add(new Rectangle(x, y, width, heightDrawn));
            return call.callRealMethod();
        }).when(canvas).drawRoundedRectangle(anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), any(Color.class));
        doAnswer(call -> {
            List<Point> points = call.getArgument(0);
            if (points.size() >= 2) {
                var path = new Path2D.Double();
                path.moveTo(points.get(0).x, points.get(0).y);
                points.subList(1, points.size()).forEach(point -> path.lineTo(point.x, point.y));
                int width = call.getArgument(2);
                drawing.strokes().add(new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND).createStrokedShape(path));
            }
            return call.callRealMethod();
        }).when(canvas).drawPolyline(anyList(), eq(palette.curveOnline()), anyInt());
        var recordingFactory = spy(factory);
        doReturn(canvas).when(recordingFactory).create(760, height);
        assertTrue(new BilibiliAssistantChartPainter(recordingFactory, fonts).render(chart).isPresent());
        return drawing;
    }

    @Test
    @DisplayName("即使系统绘图字体配置不同，图表的每个中文字仍由内置中文字体显示")
    void bundledFontDisplaysEveryChineseTitleCharacter() throws Exception {
        String title = "近十场直播流水与观众互动";
        var font = painter.chineseFont();
        assertNotNull(font);
        assertTrue(font.getFamily(java.util.Locale.ROOT).contains("Noto Sans SC"), font::getFamily);
        title.codePoints().forEach(cp -> assertTrue(font.canDisplay(cp), () -> Character.toString(cp)));
        var image = render(new Chart(Type.LINE, title, "时间", "人数", List.of("观众"),
                List.of(row("昨晚", 7), row("今晚", 30)), new ReferenceLine(BigDecimal.valueOf(20), "目标"), "合成归档", 2));
        assertTrue(darkPixels(image, 35, 500, 39, 85) > 200, "实际标题区域中应有可见文字");
        assertTrue(darkPixels(image, 590, 727, 522, 550) > 100, "出图标注应在图片内部");
    }

    @Test
    @DisplayName("柱形像素高度必须按系统收到的数值成比例，表格导出固定幅面")
    void pngBarsUseRecordedValuesAndTableKeepsFixedCanvas() throws Exception {
        var rows = List.of(row("首场", 10), row("次场", 30));
        var bar = render(new Chart(Type.BAR, "近两场流水", "场次", "金额（元）", List.of("流水"), rows, null, "合成归档", 2));
        int first = band(bar, 114, 400, ReportPalette.DEFAULT.curveOnline());
        int second = band(bar, 410, 705, ReportPalette.DEFAULT.curveOnline());
        assertTrue(first > 0);
        assertEquals(3.0, (double) second / first, 0.05);
        assertEquals(760, bar.getWidth());
        var table = render(new Chart(Type.TABLE, "近两场流水", "场次", "金额（元）", List.of("流水"), rows, null, "合成归档", 2));
        assertEquals(bar.getWidth(), table.getWidth());
        assertEquals(816, table.getHeight());
        assertTrue(darkPixels(table, 590, 727, 778, 806) > 100, "表格同样保留生成标注");
    }

    private static BufferedImage render(Chart chart) throws Exception {
        var bytes = Base64.getDecoder().decode(painter.render(chart).orElseThrow());
        return ImageIO.read(new ByteArrayInputStream(bytes));
    }

    private static Row row(String label, long value) {
        return new Row(label, List.of(new Value(Long.toString(value), BigDecimal.valueOf(value))));
    }

    private static int band(BufferedImage image, int left, int right, Color color) {
        return IntStream.range(left, right).map(x -> (int) IntStream.range(155, 443)
                .filter(y -> image.getRGB(x, y) == color.getRGB()).count()).max().orElse(0);
    }

    private static long darkPixels(BufferedImage image, int left, int right, int top, int bottom) {
        return IntStream.range(left, right).mapToLong(x -> IntStream.range(top, bottom)
                .filter(y -> new Color(image.getRGB(x, y)).getRed() < 150).count()).sum();
    }
}
