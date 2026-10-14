package org.frostnova.nova.core.analytics;

import org.frostnova.nova.core.model.LiveSession;
import org.frostnova.nova.core.model.StreamerSnapshot;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/** 上一个完整统计周的内容；只保留汇总数，不保留观众名单。 */
public final class WeeklyReport {
    public record Day(String date, int sessions, Map<String, Number> metrics) {}
    public record Data(LocalDate firstDay, LocalDate lastDay, Map<String, Number> metrics,
                       StreamerInsights.Fans fans, StreamerInsights.Composition composition,
                       StreamerInsights.Coverage coverage, List<Day> daily, String chartMetric) {}
    public record Card(String label, String value, String note) {}
    public record Preview(Data data, String summary, String text, byte[] image) {}

    public static StreamerInsights.Range previousWeek(Instant now) {
        LocalDate monday = StatisticsDay.dateOf(now).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return new StreamerInsights.Range(StatisticsDay.startOf(monday.minusWeeks(1)), StatisticsDay.startOf(monday));
    }

    public static Data calculate(Collection<LiveSession> sessions, Collection<StreamerSnapshot> snapshots,
                                 Instant now, LiveMetricCatalog.Insights rules, boolean showRevenue) {
        var range = previousWeek(now);
        var result = new StreamerInsights().calculate(sessions, snapshots, range.from(), range.to(), rules);
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
            if (showRevenue) put(values, "revenue", d.revenue());
            return new Day(d.date(), d.sessions(), Map.copyOf(values));
        }).toList();
        return new Data(StatisticsDay.dateOf(range.from()), StatisticsDay.dateOf(range.to()).minusDays(1),
                Map.copyOf(metrics), result.fans(), result.composition(), result.coverage(), daily,
                showRevenue ? "revenue" : "messages");
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
        String partial = d.fans() != null && d.fans().partial() ? "从 " + Instant.ofEpochMilli(d.fans().fromAt())
                .atZone(StatisticsDay.ZONE).format(DateTimeFormatter.ofPattern("M 月 d 日 HH:mm")) + " 起算" : "";
        return List.of(new Card("直播时长", duration(m.get("durationSeconds").longValue()), number(m.get("sessions"), " 场", false)),
                new Card(revenue ? "流水" : "场次", number(m.get(revenue ? "revenue" : "sessions"), revenue ? " 元" : " 场", revenue), ""),
                new Card("弹幕", number(m.get("messages"), " 条", false), ""),
                new Card("观众", number(m.get("audience"), " 人", false), ""),
                new Card("互动观众", number(m.get("interactionAudience"), " 人", false), ""),
                new Card("粉丝净增", number(d.fans() == null ? null : d.fans().change(), " 人", false), partial));
    }

    public static String summary(Data d) {
        String lead = "上周（" + period(d) + "）";
        if (d.metrics().get("sessions").intValue() == 0) return lead + "没有记录到开播。";
        var c = cards(d);
        return lead + "共直播 " + number(d.metrics().get("sessions"), " 场", false) + "、" + c.get(0).value()
                + (d.chartMetric().equals("revenue") ? "，流水 " + c.get(1).value() : "")
                + "，弹幕 " + c.get(2).value() + "，观众 " + c.get(3).value() + "。";
    }

    public static String composition(Data d) {
        return d.composition() == null ? "首次记录与回访：暂无数据" : "本周首次记录 "
                + d.composition().firstRecorded() + " 人 · 回访 " + d.composition().returning() + " 人";
    }

    public static String coverage(Data d) {
        var c = d.coverage();
        if (c.anonymous() + c.unknown() + c.missing() == 0) return "";
        var parts = new java.util.ArrayList<String>();
        if (c.complete() != 0) parts.add("完整 " + c.complete() + " 场");
        if (c.anonymous() != 0) parts.add("有认不出的观众 " + c.anonymous() + " 场（这几场的人数偏少）");
        if (c.unknown() != 0) parts.add("不知道是否完整 " + c.unknown() + " 场（升级到 6.0 之前开播的场次没有记这一项）");
        if (c.missing() != 0) parts.add("没有名单 " + c.missing() + " 场");
        return "名单：" + String.join("；", parts);
    }

    public static String text(Data d) {
        if (d.metrics().get("sessions").intValue() == 0) return summary(d);
        var lines = new java.util.ArrayList<String>();
        lines.add("运营周报（" + period(d) + "）");
        for (var c : cards(d)) lines.add(c.label() + "：" + c.value() + (c.note().isEmpty() ? "" : "（" + c.note() + "）"));
        lines.add(composition(d));
        if (!coverage(d).isEmpty()) lines.add(coverage(d));
        lines.add("一天从早上 6 点算起");
        return String.join("\n", lines);
    }
}
