package org.frostnova.nova.adapter.onebot.health;

import org.frostnova.nova.core.plugin.NovaComponent;
import lombok.Getter;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OneBot 连接状态
 * <p>
 * 各处的连接检查此前只把结果写进日志，界面与告警都读不到，使用者只能靠翻日志判断「通没通」。
 * 此处集中记录各推送平台的连通状况，供健康探针读取——探针必须廉价且不阻塞，
 * 因此实际探测由既有的定时任务完成，探针只读这里缓存的结果。
 */
@NovaComponent
public class OneBotConnectionState {
    /**
     * 连接测试用的保留平台名
     * <p>
     * 测试连接时会临时构造一个假平台去调一次真接口。它的结果不代表任何在用的平台，
     * 记进来会污染真实平台的统计——尤其是耗时，测试常常正是指向一个填错的地址。
     */
    public static final String RESERVED_TEST_SENDER = "__connection_test__";

    /**
     * 参与耗时中位数计算的最近样本数
     * <p>
     * 取中位数而不是最近一次：健康时也有偶发慢调用（实测 2026-08-08/09 两天中位
     * <b>0.00 秒</b>、p99 2.4 秒），只看最近一次会被这种毛刺反复翻黄；
     * 真退化时是整体抬升（同一测法 2026-08-10 中位 <b>2.83 秒</b>、p90 10.4 秒）。
     * 中位数能把这两种情形分开，而单点样本不能。
     */
    static final int LATENCY_SAMPLES = 20;

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    /**
     * 记录 HTTP 连接正常
     * @param sender 推送平台名
     * @param detail 补充信息，例如 OneBot 实现版本与登录账号
     */
    public void httpOk(String sender, String detail) {
        entry(sender).http = new Status(Kind.OK, detail, Instant.now());
    }

    /**
     * 记录 HTTP 连接异常
     * @param sender 推送平台名
     * @param kind 异常类型
     * @param detail 失败原因
     */
    public void httpFailed(String sender, Kind kind, String detail) {
        entry(sender).http = new Status(kind, detail, Instant.now());
    }

    /**
     * 记录账号在线
     * <p>
     * 账号是否在线与 HTTP 是否调得通是两件事：QQ 掉线时 OneBot 实现本身完全正常，
     * 接口照样返回 200，只是发出去的消息没人收得到。混在一起记会报成「HTTP 异常」，
     * 让人去查一个根本没坏的东西。
     * @param sender 推送平台名
     * @param detail 补充信息，例如信息来源
     */
    public void accountOnline(String sender, String detail) {
        entry(sender).account = new Status(Kind.OK, detail, Instant.now());
    }

    /**
     * 记录账号已掉线
     * @param sender 推送平台名
     * @param detail 详情
     */
    public void accountOffline(String sender, String detail) {
        entry(sender).account = new Status(Kind.SERVICE_ABNORMAL, detail, Instant.now());
    }

    /**
     * 记录账号在线状态已无从判断
     * <p>
     * 连不上 OneBot 实现时，上一次记录的「在线」会一直挂在界面上，看起来像是还好着。
     * 与其展示一个过期的结论，不如明说现在不知道。
     * @param sender 推送平台名
     * @param detail 无法判断的原因
     */
    public void accountUnknown(String sender, String detail) {
        entry(sender).account = new Status(Kind.UNKNOWN, detail, Instant.now());
    }

    /**
     * 记录 Websocket 已连接
     * @param sender 推送平台名
     */
    public void websocketConnected(String sender) {
        entry(sender).websocket = new Status(Kind.OK, "已连接", Instant.now());
    }

    /**
     * 记录 Websocket 已断开
     * @param sender 推送平台名
     * @param detail 断开原因
     */
    public void websocketDisconnected(String sender, String detail) {
        entry(sender).websocket = new Status(Kind.UNREACHABLE, detail, Instant.now());
    }

    /**
     * 标记该推送平台未启用 Websocket
     * @param sender 推送平台名
     */
    public void websocketDisabled(String sender) {
        entry(sender).websocket = new Status(Kind.DISABLED, "未启用", Instant.now());
    }

