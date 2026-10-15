package org.frostnova.nova.core.analytics;

import org.frostnova.nova.core.model.LiveSession;
import lombok.NonNull;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 直播场次的周期聚合
 * <p>
 * 把归档里的场次按周或月归拢，供管理后台做运营分析。**纯函数**：输入场次列表，
 * 输出各周期的汇总，不碰存储也不碰 Web，因此能脱离运行环境把边界逐个钉死。
 * <p>
 * 三条容易出错的规则集中在这里：
 * <ul>
 *     <li><b>按东八区早上 6 点切统计日</b>，使用 {@link StatisticsDay}。
 *     主播多播到凌晨，同一晚归一天；周从周一 6 点起，月从 1 号 6 点起</li>
 *     <li><b>整场算在开播时刻所属的统计日</b>。
 *     跨过统计日边界也不拆到两个周期里各算半截</li>
 *     <li><b>中间没播的周期也要出现</b>。空着的那一格本身就是运营信息，
 *     跳过它会让趋势看起来比实际连贯</li>
 * </ul>
 */
public class LiveSessionAnalytics {
    private static final DateTimeFormatter DAY_LABEL = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private static final DateTimeFormatter DAY_IN_YEAR_LABEL = DateTimeFormatter.ofPattern("MM-dd");

    /**
     * 名字带 {@code _LABEL} 后缀是为了不与枚举常量 {@link Period#MONTH} 撞名——
     * 撞上时枚举常量体内的 {@code MONTH} 会解析成常量自己，而不是这个格式化器
     */
    private static final DateTimeFormatter MONTH_LABEL = DateTimeFormatter.ofPattern("yyyy-MM");

    /**
     * 按周期聚合场次
     * @param sessions 场次列表，顺序不限
     * @param period 周期
     * @param metrics 参与聚合的指标键。**只有明确可累加的指标才该传进来**，
     *                详见 {@link LiveMetricCatalog}
     * @return 各周期的汇总，按时间升序；无场次时为空表
     */
    public List<Bucket> aggregate(@NonNull Collection<LiveSession> sessions,
                                  @NonNull Period period, @NonNull Set<String> metrics) {
        if (sessions.isEmpty()) {
            return List.of();
        }

        TreeMap<LocalDate, Accumulator> byStart = new TreeMap<>();
        for (LiveSession session : sessions) {
            byStart.computeIfAbsent(period.startOf(dateOf(session.startTime())), key -> new Accumulator())
                    .add(session, metrics);
        }

        List<Bucket> result = new ArrayList<>();
        LocalDate cursor = byStart.firstKey();
        LocalDate last = byStart.lastKey();

        while (!cursor.isAfter(last)) {
            LocalDate next = period.next(cursor);
            Accumulator accumulator = byStart.get(cursor);
            result.add((accumulator == null ? new Accumulator() : accumulator)
                    .toBucket(period.label(cursor), millisOf(cursor), millisOf(next)));
            cursor = next;
        }

        return result;
    }

    /** 页面范围内按开播统计日归桶，保留两端及中间的空周期。 */
    public List<Bucket> aggregate(Collection<LiveSession> sessions, Period period, Set<String> metrics,
                                  Instant from, Instant to) {
        TreeMap<LocalDate, Accumulator> grouped = new TreeMap<>();
        for (var session : sessions) {
            if (session.startTime() >= from.toEpochMilli() && session.startTime() < to.toEpochMilli()) {
                grouped.computeIfAbsent(period.startOf(dateOf(session.startTime())), ignored -> new Accumulator())
                        .add(session, metrics);
            }
        }
        List<Bucket> result = new ArrayList<>();
        var last = period.startOf(StatisticsDay.dateOf(to.minusMillis(1)));
        for (var date = period.startOf(StatisticsDay.dateOf(from)); !date.isAfter(last); date = period.next(date)) {
            var bucket = grouped.getOrDefault(date, new Accumulator());
            metrics.forEach(key -> bucket.metrics.putIfAbsent(key, 0d));
            result.add(bucket.toBucket(period.label(date), millisOf(date), millisOf(period.next(date))));
        }
        return List.copyOf(result);
    }

