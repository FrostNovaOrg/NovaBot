package org.frostnova.nova.bilibili.model;

/**
 * 哔哩哔哩本场直播统计指标名
 * <p>
 * 指标由 {@code BilibiliLiveStatsAggregator} 在直播期间累计，
 * 存放于 {@link org.frostnova.nova.core.service.LiveDataService}，开播时清零。
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

    /**
     * 礼物榜口径变更过这件事本身，只在<b>跨场累计</b>范围下展示
     * <p>
     * 累计数据是逐场累加上来的，口径变更那一刻之前的部分按旧口径（观众实扣）攒的、
     * 之后的部分按新口径（主播到手价值）攒的，**同一个数里含两段口径**——
     * 这恰好撞上「同名指标必须同口径」那条规矩，所以必须说出来。
     * <p>
     * 不回算、也不归零：历史数据没有留原始礼物流，回算做不到；
     * 归零则是让老观众替一次口径决策买单。剩下的唯一诚实做法就是标注。
     * <p>
     * <b>变更时点写的是版本号而不是日期</b>：这是个发给别人部署的程序，
     * 「什么时候变的」对每个部署者都是「他升级到那个版本的那一刻」，日期只对我们自己成立。
     * <p>
     * ⚠️ <b>这个版本号跟着更新日志里那条走。</b>发布号若在终审时改了，这里要一起改——
     * 它是这个字符串里唯一会过期的东西。
     */
    public static final String GIFT_RANKING_SCOPE_CHANGE_NOTE =
            "累计榜含两段口径：升级到 4.3.0 之前按观众实扣累计，之后按主播到手价值累计，历史数据未回算";

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

    /**
     * 新增关注人次
     * <p>
     * ⚠️ <b>平台把关注/分享播成两种形态，本计数只统计其中一种。</b>
     * 另一种（聚合）是否会让这个数偏小，<b>尚未证实</b>——见下方「已证与未证」：
     * <ul>
     *   <li><b>单播</b> {@code INTERACT_WORD_V2} msg_type=2/3 —— 带 uid 与昵称，本计数只统计它</li>
     *   <li><b>聚合</b> {@code DM_INTERACTION} type=103/105（"N 人关注了主播" /
     *       "N 人分享了直播间"）—— <b>只有人数，没有 uid</b>，代码库目前完全不解析</li>
     * </ul>
     * <b>已证</b>（2026-08-11/12，同一直播间、同一次分享的对照）：
     * <b>登录态连接收到了带 uid 的完整单播事件，匿名连接同一时刻只收到聚合卡片。</b>
     * 本程序正常使用时是登录态的。
     * <p>
     * <b>未证</b>：登录态是否<b>曾经</b>因折叠而漏掉事件。生产不留原始报文，现有数据答不了。
     * <b>别把「未证」写成「没问题」，也别写成「会少报」</b>——当晚两个方向都写错过一次。
     * <p>
     * ⚠️ <b>那次对照分不开两种解释，别把它当成「聚合是匿名专用形态」的证据。</b>
     * 同一房间同一 37 分钟的登录态/匿名对照里，<b>匿名每一类消息都只收到约 1/9</b>
     * （连服务端定时广播的 {@code WATCHED_CHANGE} 也是，逐分钟无一分钟为零，
     * 所以不是掉线）。在这个前提下，「平台对匿名改发聚合」与
     * 「匿名整条流被等比抽稀、那条单播恰好没抽中」都能解释观察到的现象。
     * <b>这个 1/9 只有一房一窗，跨房未验。</b>
     * <p>
     * <b>已被证伪、别再重提的解释</b>：「房间流量越高越倾向折叠」——
     * 被一个人气仅 1327、却只发聚合卡片的直播间推翻。
     * <p>
     * 上面这些数都出自实抓语料，回放办法见 {@code docs/runbook-protocol-corpus.md}。
     */
    public static final String FOLLOW_COUNT = "follow_count";

    /** 进入过直播间的独立用户数 */
    public static final String ENTER_USERS = "enter_users";

    /** 点赞总数（服务端下发的单调累计值） */
    public static final String LIKE_TOTAL = "like_total";

    /** 点赞过的独立用户数 */
    public static final String LIKE_USERS = "like_users";

    /**
     * 分享直播间人次
     * <p>
     * ⚠️ <b>两种播法的问题与 {@link #FOLLOW_COUNT} 完全相同，说明见那里。</b>
     * <b>别写成「高流量房才会折叠」——那个解释已被证伪</b>（见 {@link #FOLLOW_COUNT}）。
     * <p>
     * <b>本项已于 2026-09-02 转正</b>，与 {@link #FOLLOW_COUNT} 同为正式指标。
     * 此前对外标注为「实验性」，差别只在样本量（转正线 8，当时分享只有 5 条）。
     * 现据 2026-08-12～08-30 生产语料：分享 13 条、跨 2 个直播间、13/13 带 uid，
     * 用到的字段全在「关注 81 条 ∪ 进房 3726 条」出现过的字段里，
     * 没有一个是只在分享里出现的。<b>解析逻辑从来就与关注同一套，转正没有动它。</b>
     */
    public static final String SHARE_COUNT = "share_count";

    /**
     * 本场有几条推送<b>文字送到了、图片没送到</b>
     * <p>
     * 由发送侧的兜底触发（{@link org.frostnova.nova.core.sender.NovaMessageSender} 剥掉图片段
     * 重发纯文字并送达时记一次）。<b>它不是平台指标，是我们自己的投递质量指标。</b>
     * <p>
     * ⚠️ <b>为什么要占报告的版面</b>：兜底之后开播通知不再整条丢失，
     * 但主播看到的那条通知是<b>没有封面的</b>——这是他能感知的差异。
     * 日志里那行 WARN 只有运维看得见，主播只会觉得「今天的开播图怎么没了」。
     * <p>
     * <b>为零时报告不显示这一项</b>，绝大多数场次都该是零。
     */
    public static final String IMAGE_DEGRADED_COUNT = "image_degraded_count";

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
     * 高能榜人数（{@code count} 字段）
     * <p>
     * 与只增不减的看过人数不同，它会随时间涨落。瞬时量，取最大而非累加。
     */
    public static final String ONLINE_RANK_COUNT = "online_rank_count";

    /**
     * 在线人数（{@code online_count} 字段，高能榜头部显示的登录观众数）
     * <p>
     * 瞬时量，每分钟取最大。缺该字段时回落到 {@link #ONLINE_RANK_COUNT} 所用的 {@code count}。
     */
    public static final String ONLINE_COUNT = "online_count";

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
