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

    /** 付费礼物价值，单位：元（含盲盒开出的礼物价值）。这是「<b>主播到手</b>」的口径 */
    public static final String GIFT_VALUE = "gift_value";

    /**
     * 付费礼物的<b>实扣</b>金额，单位：元
     * <p>
     * 「实扣」= 平台从送礼人账上扣掉多少，<b>不是「他花了多少人民币」</b>。
     * 账上的余额可能是白来的（抢红包、活动、签到），也可能是以某个充值优惠价买来的；
     * 那笔充值交易不经过直播间，所以<b>真实人民币支出在这里根本不存在</b>，不要试图去算。
     * <p>
     * 与 {@link #GIFT_VALUE} 的差别只在盲盒与背包礼物上，普通礼物两者相等：
     * <ul>
     *     <li><b>盲盒</b>：扣的是盲盒的价，主播收到的是开出物的价值，两者可以差很远</li>
     *     <li><b>背包礼物</b>：扣 0，主播却有收益——礼物是抢红包、活动或签到白得的</li>
     * </ul>
     * 背包礼物<b>只能靠 {@code bag_gift} 字段认出来</b>：实测它的 {@code total_coin}
     * 等于礼物原价而不是 0，拿金额去猜必然猜错。
     * 两个口径的差额本身就有意义，它约等于「靠盲盒运气与红包活动带来的那部分收益」。
     * <p>
     * <b>键名仍是 {@code gift_paid}，与「实扣」这个叫法不一致，这是有意为之。</b>
     * 它是落盘的键，线上 {@code data.json} 里存着历史数据；改名会让历史数据变成孤儿，
     * 或者需要一次迁移。要改得连同口径版本化与历史回算一起做，别单独动。
     */
    public static final String GIFT_PAID = "gift_paid";

    /**
     * 礼物用户计分表，得分为该用户送出礼物的<b>主播到手价值</b>（元）
     * <p>
     * <b>与 {@link #GIFT_VALUE} 同口径，这是硬要求</b>：卡片上的「礼物 ¥86.6」与
     * 排行榜上的每一行必须能相加对得上，否则报告里的数字互相打架，主播读不出结论。
     * <p>
     * 早先这里记的是实扣（{@link #GIFT_PAID} 口径），理由是「按到手价值排等于按运气排名：
     * 花 100 元开出一堆小心心的人排榜尾，花 10 元中了大奖的人排榜首」。
     * 那个理由<b>只对盲盒成立，而它漏了背包礼物这个反例</b>——
     * 背包礼物实扣恒为 0，于是送出一份 80 元的背包礼物在榜上记 0，
     * 而同一份礼物在卡片的总额里照记 80。2026-08-10 实际发生过：
     * 观众送出 80 元礼物，礼物榜显示 ¥1.1，礼物总额 ¥86.6，
     * <b>同一份报告里同一件事有两个数，而界面上没有任何地方说明它们不同口径</b>。
     * <p>
     * 盲盒「按运气排名」的顾虑改由 {@link #BOX_PROFIT_USERS} 那张独立的盲盒盈亏榜表达：
     * 为它把主榜改成实扣，代价落在每一个送背包礼物的观众身上，而收益已经由另一张榜提供了。
     * <p>
     * 想看「谁真的掏了钱」用 {@link #GIFT_PAID} 的口径另开一张榜，别改这一张。
     */
    public static final String GIFT_USERS = "gift_users";

    /**
     * 礼物榜的口径说明，展示在榜单与礼物数据卡片旁
     * <p>
     * <b>报告图片、排行榜命令与我的数据命令用的是同一份文本，就是这一份</b>——
     * 与 {@code ANONYMOUS_NOTICE} 同样的理由：分三处各写一遍，改了一处忘了另一处，
     * 界面之间就会自己打起来，而这句话存在的意义正是消除那种打架。
     * <p>
     * 「到手价值」而不是「花了多少钱」：背包礼物与盲盒开出物都按主播收到的价值计，
     * 那与观众账上被扣掉的数额不是一回事，更不等于人民币支出
     * （{@link #GIFT_PAID} 的注释里讲了为什么后者根本算不出来）。
     */
    public static final String GIFT_RANKING_NOTE = "礼物按主播到手价值计，与礼物总额同口径；背包与盲盒礼物同样如此，不是观众实付";

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

    /**
     * 看过人数，本场累计
     * <p>
     * <b>不是「同时在线人数」。</b>它只增不减，画出来永远在往上爬；
     * 真正有信息量的是曲线的<b>斜率</b>，也就是每分钟新来多少人。
     * <p>
     * 平台每分钟下发数次，每次给的都是当前值，因此以「取最大」而非累加的方式记录。
     */
    public static final String WATCHED_COUNT = "watched_count";

    /**
     * 高能用户数
     * <p>
     * 高能榜只收有过消费的观众，所以这个数远小于观看人数，更接近「有多少人真的掏了钱」。
     * 与只增不减的看过人数不同，它会随时间涨落。同样是瞬时量，取最大而非累加。
     */
    public static final String ONLINE_RANK_COUNT = "online_rank_count";

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
