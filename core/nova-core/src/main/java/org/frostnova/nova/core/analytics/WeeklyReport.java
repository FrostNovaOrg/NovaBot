package org.frostnova.nova.core.analytics;

import org.frostnova.nova.core.model.LiveSession;
import org.frostnova.nova.core.model.StreamerSnapshot;

import java.time.DayOfWeek;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/** 上一个完整统计周的内容；只保留汇总数，不保留观众名单。 */
public final class WeeklyReport {
    public record Day(String date, int sessions, Map<String, Number> metrics) {}
    public record Data(String streamerName, LocalDate firstDay, LocalDate lastDay, Map<String, Number> metrics,
                       StreamerInsights.Fans fans, StreamerInsights.Composition composition,
                       StreamerInsights.Coverage coverage, List<Day> daily, String chartMetric,
                       Map<String, StreamerInsights.Comparison> comparisons, String comparisonNote,
                       List<Session> sessionRows, int hiddenSessions, StreamerCalendar.Result calendar) {
        public Data(String streamerName, LocalDate firstDay, LocalDate lastDay, Map<String, Number> metrics,
                    StreamerInsights.Fans fans, StreamerInsights.Composition composition,
                    StreamerInsights.Coverage coverage, List<Day> daily, String chartMetric,
                    Map<String, StreamerInsights.Comparison> comparisons, String comparisonNote,
                    List<Session> sessionRows, int hiddenSessions) {
            this(streamerName, firstDay, lastDay, metrics, fans, composition, coverage, daily, chartMetric,
                    comparisons, comparisonNote, sessionRows, hiddenSessions,
                    new StreamerCalendar().calculate(List.of(), List.of(), List.of(), List.of(), lastDay, 35,
                            Clock.fixed(StatisticsDay.startOf(lastDay.plusDays(1)), StatisticsDay.ZONE)));
        }
        public Data withCalendar(StreamerCalendar.Result value) {
            return new Data(streamerName, firstDay, lastDay, metrics, fans, composition, coverage, daily, chartMetric,
                    comparisons, comparisonNote, sessionRows, hiddenSessions, value);
        }
        public Data(String streamerName, LocalDate firstDay, LocalDate lastDay, Map<String, Number> metrics,
                    StreamerInsights.Fans fans, StreamerInsights.Composition composition,
                    StreamerInsights.Coverage coverage, List<Day> daily, String chartMetric) {
            this(streamerName, firstDay, lastDay, metrics, fans, composition, coverage, daily, chartMetric,
                    Map.of(), null, List.of(), 0);
        }
    }
    public record Card(String label, String value, String note, Double changeRatio) {
        public Card(String label, String value, String note) { this(label, value, note, null); }
    }
    /** 场次仅保留展示字段，不携带名单或隐藏金额。 */
    public record Session(String startedAt, String title, String details, String badge) {}
    public record Preview(Data data, String summary, String text, byte[] image) {}

    public static StreamerInsights.Range previousWeek(Instant now) {
        LocalDate monday = StatisticsDay.dateOf(now).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return new StreamerInsights.Range(StatisticsDay.startOf(monday.minusWeeks(1)), StatisticsDay.startOf(monday));
    }

