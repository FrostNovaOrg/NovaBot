package org.frostnova.nova.report.painter;

import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.analytics.WeeklyReport;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import org.frostnova.nova.core.model.TextWithStyle;
import org.frostnova.nova.core.service.WeeklyReportRenderer;
import org.frostnova.nova.report.factory.NovaCommonPainterFactory;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.awt.Font;
import java.awt.Point;
import java.time.LocalDate;
import java.util.Base64;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.frostnova.nova.report.painter.ReportSharedStyle.*;

/** 仅绘制汇总内容的运营周报。 */
@Component
public class BilibiliWeeklyReportPainter implements WeeklyReportRenderer {
    private final NovaCommonPainterFactory factory;
    private final BilibiliApiUtil api;
    private final NovaBilibiliProperties properties;
    private final Logo logo = new Logo();

    public BilibiliWeeklyReportPainter(NovaCommonPainterFactory factory, BilibiliApiUtil api,
                                      NovaBilibiliProperties properties) {
        this.factory = factory;
        this.api = api;
        this.properties = properties;
    }

    @Override public String platform() { return "bilibili"; }

    @Override public byte[] render(LiveStreamerInfo streamer, WeeklyReport.Data data) {
        CommonPainter p = factory.create(WIDTH, 1200, true);
        p.setPos(MARGIN, MARGIN);
        drawSimpleHeader(p, streamer.getUname(), faceImage(api, streamer), "运营周报");
        p.drawSection(date(data.firstDay()) + "（周一）至 " + date(data.lastDay()) + "（周日）", COLOR_TEXT);
        p.drawTip(WeeklyReport.activity(data), COLOR_TIP);
        p.movePos(0, 16);
        int top = p.getY();
        var cards = WeeklyReport.cards(data);
        for (int i = 0; i < cards.size(); i++) {
            var c = cards.get(i);
            int x = MARGIN + i % CARD_COLUMNS * (CARD_WIDTH + CARD_GAP);
            int y = top + i / CARD_COLUMNS * (WEEKLY_CARD_HEIGHT + CARD_GAP);
            Color color = c.changeRatio() == null || Math.abs(c.changeRatio()) < .005 ? COLOR_TIP
                    : c.changeRatio() > 0 ? COLOR_UP : COLOR_DOWN;
            drawCard(p, c.value(), c.label(), c.note(), color, x, y);
        }
        p.setPos(MARGIN, top + 2 * (WEEKLY_CARD_HEIGHT + CARD_GAP) + 8);
        if (!WeeklyReport.comparisonExplanation(data).isEmpty()) smallLine(p, WeeklyReport.comparisonExplanation(data));
        drawDays(p, data);
        drawAudience(p, data);
        drawSessions(p, data);
        if (!WeeklyReport.coverage(data).isEmpty()) {
            p.drawTextWithStyle(List.of(new TextWithStyle(WeeklyReport.coverage(data), 20, COLOR_TIP, Font.PLAIN)), null, true, MARGIN);
        }
        smallLine(p, "数字只算记录到的数据，采集缺口不补估。");
        p.movePos(0, 20);
        logo.draw(p, properties);
        p.drawCopyright(MARGIN);
        p.movePos(0, 10);
        p.createSolidRoundedRectangleBackground(Color.WHITE, CANVAS_RADIUS);
        return Base64.getDecoder().decode(p.base64().orElseThrow(() -> new IllegalStateException("PNG 编码失败")));
    }

    private static String date(LocalDate d) { return d.getMonthValue() + " 月 " + d.getDayOfMonth() + " 日"; }

    private static void smallLine(CommonPainter p, String text) {
        p.drawTextWithStyle(List.of(new TextWithStyle(text, 20, COLOR_TIP, Font.PLAIN)), null, true, MARGIN);
        p.movePos(0, 12);
    }

    private static void text(CommonPainter p, String text, int size, Color color, int x, int y, int width) {
        var t = new TextWithStyle(text, size, color, Font.PLAIN);
        t.setText(p.truncateToWidth(t, width));
        p.drawTextWithStyle(List.of(t), new Point(x, y));
    }

    private static void centered(CommonPainter p, String text, int size, Color color, int center, int y) {
        var t = new TextWithStyle(text, size, color, Font.PLAIN);
        int width = p.getStringWidthAndHeight(t).getFirst();
        p.drawTextWithStyle(List.of(t), new Point(center - width / 2, y));
    }

