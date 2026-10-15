package org.frostnova.nova.report.painter;

import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.analytics.WeeklyReport;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.enums.LiveEndReason;
import org.frostnova.nova.core.model.LiveSession;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import org.frostnova.nova.core.model.StreamerSnapshot;
import org.frostnova.nova.core.service.LiveDataService;
import org.frostnova.nova.core.service.LiveRoomInfoHistory;
import org.frostnova.nova.report.analytics.BilibiliLiveMetricCatalog;
import org.frostnova.nova.report.factory.NovaCommonPainterFactory;
import org.frostnova.nova.report.util.FontUtil;
import org.junit.jupiter.api.*;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.io.DefaultResourceLoader;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Font;
import org.frostnova.nova.core.model.TextWithStyle;
import java.awt.Point;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("运营周报图片")
class BilibiliWeeklyReportPainterTest {
    NovaCommonPainterFactory factory;
    BilibiliApiUtil api;
    FontUtil fonts;
    BilibiliWeeklyReportPainter painter;
    final Instant now = Instant.parse("2026-10-12T02:00:00Z");
    final Instant from = Instant.parse("2026-10-04T22:00:00Z");
    final LiveStreamerInfo streamer = new LiveStreamerInfo(1L, "主播示例", 2L, "https://pic.example/avatar.png");

    @BeforeEach void setup() {
        var props = new NovaCoreProperties(); props.getPaint().getFonts().add("内置");
        fonts = new FontUtil(new DefaultResourceLoader(), props); fonts.init();
        var info = new Properties(); info.setProperty("version", "test");
        factory = new NovaCommonPainterFactory(new BuildProperties(info), props, fonts);
        api = mock(BilibiliApiUtil.class);
        var face = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = face.createGraphics(); g.setColor(new Color(120, 170, 220)); g.fillRect(0, 0, 100, 100); g.dispose();
        when(api.getBilibiliImage(anyString())).thenReturn(Optional.of(face));
        painter = new BilibiliWeeklyReportPainter(factory, api, new NovaBilibiliProperties());
    }
    LiveSession session(Instant start, double revenue, long messages, List<Long> users, Map<String, Long> anonymous) {
        return new LiveSession("bilibili", 1L, "主播示例", 2L, start.toEpochMilli(), start.plusSeconds(13200).toEpochMilli(),
                13200, Map.of("gift_value", revenue, "danmu_count", (double) messages), Map.of(), LiveEndReason.NORMAL,
                List.of(), 0, Map.of("enter_users", users, "danmu_users", users), 0, Map.of(), anonymous);
    }
    WeeklyReport.Data data(boolean visible) {
        var sessions = List.of(session(from.minusSeconds(86400), 500, 500, List.of(9200000001L), Map.of()),
                session(from, 500, 1000, List.of(9200000001L, 9200000002L), Map.of()),
                session(from.plusSeconds(86400), 0, 0, List.of(), Map.of()),
                session(from.plusSeconds(3 * 86400), 234.50, 2901, List.of(9200000003L), Map.of("enter_users", 1L)),
                session(from.plusSeconds(4 * 86400), 300, 2000, List.of(9200000002L), null),
                session(from.plusSeconds(7 * 86400 - 10800), 200, 3000, List.of(9200000001L), Map.of()));
        var snapshots = List.of(new StreamerSnapshot("bilibili", 1L, "主播示例", from.plusSeconds(7200).toEpochMilli(), Map.of("fans", 100d)),
                new StreamerSnapshot("bilibili", 1L, "主播示例", from.plusSeconds(6 * 86400).toEpochMilli(), Map.of("fans", 125d)));
        return WeeklyReport.calculate("主播示例", sessions, snapshots, now, new BilibiliLiveMetricCatalog().insights(), visible);
    }
    @Test @DisplayName("可见与隐藏金额出图，缺记录用破折号，已记录零写零")
    void samples() throws Exception {
        for (boolean visible : List.of(true, false)) {
            var data = data(visible);
            var p = WeeklyReport.preview(data, () -> painter.render(streamer, data));
            assertNotNull(p.image());
            var image = ImageIO.read(new java.io.ByteArrayInputStream(p.image()));
            assertEquals(900, image.getWidth());
            assertTrue(image.getHeight() > 650);
            assertEquals(0, data.daily().get(1).metrics().get(data.chartMetric()).doubleValue());
            assertEquals(0, data.daily().get(2).sessions());
            String output = System.getProperty("weekly.samples");
            if (output != null) Files.write(Path.of(output).resolve(visible ? "visible.png" : "hidden.png"), p.image());
            System.out.println((visible ? "VISIBLE_SUMMARY: " : "HIDDEN_SUMMARY: ") + p.summary());
        }
        var d = data(true);
        var failed = WeeklyReport.preview(d, () -> { throw new IllegalStateException("image unavailable"); });
        System.out.println("FALLBACK_TEXT:\n" + failed.text());
        var empty = WeeklyReport.calculate("主播示例", List.of(), List.of(), now, new BilibiliLiveMetricCatalog().insights(), true);
        System.out.println("EMPTY_TEXT: " + WeeklyReport.preview(empty, () -> { fail("不应出图"); return null; }).text());
    }
    @Test @DisplayName("日图显示真实零与未记录，隐藏金额使用弹幕且不带观众身份")
    void chartLabels() {
        for (boolean money : List.of(true, false)) {
            var p = spy(factory.create(900, 1200, true)); p.setPos(35, 35);
            painter.drawDays(p, data(money));
            var strings = org.mockito.ArgumentCaptor.forClass(String.class);
            verify(p).drawSection(strings.capture(), any(Color.class));
            assertEquals(money ? "每天流水（元）" : "每天弹幕（条）", strings.getValue());
            var texts = org.mockito.ArgumentCaptor.forClass(List.class);
            verify(p, atLeastOnce()).drawTextWithStyle(texts.capture(), any(Point.class));
            var drawn = new StringBuilder();
            for (List row : texts.getAllValues()) for (Object t : row)
                drawn.append(((org.frostnova.nova.core.model.TextWithStyle)t).getText());
            String raw = drawn.toString();
            assertTrue(raw.contains("—")); assertTrue(raw.contains(money ? "0.00" : "0"));
            assertFalse(raw.contains("9200000001"));
        }
    }
    record TextCall(String text, Font font, int size, Color color, int style) {
        static TextCall of(TextWithStyle t) {
            return new TextCall(t.getText(), t.getFont(), t.getSize(), t.getColor(), t.getStyle());
        }
    }
    record PaintCall(String method, List<?> arguments) {}

