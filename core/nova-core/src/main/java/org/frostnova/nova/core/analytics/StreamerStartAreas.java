package org.frostnova.nova.core.analytics;

import org.frostnova.nova.core.model.LiveSession;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 直播分区对比：整场按开播时的分区归属，中途换分区的不拆分。
 * <p>
 * 场次范围与开播时段热图同一套选场规则——区间含起点、不含终点，只看开播时刻，
 * 有效直播时长也是热图算每小时效率用的那一个定义，两张卡因此数的是同一组场次。
 */
public final class StreamerStartAreas {
    /** 没有轨迹或首条分区为空的场次归入这一行。 */
    public static final String UNRECORDED = "未记录分区";

    public record Area(String area, int sessions, long effectiveSeconds,
                       Double revenue, Double revenuePerHour, List<Long> starts) {}

    /** 输入场次须属于同一主播；区间含起点、不含终点。指标口径仅来自平台插件。 */
    public List<Area> calculate(Collection<LiveSession> sessions, Instant from, Instant to,
                                LiveMetricCatalog.Insights rules) {
        if (!from.isBefore(to)) throw new IllegalArgumentException("统计起点必须早于终点");
        long start = from.toEpochMilli();
        long end = to.toEpochMilli();
        var byArea = new LinkedHashMap<String, List<LiveSession>>();
        for (var session : sessions) {
            if (session.startTime() < start || session.startTime() >= end) continue;
            byArea.computeIfAbsent(openingArea(session), ignored -> new ArrayList<>()).add(session);
        }
        var areas = new ArrayList<Area>();
        for (var entry : byArea.entrySet()) {
            long effective = 0;
            Double revenue = null;
            var starts = new ArrayList<Long>();
            for (var session : entry.getValue()) {
                effective += StreamerStartSlots.effectiveSeconds(session);
                Double amount = revenueOf(session, rules);
                if (amount != null) revenue = revenue == null ? amount : revenue + amount;
                starts.add(session.startTime());
            }
            areas.add(new Area(entry.getKey(), entry.getValue().size(), effective, revenue,
                    revenue == null || effective <= 0 ? null : revenue * 3600 / effective,
                    List.copyOf(starts)));
        }
        // 照运营分析里分区表的排法：每小时流水高的在前，流水未知的沉底
        areas.sort(Comparator.comparingDouble((Area row) ->
                row.revenuePerHour() == null ? -1 : row.revenuePerHour()).reversed());
        return List.copyOf(areas);
    }

    /** 开播时的分区。中途换过的整场归首条，没有轨迹或首条为空的归 {@link #UNRECORDED}。 */
    private static String openingArea(LiveSession session) {
        if (session.titles() == null || session.titles().isEmpty()) return UNRECORDED;
        var first = session.titles().get(0);
        if (first == null) return UNRECORDED;
        String area = first.area();
        return area == null || area.isBlank() ? UNRECORDED : area;
    }

    /**
     * 这一场按收入键合计的流水
     * <p>
     * <b>一个收入键都没记到的场次是「不知道」不是 0。</b>写成 0 的话，没留下指标的那些场
     * 会把这个分区的每小时流水拖低，而屏幕上分不出「没赚」和「没记」。
     * 记到的零元则是数据，照零算。
     */
    private static Double revenueOf(LiveSession session, LiveMetricCatalog.Insights rules) {
        if (rules == null || rules.revenueKeys() == null || rules.revenueKeys().isEmpty()) return null;
        Double total = null;
        for (String key : rules.revenueKeys()) {
            Double value = session.metrics() == null ? null : session.metrics().get(key);
            if (value != null && Double.isFinite(value)) total = (total == null ? 0 : total) + value;
        }
        return total;
    }
}