    /**
     * 记录一次 OneBot 接口调用的往返耗时
     * <p>
     * 只判「通不通」会漏掉一整类故障。2026-08-10 生产上 OneBot 接口连续十小时每次要
     * 2~11 秒，接口一直返回 200、账号一直在线，状态页因此<b>全程显示正常</b>，
     * 而带图的推送已经在丢：图片是几百 KB 的内联 base64，转发一慢就没有工作线程去读它，
     * 最终卡满超时被丢弃。事后查明是 cgroup 内存软上限在节流整个进程。
     * <p>
     * 那次故障里唯一如实变化的量就是耗时，所以它必须和连通性一样是一个健康维度。
     * 只记成功的调用：失败自有 {@link #httpFailed} 记录并直接判 DOWN，
     * 把失败的耗时混进来只会让中位数变成两种含义的混合物。
     * @param sender 推送平台名
     * @param millis 本次调用的往返耗时，单位: 毫秒
     */
    public void recordLatency(String sender, long millis) {
        if (RESERVED_TEST_SENDER.equals(sender)) {
            return;
        }
        entry(sender).latency.add(millis);
    }

    /**
     * 获取全部推送平台的连接状态
     * @return 推送平台名到状态的映射
     */
    public Map<String, Entry> all() {
        return Map.copyOf(entries);
    }

    /**
     * 获取全部已知的推送平台名
     * @return 推送平台名集合
     */
    public Collection<String> senders() {
        return entries.keySet();
    }

    private Entry entry(String sender) {
        return entries.computeIfAbsent(sender, key -> new Entry());
    }

    /**
     * 状态类型
     */
    public enum Kind {
        /**
         * 正常
         */
        OK,

        /**
         * Token 不正确
         */
        TOKEN_INVALID,

        /**
         * 无法连接
         */
        UNREACHABLE,

        /**
         * OneBot 实现自身状态异常，例如 QQ 账号掉线
         */
        SERVICE_ABNORMAL,

        /**
         * 未启用
         */
        DISABLED,

        /**
         * 尚未检查
         */
        UNKNOWN
    }

    /**
     * 单项状态
     *
     * @param kind 状态类型
     * @param detail 详情
     * @param at 记录时间
     */
    public record Status(Kind kind, String detail, Instant at) {
    }

    /**
     * 单个推送平台的连接状态
     */
    @Getter
    public static class Entry {
        private volatile Status http = new Status(Kind.UNKNOWN, "尚未检查", null);

        private volatile Status account = new Status(Kind.UNKNOWN, "尚未检查", null);

        private volatile Status websocket = new Status(Kind.UNKNOWN, "尚未检查", null);

        private final Latency latency = new Latency();
    }

    /**
     * 最近若干次调用的往返耗时
     * <p>
     * 有意做成固定长度的环：健康探针会被状态页反复调用，不能让它读一个持续增长的列表。
     */
    public static class Latency {
        private final long[] samples = new long[LATENCY_SAMPLES];

        private int count;

        private int next;

        private synchronized void add(long millis) {
            samples[next] = millis;
            next = (next + 1) % samples.length;
            if (count < samples.length) {
                count++;
            }
        }

        /**
         * 已积累的样本数
         * @return 样本数
         */
        public synchronized int count() {
            return count;
        }

        /**
         * 最近若干次调用耗时的中位数
         * <p>
         * 样本不足时返回空，而不是拿一两个样本硬算一个数字充当结论。
         * @return 中位数（毫秒），样本不足时为空
         */
        public synchronized Optional<Long> median() {
            if (count < 3) {
                return Optional.empty();
            }
            long[] sorted = Arrays.copyOf(samples, count);
            Arrays.sort(sorted);
            return Optional.of(sorted[count / 2]);
        }

        /**
         * 最近若干次调用里最慢的一次
         * @return 最大耗时（毫秒），无样本时为空
         */
        public synchronized Optional<Long> max() {
            if (count == 0) {
                return Optional.empty();
            }
            long worst = samples[0];
            for (int i = 1; i < count; i++) {
                worst = Math.max(worst, samples[i]);
            }
            return Optional.of(worst);
        }
    }
}
