package com.starlwr.bot.core.model;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 一段没有采集到数据的区间
 * <p>
 * 此前缺口只有一个总秒数，报告上写得出「缺了 12 分 34 秒」，写不出「缺在哪儿」——
 * 而曲线上那一段照样画着贴地的面积，读的人无从分辨「这几分钟没人说话」与
 * 「这几分钟我们没在听」。这两件事对主播的含义完全相反。
 * <p>
 * <b>成因必须跟着区间走，不能另存一份。</b>一场直播里的几段缺口成因往往不同
 * （先是重启，后来那个房间自己断了流），只报一个「主要成因」就等于替读的人猜。
 *
 * @param from 起始时刻（毫秒，含）
 * @param to 结束时刻（毫秒，不含）
 * @param reason 成因
 */
public record LiveGap(long from, long to, Reason reason) {
    /**
     * 缺口的成因
     * <p>
     * <b>闭集，且与概览那一行的分栏一一对应</b>：报告上写的是
     * 「采集缺口 共 X：维护 a／重启 b／断流 c」，多一个成因就多一栏，
     * 因此新增一项之前必须先想清楚它在那一行怎么说。
     * <p>
     * 之所以嵌在这里而不放进 {@code enums/}：边界尺按目录判核心件，{@code model/}
     * 整目录算核心而 {@code enums/} 是逐件点名的——放进 enums/ 就得同时改那张名单，
     * 否则「核心件引用壳侧件」当场判红。成因只有这一个区间类型在用，跟着它走即可。
     */
    @Getter
    @AllArgsConstructor
    public enum Reason {
        /**
         * 维护：上一个进程正常退出，这段空白是计划内的停机
         */
        MAINTENANCE("维护"),

        /**
         * 重启：上一个进程崩溃或被强杀，重启才发现这段没人在采
         */
        RESTART("重启"),

        /**
         * 断流：进程一直在跑，是这个直播间自己的长连接断了
         */
        STREAM_LOSS("断流"),

        /**
         * 原因未定：区间是真的，成因推不出来
         * <p>
         * <b>不许拿它当默认的「维护」用。</b>「我们不知道为什么没采到」与
         * 「这是一次计划内维护」在主播眼里是两件事，前者才是该有人去查的那件。
         */
        UNKNOWN("原因未定");

        private final String description;
    }

    /**
     * 本段时长（毫秒）。起止倒置时为 0，不返回负数
     */
    public long durationMillis() {
        return Math.max(0, to - from);
    }

    /**
     * 本段与 {@code [from, to)} 重叠的那一部分
     * <p>
     * 一段跨越开播时刻的停机，开播之前那一截不属于本场——裁剪这件事只写在这里一处，
     * 「共缺了多久」与「缺在哪几段」便不会各裁各的。
     * @param from 区间起始（毫秒，含）
     * @param to 区间结束（毫秒，不含）
     * @return 重叠部分，没有交集时为空
     */
    public Optional<LiveGap> overlap(long from, long to) {
        long start = Math.max(this.from, from);
        long end = Math.min(this.to, to);
        return end > start ? Optional.of(new LiveGap(start, end, reason)) : Optional.empty();
    }

    /**
     * 把若干份区间表合并成一份互不重叠的缺口表，按时间先后排列
     * <p>
     * <b>靠前的那一份优先占位，不相加。</b>程序停机期间每个房间当然也是断的，
     * 全局停机与单房断线两份必然重叠——相加就是把同一秒数两遍，算得出比整场
     * 时长还大的缺口。让先到的成因占住那一秒之后，各成因时长之和恰好等于总时长，
     * 概览那一行的「共 X：维护 a／重启 b／断流 c」才是一个真的分栏。
     * <p>
     * 同一份表内部互相重叠也一并去重：一次断线还没恢复又记了一次，是实际会发生的。
     * @param byPriority 若干份区间表，靠前的优先
     * @return 互不重叠、按起始时刻升序的区间表
     */
    public static List<LiveGap> merge(List<List<LiveGap>> byPriority) {
        List<LiveGap> claimed = new ArrayList<>();

        for (List<LiveGap> group : byPriority) {
            if (group == null) {
                continue;
            }
            for (LiveGap gap : group) {
                if (gap == null || gap.durationMillis() <= 0) {
                    continue;
                }

                // 减去已被更高优先级占住的部分，剩下几截就落几截
                List<long[]> pieces = new ArrayList<>();
                pieces.add(new long[]{gap.from(), gap.to()});
                for (LiveGap taken : claimed) {
                    List<long[]> rest = new ArrayList<>(pieces.size() + 1);
                    for (long[] piece : pieces) {
                        if (taken.to() <= piece[0] || taken.from() >= piece[1]) {
                            rest.add(piece);
                            continue;
                        }
                        if (piece[0] < taken.from()) {
                            rest.add(new long[]{piece[0], taken.from()});
                        }
                        if (taken.to() < piece[1]) {
                            rest.add(new long[]{taken.to(), piece[1]});
                        }
                    }
                    pieces = rest;
                }

                for (long[] piece : pieces) {
                    claimed.add(new LiveGap(piece[0], piece[1], gap.reason()));
                }
            }
        }

        claimed.sort(Comparator.comparingLong(LiveGap::from));
        return List.copyOf(claimed);
    }

    /**
     * 一份区间表的总时长（毫秒）
     * <p>
     * 只对<b>互不重叠</b>的表成立，因此入参一般是 {@link #merge} 的结果或本来就不重叠的一份。
     * @param gaps 区间表
     * @return 总毫秒数
     */
    public static long totalMillis(List<LiveGap> gaps) {
        long total = 0;
        for (LiveGap gap : gaps) {
            total += gap.durationMillis();
        }
        return total;
    }
}