    public static Data calculate(String streamerName, Collection<LiveSession> sessions, Collection<StreamerSnapshot> snapshots,
                                 Instant now, LiveMetricCatalog.Insights rules, boolean showRevenue) {
        var range = previousWeek(now);
        var comparison = new StreamerInsights().comparePeriods(sessions, snapshots, range.from(), range.to(),
                range.from().minusSeconds(7 * 86400), range.to().minusSeconds(7 * 86400), rules);
        var result = comparison.current();
        Map<String, StreamerInsights.Comparison> comparisons = new LinkedHashMap<>(comparison.comparisons());
        if (!showRevenue || !result.metricsKnown()) comparisons.remove("revenue");
        Map<String, Number> metrics = new LinkedHashMap<>();
        metrics.put("sessions", result.sessions());
        metrics.put("durationSeconds", result.durationSeconds());
        put(metrics, "messages", result.messages());
        put(metrics, "audience", result.audience());
        put(metrics, "interactionAudience", result.interactionAudience());
        if (showRevenue) put(metrics, "revenue", result.revenue());
        List<Day> daily = result.daily().stream().map(d -> {
            Map<String, Number> values = new LinkedHashMap<>();
            put(values, "messages", d.messages());
            if (!result.metricsKnown()) values.put("durationSeconds", d.durationSeconds());
            if (showRevenue) put(values, "revenue", d.revenue());
            return new Day(d.date(), d.sessions(), Map.copyOf(values));
        }).toList();
        var rows = sessions.stream().filter(s -> s.startTime() >= range.from().toEpochMilli()
                        && s.startTime() < range.to().toEpochMilli())
                .sorted(Comparator.comparingLong(LiveSession::startTime).reversed()).limit(7)
                .map(s -> session(s, rules, showRevenue)).toList();
        return new Data(streamerName == null ? "未知主播" : streamerName,
                StatisticsDay.dateOf(range.from()), StatisticsDay.dateOf(range.to()).minusDays(1),
                Map.copyOf(metrics), result.fans(), result.composition(), result.coverage(), daily,
                result.metricsKnown() ? (showRevenue ? "revenue" : "messages") : "durationSeconds",
                Map.copyOf(comparisons), comparison.comparisonNote(), rows, Math.max(0, result.sessions() - rows.size()),
                new StreamerCalendar().calculate(sessions, List.of(), List.of(), List.of(),
                        StatisticsDay.dateOf(range.to()).minusDays(1), 35, Clock.fixed(now, StatisticsDay.ZONE)));
    }

    private static Session session(LiveSession s, LiveMetricCatalog.Insights rules, boolean showRevenue) {
        var at = Instant.ofEpochMilli(s.startTime()).atZone(StatisticsDay.ZONE);
        String started = at.format(DateTimeFormatter.ofPattern("MM/dd HH:mm", Locale.ROOT));
        String title = s.titles() == null || s.titles().isEmpty() ? null : s.titles().get(s.titles().size() - 1).title();
        if (title == null || title.isBlank()) title = "未记录标题";
        // Keep titles on one line in both the image and text fallback.
        title = title.replaceAll("[\\p{Cc}\\p{Zl}\\p{Zp}]", " ");
        Double revenue = rules == null || rules.revenueKeys().isEmpty() || s.metrics() == null || s.metrics().isEmpty()
                ? null : rules.revenue(s.metrics());
        Number messages = rules == null || rules.messageKey() == null || s.metrics() == null
                ? null : s.metrics().get(rules.messageKey());
        long minutes = Math.round(s.durationSeconds() / 60.0);
        String details = "时长 " + (minutes >= 60 ? minutes / 60 + " 时 " + minutes % 60 + " 分" : minutes + " 分")
                + (showRevenue && rules != null ? " · 流水 " + (revenue == null ? "—" : number(revenue, " 元", true)) : "")
                + " · 弹幕 " + (messages == null ? "—" : number(messages, " 条", false))
                + (s.interrupted() ? " · " + s.endReason().getDescription() : "");
        long gap = Math.max(s.maintenanceGapSeconds(), s.roomOutageSeconds());
        return new Session(started, title, details, gap > 0 ? "缺 " + gap(gap) : "完整");
    }

    private static String gap(long seconds) {
        var parts = new ArrayList<String>();
        if (seconds / 3600 > 0) parts.add(seconds / 3600 + " 时");
        if (seconds % 3600 / 60 > 0) parts.add(seconds % 3600 / 60 + " 分");
        if (seconds % 60 > 0) parts.add(seconds % 60 + " 秒");
        return String.join(" ", parts);
    }

