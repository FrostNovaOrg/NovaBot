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
 * 判据只用<b>手上已经有的信号</b>：谁关的、关闭码、这条连接活了多久、认证过没有。
 * 不为归因增加任何接口调用——需要告警的时候往往正是网络不好的时候。
 * <p>
 * ⚠️ <b>「是谁关的」必须由调用方告知，不能从关闭码猜。</b>这一条是踩出来的：
 * 早先的判据只看「是否已被永久关闭」与关闭码，于是本端心跳超时后主动重连时——
 * 我们自己 {@code session.close()} 关掉会话，容器回调给的关闭码正是 1000——
 * 被一路归成「服务端正常关闭」。2026-08-11 早上我据此写了一份汇报，
 * 把一次<b>笔记本合盖睡眠</b>说成平台断线，PM 又在这个错归因上加工出了一层机制解释。
 * <p>
 * <b>关闭码 1000 的含义只是「有人正常关闭了连接」，「有人」是谁不在这个字段里。</b>
 * 所以入参用 {@link Closer} 而不是布尔：类型逼着调用方回答这个问题，
 * 而不是留一个默认值让它悄悄错下去。
 */
@Getter
@AllArgsConstructor
public enum BilibiliDisconnectCause {
    /**
     * 我们自己关的，而且不打算再连（停止监听、程序退出）
     */
    BY_US("主动关闭", "正常，不必处理"),

    /**
     * 我们自己关的，为了立刻重连
     * <p>
     * 触发它的是<b>本端</b>的判断：心跳超时、发包失败、断流后先重连一次验证。
     * 所以它与 {@link #BY_US} 分开——那个不必处理，这个要处理，而且
     * <b>要往本机方向查</b>：出网、机器挂起、本机时钟，都会让本端误判连接不可用。
     * <p>
     * 它被记进断线摘要，{@code BY_US} 不记：停止监听不是故障，本端判定连接失效是。
     */
    LOCAL_RECONNECT("本端主动重连", "本端判定连接不可用（心跳超时/发包失败/断流验证），先查出网与本机是否被挂起"),

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
     * 这次关闭是谁发起的
     * <p>
     * 做成枚举而不是布尔：这三种情形的处置方向完全不同（不必管 / 往本机查 / 往平台查），
     * 而它们的关闭码可以一模一样。
     */
    public enum Closer {
        /** 对端（平台）关的，或者连接自己断了 */
        PLATFORM,

        /** 本端关的，而且不再重连：停止监听、程序退出 */
        US_STOPPING,

        /** 本端关的，为了立刻重连：心跳超时、发包失败、断流验证 */
        US_RECONNECTING
    }

    /**
     * 按现场信号归因
     * @param closer 这次关闭是谁发起的，<b>不能从关闭码猜</b>，见类注释
     * @param closeCode WebSocket 关闭码
     * @param authenticated 这条连接认证成功过没有
     * @param lived 这条连接活了多久，未知时传 null
     * @return 归因
     */
    public static BilibiliDisconnectCause classify(Closer closer, int closeCode,
                                                   boolean authenticated, Duration lived) {
        if (closer == Closer.US_STOPPING) {
            return BY_US;
        }
        if (closer == Closer.US_RECONNECTING) {
            return LOCAL_RECONNECT;
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
