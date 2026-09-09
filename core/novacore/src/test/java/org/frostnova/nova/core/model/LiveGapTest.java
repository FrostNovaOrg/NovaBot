package org.frostnova.nova.core.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 缺口区间的合并与裁剪测试
 * <p>
 * 这里量的是<b>「同一秒不许数两遍」</b>：全局停机与单房断线必然重叠，
 * 而报告上「共 X：维护 a／重启 b／断流 c」只有在各栏互不重叠时才是一句真话。
 */
@DisplayName("采集缺口区间")
class LiveGapTest {
    private static LiveGap gap(long from, long to, LiveGap.Reason reason) {
        return new LiveGap(from, to, reason);
    }

    @Test
    @DisplayName("裁剪只留与给定区间重叠的那一截")
    void clipsToOverlap() {
        LiveGap gap = gap(100, 200, LiveGap.Reason.MAINTENANCE);

        assertEquals(Optional.of(gap(150, 200, LiveGap.Reason.MAINTENANCE)), gap.overlap(150, 300));
        assertEquals(Optional.of(gap(100, 180, LiveGap.Reason.MAINTENANCE)), gap.overlap(50, 180));
        assertEquals(Optional.of(gap), gap.overlap(0, 1000));
        assertEquals(Optional.empty(), gap.overlap(200, 300), "贴边不算重叠：止时刻是不含的");
        assertEquals(Optional.empty(), gap.overlap(0, 100));
    }

    @Test
    @DisplayName("互不重叠的几段原样留下，只是排好序")
    void keepsDisjointGapsAndSortsThem() {
        List<LiveGap> merged = LiveGap.merge(List.of(List.of(
                gap(300, 400, LiveGap.Reason.RESTART),
                gap(100, 200, LiveGap.Reason.MAINTENANCE))));

        assertEquals(List.of(
                gap(100, 200, LiveGap.Reason.MAINTENANCE),
                gap(300, 400, LiveGap.Reason.RESTART)), merged);
        assertEquals(200, LiveGap.totalMillis(merged));
    }

    @Test
    @DisplayName("靠前那一份优先占位，被它盖住的那一段不再重复计入")
    void earlierListWins() {
        List<LiveGap> merged = LiveGap.merge(List.of(
                List.of(gap(100, 200, LiveGap.Reason.MAINTENANCE)),
                List.of(gap(120, 180, LiveGap.Reason.STREAM_LOSS))));

        assertEquals(List.of(gap(100, 200, LiveGap.Reason.MAINTENANCE)), merged,
                "断线整段落在停机里, 那 60 毫秒只该算一次, 而且算给停机");
        assertEquals(100, LiveGap.totalMillis(merged), "相加会得 160, 那就是把同一段数了两遍");
    }

    @Test
    @DisplayName("只被盖住一半时，剩下的那一半仍按自己的成因留着")
    void partialOverlapKeepsTheRemainder() {
        List<LiveGap> merged = LiveGap.merge(List.of(
                List.of(gap(100, 200, LiveGap.Reason.MAINTENANCE)),
                List.of(gap(150, 260, LiveGap.Reason.STREAM_LOSS))));

        assertEquals(List.of(
                gap(100, 200, LiveGap.Reason.MAINTENANCE),
                gap(200, 260, LiveGap.Reason.STREAM_LOSS)), merged);
        assertEquals(160, LiveGap.totalMillis(merged));
    }

    @Test
    @DisplayName("被从中间挖空时会裂成两段，两段都还认得出自己的成因")
    void splitsIntoTwoWhenCoveredInTheMiddle() {
        List<LiveGap> merged = LiveGap.merge(List.of(
                List.of(gap(140, 160, LiveGap.Reason.RESTART)),
                List.of(gap(100, 200, LiveGap.Reason.STREAM_LOSS))));

        assertEquals(List.of(
                gap(100, 140, LiveGap.Reason.STREAM_LOSS),
                gap(140, 160, LiveGap.Reason.RESTART),
                gap(160, 200, LiveGap.Reason.STREAM_LOSS)), merged);
        assertEquals(100, LiveGap.totalMillis(merged));
    }

    @Test
    @DisplayName("同一份表内部互相重叠也去重：一次断线没恢复又记了一次")
    void deduplicatesWithinOneList() {
        List<LiveGap> merged = LiveGap.merge(List.of(List.of(
                gap(100, 200, LiveGap.Reason.STREAM_LOSS),
                gap(150, 250, LiveGap.Reason.STREAM_LOSS))));

        assertEquals(150, LiveGap.totalMillis(merged), "相加会得 200, 中间那 50 毫秒被数了两遍");
    }

    @Test
    @DisplayName("起止倒置的区间不占位、不入表")
    void dropsInvertedIntervals() {
        assertEquals(List.of(), LiveGap.merge(List.of(List.of(gap(200, 100, LiveGap.Reason.RESTART)))));
        assertEquals(0, gap(200, 100, LiveGap.Reason.RESTART).durationMillis(), "时长不返回负数");
    }
}
