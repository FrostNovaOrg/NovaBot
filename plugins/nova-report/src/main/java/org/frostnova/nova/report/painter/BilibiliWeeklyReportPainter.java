package org.frostnova.nova.report.painter;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.Ticker;
import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.analytics.WeeklyReport;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import org.frostnova.nova.core.model.TextWithStyle;
import org.frostnova.nova.core.service.WeeklyReportRenderer;
import org.frostnova.nova.core.service.WeeklyReportLayout;
import org.frostnova.nova.report.factory.NovaCommonPainterFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.awt.Font;
import java.awt.Point;
import java.awt.image.BufferedImage;
import java.time.Duration;
import java.time.LocalDate;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Base64;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.frostnova.nova.report.painter.ReportSharedStyle.*;

/** 仅绘制汇总内容的运营周报。 */
@Component
public class BilibiliWeeklyReportPainter implements WeeklyReportRenderer {
    private final NovaCommonPainterFactory factory;
    private final BilibiliApiUtil api;
    private final NovaBilibiliProperties properties;
    private final Logo logo = new Logo();
    private final ReportPalettes palettes;
    private final Cache<Long, Optional<BufferedImage>> avatars;

    public BilibiliWeeklyReportPainter(NovaCommonPainterFactory factory, BilibiliApiUtil api,
                                      NovaBilibiliProperties properties) {
        this(factory, api, properties, ReportPalettes.factory());
    }

    @Autowired
    public BilibiliWeeklyReportPainter(NovaCommonPainterFactory factory, BilibiliApiUtil api,
                                      NovaBilibiliProperties properties, ReportPalettes palettes) {
        this(factory, api, properties, palettes, Ticker.systemTicker());
    }

    BilibiliWeeklyReportPainter(NovaCommonPainterFactory factory, BilibiliApiUtil api,
                               NovaBilibiliProperties properties, ReportPalettes palettes, Ticker ticker) {
        this.factory = factory;
        this.api = api;
        this.properties = properties;
        this.palettes = palettes;
        // 预览和两种发送共用这个绘图器；失败也缓存，避免编辑时反复等待接口重试。
        this.avatars = Caffeine.newBuilder().maximumSize(256).ticker(ticker)
                .expireAfter(new Expiry<Long, Optional<BufferedImage>>() {
                    @Override public long expireAfterCreate(Long uid, Optional<BufferedImage> image, long now) {
                        return (image.isPresent() ? Duration.ofHours(1) : Duration.ofMinutes(1)).toNanos();
                    }
                    @Override public long expireAfterUpdate(Long uid, Optional<BufferedImage> image, long now, long remaining) {
                        return expireAfterCreate(uid, image, now);
                    }
                    @Override public long expireAfterRead(Long uid, Optional<BufferedImage> image, long now, long remaining) {
                        return remaining;
                    }
                }).build();
    }

    @Override public String platform() { return "bilibili"; }

    @Override public byte[] render(LiveStreamerInfo streamer, WeeklyReport.Data data) {
        return render(streamer, data, ReportPalette.DEFAULT);
    }

    /** 核心按通道解析出的预设 id；认不出时照默认模板的配色。 */
    @Override public byte[] render(LiveStreamerInfo streamer, WeeklyReport.Data data, String palette) {
        return render(streamer, data, palettes.forId(palette).palette());
    }

    /** 认不出的 id 照默认模板的那一套画，答的就是那一套。 */
    @Override public String paletteId(String palette) {
        return palettes.forId(palette).id();
    }

    @Override public byte[] render(LiveStreamerInfo streamer, WeeklyReport.Data data, String palette, WeeklyReportLayout layout) {
        return render(streamer, data, palettes.forId(palette).palette(), layout);
    }

    public byte[] render(LiveStreamerInfo streamer, WeeklyReport.Data data, ReportPalette palette) {
        return render(streamer, data, palette, WeeklyReportLayout.of(null));
    }

