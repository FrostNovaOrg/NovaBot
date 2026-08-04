package com.starlwr.bot.bilibili.model;

/**
 * 哔哩哔哩本场直播统计指标名
 * <p>
 * 指标由 {@code BilibiliLiveStatsAggregator} 在直播期间累计，
 * 存放于 {@link com.starlwr.bot.core.service.LiveDataService}，开播时清零。
 */
public final class BilibiliLiveMetric {
    /** 弹幕条数（含表情包弹幕） */
    public static final String DANMU_COUNT = "danmu_count";

    /** 发送过弹幕的独立用户数 */
    public static final String DANMU_USERS = "danmu_users";

    /** 付费礼物价值，单位：元（含盲盒开出的礼物价值） */
    public static final String GIFT_VALUE = "gift_value";

    /** 送出过付费礼物的独立用户数 */
    public static final String GIFT_USERS = "gift_users";

    /** 免费礼物个数 */
    public static final String FREE_GIFT_COUNT = "free_gift_count";

    /** 盲盒个数 */
    public static final String BOX_COUNT = "box_count";

    /** 盲盒盈亏，单位：元，正值为主播收益方向 */
    public static final String BOX_PROFIT = "box_profit";

    /** 醒目留言条数 */
    public static final String SUPER_CHAT_COUNT = "super_chat_count";

    /** 醒目留言价值，单位：元 */
    public static final String SUPER_CHAT_VALUE = "super_chat_value";

    /** 上舰人次（舰长） */
    public static final String CAPTAIN_COUNT = "captain_count";

    /** 上舰人次（提督） */
    public static final String COMMANDER_COUNT = "commander_count";

    /** 上舰人次（总督） */
    public static final String GOVERNOR_COUNT = "governor_count";

    /** 大航海价值，单位：元 */
    public static final String GUARD_VALUE = "guard_value";

    /** 新增关注人次 */
    public static final String FOLLOW_COUNT = "follow_count";

    /** 进入过直播间的独立用户数 */
    public static final String ENTER_USERS = "enter_users";

    /** 点赞总数（服务端下发的单调累计值） */
    public static final String LIKE_TOTAL = "like_total";

    /** 点赞过的独立用户数 */
    public static final String LIKE_USERS = "like_users";

    /** 分享直播间人次 */
    public static final String SHARE_COUNT = "share_count";

    private BilibiliLiveMetric() {
    }
}
