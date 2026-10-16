package org.frostnova.nova.core.analytics;

import org.frostnova.nova.core.model.LiveSession;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/** 按实际开播星期与钟点聚合整场表现，范围沿用统计日，格子使用东八区钟表时间。 */
public final class StreamerStartSlots {
    public static final int MIN_SESSIONS = 3;

    public record Session(long startTime, long durationSeconds, Double revenue, Long messages) {}
    /** weekday 为周一至周日的 1–7；startHour 为两小时时段的起始钟点。 */
    public record Cell(int weekday, int startHour, int sessions, long effectiveSeconds,
                       Double revenue, Long messages, Double revenuePerHour, Double messagesPerHour,
                       List<Session> sessionItems) {}
    public record Top(int weekday, int startHour, int sessions, double value) {}
    public record Result(long from, long to, List<Cell> cells, List<Top> topRevenue, List<Top> topMessages) {}

    /** 输入场次须属于同一主播；区间含起点、不含终点。指标口径仅来自平台插件。 */
    public Result calculate(Collection<LiveSession> sessions, Instant from, Instant to,
                            LiveMetricCatalog.Insights rules) {
        if (!from.isBefore(to)) throw new IllegalArgumentException("统计起点必须早于终点");
        long start = from.toEpochMilli();
        long end = to.toEpochMilli();
        boolean revenueKnown = rules != null && rules.revenueKeys() != null && !rules.revenueKeys().isEmpty();
        boolean messagesKnown = rules != null && rules.messageKey() != null && !rules.messageKey().isBlank();
        var groups = new ArrayList<List<LiveSession>>();
        for (int i = 0; i < 84; i++) groups.add(new ArrayList<>());
        for (var session : sessions) {
            if (session.startTime() < start || session.startTime() >= end) continue;
            var local = Instant.ofEpochMilli(session.startTime()).atZone(StatisticsDay.ZONE);
            int index = (local.getDayOfWeek().getValue() - 1) * 12 + local.getHour() / 2;
            groups.get(index).add(session);
        }
        var cells = new ArrayList<Cell>();
        for (int i = 0; i < groups.size(); i++) {
            long effective = 0;
            double revenue = 0;
            long messages = 0;
            var items = new ArrayList<Session>();
            var group = groups.get(i);
            group.sort(Comparator.comparingLong(LiveSession::startTime).reversed());
            for (var session : group) {
                effective += effectiveSeconds(session);
                Double sessionRevenue = revenueKnown ? rules.revenueKeys().stream()
                        .mapToDouble(session::metric).sum() : null;
                Long sessionMessages = messagesKnown ? (long) session.metric(rules.messageKey()) : null;
                if (sessionRevenue != null) revenue += sessionRevenue;
                if (sessionMessages != null) messages += sessionMessages;
                items.add(new Session(session.startTime(), session.durationSeconds(), sessionRevenue, sessionMessages));
            }
            boolean usable = group.size() >= MIN_SESSIONS && effective > 0;
            cells.add(new Cell(i / 12 + 1, i % 12 * 2, group.size(), effective,
                    revenueKnown ? revenue : null, messagesKnown ? messages : null,
                    usable && revenueKnown ? revenue * 3600 / effective : null,
                    usable && messagesKnown ? messages * 3600d / effective : null, List.copyOf(items)));
        }
        return new Result(start, end, List.copyOf(cells), top(cells, Cell::revenuePerHour),
                top(cells, Cell::messagesPerHour));
    }

    /**
     * 有效直播时长：整场时长扣掉程序停机与单房断线中的大者，截到零。
     * 两种缺口会重叠，不能相加扣除。
     * <p>
     * 每小时效率与直播分区对比都用这一个定义，两边各写一遍就迟早分家。
     */
    static long effectiveSeconds(LiveSession session) {
        return Math.max(0, session.durationSeconds()
                - Math.max(session.maintenanceGapSeconds(), session.roomOutageSeconds()));
    }

    private List<Top> top(List<Cell> cells, Function<Cell, Double> value) {
        return cells.stream().filter(cell -> value.apply(cell) != null)
                .map(cell -> new Top(cell.weekday(), cell.startHour(), cell.sessions(), value.apply(cell)))
                .sorted(Comparator.comparingDouble(Top::value).reversed()
                        .thenComparing(Comparator.comparingInt(Top::sessions).reversed())
                        .thenComparingInt(Top::weekday).thenComparingInt(Top::startHour))
                .limit(3).toList();
    }
}