    public byte[] render(LiveStreamerInfo streamer, WeeklyReport.Data data, ReportPalette palette, WeeklyReportLayout layout) {
        CommonPainter p = factory.create(WIDTH, 1200, true, palette);
        p.setPos(MARGIN, MARGIN);
        drawSimpleHeader(p, streamer.getUname(), weeklyAvatar(streamer), "运营周报");
        p.drawSection(date(data.firstDay()) + "（周一）至 " + date(data.lastDay()) + "（周日）", p.palette().text());
        p.drawTip(WeeklyReport.activity(data), p.palette().tip());
        p.movePos(0, 16);
        int top = p.getY();
        var allCards = WeeklyReport.cards(data);
        var cards = layout.visibleCards().stream().map(key -> allCards.get(WeeklyReportLayout.CARD_KEYS.indexOf(key))).toList();
        for (int i = 0; i < cards.size(); i++) {
            var c = cards.get(i);
            int x = MARGIN + i % CARD_COLUMNS * (CARD_WIDTH + CARD_GAP);
            int y = top + i / CARD_COLUMNS * (WEEKLY_CARD_HEIGHT + CARD_GAP);
            Color color = c.changeRatio() == null || Math.abs(c.changeRatio()) < .005 ? p.palette().tip()
                    : c.changeRatio() > 0 ? p.palette().up() : p.palette().down();
            drawCard(p, c.value(), c.label(), c.note(), color, x, y);
        }
        int rows = (cards.size() + CARD_COLUMNS - 1) / CARD_COLUMNS;
        p.setPos(MARGIN, top + rows * (WEEKLY_CARD_HEIGHT + CARD_GAP) + (rows > 0 ? 8 : 0));
        var metrics = List.of("durationSeconds", data.chartMetric().equals("revenue") ? "revenue" : "sessions",
                "messages", "audience", "interactionAudience", "fans");
        var comparable = layout.visibleCards().stream().map(key -> metrics.get(WeeklyReportLayout.CARD_KEYS.indexOf(key)))
                .filter(key -> !key.equals("sessions") && !key.equals("fans")).toList();
        String explanation = WeeklyReport.comparisonExplanation(data, comparable);
        if (!explanation.isEmpty()) smallLine(p, explanation);
        if (layout.enabled("days")) drawDays(p, data);
        if (layout.enabled("audience")) drawAudience(p, data);
        if (layout.enabled("calendar")) drawCalendar(p, data);
        if (layout.enabled("sessions")) drawSessions(p, data);
        if (layout.audienceCoverage() && !WeeklyReport.coverage(data).isEmpty()) {
            p.drawTextWithStyle(List.of(new TextWithStyle(WeeklyReport.coverage(data), 20, p.palette().tip(), Font.PLAIN)), null, true, MARGIN);
        }
        smallLine(p, "数字只算记录到的数据，采集缺口不补估。");
        p.movePos(0, 20);
        logo.draw(p, properties);
        p.drawCopyright(MARGIN);
        p.movePos(0, 10);
        p.createSolidRoundedRectangleBackground(p.palette().background(), CANVAS_RADIUS);
        return Base64.getDecoder().decode(p.base64().orElseThrow(() -> new IllegalStateException("PNG 编码失败")));
    }

    private BufferedImage weeklyAvatar(LiveStreamerInfo streamer) {
        if (streamer.getUid() == null) return faceImage(api, streamer);
        return avatars.get(streamer.getUid(), uid -> Optional.ofNullable(faceImage(api, streamer))).orElse(null);
    }

    private static String date(LocalDate d) { return d.getMonthValue() + " 月 " + d.getDayOfMonth() + " 日"; }