    public static Preview preview(Data data, Supplier<byte[]> paint) {
        byte[] image = null;
        if (data.metrics().get("sessions").intValue() > 0) {
            try {
                image = paint.get();
            } catch (Exception e) {
                org.slf4j.LoggerFactory.getLogger(WeeklyReport.class).warn("运营周报绘图失败，改用文字版", e);
            }
        }
        return new Preview(data, summary(data), text(data), image);
    }

    private static void put(Map<String, Number> values, String name, Number value) {
        if (value != null) values.put(name, value);
    }

    public static String period(Data data) {
        LocalDate a = data.firstDay(), b = data.lastDay();
        return a.getMonthValue() + " 月 " + a.getDayOfMonth() + " 日至 "
                + (a.getMonth() == b.getMonth() && a.getYear() == b.getYear() ? "" : b.getMonthValue() + " 月 ")
                + b.getDayOfMonth() + " 日";
    }

    public static String duration(long seconds) {
        return seconds / 3600 + " 小时 " + seconds % 3600 / 60 + " 分";
    }

    private static String number(Number n, String unit, boolean money) {
        return n == null ? "暂无数据" : String.format(Locale.ROOT, money ? "%,.2f" : "%,d",
                money ? n.doubleValue() : (Object) n.longValue()) + unit;
    }

    public static List<Card> cards(Data d) {
        var m = d.metrics();
        boolean revenue = d.chartMetric().equals("revenue");
        String partial = d.fans() == null ? "" : d.fans().partial() ? "从 " + Instant.ofEpochMilli(d.fans().fromAt())
                .atZone(StatisticsDay.ZONE).format(DateTimeFormatter.ofPattern("M 月 d 日 HH:mm")) + " 起算" : "按区间边界有效快照计算";
        return List.of(comparedCard(d, "durationSeconds", "直播时长", duration(m.get("durationSeconds").longValue())),
                revenue ? comparedCard(d, "revenue", "流水", number(m.get("revenue"), " 元", true))
                        : new Card("场次", number(m.get("sessions"), " 场", false), ""),
                comparedCard(d, "messages", "弹幕", number(m.get("messages"), " 条", false)),
                comparedCard(d, "audience", "观众", number(m.get("audience"), " 人", false)),
                comparedCard(d, "interactionAudience", "互动观众", number(m.get("interactionAudience"), " 人", false)),
                new Card("粉丝净增", number(d.fans() == null ? null : d.fans().change(), " 人", false), partial));
    }

    private static Card comparedCard(Data d, String key, String label, String value) {
        var c = d.comparisons().get(key);
        Double ratio = c == null ? null : c.changeRatio();
        return new Card(label, value, "较上周" + (ratio == null ? " —" : change(ratio).equals("持平") ? "持平" : " " + change(ratio)), ratio);
    }

    public static String change(double ratio) {
        if (Math.abs(ratio) < .005) return "持平";
        return (ratio > 0 ? "+" : "−") + Math.round(Math.abs(ratio) * 100) + "%";
    }

    public static String comparisonExplanation(Data d) {
        return comparisonExplanation(d, List.of("durationSeconds", "revenue", "messages", "audience", "interactionAudience"));
    }

    public static String comparisonExplanation(Data d, List<String> keys) {
        if (keys.isEmpty()) return "";
        var reasons = new java.util.LinkedHashSet<String>();
        for (String key : keys) {
            var c = d.comparisons().get(key);
            if (c != null && c.reason() != null) reasons.add(c.reason().replace("本期", "本周").replace("上期", "上周"));
        }
        String text = reasons.isEmpty() ? "" : "较上周：" + String.join("，", reasons) + "，不比较。";
        return text + (d.comparisonNote() == null ? "" : d.comparisonNote() + "。");
    }

