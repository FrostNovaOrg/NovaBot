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
        p.drawTip("一天从早上 6 点算起", COLOR_TIP);
        p.movePos(0, 16);
        int top = p.getY();
        var cards = WeeklyReport.cards(data);
        for (int i = 0; i < cards.size(); i++) {
            var c = cards.get(i);
            int x = MARGIN + i % CARD_COLUMNS * (CARD_WIDTH + CARD_GAP);
            int y = top + i / CARD_COLUMNS * (WEEKLY_CARD_HEIGHT + CARD_GAP);
            drawCard(p, c.value(), c.label(), c.note(), x, y);
        }
        p.setPos(MARGIN, top + 2 * (WEEKLY_CARD_HEIGHT + CARD_GAP) + 8);
        drawDays(p, data);
        p.drawTextMultiLine(WeeklyReport.composition(data), COLOR_TEXT, null, MARGIN);
        if (!WeeklyReport.coverage(data).isEmpty()) {
            p.drawTextWithStyle(List.of(new TextWithStyle(WeeklyReport.coverage(data), 20, COLOR_TIP, Font.PLAIN)), null, true, MARGIN);
        }
        p.movePos(0, 20);
        logo.draw(p, properties);
        p.drawCopyright(MARGIN);
        p.movePos(0, 10);
        p.createSolidRoundedRectangleBackground(Color.WHITE, CANVAS_RADIUS);
        return Base64.getDecoder().decode(p.base64().orElseThrow(() -> new IllegalStateException("PNG 编码失败")));
    }

    private static String date(LocalDate d) { return d.getMonthValue() + " 月 " + d.getDayOfMonth() + " 日"; }

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
        for (int i = 0; i < 7; i++) {
            var d = data.daily().get(i);
            Number value = d.metrics().get(metric);
            int x = MARGIN + i * slot;
            boolean known = d.sessions() > 0 && value != null;
            int height = known && max > 0 ? (int) Math.round(value.doubleValue() / max * 150) : 0;
            if (known && height > 0) p.drawRectangle(x + 32, base - height, slot - 64, height, COLOR_NAME);
            String label = !known ? "—" : String.format(Locale.ROOT, money || duration ? "%,.2f" : "%,d",
                    duration ? value.doubleValue() / 3600 : money ? value.doubleValue() : (Object) value.longValue());
            var text = new TextWithStyle(label, 18, COLOR_TEXT, Font.PLAIN);
            text.setText(p.truncateToWidth(text, slot - 4));
            int width = p.getStringWidthAndHeight(text).getFirst();
            p.drawTextWithStyle(List.of(text), new Point(x + (slot - width) / 2, known ? base - height - 28 : base + 8));
            p.drawTip(List.of("周一", "周二", "周三", "周四", "周五", "周六", "周日").get(i),
                    COLOR_TIP, new Point(x + 30, base + 38));
        }
        p.setPos(MARGIN, base + 90);
    }
}
