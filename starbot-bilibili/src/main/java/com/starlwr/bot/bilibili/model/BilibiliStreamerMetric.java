package com.starlwr.bot.bilibili.model;

/**
 * 哔哩哔哩主播基础数据的指标名
 * <p>
 * 与 {@link BilibiliLiveMetric} 的区别在于<b>它们不属于任何一场直播</b>：
 * 那边的指标开播清零、下播归档，而这些数字在没播的日子里照样在变，
 * 由 {@code BilibiliStreamerSnapshotService} 按固定间隔采样留档。
 */
public final class BilibiliStreamerMetric {
    /**
     * 粉丝数
     */
    public static final String FANS = "fans";

    /**
     * 粉丝团人数
     */
    public static final String FANS_MEDAL = "fans_medal";

    /**
     * 大航海人数
     */
    public static final String GUARD = "guard";

    private BilibiliStreamerMetric() {
    }
}