    public static String activity(Data d) {
        return "本周开播 " + d.daily().stream().filter(day -> day.sessions() > 0).count() + " 天、共 "
                + d.metrics().get("sessions") + " 场 · 统计日 06:00–次日 06:00";
    }

    public static String summary(Data d) {
        String lead = d.streamerName() + " 上周（" + period(d) + "）";
        if (d.metrics().get("sessions").intValue() == 0) return lead + "没有记录到开播。";
        var c = cards(d);
        String activity = lead + "共直播 " + number(d.metrics().get("sessions"), " 场", false) + "、" + c.get(0).value();
        String totals = activity + (d.chartMetric().equals("durationSeconds") ? "。" : ""
                + (d.chartMetric().equals("revenue") ? "，流水 " + c.get(1).value() : "")
                + "，弹幕 " + c.get(2).value() + "，观众 " + c.get(3).value() + "。");
        var changes = new ArrayList<String>();
        for (var card : c) if (card.changeRatio() != null)
            changes.add((card.label().equals("直播时长") ? "时长" : card.label()) + " " + change(card.changeRatio()));
        return totals + (changes.isEmpty() ? "" : "比前一周：" + String.join("，", changes) + "。");
    }

    public static String composition(Data d) {
        return d.composition() == null ? "首次记录与回访：暂无数据" : "本周首次记录 "
                + number(d.composition().firstRecorded(), " 人", false) + " · 回访 "
                + number(d.composition().returning(), " 人", false);
    }

    public static String coverage(Data d) {
        var c = d.coverage();
        if (c.anonymous() + c.unknown() + c.missing() == 0) return "";
        var parts = new java.util.ArrayList<String>();
        if (c.complete() != 0) parts.add("完整 " + c.complete() + " 场");
        if (c.anonymous() != 0) parts.add("含无法识别的观众 " + c.anonymous() + " 场（这几场的人数偏少）");
        if (c.unknown() != 0) parts.add("完整性未知 " + c.unknown() + " 场（升级到 6.0 之前开播的场次未记录此项）");
        if (c.missing() != 0) parts.add("没有名单 " + c.missing() + " 场");
        return "名单：" + String.join("；", parts);
    }

    public record CalendarTotals(long recorded, long off, long unknown) {}

    public static CalendarTotals calendarTotals(Data d) {
        long recorded = d.calendar().items().stream().filter(day -> "recorded".equals(day.status())).count();
        long off = d.calendar().items().stream().filter(day -> "off".equals(day.status())).count();
        return new CalendarTotals(recorded, off, d.calendar().items().size() - recorded - off);
    }

    public static String calendarSummary(Data d) {
        var totals = calendarTotals(d);
        return "开播日历（近 5 周）：有直播 " + totals.recorded() + " 天、确认没播 " + totals.off()
                + " 天、说不准 " + totals.unknown() + " 天";
    }

    public static String text(Data d) {
        if (d.metrics().get("sessions").intValue() == 0) return summary(d);
        var lines = new java.util.ArrayList<String>();
        lines.add(d.streamerName() + " 运营周报（" + period(d) + "）");
        lines.add(activity(d));
        for (var c : cards(d)) lines.add(c.label() + "：" + c.value() + (c.note().isEmpty() ? "" : "（" + c.note() + "）"));
        if (!comparisonExplanation(d).isEmpty()) lines.add(comparisonExplanation(d));
        lines.add(composition(d));
        lines.add(calendarSummary(d));
        lines.add("本周场次 · " + d.metrics().get("sessions") + " 场");
        for (var s : d.sessionRows()) lines.add(s.startedAt() + " " + s.title() + " · " + s.details() + " · " + s.badge());
        if (d.hiddenSessions() > 0) lines.add("另有 " + d.hiddenSessions() + " 场未列出");
        if (!coverage(d).isEmpty()) lines.add(coverage(d));
        lines.add("数字只算记录到的数据，采集缺口不补估。");
        return String.join("\n", lines);
    }
}