    @Test @DisplayName("下播卡片的全部绘制与截断调用保持抽取前的参数")
    void liveCardCalls() throws Exception {
        var calls = new ArrayList<PaintCall>();
        var p = mock(CommonPainter.class, invocation -> {
            String name = invocation.getMethod().getName();
            Object[] args = invocation.getArguments();
            if (name.equals("truncateToWidth")) {
                var text = (TextWithStyle) args[0];
                calls.add(new PaintCall(name, List.of(TextCall.of(text), args[1])));
                return "fit:" + text.getText();
            }
            if (name.equals("drawTextWithStyle")) {
                var texts = (List<TextWithStyle>) args[0];
                calls.add(new PaintCall(name, List.of(texts.stream().map(TextCall::of).toList(), new Point((Point) args[1]))));
            } else {
                calls.add(new PaintCall(name, Arrays.asList(args.clone())));
            }
            return invocation.getMethod().getReturnType() == CommonPainter.class ? invocation.getMock() : null;
        });
        var live = new BilibiliLiveReportPainter(factory, api, mock(LiveDataService.class), fonts,
                new NovaBilibiliProperties(), mock(LiveRoomInfoHistory.class));
        var method = BilibiliLiveReportPainter.class.getDeclaredMethod("drawCard", CommonPainter.class,
                BilibiliLiveReportPainter.Card.class, int.class, int.class);
        method.setAccessible(true);
        method.invoke(live, p, new BilibiliLiveReportPainter.Card("1,234.50 元", "流水 · 5 人"), 35, 35);
        // 抽取前 drawCard 的参数；不引用共用常量，避免常量改变时预期也跟着变。
        var expected = List.of(
                new PaintCall("drawRoundedRectangle", List.of(35, 35, 264, 112, 16, new Color(246, 247, 249))),
                new PaintCall("truncateToWidth", List.of(new TextCall("1,234.50 元", null, 34, new Color(51, 51, 51), Font.BOLD), 224)),
                new PaintCall("drawTextWithStyle", List.of(List.of(new TextCall("fit:1,234.50 元", null, 34, new Color(51, 51, 51), Font.BOLD)), new Point(55, 51))),
                new PaintCall("truncateToWidth", List.of(new TextCall("流水 · 5 人", null, 22, new Color(153, 162, 170), Font.PLAIN), 224)),
                new PaintCall("drawTextWithStyle", List.of(List.of(new TextCall("fit:流水 · 5 人", null, 22, new Color(153, 162, 170), Font.PLAIN)), new Point(55, 103))));
        assertEquals(expected.size(), calls.size(), "绘制与截断调用数");
        for (int i = 0; i < expected.size(); i++) assertEquals(expected.get(i), calls.get(i), "call " + i);
    }