    private static void smallLine(CommonPainter p, String text) {
        p.drawTextWithStyle(List.of(new TextWithStyle(text, 20, p.palette().tip(), Font.PLAIN)), null, true, MARGIN);
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
        p.drawSection(duration ? "每天直播时长（小时）" : money ? "每天流水（元）" : "每天弹幕（条）", p.palette().text());
        int top = p.getY() + 32;
        int base = top + 150;
        int slot = CONTENT_WIDTH / 7;
        double max = data.daily().stream().filter(d -> d.sessions() > 0)
                .map(d -> d.metrics().get(metric)).filter(java.util.Objects::nonNull)
                .mapToDouble(Number::doubleValue).filter(Double::isFinite).max().orElse(0);
        p.drawRectangle(MARGIN, base, CONTENT_WIDTH, 2, p.palette().card());
        var numbers = new DecimalFormat("#,##0.#", DecimalFormatSymbols.getInstance(Locale.ROOT));
        numbers.setRoundingMode(RoundingMode.HALF_UP);
        var segment = new ArrayList<Point>();
        for (int i = 0; i < 7; i++) {
            var d = data.daily().get(i);
            Number value = d.metrics().get(metric);
            int x = MARGIN + i * slot + slot / 2;
            boolean known = d.sessions() > 0 && value != null && Double.isFinite(value.doubleValue());
            int height = known && max > 0 ? (int) Math.round(value.doubleValue() / max * 150) : 0;
            if (known) {
                segment.add(new Point(x, base - height));
            } else {
                if (segment.size() > 1) p.drawPolyline(List.copyOf(segment), p.palette().accent(), 3);
                segment.clear();
            }
        }
        if (segment.size() > 1) p.drawPolyline(List.copyOf(segment), p.palette().accent(), 3);
        for (int i = 0; i < 7; i++) {
            var d = data.daily().get(i);
            Number value = d.metrics().get(metric);
            int x = MARGIN + i * slot + slot / 2;
            boolean known = d.sessions() > 0 && value != null && Double.isFinite(value.doubleValue());
            if (known) {
                int height = max > 0 ? (int) Math.round(value.doubleValue() / max * 150) : 0;
                p.drawRoundedRectangle(x - 5, base - height - 5, 10, 10, 10, p.palette().accent());
                double v = duration ? value.doubleValue() / 3600 : value.doubleValue();
                String label = numbers.format(new BigDecimal(Double.toString(v)).setScale(1, RoundingMode.HALF_UP));
                centered(p, label, 18, p.palette().text(), x, base - height - 32);
            }
            centered(p, List.of("周一", "周二", "周三", "周四", "周五", "周六", "周日").get(i), 20, p.palette().text(), x, base + 16);
            LocalDate day = LocalDate.parse(d.date());
            centered(p, day.getMonthValue() + "/" + day.getDayOfMonth(), 16, p.palette().tip(), x, base + 46);
            if (!known) {
                boolean confirmedOff = data.calendar().items().stream()
                        .anyMatch(dayStatus -> dayStatus.date().equals(d.date()) && "off".equals(dayStatus.status()));
                String label = d.sessions() > 0 ? "无数据" : confirmedOff ? "未播" : "说不准";
                centered(p, label, 16, p.palette().tip(), x, base + 72);
            }
        }
        p.setPos(MARGIN, base + 116);
    }

