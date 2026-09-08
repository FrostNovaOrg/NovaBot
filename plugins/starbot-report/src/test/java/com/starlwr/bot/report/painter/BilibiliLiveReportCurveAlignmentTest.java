package com.starlwr.bot.report.painter;

import com.starlwr.bot.core.model.LiveGap;
import com.starlwr.bot.core.service.LiveDataService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 曲线时间格与采集端的对齐法
 * <p>
 * 采集端把每分钟的数据记在<b>绝对分钟格</b>上：12:00:00 到 12:00:59 的任何一次采样
 * 都落在键 {@code 12:00:00}（见 {@code DefaultLiveDataService#incrementLiveSeries}），
 * 高能时刻那一路读的也是同一套键。而曲线这边曾按<b>开播时刻</b>起算自己的格：
 * 开播在 12:00:30 时，采下来的 12:00 那一分钟落在开播相对格 -0.5 上被丢弃，
 * 之后的每一格都向前错一格——第一格丢、整条错位。
 * <p>
 * 这里的夹具全部按「开播在某个整分之后 30 秒」的形态造，正是踩坑的那一形。
 */
@DisplayName("曲线对齐：开播非整分时按绝对分钟格取值")
class BilibiliLiveReportCurveAlignmentTest {
    private static final long MINUTE = LiveDataService.SERIES_BUCKET_MILLIS;

    /**
     * 整分锚点：某天的 12:00:00（任意常量即可，取整分是为了让夹具里的偏移可读）
     */
    private static final long NOON = 46_800_000L;

    private static final long START = NOON + 30_000L;

    /**
     * 阳：开播那一分钟不丢，且每个键落在它自己的绝对分钟列上
     * <p>
     * 序列按采集端的真实形态造：键一律是整分。开播 12:00:30，
     * 12:00 那一格（开播后 30 秒内采到的那一分钟）必须落在第 0 列；
     * 12:03 那一格必须落在第 3 列，而不是因为「距开播 2.5 分」被压进第 2 列。
     */
    @Test
    @DisplayName("阳：开播 12:00:30 时 12:00 那格在第 0 列、12:03 那格在第 3 列")
    void firstMinuteLandsInColumnZeroWhenStartIsMidMinute() {
        Map<Long, Double> series = new LinkedHashMap<>();
        series.put(NOON, 5.0);
        series.put(NOON + 3 * MINUTE, 9.0);

        // 终点取整分 12:11:00：绝对分钟格覆盖 12:00..12:11 共 12 格
        long end = NOON + 11 * MINUTE;
        assertEquals(12, BilibiliLiveReportPainter.bucketCount(START, end),
                "时间格数应按绝对分钟格数（12:00..12:11），不应从开播时刻起算");

        double[] values = BilibiliLiveReportPainter.resample(series, START, end, 12);
        assertEquals(5.0, values[0], "开播那一分钟（12:00 格）不得因开播在 12:00:30 而被丢弃");
        assertEquals(9.0, values[3], "12:03 格应落在第 3 列，不得向前错一格");
        assertEquals(0.0, values[1]);
        assertEquals(0.0, values[2]);
    }

    /**
     * 阴：直播区间之外的格仍然丢弃
     * <p>
     * 对齐法改的是格的原点，不是区间的边界：开播之前的残留（上一场、时钟回拨）
     * 与终点之后的键照旧不进图——否则「错一格」会被修成「多画一格」。
     */
    @Test
    @DisplayName("阴：开播前与终点后的键不进图")
    void keysOutsideTheLiveIntervalAreStillDropped() {
        Map<Long, Double> series = new LinkedHashMap<>();
        series.put(NOON - 3 * MINUTE, 7.0);
        series.put(NOON + 20 * MINUTE, 4.0);

        long end = NOON + 11 * MINUTE;
        double[] values = BilibiliLiveReportPainter.resample(series, START, end, 12);
        assertArrayEquals(new double[12], values);
    }

    /**
     * 缺口列与取值列必须共用同一个原点
     * <p>
     * 一段完全落在 12:02 那一分钟之内的缺口（如 12:02:00-12:02:10），
     * 斜纹应压在第 2 列——那一列画的就是 12:02。若缺口列从开播时刻起算，
     * 斜纹会压到第 1 列上，盖住的恰是有数据的那一列。
     */
    @Test
    @DisplayName("缺口列与取值列同原点：12:02 内的缺口压第 2 列")
    void gapColumnsShareTheOriginWithValues() {
        long end = NOON + 11 * MINUTE;
        int buckets = 12;
        List<LiveGap> gaps = List.of(new LiveGap(NOON + 2 * MINUTE, NOON + 2 * MINUTE + 10_000L, LiveGap.Reason.STREAM_LOSS));

        boolean[] missing = BilibiliLiveReportPainter.gapColumns(gaps, START, buckets, 12);
        assertEquals(true, missing[2], "12:02 内的缺口应压第 2 列");
        assertEquals(false, missing[1], "斜纹不得前移一格盖住 12:01 那一列");
    }
}
