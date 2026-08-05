package com.starlwr.bot.bilibili.model;

/**
 * 哔哩哔哩本场直播统计指标名
 * <p>
 * 指标由 {@code BilibiliLiveStatsAggregator} 在直播期间累计，
 * 存放于 {@link com.starlwr.bot.core.service.LiveDataService}，开播时清零。
 */
public final class BilibiliLiveMetric {
    // 带 _USERS 后缀的指标是「按用户计分」表：表的大小即独立人数，
    // 每个用户的得分含义见各自注释，用于排行榜与个人数据查询

    /** 弹幕条数（含表情包弹幕） */
    public static final String DANMU_COUNT = "danmu_count";

    /** 弹幕用户计分表，得分为该用户发送的弹幕条数 */
    public static final String DANMU_USERS = "danmu_users";

    /** 付费礼物价值，单位：元（含盲盒开出的礼物价值） */
    public static final String GIFT_VALUE = "gift_value";

    /** 礼物用户计分表，得分为该用户送出的礼物价值（元） */
    public static final String GIFT_USERS = "gift_users";

    /** 醒目留言用户计分表，得分为该用户的醒目留言总额（元） */
    public static final String SUPER_CHAT_USERS = "super_chat_users";

    /** 盲盒用户计分表，得分为该用户开出的盲盒个数 */
    public static final String BOX_USERS = "box_users";

    /** 盲盒盈亏用户计分表，得分为该用户的盲盒盈亏（元），可为负 */
    public static final String BOX_PROFIT_USERS = "box_profit_users";

    /** 大航海用户计分表，得分为该用户开通大航海的次数 */
    public static final String GUARD_USERS = "guard_users";

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

    // 以下三项是**开播那一刻的快照**，由 setLiveMetric 写入而非累加。
    // 报告展示的是「现在多少、这场涨了多少」，涨幅由绘制时的实时值减去快照得到——
    // 这样直播中的实时报告与下播报告共用同一段逻辑，不必再单独记一份终值

    /** 开播时的粉丝数 */
    public static final String FANS_AT_START = "fans_at_start";

    /** 开播时的粉丝团人数 */
    public static final String FANS_MEDAL_AT_START = "fans_medal_at_start";

    /** 开播时的大航海人数 */
    public static final String GUARD_AT_START = "guard_at_start";

    private BilibiliLiveMetric() {
    }
}