    void drawAudience(CommonPainter p, WeeklyReport.Data data) {
        p.drawSection("本周观众", p.palette().text());
        int top = p.getY() + 14;
        int diameter = 210;
        int ringX = MARGIN + 16;
        int center = ringX + diameter / 2;
        Color returningColor = p.palette().returningAudience();
        p.drawArc(ringX, top + 12, diameter, 90, -360, 22, p.palette().card());
        Number audience = data.metrics().get("audience");
        var c = data.composition();
        if (c != null && c.firstRecorded() + c.returning() > 0) {
            double first = c.firstRecorded() * 360.0 / (c.firstRecorded() + c.returning());
            p.drawArc(ringX, top + 12, diameter, 90, -first, 22, p.palette().accent());
            p.drawArc(ringX, top + 12, diameter, 90 - first, -(360 - first), 22, returningColor);
        }
        centered(p, "本周去重观众", 20, p.palette().tip(), center, top + 75);
        centered(p, audience == null ? "暂无数据" : String.format(Locale.ROOT, "%,d 人", audience.longValue()),
                32, p.palette().text(), center, top + 113);
        int right = MARGIN + 280;
        int blockWidth = (CONTENT_WIDTH - 296) / 2;
        for (int i = 0; i < 2; i++) {
            int x = right + i * (blockWidth + 16);
            Color color = i == 0 ? p.palette().accent() : returningColor;
            p.drawRoundedRectangle(x, top, blockWidth, 118, CARD_RADIUS, p.palette().card());
            text(p, i == 0 ? "本周首次记录" : "历史回访", 21, color, x + 14, top + 14, blockWidth - 28);
            Integer n = c == null ? null : i == 0 ? c.firstRecorded() : c.returning();
            text(p, n == null ? "暂无数据" : String.format(Locale.ROOT, "%,d 人 · 占 %s", n,
                    percent(n, c.firstRecorded() + c.returning())), 20, p.palette().text(), x + 14, top + 64, blockWidth - 28);
        }
        Number interaction = data.metrics().get("interactionAudience");
        String active = "本周互动观众 " + (interaction == null ? "暂无数据" : String.format(Locale.ROOT, "%,d 人", interaction.longValue()))
                + " · 占观众 " + (interaction == null || audience == null ? "暂无数据" : percent(interaction.doubleValue(), audience.doubleValue()));
        text(p, active, 21, p.palette().text(), right, top + 146, CONTENT_WIDTH - 280);
        int barWidth = CONTENT_WIDTH - 280;
        p.drawRoundedRectangle(right, top + 194, barWidth, 12, 12, p.palette().card());
        if (interaction != null && audience != null && audience.doubleValue() > 0) {
            int fill = (int) Math.round(barWidth * Math.min(1, interaction.doubleValue() / audience.doubleValue()));
            if (fill > 0) p.drawRoundedRectangle(right, top + 194, fill, 12, 12, p.palette().accent());
        }
        p.setPos(MARGIN, top + 254);
        smallLine(p, "首次记录指系统里第一次记录到，不等于第一次观看直播。");
    }

    private static String percent(double value, double total) {
        return String.format(Locale.ROOT, "%.0f%%", total > 0 ? value / total * 100 : 0);
    }

