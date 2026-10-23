package org.frostnova.nova.report.painter;

import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.analytics.WeeklyReport;
import org.frostnova.nova.core.analytics.StatisticsDay;
import org.frostnova.nova.core.analytics.StreamerCalendar;
import org.frostnova.nova.core.model.LiveGap;
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
    WeeklyReport.Data data(boolean visible) { return data(visible, false); }
    WeeklyReport.Data data(boolean visible, boolean sample) {
        var sessions = List.of(session(from.minusSeconds(2 * 86400), 1000, 4000, List.of(9200000002L), Map.of()),
                session(from.minusSeconds(86400), 500, 500, List.of(9200000001L), Map.of()),
                session(from, sample ? 7209.4 : 500, 1000, List.of(9200000001L, 9200000002L), Map.of()),
                session(from.plusSeconds(86400), 0, 0, List.of(), Map.of()),
                session(from.plusSeconds(3 * 86400), sample ? 71000 : 234.50, sample ? 71000 : 2901, List.of(9200000003L), Map.of("enter_users", 1L)),
                sample ? new LiveSession("bilibili", 1L, "主播示例", 2L, from.plusSeconds(4 * 86400).toEpochMilli(),
                        from.plusSeconds(4 * 86400 + 13200).toEpochMilli(), 13200, Map.of(), Map.of(),
                        LiveEndReason.NORMAL, List.of())
                        : session(from.plusSeconds(4 * 86400), 300, 2000, List.of(9200000002L), null),
                session(from.plusSeconds(7 * 86400 - 10800), 200, 3000, List.of(9200000001L), Map.of()));
        var snapshots = List.of(new StreamerSnapshot("bilibili", 1L, "主播示例", from.plusSeconds(7200).toEpochMilli(), Map.of("fans", 100d)),
                new StreamerSnapshot("bilibili", 1L, "主播示例", from.plusSeconds(6 * 86400).toEpochMilli(), Map.of("fans", 125d)));
        var data = WeeklyReport.calculate("主播示例", sessions, snapshots, now, new BilibiliLiveMetricCatalog().insights(), visible);
        return data.withCalendar(new StreamerCalendar().calculate(sessions,
                List.of(new LiveGap(from.minusSeconds(14 * 86400).toEpochMilli(), from.plusSeconds(5 * 86400).toEpochMilli(), LiveGap.Reason.UNKNOWN),
                        new LiveGap(from.plusSeconds(6 * 86400).toEpochMilli(), now.toEpochMilli(), LiveGap.Reason.UNKNOWN)),
                List.of(), List.of(), data.lastDay(), 35, java.time.Clock.fixed(now, StatisticsDay.ZONE)));
    }
    @Test @DisplayName("可见与隐藏金额出图，缺记录用破折号，已记录零写零")
    void samples() throws Exception {
        for (boolean visible : List.of(true, false)) {
            var source = data(visible, true);
            var days = new ArrayList<>(source.daily());
            var missing = days.get(4);
            days.set(4, new WeeklyReport.Day(missing.date(), missing.sessions(), Map.of()));
            var data = new WeeklyReport.Data(source.streamerName(), source.firstDay(), source.lastDay(), source.metrics(),
                    source.fans(), source.composition(), source.coverage(), days, source.chartMetric(),
                    source.comparisons(), source.comparisonNote(), source.sessionRows(), source.hiddenSessions(), source.calendar());
            var p = WeeklyReport.preview(data, () -> painter.render(streamer, data));
            assertNotNull(p.image());
            var image = ImageIO.read(new java.io.ByteArrayInputStream(p.image()));
            assertEquals(900, image.getWidth());
            assertTrue(image.getHeight() > 650);
            System.out.println((visible ? "VISIBLE_IMAGE: " : "HIDDEN_IMAGE: ") + image.getWidth() + "x" + image.getHeight());
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
            assertTrue(raw.contains("未播")); assertTrue(raw.contains("0"));
            assertFalse(raw.contains("9200000001"));
        }
    }
    @Test @DisplayName("图片场次保留真实日期，凌晨场次不误认成周首日")
    void sessionDateIsNotTruncated() {
        var d = WeeklyReport.calculate("示例", List.of(session(Instant.parse("2026-10-18T16:10:00Z"),
                0, 0, List.of(), Map.of())), List.of(), Instant.parse("2026-10-19T02:00:00Z"),
                new BilibiliLiveMetricCatalog().insights(), true);
        var p = spy(factory.create(900, 1200, true)); p.setPos(35, 35);
        painter.drawSessions(p, d);
        assertTrue(drawnText(p).contains("10/19 00:10"));
        assertFalse(drawnText(p).contains("周一 00:10"));
    }

    @Test @DisplayName("无场次日沿用日历结论，避免折线与日历互相矛盾")
    void emptyDaysAgreeWithCalendar() {
        var d = data(false);
        var p = spy(factory.create(900, 1200, true)); p.setPos(35, 35);
        painter.drawDays(p, d);
        var texts = org.mockito.ArgumentCaptor.forClass(List.class);
        var points = org.mockito.ArgumentCaptor.forClass(Point.class);
        verify(p, atLeastOnce()).drawTextWithStyle(texts.capture(), points.capture());
        int off = 0, unknown = 0;
        for (int i = 0; i < 7; i++) {
            var day = d.daily().get(i);
            if (day.sessions() > 0) continue;
            String status = d.calendar().items().stream().filter(c -> c.date().equals(day.date()))
                    .findFirst().orElseThrow().status();
            String expected = status.equals("off") ? "未播" : "说不准";
            if (status.equals("off")) off++; else unknown++;
            final int center = 35 + i * 118 + 59;
            var labels = new ArrayList<String>();
            for (int j = 0; j < texts.getAllValues().size(); j++) {
                var t = (TextWithStyle)texts.getAllValues().get(j).get(0);
                var point = points.getAllValues().get(j);
                if (point != null && Math.abs(point.x - p.getStringWidthAndHeight(t).getFirst() / 2 - center) <= 1)
                    labels.add(t.getText());
            }
            assertTrue(labels.contains(expected), day.date() + " " + expected);
            assertFalse(labels.contains(status.equals("off") ? "说不准" : "未播"));
        }
        assertTrue(off > 0 && unknown > 0, "同时覆盖确认没播和监看不完整的日期");
    }

    @Test @DisplayName("有直播但指标缺失写无数据，避免被误认成未播")
    void liveDayWithMissingMetricHasNoDataLabel() {
        var d = data(false);
        var days = d.daily().stream().map(day -> new WeeklyReport.Day(day.date(), 1, Map.<String, Number>of())).toList();
        var missing = new WeeklyReport.Data(d.streamerName(), d.firstDay(), d.lastDay(), d.metrics(), d.fans(),
                d.composition(), d.coverage(), days, "messages");
        var p = spy(factory.create(900, 1200, true)); p.setPos(35, 35);
        painter.drawDays(p, missing);
        var texts = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(p, atLeastOnce()).drawTextWithStyle(texts.capture(), any(Point.class));
        assertEquals(7, texts.getAllValues().stream().filter(row ->
                ((TextWithStyle)row.get(0)).getText().equals("无数据")).count());
        assertFalse(drawnText(p).contains("未播"));
        verify(p, never()).drawPolyline(anyList(), any(Color.class), anyInt());
    }

    @Test @DisplayName("三种折线数字按十进制进位，避免同一天的流水与控制台相差 0.1")
    void chartNumbersMatchConsole() {
        for (String metric : List.of("revenue", "messages", "durationSeconds")) {
            for (var example : List.of(
                    Map.entry(List.of(7209.4, 71000d, 0d, 1.25, 1.24, 10000d, 12d),
                            List.of("7,209.4", "71,000", "0", "1.3", "1.2", "10,000", "12")),
                    Map.entry(List.of(71000.45, 7209.45, 12.35, 0.15, 0d, 10000d, 12d),
                            List.of("71,000.5", "7,209.5", "12.4", "0.2", "0", "10,000", "12")))) {
                var d = data(true);
                var values = example.getKey();
                var days = new ArrayList<WeeklyReport.Day>();
                for (int i = 0; i < 7; i++) days.add(new WeeklyReport.Day(d.daily().get(i).date(), 1,
                        Map.of(metric, values.get(i) * (metric.equals("durationSeconds") ? 3600 : 1))));
                var chart = new WeeklyReport.Data(d.streamerName(), d.firstDay(), d.lastDay(), d.metrics(), d.fans(),
                        d.composition(), d.coverage(), days, metric);
                var p = spy(factory.create(900, 1200, true)); p.setPos(35, 35);
                painter.drawDays(p, chart);
                var texts = org.mockito.ArgumentCaptor.forClass(List.class);
                verify(p, atLeastOnce()).drawTextWithStyle(texts.capture(), any(Point.class));
                var labels = texts.getAllValues().stream().map(row -> (TextWithStyle)row.get(0))
                        .filter(t -> t.getSize() == 18).map(TextWithStyle::getText).toList();
                assertEquals(example.getValue(), labels, metric);
            }
        }
    }

    @Test @DisplayName("日图非有限数按无数据处理，其余日期照常绘制")
    void nonFiniteDailyValuesHaveNoDataLabels() {
        var source = data(false);
        var values = List.of(10d, Double.NaN, 30d, Double.POSITIVE_INFINITY, 50d, 60d, 70d);
        var days = new ArrayList<WeeklyReport.Day>();
        for (int i = 0; i < 7; i++) days.add(new WeeklyReport.Day(source.daily().get(i).date(), 1,
                Map.of("messages", values.get(i))));
        var chart = new WeeklyReport.Data(source.streamerName(), source.firstDay(), source.lastDay(),
                source.metrics(), source.fans(), source.composition(), source.coverage(), days, "messages");
        assertNotNull(assertDoesNotThrow(() -> painter.render(streamer, chart)));

        var p = spy(factory.create(900, 1200, true)); p.setPos(35, 35);
        painter.drawDays(p, chart);
        var texts = org.mockito.ArgumentCaptor.forClass(List.class);
        var positions = org.mockito.ArgumentCaptor.forClass(Point.class);
        verify(p, atLeastOnce()).drawTextWithStyle(texts.capture(), positions.capture());
        var labels = texts.getAllValues().stream().map(row -> (TextWithStyle) row.get(0))
                .filter(t -> t.getSize() == 18).map(TextWithStyle::getText).toList();
        assertEquals(List.of("10", "30", "50", "60", "70"), labels);
        var missingCenters = new ArrayList<Integer>();
        for (int i = 0; i < texts.getAllValues().size(); i++) {
            var text = (TextWithStyle) texts.getAllValues().get(i).get(0);
            if (text.getText().equals("无数据")) missingCenters.add(positions.getAllValues().get(i).x
                    - p.getStringWidthAndHeight(text).getFirst() / 2);
        }
        assertEquals(List.of(35 + 118 + 59, 35 + 3 * 118 + 59), missingCenters);
        var lines = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(p).drawPolyline(lines.capture(), any(Color.class), eq(3));
        var line = lines.getValue();
        assertEquals(3, line.size());
        assertEquals(35 + 4 * 118 + 59, ((Point) line.get(0)).x);
        assertEquals(43, ((Point) line.get(0)).y - ((Point) line.get(2)).y);
        verify(p, times(5)).drawRoundedRectangle(anyInt(), anyInt(), eq(10), eq(10), eq(10), any(Color.class));
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
            if (invocation.getMethod().getName().equals("palette")) return ReportPalette.DEFAULT;
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

    @Test @DisplayName("副行使用20号字且完整换行，避免手机缩图看不清或截掉说明")
    void weeklyCardSpacing() {
        for (String note : List.of("较上周 +4%", "从 10 月 5 日 08:00 起算", "按区间边界有效快照计算")) {
            var p = spy(factory.create(900, 400, true));
            ReportSharedStyle.drawCard(p, "25 人", "粉丝净增", note, 35, 35);
            var heights = org.mockito.ArgumentCaptor.forClass(Integer.class);
            verify(p).drawRoundedRectangle(eq(35), eq(35), eq(264), heights.capture(), eq(16), any(Color.class));
            var texts = org.mockito.ArgumentCaptor.forClass(List.class);
            var points = org.mockito.ArgumentCaptor.forClass(Point.class);
            verify(p, atLeast(3)).drawTextWithStyle(texts.capture(), points.capture());
            var complete = new StringBuilder();
            for (int i = 2; i < texts.getAllValues().size(); i++) {
                var t = (TextWithStyle)texts.getAllValues().get(i).get(0);
                assertEquals(20, t.getSize());
                assertTrue(p.getStringWidthAndHeight(t).getFirst() <= 224);
                assertTrue(points.getAllValues().get(i).y + p.getStringWidthAndHeight(t).getSecond() + 18 <= 35 + heights.getValue());
                complete.append(t.getText());
            }
            assertEquals(note, complete.toString());
            var label = (TextWithStyle)texts.getAllValues().get(1).get(0);
            assertTrue(points.getAllValues().get(2).y - points.getAllValues().get(1).y
                    - p.getStringWidthAndHeight(label).getSecond() >= 12);
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
            assertEquals(List.of("3.7", "0"), labels);
            verify(p).drawPolyline(argThat(points -> points.size() == 2), eq(ReportPalette.DEFAULT.streamerName()), eq(3));
        }
    }

    // Prevent the image from omitting the new sections or exposing amounts in a hidden-money group.
    @Test void renderedSectionsAndPrivacy() {
        for (boolean visible : List.of(true, false)) {
            var p = spy(factory.create(900, 1200, true));
            var recordingFactory = mock(NovaCommonPainterFactory.class);
            when(recordingFactory.create(900, 1200, true, ReportPalette.DEFAULT)).thenReturn(p);
            new BilibiliWeeklyReportPainter(recordingFactory, api, new NovaBilibiliProperties()).render(streamer, data(visible));
            String raw = drawnText(p);
            for (String label : List.of("本周开播 5 天、共 5 场", "统计日 06:00–次日 06:00", "较上周 +",
                    "名单完整性未知", "本周观众", "本周去重观众", "本周首次记录", "历史回访",
                    "本周互动观众", "不等于第一次观看直播", "开播日历 · 近 5 周", "确认没播", "说不准",
                    "本周场次 · 5 场", "未播", "10/5",
                    "数字只算记录到的数据，采集缺口不补估")) assertTrue(raw.contains(label), label);
            assertTrue(raw.indexOf("本周观众") < raw.indexOf("开播日历 · 近 5 周"));
            assertTrue(raw.indexOf("开播日历 · 近 5 周") < raw.indexOf("本周场次 · 5 场"));
            if (!visible) for (String token : List.of("流水", "元", "revenue", "234.50")) assertFalse(raw.contains(token), token);
            var texts = org.mockito.ArgumentCaptor.forClass(List.class);
            verify(p, atLeastOnce()).drawTextWithStyle(texts.capture(), any(Point.class));
            var notes = new ArrayList<TextWithStyle>();
            for (var row : texts.getAllValues()) for (Object item : row)
                if (item instanceof TextWithStyle t && t.getText().startsWith("较上周")) notes.add(t);
            assertTrue(notes.stream().anyMatch(t -> t.getText().startsWith("较上周 +") && t.getColor().equals(new Color(0xCA1226))));
            if (visible) assertTrue(notes.stream().anyMatch(t -> t.getText().startsWith("较上周 −") && t.getColor().equals(new Color(0x167349))));
        }
    }

    // Prevent joining across missing days, dropping recorded zeroes, or inventing a line for one point.
    @Test void dailyLinesBreakAtMissingValues() {
        var d = data(false);
        var days = new ArrayList<WeeklyReport.Day>();
        for (int i = 0; i < 7; i++) days.add(new WeeklyReport.Day(d.daily().get(i).date(), 1,
                i == 1 || i == 4 || i == 6 ? Map.of() : Map.of("messages", i == 2 ? 0 : i + 1)));
        var data = new WeeklyReport.Data(d.streamerName(), d.firstDay(), d.lastDay(), d.metrics(), d.fans(),
                d.composition(), d.coverage(), days, "messages");
        var p = spy(factory.create(900, 1200, true)); p.setPos(35, 35);
        painter.drawDays(p, data);
        var points = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(p).drawPolyline(points.capture(), any(Color.class), eq(3));
        assertEquals(2, points.getValue().size());
        assertEquals(35 + 2 * 118 + 59, ((Point) points.getValue().get(0)).x);
        assertEquals(35 + 3 * 118 + 59, ((Point) points.getValue().get(1)).x);
        verify(p, times(4)).drawRoundedRectangle(anyInt(), anyInt(), eq(10), eq(10), eq(10), any(Color.class));
        assertTrue(drawnText(p).contains("无数据"));
        assertTrue(drawnText(p).contains("0"));
        for (int i = 1; i < 7; i++) days.set(i, new WeeklyReport.Day(days.get(i).date(), 0, Map.of()));
        p = spy(factory.create(900, 1200, true)); p.setPos(35, 35);
        painter.drawDays(p, data);
        verify(p, never()).drawPolyline(anyList(), any(Color.class), anyInt());
        verify(p).drawRoundedRectangle(anyInt(), anyInt(), eq(10), eq(10), eq(10), any(Color.class));
    }

    // Prevent the image from presenting an unavailable comparison as a percentage.
    @Test void unavailableComparisonAndAudience() {
        var data = WeeklyReport.calculate("示例", List.of(session(from, 0, 0, List.of(), Map.of())), List.of(), now,
                new BilibiliLiveMetricCatalog().insights(), true);
        var p = spy(factory.create(900, 1200, true));
        var recordingFactory = mock(NovaCommonPainterFactory.class);
        when(recordingFactory.create(900, 1200, true, ReportPalette.DEFAULT)).thenReturn(p);
        new BilibiliWeeklyReportPainter(recordingFactory, api, new NovaBilibiliProperties()).render(streamer, data);
        String raw = drawnText(p);
        assertTrue(raw.contains("较上周 —"));
        assertTrue(raw.contains("较上周：上周没有开播，不比较。"));
        assertFalse(raw.contains("较上周 +")); assertFalse(raw.contains("较上周 −"));
        assertTrue(raw.contains("暂无数据"));
        p = spy(factory.create(900, 1200, true)); p.setPos(35, 35);
        var d = data(false);
        painter.drawAudience(p, new WeeklyReport.Data(d.streamerName(), d.firstDay(), d.lastDay(), Map.of("sessions", 1),
                null, null, d.coverage(), d.daily(), "messages"));
        assertFalse(drawnText(p).contains("0 人"));
        assertTrue(drawnText(p).contains("暂无数据"));
        verify(p).drawArc(anyInt(), anyInt(), anyInt(), eq(90d), eq(-360d), eq(22), any(Color.class));
    }

    @Test void comparisonReasonsAreSharedWithText() {
        var d = data(true);
        for (String reason : List.of("上期没有开播", "本期或上期少于 2 场", "平台没有这项口径", "上期为 0", "有场次没有观众名单")) {
            var comparisons = new LinkedHashMap<String, org.frostnova.nova.core.analytics.StreamerInsights.Comparison>();
            for (String key : List.of("durationSeconds", "revenue", "messages", "audience", "interactionAudience"))
                comparisons.put(key, new org.frostnova.nova.core.analytics.StreamerInsights.Comparison(null, "unavailable", reason));
            var data = new WeeklyReport.Data(d.streamerName(), d.firstDay(), d.lastDay(), d.metrics(), d.fans(), d.composition(),
                    d.coverage(), d.daily(), d.chartMetric(), comparisons, null, d.sessionRows(), 0);
            var p = spy(factory.create(900, 1200, true));
            var recordingFactory = mock(NovaCommonPainterFactory.class);
            when(recordingFactory.create(900, 1200, true, ReportPalette.DEFAULT)).thenReturn(p);
            new BilibiliWeeklyReportPainter(recordingFactory, api, new NovaBilibiliProperties()).render(streamer, data);
            String explanation = "较上周：" + reason.replace("本期", "本周").replace("上期", "上周") + "，不比较。";
            assertEquals(explanation, WeeklyReport.comparisonExplanation(data));
            assertTrue(drawnText(p).contains(explanation));
            assertTrue(WeeklyReport.text(data).contains(explanation));
            assertFalse(drawnText(p).contains("较上周 +"));
            assertFalse(drawnText(p).contains("较上周 −"));
            for (int i = 0; i < 5; i++) assertEquals("较上周 —", WeeklyReport.cards(data).get(i).note());
        }
    }

    // Prevent the image session list from concealing unknown values, interruptions, or additional sessions.
    @Test void sessionRowsAndOverflow() {
        var sessions = new ArrayList<LiveSession>();
        for (int i = 0; i < 8; i++) sessions.add(session(from.plusSeconds(i), 0, 0, List.of(), Map.of()));
        sessions.add(new LiveSession("bilibili", 1L, "示例", 2L, from.plusSeconds(10).toEpochMilli(),
                from.plusSeconds(70).toEpochMilli(), 60, Map.of(), Map.of(), LiveEndReason.CUT_OFF,
                List.of(new org.frostnova.nova.core.model.RoomInfoSnapshot(0, "标题".repeat(100), "")),
                66, Map.of(), 40));
        for (boolean visible : List.of(true, false)) {
            var d = WeeklyReport.calculate("示例", sessions, List.of(), now, new BilibiliLiveMetricCatalog().insights(), visible);
            var p = spy(factory.create(900, 1200, true)); p.setPos(35, 35);
            painter.drawSessions(p, d);
            String text = drawnText(p);
            for (String value : List.of("本周场次 · 9 场", "另有 2 场未列出", "被平台切断", "缺 1 分 6 秒", "…"))
                assertTrue(text.contains(value), value);
            if (visible) { assertTrue(text.contains("流水 —")); assertTrue(text.contains("流水 0.00 元")); }
            else { assertFalse(text.contains("流水")); assertFalse(text.contains("元")); }
        }
    }

    @Test @DisplayName("日历铺满五行七列，报告周描边，三态合计与文字一致")
    void calendarLayoutAndSharedCounts() {
        var data = data(false);
        var p = spy(factory.create(900, 1200, true)); p.setPos(35, 35);
        painter.drawCalendar(p, data);
        String raw = drawnText(p);
        for (String text : List.of("9/7 – 10/11", "有直播", "确认没播", "说不准", "不按没播算"))
            assertTrue(raw.contains(text), text);
        var totals = WeeklyReport.calendarTotals(data);
        assertEquals(35, totals.recorded() + totals.off() + totals.unknown());
        assertTrue(totals.recorded() > 0 && totals.off() > 0 && totals.unknown() > 0);
        assertTrue(raw.contains(totals.recorded() + "天 有直播"));
        assertTrue(raw.contains(totals.off() + "天 确认没播"));
        assertTrue(raw.contains(totals.unknown() + "天 说不准"));
        var cells = mockingDetails(p).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("drawRoundedRectangle") && i.getArgument(3).equals(64)).toList();
        assertEquals(35, cells.size(), "不能漏日期或多画报告周之后的日期");
        assertEquals(5, cells.stream().map(i -> (Integer)i.getArgument(1)).distinct().count());
        assertEquals(7, cells.stream().map(i -> (Integer)i.getArgument(0)).distinct().count());
        assertEquals(7, cells.stream().filter(i -> ReportPalette.DEFAULT.text().equals(i.getArgument(5))).count());
        for (int i = 0; i < cells.size(); i++) {
            var cell = cells.get(i);
            assertEquals(i >= 28, ReportPalette.DEFAULT.text().equals(cell.getArgument(5)), "只有报告周描边");
            assertTrue((Integer)cell.getArgument(0) + (Integer)cell.getArgument(2) <= 865);
        }
        var texts = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(p, atLeastOnce()).drawTextWithStyle(texts.capture(), any(Point.class));
        var dates = new ArrayList<String>();
        for (var row : texts.getAllValues()) for (Object item : row) {
            String value = ((TextWithStyle)item).getText();
            if (value.matches("[0-9]+/[0-9]+")) dates.add(value);
        }
        assertEquals(data.calendar().items().stream().map(d -> {
            var date = java.time.LocalDate.parse(d.date());
            return date.getMonthValue() + "/" + date.getDayOfMonth();
        }).toList(), dates);
    }

    String drawnText(CommonPainter p) {
        var result = new StringBuilder();
        for (var call : mockingDetails(p).getInvocations()) {
            if (!call.getMethod().getName().startsWith("draw")) continue;
            for (Object arg : call.getArguments()) {
                if (arg instanceof String s) result.append(s);
                if (arg instanceof List<?> list) for (Object item : list)
                    if (item instanceof TextWithStyle t) result.append(t.getText());
            }
        }
        return result.toString();
    }
}