    @Test @DisplayName("周报副行与标签及卡片下沿留出间距")
    void weeklyCardSpacing() {
        for (String note : List.of("5 场", "从 10 月 5 日 08:00 起算")) {
            var p = mock(CommonPainter.class, RETURNS_SELF);
            when(p.truncateToWidth(any(TextWithStyle.class), anyInt())).thenAnswer(i -> ((TextWithStyle)i.getArgument(0)).getText());
            ReportSharedStyle.drawCard(p, "25 人", "粉丝净增", note, 35, 35);
            var heights = org.mockito.ArgumentCaptor.forClass(Integer.class);
            verify(p).drawRoundedRectangle(eq(35), eq(35), eq(264), heights.capture(), eq(16), any(Color.class));
            var texts = org.mockito.ArgumentCaptor.forClass(List.class);
            var points = org.mockito.ArgumentCaptor.forClass(Point.class);
            verify(p, times(3)).drawTextWithStyle(texts.capture(), points.capture());
            int labelHeight = factory.create(900, 200, true).getStringWidthAndHeight((TextWithStyle)texts.getAllValues().get(1).get(0)).getSecond();
            int noteHeight = factory.create(900, 200, true).getStringWidthAndHeight((TextWithStyle)texts.getAllValues().get(2).get(0)).getSecond();
            int above = points.getAllValues().get(2).y - points.getAllValues().get(1).y - labelHeight;
            int below = 35 + heights.getValue() - points.getAllValues().get(2).y - noteHeight;
            assertTrue(above >= 12, "副行上方间距: " + above);
            assertTrue(below >= 18, "副行下方间距: " + below);
            System.out.println("CARD_NOTE_SPACING: " + note + " above=" + above + "px below=" + below + "px");
        }
    }

    @Test @DisplayName("无统计口径的按天图展示时长并区分零和未开播")
    void noCatalogChart() {
        for (boolean visible : List.of(true, false)) {
            var sessions = List.of(session(from, 500, 1000, List.of(), Map.of()),
                    new LiveSession("sample", 1L, "主播示例", 2L, from.plusSeconds(86400).toEpochMilli(),
                            from.plusSeconds(86400).toEpochMilli(), 0, Map.of(), Map.of(), LiveEndReason.NORMAL, List.of()));
            var data = WeeklyReport.calculate("主播示例", sessions, List.of(), now, null, visible);
            var p = spy(factory.create(900, 1200, true)); p.setPos(35, 35);
            painter.drawDays(p, data);
            var title = org.mockito.ArgumentCaptor.forClass(String.class);
            verify(p).drawSection(title.capture(), any(Color.class));
            assertEquals("每天直播时长（小时）", title.getValue());
            var texts = org.mockito.ArgumentCaptor.forClass(List.class);
            verify(p, atLeast(7)).drawTextWithStyle(texts.capture(), any(Point.class));
            var labels = texts.getAllValues().stream().map(row -> (TextWithStyle)row.get(0)).filter(t -> t.getSize() == 18).map(TextWithStyle::getText).toList();
            assertEquals(List.of("3.67", "0.00", "—", "—", "—", "—", "—"), labels);
            verify(p).drawRectangle(eq(67), anyInt(), eq(54), eq(150), eq(ReportSharedStyle.COLOR_NAME));
        }
    }
}