    void drawDays(CommonPainter p, WeeklyReport.Data data) {
        String metric = data.chartMetric();
        boolean money = metric.equals("revenue");
        boolean duration = metric.equals("durationSeconds");
        p.drawSection(duration ? "每天直播时长（小时）" : money ? "每天流水（元）" : "每天弹幕（条）", COLOR_TEXT);
        int top = p.getY() + 32;
        int base = top + 150;
        int slot = CONTENT_WIDTH / 7;
        double max = data.daily().stream().filter(d -> d.sessions() > 0)
                .map(d -> d.metrics().get(metric)).filter(java.util.Objects::nonNull)
                .mapToDouble(Number::doubleValue).max().orElse(0);
        p.drawRectangle(MARGIN, base, CONTENT_WIDTH, 2, COLOR_CARD);
        var segment = new ArrayList<Point>();
        for (int i = 0; i < 7; i++) {
            var d = data.daily().get(i);
            Number value = d.metrics().get(metric);
            int x = MARGIN + i * slot + slot / 2;
            boolean known = d.sessions() > 0 && value != null;
            int height = known && max > 0 ? (int) Math.round(value.doubleValue() / max * 150) : 0;
            if (known) {
                segment.add(new Point(x, base - height));
            } else {
                if (segment.size() > 1) p.drawPolyline(List.copyOf(segment), COLOR_NAME, 3);
                segment.clear();
            }
        }
        if (segment.size() > 1) p.drawPolyline(List.copyOf(segment), COLOR_NAME, 3);
        for (int i = 0; i < 7; i++) {
            var d = data.daily().get(i);
            Number value = d.metrics().get(metric);
            int x = MARGIN + i * slot + slot / 2;
            boolean known = d.sessions() > 0 && value != null;
            if (known) {
                int height = max > 0 ? (int) Math.round(value.doubleValue() / max * 150) : 0;
                p.drawRoundedRectangle(x - 5, base - height - 5, 10, 10, 10, COLOR_NAME);
                double v = duration ? value.doubleValue() / 3600 : value.doubleValue();
                String label = Math.abs(v) >= 10000 ? String.format(Locale.ROOT, "%.1f万", v / 10000)
                        : String.format(Locale.ROOT, money || duration ? "%,.2f" : "%,d", money || duration ? v : (Object) value.longValue());
                centered(p, label, 18, COLOR_TEXT, x, base - height - 32);
            }
            centered(p, List.of("周一", "周二", "周三", "周四", "周五", "周六", "周日").get(i), 20, COLOR_TEXT, x, base + 16);
            LocalDate day = LocalDate.parse(d.date());
            centered(p, day.getMonthValue() + "/" + day.getDayOfMonth(), 16, COLOR_TIP, x, base + 46);
            if (!known) centered(p, "未播", 16, COLOR_TIP, x, base + 72);
        }
        p.setPos(MARGIN, base + 116);
    }