    void drawCalendar(CommonPainter p, WeeklyReport.Data data) {
        p.drawSection("开播日历 · 近 5 周", p.palette().text());
        LocalDate first = data.lastDay().minusDays(34);
        p.drawTip(shortDate(first) + " – " + shortDate(data.lastDay()), p.palette().tip());
        int top = p.getY() + 14;
        int gap = 8;
        int height = 64;
        Color recorded = p.palette().calendarRecorded();
        Color off = p.palette().calendarOff();
        Color unknown = p.palette().calendarUnknown();
        Color border = p.palette().calendarBorder();
        for (int column = 0; column < 7; column++) {
            int x = MARGIN + column * (CONTENT_WIDTH + gap) / 7;
            int width = (column + 1) * (CONTENT_WIDTH + gap) / 7 - column * (CONTENT_WIDTH + gap) / 7 - gap;
            centered(p, List.of("周一", "周二", "周三", "周四", "周五", "周六", "周日").get(column),
                    22, p.palette().text(), x + width / 2, top);
        }
        int gridTop = top + 38;
        for (int i = 0; i < data.calendar().items().size(); i++) {
            var day = data.calendar().items().get(i);
            int column = i % 7;
            int x = MARGIN + column * (CONTENT_WIDTH + gap) / 7;
            int width = (column + 1) * (CONTENT_WIDTH + gap) / 7 - column * (CONTENT_WIDTH + gap) / 7 - gap;
            int y = gridTop + i / 7 * (height + gap);
            Color fill = switch (day.status()) { case "recorded" -> recorded; case "off" -> off; default -> unknown; };
            boolean reportWeek = !LocalDate.parse(day.date()).isBefore(data.firstDay());
            int inset = reportWeek ? 2 : 1;
            p.drawRoundedRectangle(x, y, width, height, 8, reportWeek ? p.palette().text() : border);
            p.drawRoundedRectangle(x + inset, y + inset, width - inset * 2, height - inset * 2, 6, fill);
            centered(p, shortDate(LocalDate.parse(day.date())), 26, p.palette().text(), x + width / 2, y + 15);
        }
        int legendTop = gridTop + 5 * (height + gap) + 10;
        var totals = WeeklyReport.calendarTotals(data);
        var counts = List.of(totals.recorded(), totals.off(), totals.unknown());
        var labels = List.of("有直播", "确认没播", "说不准");
        var colors = List.of(recorded, off, unknown);
        int blockWidth = (CONTENT_WIDTH - 2 * CARD_GAP) / 3;
        for (int i = 0; i < 3; i++) {
            int x = MARGIN + i * (blockWidth + CARD_GAP);
            p.drawRoundedRectangle(x, legendTop + 3, 22, 22, 4, border);
            p.drawRoundedRectangle(x + 1, legendTop + 4, 20, 20, 3, colors.get(i));
            text(p, labels.get(i), 21, p.palette().text(), x + 32, legendTop, blockWidth - 32);
            p.drawRoundedRectangle(x, legendTop + 42, blockWidth, 60, 12, p.palette().card());
            centered(p, counts.get(i) + "天 " + labels.get(i), 23, p.palette().text(), x + blockWidth / 2, legendTop + 57);
        }
        p.setPos(MARGIN, legendTop + 122);
        smallLine(p, "说不准＝那天没录到开播，但监看不完整（停机、断线，或在监看记录开始之前），不按没播算。");
    }

    private static String shortDate(LocalDate day) { return day.getMonthValue() + "/" + day.getDayOfMonth(); }

    void drawSessions(CommonPainter p, WeeklyReport.Data data) {
        p.drawSection("本周场次 · " + data.metrics().get("sessions") + " 场", p.palette().text());
        for (var row : data.sessionRows()) {
            int y = p.getY() + 8;
            var details = detailLines(p, row.details());
            int height = 80 + details.size() * 30;
            p.drawRoundedRectangle(MARGIN, y, CONTENT_WIDTH, height, CARD_RADIUS, p.palette().card());
            text(p, row.startedAt(), 22, p.palette().tip(), MARGIN + 16, y + 14, 135);
            int badgeWidth = Math.max(80, p.getStringWidthAndHeight(new TextWithStyle(row.badge(), 18, p.palette().tip(), Font.PLAIN)).getFirst() + 24);
            int badgeX = WIDTH - MARGIN - badgeWidth - 14;
            text(p, row.title(), 24, p.palette().text(), MARGIN + 165, y + 12, badgeX - MARGIN - 185);
            p.drawRoundedRectangle(badgeX, y + 14, badgeWidth, 32, 12, p.palette().sessionBadge());
            centered(p, row.badge(), 18, p.palette().tip(), badgeX + badgeWidth / 2, y + 16);
            for (int i = 0; i < details.size(); i++)
                text(p, details.get(i), 20, p.palette().text(), MARGIN + 16, y + 64 + i * 30, CONTENT_WIDTH - 32);
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
            if (!line.isEmpty() && p.getStringWidthAndHeight(new TextWithStyle(next, 20, p.palette().text(), Font.PLAIN)).getFirst() > CONTENT_WIDTH - 32) {
                lines.add(line);
                line = field;
            } else line = next;
        }
        if (!line.isEmpty()) lines.add(line);
        return lines;
    }
}
