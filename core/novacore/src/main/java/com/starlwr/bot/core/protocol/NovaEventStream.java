package com.starlwr.bot.core.protocol;

import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 事件输出协议 v2 的编号与回补中枢
 * <p>
 * 只负责三件事：给每条消息编号、把最近的消息留在环形缓冲里、把新消息分发给订阅者。
 * <b>不碰 WebSocket、不依赖 Spring</b>——编号与回补的正确性能被单元测试完整覆盖，
 * 而这正是协议里最容易出错、又最难在真实连接上复现的部分。
 *
 * <h2>seq 的语义</h2>
 * 从 1 开始严格递增，<b>只有房间级与用户级消息占号</b>，{@code hello}/{@code ping}/
 * {@code pong}/{@code resume} 这些控制类消息不占。会话内不重号、不缺号，
 * 因此下游可以拿它做去重与断线回补的唯一依据。
 * <p>
 * 进程重启后 seq 从头开始，同时 {@link #getSessionId()} 换成一个新值。
 * <b>客户端必须用 sessionId 判断是不是同一条流</b>：只看 seq 会把「重启后的第 5 条」
 * 当成「重启前那条 5 的重复」而丢掉。
 *
 * <h2>缓冲窗口</h2>
 * 缓冲是全房间共用的一条流，不是每个房间一份。所以窗口能覆盖多长时间，
 * 取决于当时连了几个房间、各自多热闹——三个房间每分钟各 100 条时，
 * 2000 条的缓冲约覆盖 6 分钟，够断线重连用；房间再多就要相应调大。
 */
public class NovaEventStream {
    /**
     * 一条已编号并序列化好的消息
     * <p>
     * 序列化在编号时一次做完而不是发送时每个订阅者各做一遍：同一帧要发给所有订阅者，
     * 还要留在缓冲里等回补，重复序列化没有意义。
     *
     * @param seq 会话内序号
     * @param json 完整信封的 JSON 文本
     */
    public record Frame(long seq, String json) {
    }

    /**
     * 帧订阅者
     */
    public interface Subscriber {
        /**
         * 收到一帧
         * @param frame 帧
         */
        void onFrame(Frame frame);
    }

    /**
     * 本次进程的会话标识
     * <p>
     * 去掉连字符只是为了日志里短一点，没有语义。
     */
    private final String sessionId = UUID.randomUUID().toString().replace("-", "");

    /**
     * 环形缓冲容量，单位为消息条数
     */
    private final int capacity;

    /**
     * 编号、缓冲、订阅者三者必须在同一把锁下变更
     * <p>
     * 订阅时要「取一段回补 + 加入实时流」，这两步之间若有新消息挤进来，
     * 客户端就会缺一条或多一条。分开加锁看着更细粒度，实际上把竞态藏了起来。
     */
    private final Object lock = new Object();

    private final Deque<Frame> buffer = new ArrayDeque<>();

    private final Set<Subscriber> subscribers = new LinkedHashSet<>();

    /**
     * 已分配出去的最大序号，尚未发过任何消息时为 0
     */
    private long lastSeq = 0;

    /**
     * @param capacity 环形缓冲容量，至少 1
     */
    public NovaEventStream(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    public String getSessionId() {
        return sessionId;
    }

    /**
     * @return 环形缓冲容量，单位为消息条数
     */
    public int getCapacity() {
        return capacity;
    }

    /**
     * @return 已分配出去的最大序号，尚未发过任何消息时为 0
     */
    public long getLastSeq() {
        synchronized (lock) {
            return lastSeq;
        }
    }

    /**
     * 流在某一瞬间的状态
     *
     * @param lastSeq 已分配出去的最大序号
     * @param bufferedFrom 缓冲里最老的序号。缓冲为空时为 {@code lastSeq + 1}，
     *                     含义是「从下一条起才有」——这样它在任何状态下都有确切含义，
     *                     不必让客户端去分辨「0 是空缓冲还是真有第 0 条」
     */
    public record State(long lastSeq, long bufferedFrom) {
    }

    /**
     * 取一份状态快照
     * <p>
     * <b>两个数必须一起取。</b> 分两次读会拿到两个不同瞬间的值，
     * 客户端据此算出的「能不能回补」就可能是错的。
     * @return 快照
     */
    public State snapshot() {
        synchronized (lock) {
            Frame oldest = buffer.peekFirst();
            return new State(lastSeq, oldest == null ? lastSeq + 1 : oldest.seq());
        }
    }

    /**
     * 编号、缓冲并分发一条消息
     * <p>
     * <b>序列化必须带上 {@code WriteNulls}。</b> fastjson2 默认丢弃空值，
     * 而协议里 {@code face}、{@code medal}、{@code emoji}、{@code replyTo}、
     * {@code blindBox}、{@code combo}、{@code companionDays} 都是「可以为 null」
     * 而不是「可以不存在」——少了这些键，按协议写的客户端会当成字段缺失而报校验错。
     * @param envelope 信封，不含 {@code seq}，由本方法补上
     * @return 编号后的帧
     */
    public Frame publish(JSONObject envelope) {
        synchronized (lock) {
            envelope.put("seq", ++lastSeq);
            Frame frame = new Frame(lastSeq, envelope.toString(JSONWriter.Feature.WriteNulls));

            buffer.addLast(frame);
            while (buffer.size() > capacity) {
                buffer.pollFirst();
            }

            // 🔴 必须遍历副本：onFrame 会同步回调进 unsubscribe。
            // 那不是假想情况，是设计上就会走到的路——端点在「客户端队列满」时断开它，
            // 断开就退订，而此刻我们正拿着迭代器站在这个集合上（锁可重入，进得来）。
            // 直接遍历原集合会抛 ConcurrentModificationException，
            // 而且抛在发布线程上，也就是整条弹幕流水线上。
            // 副本的语义也正是我们要的：这一帧照发给当时在册的每一位，退订下一帧生效。
            for (Subscriber subscriber : List.copyOf(subscribers)) {
                subscriber.onFrame(frame);
            }
            return frame;
        }
    }

    /**
     * 订阅实时流，并按需回补缺口
     * <p>
     * 回补与转入实时流在同一把锁下完成，中间不会漏进新消息，因此订阅者收到的序号
     * 一定是连续的。
     * @param subscriber 订阅者
     * @param fromSeq 客户端已持有的最大序号，回补从它之后一条开始；传 {@code lastSeq} 即不回补
     * @return 缺口是否补齐。为 {@code false} 说明缺口已滑出缓冲窗口，
     *         订阅者<b>未</b>被加入实时流，调用方应让客户端重新握手
     */
    public boolean subscribe(Subscriber subscriber, long fromSeq) {
        synchronized (lock) {
            // 客户端报的序号比我们发出去的还大，只可能是它拿着别的会话的进度。
            // 这不是「缺口太大」而是「根本不是同一条流」，同样拒绝
            if (fromSeq > lastSeq) {
                return false;
            }

            List<Frame> replay = new ArrayList<>();
            for (Frame frame : buffer) {
                if (frame.seq() > fromSeq) {
                    replay.add(frame);
                }
            }

            // 缓冲里凑不齐 fromSeq+1 .. lastSeq 这一整段，说明缺口已经滑出窗口。
            // 此时宁可让客户端重来，也不能把残缺的一段发过去——补了一半的累计
            // 看起来精确而实际少算，比明说「补不上」危险得多
            if (replay.size() != lastSeq - fromSeq) {
                return false;
            }

            for (Frame frame : replay) {
                subscriber.onFrame(frame);
            }
            subscribers.add(subscriber);
            return true;
        }
    }

    /**
     * 退订
     * @param subscriber 订阅者
     */
    public void unsubscribe(Subscriber subscriber) {
        synchronized (lock) {
            subscribers.remove(subscriber);
        }
    }

    /**
     * @return 当前订阅者数量
     */
    public int getSubscriberCount() {
        synchronized (lock) {
            return subscribers.size();
        }
    }
}
