package com.starlwr.bot.bilibili.health;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.Duration;

/**
 * 一次断线的归因
 * <p>
 * <b>为什么要归因</b>：此前日志只写「连接已断开 (1006)，将尝试重连」。1006 的含义是
 * 「连接被切断且没有收到关闭帧」——它把好几件完全不同的事说成同一句话：
 * 认证被服务端拒、家里网断了、服务端重启、被风控踢。这四件的处理方式毫不相干，
 * 而看日志的人只能看到同一个数字。
 * <p>
 * 判据只用<b>手上已经有的信号</b>：关闭码、这条连接活了多久、认证过没有。
 * 不为归因增加任何接口调用——需要告警的时候往往正是网络不好的时候。
 */
@Getter
@AllArgsConstructor
public enum BilibiliDisconnectCause {
    /**
     * 我们自己关的（停止监听、程序退出）
     */
    BY_US("主动关闭", "正常，不必处理"),

    /**
     * 服务端发了正常关闭帧
     */
    SERVER_CLOSED("服务端正常关闭", "多为服务端重启或房间下播，重连即可"),

    /**
     * 认证还没过就断了
     * <p>
     * 历史上这正是 {@code 8eafd67} 那个身份竞态的表现：认证包带了匿名 token 与登录 uid，
     * 服务端握手后立刻切断且不发关闭帧。所以这一类<b>要优先怀疑认证包内容</b>，
     * 而不是怀疑网络。
     */
    REJECTED_AT_HANDSHAKE("握手期被切断", "优先查认证包内容与凭据是否一致，其次查出网"),

    /**
     * 认证过了，但活得很短
     */
    DROPPED_EARLY("认证后很快被切断", "服务端主动踢或风控嫌疑，看是否成串出现"),

    /**
     * 活了一段时间之后异常中断
     */
    NETWORK_FLAP("网络抖动", "单次属正常，成串出现才查出网质量"),

    /**
     * 关闭码不是 1006 也不是 1000
     */
    OTHER("其它关闭码", "按关闭码查协议");

    /**
     * 认证之后多短算「很快」。取 30 秒：一次心跳周期都没撑过去，
     * 不可能是普通的网络抖动
     */
    private static final Duration EARLY = Duration.ofSeconds(30);

    private final String label;

    /**
     * 给人的下一步提示。归因不写建议等于只把问题换了个说法
     */
    private final String hint;

    /**
     * 按现场信号归因
     * @param closedByUs 是否我们自己关的
     * @param closeCode WebSocket 关闭码
     * @param authenticated 这条连接认证成功过没有
     * @param lived 这条连接活了多久，未知时传 null
     * @return 归因
     */
    public static BilibiliDisconnectCause classify(boolean closedByUs, int closeCode,
                                                   boolean authenticated, Duration lived) {
        if (closedByUs) {
            return BY_US;
        }
        if (closeCode == 1000) {
            return SERVER_CLOSED;
        }
        if (closeCode != 1006) {
            return OTHER;
        }
        if (!authenticated) {
            return REJECTED_AT_HANDSHAKE;
        }
        // 活了多久算不出来时按抖动处理：宁可把一次早断说成抖动，
        // 也不要凭空指认「认证有问题」让人去查一个好的认证包
        return lived != null && lived.compareTo(EARLY) < 0 ? DROPPED_EARLY : NETWORK_FLAP;
    }
}
