package com.starlwr.bot.bilibili.service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

/**
 * 直播间数据风控判定
 * <p>
 * <b>判据是「业务消息断流」，不是「进房消息多」。</b>
 * <p>
 * 真被限制下发时的形状是：<b>环境消息照收，业务消息几乎为零</b>——
 * 2026-08-07 的登录态对照实验里，被限制的那条连接 29.3 分钟只收到 6 条弹幕
 * （对独立基准到达率 4.3%），而进房、排行、点赞这些环境消息一条不少。
 * <p>
 * 此前的判据是「进房消息占比 ≥50%」，它会误报：热门房间人来人往，
 * 进房消息天然刷屏。同日实测一个 41 万人气的游戏区房间，进房占比 53% 被判风控，
 * 而它同时段每分钟收 71 条弹幕、到达率 93.3%，完全正常。
 * <b>「进房占比高」与「收不到业务消息」是两回事</b>，进房占比在这里只作辅助信号，
 * 用于把观测说清楚，不参与判定。
 * <p>
 * 冷清的直播间由样本量下限挡住：环境消息也很少时总量不达标，直接不判定——
 * 「没人说话」和「说了但我们收不到」必须区分开。
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
     */
    public record Window(int total, int business, int interact) {
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