    private LocalDate dateOf(long millis) {
        return StatisticsDay.dateOf(Instant.ofEpochMilli(millis));
    }

    private long millisOf(LocalDate date) {
        return StatisticsDay.startOf(date).toEpochMilli();
    }

    /**
     * 统计周期
     */
    public enum Period {
        DAY {
            @Override LocalDate startOf(LocalDate date) { return date; }
            @Override LocalDate next(LocalDate date) { return date.plusDays(1); }
            @Override String label(LocalDate date) { return DAY_LABEL.format(date); }
        },
        /**
         * 周，从周一早上 6 点起算
         */
        WEEK {
            @Override
            LocalDate startOf(LocalDate date) {
                return date.with(DayOfWeek.MONDAY);
            }

            @Override
            LocalDate next(LocalDate start) {
                return start.plusWeeks(1);
            }

            @Override
            String label(LocalDate start) {
                LocalDate end = start.plusDays(6);
                // 同年只写一次年份：「2026-08-03 ~ 08-09」比两个全日期好读，
                // 跨年那一周则两边都写全，否则会看成同一年
                return start.getYear() == end.getYear()
                        ? DAY_LABEL.format(start) + " ~ " + DAY_IN_YEAR_LABEL.format(end)
                        : DAY_LABEL.format(start) + " ~ " + DAY_LABEL.format(end);
            }
        },

        /**
         * 月，从 1 号早上 6 点起算
         */
        MONTH {
            @Override
            LocalDate startOf(LocalDate date) {
                return date.withDayOfMonth(1);
            }

            @Override
            LocalDate next(LocalDate start) {
                return start.plusMonths(1);
            }

            @Override
            String label(LocalDate start) {
                return MONTH_LABEL.format(start);
            }
        };

        abstract LocalDate startOf(LocalDate date);

        abstract LocalDate next(LocalDate start);

        abstract String label(LocalDate start);
    }

    /**
     * 一个周期的汇总
     * @param label 周期名，如 {@code 2026-08} 或 {@code 2026-08-03 ~ 08-09}
     * @param start 周期起点，含
     * @param end 周期终点，不含
     * @param sessions 场次数
     * @param durationSeconds 总时长
     * @param metrics 各指标合计
     * @param userTimes 各计分表的**人次**合计，详见 {@link Accumulator#add}
     */
    public record Bucket(String label, long start, long end, int sessions, long durationSeconds,
                         Map<String, Double> metrics, Map<String, Long> userTimes) {
    }

    /**
     * 周期内的累加器
     */
    private static final class Accumulator {
        private int sessions;

        private long durationSeconds;

        private final Map<String, Double> metrics = new LinkedHashMap<>();

        private final Map<String, Long> userTimes = new LinkedHashMap<>();

        /**
         * 并入一场
         * <p>
         * <b>各计分表累加出来的是人次，不是人数。</b>同一个人在三场直播里都发过弹幕，
         * 三场的 {@code danmu_users} 各记他一次，相加就是 3。归档里只存了每场的人数，
         * 没存人的集合，因此**周/月的独立人数是算不出来的**——真要这个数得改归档格式。
         * 界面上必须写「人次」，写成「人数」就是拿一个偏大的数字骗人。
         */
        void add(LiveSession session, Set<String> keys) {
            sessions++;
            durationSeconds += session.durationSeconds();

            session.metrics().forEach((key, value) -> {
                if (keys.contains(key)) {
                    metrics.merge(key, value, Double::sum);
                }
            });
            session.userCounts().forEach((key, value) -> userTimes.merge(key, (long) value, Long::sum));
        }

        Bucket toBucket(String label, long start, long end) {
            return new Bucket(label, start, end, sessions, durationSeconds,
                    Collections.unmodifiableMap(new LinkedHashMap<>(metrics)),
                    Collections.unmodifiableMap(new LinkedHashMap<>(userTimes)));
        }
    }
}
