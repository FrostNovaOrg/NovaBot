package org.frostnova.nova.bilibili.service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

/**
 * 直播间数据风控判定
 * <p>
 * <b>判据是「业务消息断流」，不是「进房消息多」。</b>
 * <p>
 * <b>真被限制下发时是整条流按比例削减，不是只削业务消息。</b>
 * 两次登录态对照实验都是这个形状：
 * <ul>
 *     <li>2026-08-07，被限制那条连接 29.3 分钟：弹幕 6/141 = <b>4.3%</b>，
 *         而环境消息（进房、排行、点赞等）133/2479 = <b>5.4%</b>，总计 139/2620 = 5.3%</li>
 *     <li>2026-08-10，同房间同时刻的成对连接：按 cmd 分类逐项都在 10~13%，总计 11.3%</li>
 * </ul>
 * 削减比例在两次实验里不同（5% 与 11%），但<b>业务与环境被削得一样多</b>。
 * <p>
 * ⚠️ 此处原先写的是「环境消息照收，业务消息几乎为零」，<b>那是错的</b>，
 * 而且推翻它的数据当时就在同一份汇报里。错法值得记住：
 * 只看被限制那条连接<b>内部</b>的构成——环境 133 条对业务 6 条，差 22 倍——
 * 看着就像「环境照收、业务归零」。<b>但那是房间流量本来的构成，不是选择性限流的证据。</b>
 * 没有登录侧做分母，这个对比说明不了任何事。同一个错在 2026-08-10 又犯了一次，
 * 靠成对连接才看清。
 * <p>
 * 这不影响判据本身：<b>业务消息连续多窗口为零</b>在两种解释下都成立，
 * 受影响的只是「为什么会这样」的机理描述。
 * <p>
 * 此前的判据是「进房消息占比 ≥50%」，它会误报：热门房间人来人往，
 * 进房消息天然刷屏。同日实测一个 41 万人气的游戏区房间，进房占比 53% 被判风控，
 * 而它同时段每分钟收 71 条弹幕、到达率 93.3%，完全正常。
 * <b>「进房占比高」与「收不到业务消息」是两回事</b>，进房占比在这里只作辅助信号，
 * 用于把观测说清楚，不参与判定。
 * <p>
 * <b>样本量下限只数逐用户事件（业务 + 进房类），定时推送不算。</b>
 * 排行、观看人数、点赞总数这些是平台按秒下发的，<b>与有没有人在互动无关</b>：
 * 任何在播房间几分钟内都必然攒够十几条，冷清房也一样。
 * 拿「全部消息数」当样本量，等于用一个恒真的条件去挡冷清。
 * <p>
 * ⚠️ 这条是 2026-08-10 深夜的生产误报换来的，而<b>成因当时就写在下面 accept 的注释里</b>：
 * 上一句说「环境消息照旧涓涓地来，轻松越过下限」，下一句却断定「下限挡的是冷清」——
 * 同一个理由推出两个相反的结论。实测那次判定是「13 条消息、业务 0、进房类 1」，
 * 13 条里 12 条是排行与看过；同期成对观察量到基准 3 条弹幕 / 我们 3 条，
 * 采集根本没停，主播那三分钟只是确实没人说话。
 * <p>
 * 换成逐用户事件之后，触发形状只剩一种：<b>进房类有量而业务为零</b>。
 * 「两者皆零」那一类（冷清、没在播、连接刚建）自然落在下限之外。
 * <p>
 * <b>未开播的直播间一律不判定。</b>没有直播就没人发弹幕，业务消息必然为零。
 * 2026-08-10 白天实测过这个误报：两个未开播/轮播的房间三个窗口共 10 条消息、
 * 业务 0 条，被判成风控并触发告警。
 * <b>这一条与上面那条下限各挡一件事，不能互相代替</b>——
 * 轮播房也可能有人进出，光靠下限挡不住「没在播」。
 */
public class BilibiliLiveRoomRiskDetector {
    /**
     * 判定所需的最小<b>逐用户事件</b>数（业务 + 进房类，跨全部观察窗口累计）
     * <p>
     * 低于此值说明这段时间本来就没多少人在互动，属于冷清而非断流，不予判定。
     * <p>
     * <b>不能用消息总量代替。</b>总量里含排行、看过、点赞总数这类定时推送，
     * 它们与有没有人互动无关，在任何在播房间都会自己攒够——
     * 用总量当下限等于没有下限，见类注释里那次误报。
     * <p>
     * ⚠️ 分子里<b>没有</b>算进房特效（{@code ENTRY_EFFECT}）与单次点赞
     * （{@code LIKE_INFO_V3_CLICK}）——它们同样是逐用户事件，因此这个下限偏保守，
     * 只有点赞没有弹幕与进房的房间会被判成冷清。<b>这个方向是有意选的</b>：
     * 判据宁可少报也不能像之前那样把安静读成故障。要放宽的话，
     * 得先有实测说明那种形状确实出现过。
     */
    static final int MIN_USER_EVENTS = 10;