    void drawAudience(CommonPainter p, WeeklyReport.Data data) {
        p.drawSection("本周观众", COLOR_TEXT);
        int top = p.getY() + 14;
        int diameter = 210;
        int ringX = MARGIN + 16;
        int center = ringX + diameter / 2;
        Color returningColor = new Color(0x69A9CC);
        p.drawArc(ringX, top + 12, diameter, 90, -360, 22, COLOR_CARD);
        Number audience = data.metrics().get("audience");
        var c = data.composition();
        if (c != null && c.firstRecorded() + c.returning() > 0) {
            double first = c.firstRecorded() * 360.0 / (c.firstRecorded() + c.returning());
            p.drawArc(ringX, top + 12, diameter, 90, -first, 22, COLOR_NAME);
            p.drawArc(ringX, top + 12, diameter, 90 - first, -(360 - first), 22, returningColor);
        }
        centered(p, "本周去重观众", 20, COLOR_TIP, center, top + 75);
        centered(p, audience == null ? "暂无数据" : String.format(Locale.ROOT, "%,d 人", audience.longValue()),
                32, COLOR_TEXT, center, top + 113);
        int right = MARGIN + 280;
        int blockWidth = (CONTENT_WIDTH - 296) / 2;
        for (int i = 0; i < 2; i++) {
            int x = right + i * (blockWidth + 16);
            Color color = i == 0 ? COLOR_NAME : returningColor;
            p.drawRoundedRectangle(x, top, blockWidth, 118, CARD_RADIUS, COLOR_CARD);
            text(p, i == 0 ? "本周首次记录" : "历史回访", 21, color, x + 14, top + 14, blockWidth - 28);
            Integer n = c == null ? null : i == 0 ? c.firstRecorded() : c.returning();
            text(p, n == null ? "暂无数据" : String.format(Locale.ROOT, "%,d 人 · 占 %s", n,
                    percent(n, c.firstRecorded() + c.returning())), 20, COLOR_TEXT, x + 14, top + 64, blockWidth - 28);
        }
        Number interaction = data.metrics().get("interactionAudience");
        String active = "本周互动观众 " + (interaction == null ? "暂无数据" : String.format(Locale.ROOT, "%,d 人", interaction.longValue()))
                + " · 占观众 " + (interaction == null || audience == null ? "暂无数据" : percent(interaction.doubleValue(), audience.doubleValue()));
        text(p, active, 21, COLOR_TEXT, right, top + 146, CONTENT_WIDTH - 280);
        int barWidth = CONTENT_WIDTH - 280;
        p.drawRoundedRectangle(right, top + 194, barWidth, 12, 12, COLOR_CARD);
        if (interaction != null && audience != null && audience.doubleValue() > 0) {
            int fill = (int) Math.round(barWidth * Math.min(1, interaction.doubleValue() / audience.doubleValue()));
            if (fill > 0) p.drawRoundedRectangle(right, top + 194, fill, 12, 12, COLOR_NAME);
        }
        p.setPos(MARGIN, top + 254);
        smallLine(p, "首次记录指系统里第一次记录到，不等于第一次观看直播。");
    }

    private static String percent(double value, double total) {
        return String.format(Locale.ROOT, "%.0f%%", total > 0 ? value / total * 100 : 0);
    }

    void drawSessions(CommonPainter p, WeeklyReport.Data data) {
        p.drawSection("本周场次 · " + data.metrics().get("sessions") + " 场", COLOR_TEXT);
        for (var row : data.sessionRows()) {
            int y = p.getY() + 8;
            var details = detailLines(p, row.details());
            int height = 80 + details.size() * 30;
            p.drawRoundedRectangle(MARGIN, y, CONTENT_WIDTH, height, CARD_RADIUS, COLOR_CARD);
            text(p, row.startedAt(), 22, COLOR_TIP, MARGIN + 16, y + 14, 135);
            int badgeWidth = Math.max(80, p.getStringWidthAndHeight(new TextWithStyle(row.badge(), 18, COLOR_TIP, Font.PLAIN)).getFirst() + 24);
            int badgeX = WIDTH - MARGIN - badgeWidth - 14;
            text(p, row.title(), 24, COLOR_TEXT, MARGIN + 165, y + 12, badgeX - MARGIN - 185);
            p.drawRoundedRectangle(badgeX, y + 14, badgeWidth, 32, 12, Color.WHITE);
            centered(p, row.badge(), 18, COLOR_TIP, badgeX + badgeWidth / 2, y + 16);
            for (int i = 0; i < details.size(); i++)
                text(p, details.get(i), 20, COLOR_TEXT, MARGIN + 16, y + 64 + i * 30, CONTENT_WIDTH - 32);
            p.setPos(MARGIN, y + height + 14);
        }
        if (data.hiddenSessions() > 0) smallLine(p, "另有 " + data.hiddenSessions() + " 场未列出");
        p.movePos(0, 12);
    }

    private static List<String> detailLines(CommonPainter p, String details) {
        var lines = new ArrayList<String>();
        String line = "";
        for (String field : details.split(" · ")) {
            String next = line.isEmpty() ? field : line + " · " + field;
            if (!line.isEmpty() && p.getStringWidthAndHeight(new TextWithStyle(next, 20, COLOR_TEXT, Font.PLAIN)).getFirst() > CONTENT_WIDTH - 32) {
                lines.add(line);
                line = field;
            } else line = next;
        }
        if (!line.isEmpty()) lines.add(line);
        return lines;
    }
}
