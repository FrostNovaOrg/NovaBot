package com.starlwr.bot.bilibili.service;

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
 * 冷清的直播间由样本量下限挡住：环境消息也很少时总量不达标，直接不判定——
 * 「没人说话」和「说了但我们收不到」必须区分开。
 * <p>
 * <b>未开播的直播间一律不判定。</b>没有直播就没人发弹幕，业务消息必然为零，
 * 而排行、观看人数这些环境消息照旧在来，样本量下限挡不住它。
 * 2026-08-10 生产实测过这个误报：两个未开播/轮播的房间三个窗口共 10 条消息、
 * 业务 0 条，被判成风控并触发告警。<b>样本量下限挡的是「冷清」，不是「没在播」。</b>
 */
public class BilibiliLiveRoomRiskDetector {
    /**
     * 判定所需的最小消息总量（跨全部观察窗口累计）
     * <p>
     * 低于此值说明环境消息本身也很稀疏，属于冷清而非断流，不予判定。
     */
    static final int MIN_TOTAL = 10;

    /**
     * 单个观察窗口的计数
     *
     * @param total 窗口内收到的全部消息数
     * @param business 其中的业务消息数（弹幕、礼物、醒目留言、上舰等）
     * @param interact 其中的进房类消息数，仅作辅助信号
     * @param living 本窗口内主播是否在直播
     */
    public record Window(int total, int business, int interact, boolean living) {
        /**
         * 在播窗口
         * <p>
         * 「未开播不判定」这条规则之前写的调用方都是在播场景，保留三参数形式供它们使用。
         */
        public Window(int total, int business, int interact) {
            this(total, business, interact, true);
        }
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
     * @return 判定为异常时返回<b>只陈述观测的</b>描述，否则为空
     */
    public Optional<String> accept(Window window) {
        // 未开播的直播间必然满足「业务消息为零」：没有直播就没人发弹幕，
        // 而排行、观看人数这些环境消息照旧涓涓地来，轻松越过 MIN_TOTAL。
        // 2026-08-10 生产实测：两个未开播/轮播的房间，三个窗口共 10 条消息、
        // 业务 0 条、进房 0 条，被判成「已被数据风控」并触发告警——纯误报。
        // MIN_TOTAL 挡的是「冷清」，挡不住「没在播」，这两件事要分开挡。
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

        // 环境消息也稀疏，属于冷清而非断流
        if (total < MIN_TOTAL) {
            return Optional.empty();
        }

        if (business > 0) {
            return Optional.empty();
        }

        // 只陈述观测到了什么，不断言原因——我们无法从这里区分
        // 「平台限制了下发」「主播那边确实没人说话但有人进出」「协议变更导致解析不出业务消息」
        return Optional.of(String.format(
                "连续 %d 个窗口共收到 %d 条消息，其中业务消息（弹幕/礼物/醒目留言）0 条，进房类 %d 条",
                requiredWindows, total, interact));
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
