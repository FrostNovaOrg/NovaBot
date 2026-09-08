package com.starlwr.bot.core.analytics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 高能片段识别测试
 * <p>
 * 这个功能会告诉主播「去剪第 30 分钟」。指错了地方比不指更浪费时间，
 * 因此重点钉死三件事：同一段热度不能拆成好几条、冷场不能硬凑、安静的分钟要算进基线。
 */
@DisplayName("高能片段识别")
class LiveHighlightFinderTest {
    private static final long MINUTE = 60_000L;

    private static final long START = 1_700_000_000_000L / MINUTE * MINUTE;

    /**
     * 默认参数：最多 3 个、至少隔 5 分钟、至少 3 倍于基线、每分钟至少 10 条
     */
    private List<LiveHighlightFinder.Highlight> find(Map<Long, Double> series, long durationMinutes) {
        return LiveHighlightFinder.find(series, MINUTE, START, START + durationMinutes * MINUTE,
                3, 5 * MINUTE, 3.0, 10);
    }

    /**
     * 构造一条每分钟 base 条弹幕的平底序列
     */
    private Map<Long, Double> flat(int minutes, double base) {
        Map<Long, Double> series = new HashMap<>();
        for (int i = 0; i < minutes; i++) {
            series.put(START + i * MINUTE, base);
        }
        return series;
    }

    @Test
    @DisplayName("三个分开的高峰应各自被挑出来")
    void findsSeparatePeaks() {
        Map<Long, Double> series = flat(60, 10);
        series.put(START + 10 * MINUTE, 100.0);
        series.put(START + 30 * MINUTE, 200.0);
        series.put(START + 50 * MINUTE, 150.0);

        List<LiveHighlightFinder.Highlight> highlights = find(series, 60);

        assertEquals(3, highlights.size());
        assertEquals(List.of(START + 30 * MINUTE, START + 50 * MINUTE, START + 10 * MINUTE),
                highlights.stream().map(LiveHighlightFinder.Highlight::at).toList());
    }

    @Test
    @DisplayName("连续热闹的一段只应算一个时刻，而不是逐分钟报三次")
    void mergesAdjacentMinutesIntoOne() {
        Map<Long, Double> series = flat(60, 10);
        series.put(START + 30 * MINUTE, 180.0);
        series.put(START + 31 * MINUTE, 200.0);
        series.put(START + 32 * MINUTE, 190.0);

        List<LiveHighlightFinder.Highlight> highlights = find(series, 60);

        assertEquals(1, highlights.size());
        assertEquals(START + 31 * MINUTE, highlights.get(0).at());
    }

    @Test
    @DisplayName("靠得太近的两个峰只保留更高的那个，哪怕中间有回落")
    void nearbyPeaksCollapseToTheHigher() {
        // 两个都是局部极大值、中间确实回落过，但只隔两分钟——对主播而言仍是同一段
        Map<Long, Double> series = flat(60, 10);
        series.put(START + 30 * MINUTE, 200.0);
        series.put(START + 31 * MINUTE, 50.0);
        series.put(START + 32 * MINUTE, 190.0);

        List<LiveHighlightFinder.Highlight> highlights = find(series, 60);

        assertEquals(1, highlights.size());
        assertEquals(START + 30 * MINUTE, highlights.get(0).at());
    }

    @Test
    @DisplayName("冷场不应硬凑高能：三条弹幕的那分钟不是高潮")
    void quietSessionHasNoHighlight() {
        Map<Long, Double> series = new HashMap<>();
        series.put(START + 5 * MINUTE, 1.0);
        series.put(START + 20 * MINUTE, 3.0);
        series.put(START + 40 * MINUTE, 2.0);

        assertTrue(find(series, 60).isEmpty());
    }

    @Test
    @DisplayName("无人说话的分钟要按零计入基线，否则基线虚高会漏掉真高峰")
    void absentMinutesCountAsZero() {
        // 全场只有三分钟有互动，若只对已有的桶取中位数，基线会是 20 而不是 0
        Map<Long, Double> series = new HashMap<>();
        series.put(START + 10 * MINUTE, 20.0);
        series.put(START + 30 * MINUTE, 60.0);
        series.put(START + 50 * MINUTE, 20.0);

        List<LiveHighlightFinder.Highlight> highlights = find(series, 60);

        assertEquals(3, highlights.size());
        assertEquals(START + 30 * MINUTE, highlights.get(0).at());
    }

    @Test
    @DisplayName("应按热度从高到低返回")
    void sortedByValueDescending() {
        Map<Long, Double> series = flat(60, 10);
        series.put(START + 10 * MINUTE, 300.0);
        series.put(START + 30 * MINUTE, 100.0);
        series.put(START + 50 * MINUTE, 200.0);

        List<Double> values = find(series, 60).stream().map(LiveHighlightFinder.Highlight::value).toList();

        assertEquals(List.of(300.0, 200.0, 100.0), values);
    }

    @Test
    @DisplayName("平顶的一段不应被整段跳过")
    void plateauIsStillDetected() {
        Map<Long, Double> series = flat(60, 10);
        series.put(START + 30 * MINUTE, 200.0);
        series.put(START + 31 * MINUTE, 200.0);

        List<LiveHighlightFinder.Highlight> highlights = find(series, 60);

        assertEquals(1, highlights.size());
        assertEquals(200.0, highlights.get(0).value());
    }

    @Test
    @DisplayName("倍数应相对中位数而非均值，均值会被高峰自己抬高")
    void ratioIsAgainstMedian() {
        Map<Long, Double> series = flat(60, 10);
        series.put(START + 30 * MINUTE, 100.0);

        List<LiveHighlightFinder.Highlight> highlights = find(series, 60);

        assertEquals(1, highlights.size());
        assertEquals(10.0, highlights.get(0).ratio(), 0.001);
    }

    @Test
    @DisplayName("超出返回上限的高峰应被截掉")
    void respectsLimit() {
        Map<Long, Double> series = flat(60, 10);
        for (int i = 5; i < 55; i += 6) {
            series.put(START + i * MINUTE, 100.0 + i);
        }

        assertEquals(3, find(series, 60).size());
    }

    @Test
    @DisplayName("空序列、零上限与非法时间区间都应返回空表")
    void degenerateInputs() {
        assertTrue(LiveHighlightFinder.find(Map.of(), MINUTE, START, START + 60 * MINUTE, 3, 5 * MINUTE, 3, 10).isEmpty());
        assertTrue(LiveHighlightFinder.find(flat(60, 100), MINUTE, START, START + 60 * MINUTE, 0, 5 * MINUTE, 3, 10).isEmpty());
        assertTrue(LiveHighlightFinder.find(flat(60, 100), MINUTE, START, START, 3, 5 * MINUTE, 3, 10).isEmpty());
    }

    @Test
    @DisplayName("落在开播前的桶不应把数组撑出下标")
    void ignoresBucketsOutsideSession() {
        Map<Long, Double> series = flat(60, 10);
        series.put(START - 10 * MINUTE, 999.0);
        series.put(START + 120 * MINUTE, 999.0);
        series.put(START + 30 * MINUTE, 200.0);

        List<LiveHighlightFinder.Highlight> highlights = find(series, 60);

        assertEquals(1, highlights.size());
        assertEquals(START + 30 * MINUTE, highlights.get(0).at());
    }
}
