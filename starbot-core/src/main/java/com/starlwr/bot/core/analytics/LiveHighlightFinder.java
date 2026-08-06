package com.starlwr.bot.core.analytics;

import lombok.NonNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 高能片段识别
 * <p>
 * 从按分钟分桶的互动序列里挑出「这场最热闹的几个时刻」，直接对应可以拿去剪切片的时间点。
 * 数据是现成的——互动曲线本来就按分钟分桶——差的只是把峰值挑出来。
 * <p>
 * <b>三条使结果可信的约束：</b>
 * <ul>
 *     <li><b>峰之间要隔开。</b>一个高潮往往连着热三五分钟，逐分钟取最大值会得到
 *         「第 30、31、32 分钟」这样三个其实是同一件事的结果。取局部极大并强制最小间隔，
 *         挑出的才是三个不同的时刻。</li>
 *     <li><b>要显著高于基线。</b>用中位数而不是均值作基线：均值会被峰值本身抬高，
 *         越是有高潮的场次基线越高，反而更难达标。</li>
 *     <li><b>要过绝对门槛。</b>只看倍数的话，一场总共二十条弹幕的冷场也能挑出
 *         「三倍于基线」的高能时刻——那分钟其实只有三条弹幕。冷场就该老实说没有高能片段。</li>
 * </ul>
 */
public final class LiveHighlightFinder {
    private LiveHighlightFinder() {
    }

    /**
     * 找出本场的高能时刻
     *
     * @param series 按时间分桶的取值，键为桶起始时刻（毫秒）
     * @param bucketMillis 分桶宽度（毫秒）
     * @param start 开播时刻（毫秒）
     * @param end 下播时刻（毫秒）
     * @param limit 最多返回多少个
     * @param minSeparationMillis 两个高能时刻之间的最小间隔（毫秒）
     * @param minRatio 相对基线的最小倍数
     * @param minValue 绝对门槛，桶内取值低于此值一律不算高能
     * @return 按取值从高到低排列的高能时刻，没有够格的返回空表
     */
    public static List<Highlight> find(@NonNull Map<Long, Double> series, long bucketMillis,
                                       long start, long end, int limit,
                                       long minSeparationMillis, double minRatio, double minValue) {
        if (series.isEmpty() || limit <= 0 || bucketMillis <= 0 || end <= start) {
            return List.of();
        }

        double[] values = fill(series, bucketMillis, start, end);
        if (values.length == 0) {
            return List.of();
        }

        double baseline = median(values);
        // 中位数为 0 说明多数时间无人说话，此时任何非零桶都是「无穷倍于基线」——
        // 倍数判据失效，只留绝对门槛把关
        double threshold = baseline > 0 ? Math.max(baseline * minRatio, minValue) : minValue;

        List<Highlight> peaks = localMaxima(values, bucketMillis, start, threshold, baseline);
        peaks.sort(Comparator.comparingDouble(Highlight::value).reversed());

        // 贪心地从最高的开始取，跳过与已选时刻靠得太近的——
        // 先排序再筛间隔，保证同一段热度里留下的是那段的最高点
        List<Highlight> result = new ArrayList<>(limit);
        for (Highlight peak : peaks) {
            if (result.size() >= limit) {
                break;
            }
            if (result.stream().noneMatch(chosen -> Math.abs(chosen.at() - peak.at()) < minSeparationMillis)) {
                result.add(peak);
            }
        }
        return result;
    }

    /**
     * 把稀疏的分桶铺成从开播到下播的连续数组
     * <p>
     * 没有互动的分钟在序列里根本不存在，直接对已有的桶取中位数会把安静时段整个忽略掉，
     * 基线因此虚高。<b>缺席的分钟是真实的零，不是缺失值。</b>
     */
    private static double[] fill(Map<Long, Double> series, long bucketMillis, long start, long end) {
        long first = start / bucketMillis * bucketMillis;
        long last = end / bucketMillis * bucketMillis;
        long count = (last - first) / bucketMillis + 1;
        if (count <= 0 || count > Integer.MAX_VALUE) {
            return new double[0];
        }

        double[] values = new double[(int) count];
        for (Map.Entry<Long, Double> entry : series.entrySet()) {
            long bucket = entry.getKey() / bucketMillis * bucketMillis;
            int index = (int) ((bucket - first) / bucketMillis);
            if (index >= 0 && index < values.length && entry.getValue() != null) {
                values[index] += entry.getValue();
            }
        }
        return values;
    }

    /**
     * 取局部极大值
     * <p>
     * 「不低于两侧邻居」而不是「严格大于」：连续相等的平台期也该被看见，
     * 否则一段平顶的高热度会被整段跳过。
     */
    private static List<Highlight> localMaxima(double[] values, long bucketMillis, long start,
                                               double threshold, double baseline) {
        long first = start / bucketMillis * bucketMillis;
        List<Highlight> peaks = new ArrayList<>();

        for (int i = 0; i < values.length; i++) {
            double value = values[i];
            if (value < threshold || value <= 0) {
                continue;
            }
            if (i > 0 && values[i - 1] > value) {
                continue;
            }
            if (i < values.length - 1 && values[i + 1] > value) {
                continue;
            }

            double ratio = baseline > 0 ? value / baseline : 0;
            peaks.add(new Highlight(first + (long) i * bucketMillis, value, ratio));
        }
        return peaks;
    }

    /**
     * 中位数。会就地排序传入数组的副本
     */
    private static double median(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        int middle = sorted.length / 2;
        return sorted.length % 2 == 1 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2;
    }

    /**
     * 一个高能时刻
     *
     * @param at 该时段的起始时刻（毫秒）
     * @param value 该时段的取值
     * @param ratio 相对基线的倍数，基线为 0 时为 0
     */
    public record Highlight(long at, double value, double ratio) {
    }
}