    /**
     * 单个观察窗口的计数
     *
     * @param total 窗口内收到的全部消息数，<b>只用于把观测说清楚，不参与判定</b>
     * @param business 其中的业务消息数（弹幕、礼物、醒目留言、上舰等）
     * @param interact 其中的进房类消息数。与 business 一起构成样本量下限的分子
     * @param parseFailed 其中的解析失败条数（未知 cmd 与解析异常）。计入 total 但
     *                    <b>不参与样本量下限</b>：解析失败不是逐用户事件，
     *                    让它顶下限的话，协议全断（连进房都解析不出）时反而能凑数报降级
     * @param living 本窗口内主播是否在直播
     */
    public record Window(int total, int business, int interact, int parseFailed, boolean living) {
        /**
         * 无解析失败的四参数形式，视为在播
         * <p>
         * 解析失败计数出现之前的调用方都长这样，保留供它们使用
         */
        public Window(int total, int business, int interact, boolean living) {
            this(total, business, interact, 0, living);
        }

        /**
         * 在播窗口
         * <p>
         * 「未开播不判定」这条规则之前写的调用方都是在播场景，保留三参数形式供它们使用。
         */
        public Window(int total, int business, int interact) {
            this(total, business, interact, 0, true);
        }
    }

    /**
     * 一次判定：观测描述＋解析降级标志
     *
     * @param observation 只陈述观测的描述，不断言原因
     * @param parseDegraded 观察窗内是否存在解析失败。「业务为零」既能来自没人说话，
     *                      也能来自协议变更解析不出——这两件事的处置方向完全不同，
     *                      描述里说得出口之外，还得让调用方机器可读地分得开
     */
    public record Judgment(String observation, boolean parseDegraded) {
    }

    private final Deque<Window> recent = new ArrayDeque<>();

    private final int requiredWindows;

    /**
     * @param requiredWindows 连续多少个窗口业务消息为零才判定，至少 1
     */
    public BilibiliLiveRoomRiskDetector(int requiredWindows) {
        this.requiredWindows = Math.max(1, requiredWindows);
    }

    /**
     * 记录一个窗口的计数并判定
     * @param window 本窗口计数
     * @return 判定为异常时返回<b>只陈述观测的</b>判定（含解析降级标志），否则为空
     */
    public Optional<Judgment> accept(Window window) {
        // 未开播的直播间必然满足「业务消息为零」：没有直播就没人发弹幕。
        // 2026-08-10 生产实测：两个未开播/轮播的房间，三个窗口共 10 条消息、
        // 业务 0 条、进房 0 条，被判成「已被数据风控」并触发告警——纯误报。
        // 轮播房也可能有人进出，所以这一条与样本量下限各挡一件事，不能互相代替。
        if (!window.living()) {
            recent.clear();
            return Optional.empty();
        }

        recent.addLast(window);
        while (recent.size() > requiredWindows) {
            recent.pollFirst();
        }

        if (recent.size() < requiredWindows) {
            return Optional.empty();
        }

        int total = recent.stream().mapToInt(Window::total).sum();
        int business = recent.stream().mapToInt(Window::business).sum();
        int interact = recent.stream().mapToInt(Window::interact).sum();
        int parseFailed = recent.stream().mapToInt(Window::parseFailed).sum();

        // 这段时间本来就没多少人在互动，属于冷清而非断流。
        // 分子只算逐用户事件：总量里的排行/看过是定时推送，攒够十几条不代表有人在
        if (business + interact < MIN_USER_EVENTS) {
            return Optional.empty();
        }

        if (business > 0) {
            return Optional.empty();
        }

        // 只陈述观测到了什么，不断言原因——我们无法从这里区分
        // 「平台限制了下发」「主播那边确实没人说话但有人进出」「协议变更导致解析不出业务消息」。
        // 把「逐用户事件几条」写进来，是为了让判据在日志里可核对：
        // 光写总数的话，读日志的人无法判断这次触发到底越过了哪个门槛
        String observation = String.format(
                "连续 %d 个窗口共收到 %d 条消息，其中逐用户事件 %d 条"
                        + "（业务消息（弹幕/礼物/醒目留言）0 条、进房类 %d 条）",
                requiredWindows, total, business + interact, interact);

        // 业务为零而解析失败在涨：与「房间没人说话」是两件事，必须说得出口。
        // 条数写进描述让人可核对，标志位交给调用方分成因——
        // 降级缺口与断流缺口在采集账上不是一个东西
        if (parseFailed > 0) {
            observation += String.format(
                    "，另有 %d 条消息收到但解析失败（解析降级，区别于房间没人发言）", parseFailed);
        }

        observation += "，其余为排行/看过一类的定时推送";
        return Optional.of(new Judgment(observation, parseFailed > 0));
    }

    /**
     * 清空观察历史
     * <p>
     * 断线重连后必须调用：跨连接累计会让重连前的窗口与重连后的混在一起。
     */
    public void reset() {
        recent.clear();
    }
}
