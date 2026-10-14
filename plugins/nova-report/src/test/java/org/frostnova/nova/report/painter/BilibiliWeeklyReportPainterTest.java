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
        return WeeklyReport.calculate(sessions, snapshots, now, new BilibiliLiveMetricCatalog().insights(), visible);
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
        var empty = WeeklyReport.calculate(List.of(), List.of(), now, new BilibiliLiveMetricCatalog().insights(), true);
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
    @Test @DisplayName("下播报告卡片在共用绘制前后像素一致")
    void liveCardPixels() throws Exception {
        String output = System.getProperty("weekly.samples");
        var p = spy(factory.create(900, 200, true)); p.setPos(35, 165);
        var live = new BilibiliLiveReportPainter(factory, api, mock(LiveDataService.class), fonts,
                new NovaBilibiliProperties(), mock(LiveRoomInfoHistory.class));
        var method = BilibiliLiveReportPainter.class.getDeclaredMethod("drawCard", CommonPainter.class,
                BilibiliLiveReportPainter.Card.class, int.class, int.class);
        method.setAccessible(true);
        method.invoke(live, p, new BilibiliLiveReportPainter.Card("1,234.50 元", "流水 · 5 人"), 35, 35);
        verify(p).drawRoundedRectangle(35, 35, 264, 112, 16, new Color(246, 247, 249));
        var texts = org.mockito.ArgumentCaptor.forClass(List.class);
        var points = org.mockito.ArgumentCaptor.forClass(Point.class);
        verify(p, times(2)).drawTextWithStyle(texts.capture(), points.capture());
        assertEquals(List.of(51, 103), points.getAllValues().stream().map(point -> point.y).toList());
        assertEquals(34, ((org.frostnova.nova.core.model.TextWithStyle)texts.getAllValues().get(0).get(0)).getSize());
        assertEquals(22, ((org.frostnova.nova.core.model.TextWithStyle)texts.getAllValues().get(1).get(0)).getSize());
        if (output == null) return;
        p.createSolidRoundedRectangleBackground(Color.WHITE, 25);
        byte[] bytes = Base64.getDecoder().decode(p.base64().orElseThrow());
        Path path = Path.of(output).resolve("live-card-before.png");
        if (Files.exists(path)) {
            var before = ImageIO.read(path.toFile()); var after = ImageIO.read(new java.io.ByteArrayInputStream(bytes));
            assertArrayEquals(before.getRGB(0, 0, before.getWidth(), before.getHeight(), null, 0, before.getWidth()), after.getRGB(0, 0, after.getWidth(), after.getHeight(), null, 0, after.getWidth()));
            System.out.println("LIVE_CARD_PIXELS: identical");
        } else Files.write(path, bytes);
    }
}
