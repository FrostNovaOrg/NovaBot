package org.frostnova.nova.core.alert;

import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.timeline.TimelineEvent;
import org.frostnova.nova.core.timeline.TimelineEventType;
import org.frostnova.nova.core.timeline.TimelineWriter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 告警服务
 * <p>
 * 把告警统一投递到所有可用通道，并按「问题标识」做收敛：故障往往持续存在，
 * 不收敛就会反复推送同一条消息，最终使人对告警彻底脱敏，真出事时反而看不见。
 *
 * <h2>发不出去的告警会入队重投</h2>
 *
 * <b>需要告警的时候往往正是发不出去的时候</b>：出网劣化、QQ 掉登录、Webhook 服务抖动，
 * 这三种情况本身就是要告警的事，而告警的出口同时也断了。此前发送失败只留一行日志，
 * 于是「没收到告警」被读成「没出事」——这是最坏的一种沉默。
 * <p>
 * 现在失败的告警进队列，按 {@code retry-interval} 重投，补发时在正文开头写明
 * <b>原始发生时刻</b>与延迟了多久。
 *
 * <h2>什么算失败</h2>
 *
 * <ul>
 *     <li><b>有通道可用但全都抛了异常</b> → 入队重投</li>
 *     <li><b>一个通道成功、另一个失败</b> → <b>不重投</b>。人已经收到了这条告警，
 *         重投只会让他收到第二条一样的</li>
 *     <li><b>压根没有配置任何通道</b> → 不入队。没有出口，重投一万次也是失败，
 *         队列只会被填满然后开始丢东西</li>
 * </ul>
 *
 * <h2>队列只在进程内</h2>
 *
 * 重启会丢掉待重投的告警。<b>这一点在退出时如实写进日志</b>（连同丢掉的是哪几条），
 * 而不是假装队列可靠——把队列落盘要处理陈旧告警、重复告警与文件损坏，
 * 收益远小于代价，任务书也明确说重启丢失如实标注即可。
 */
@Slf4j
@Service
public class AlertService {
    /**
     * 测试告警的标题与正文
     * <p>
     * 写明是人按出来的：收到的人第一反应是「出事了」，而这条恰恰不是。
     */
    private static final String TEST_SUBJECT = "NovaBot 告警通道测试";

    private static final String TEST_CONTENT = "这是一条测试消息，由控制台上的「发一条测试」按出来，不代表出了任何问题。"
            + "能收到它，说明这一路告警通道是通的。";

    private final NovaCoreProperties properties;

    private final ObjectProvider<AlertChannel> channels;

    /**
     * 事件时间线
     * <p>
     * 记的是「这条告警报没报出去」，与 {@link HealthAlertMonitor} 记的「哪一项状态变了」
     * 是两件事。最坏的一种故障里两者同时发生：出网断了，于是既该告警、又发不出告警——
     * 而「没收到告警」在使用者眼里与「没出事」长得一模一样。
     */
    private final TimelineWriter timeline;

    /**
     * 各问题标识最近一次告警的时间
     */
    private final Map<String, Instant> lastAlertAt = new ConcurrentHashMap<>();

    /**
     * 待重投队列。用双端队列是为了满了之后能从头部丢最旧的
     */
    private final Deque<PendingAlert> pending = new ArrayDeque<>();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "alert-retry");
        thread.setDaemon(true);
        return thread;
    });

    @Autowired
    public AlertService(NovaCoreProperties properties, ObjectProvider<AlertChannel> channels,
                        TimelineWriter timeline) {
        this.properties = properties;
        this.channels = channels;
        this.timeline = timeline;
    }

    /**
     * 启动重投轮询
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReadyEvent() {
        int interval = properties.getAlert().getRetryInterval();
        if (interval <= 0) {
            log.info("告警重投已关闭, 发送失败的告警只会写进日志");
            return;
        }

        scheduler.scheduleWithFixedDelay(this::retryPending, interval, interval, TimeUnit.SECONDS);
        log.info("告警重投已启动, 间隔 {} 秒, 队列上限 {} 条", interval, properties.getAlert().getRetryQueueSize());
    }

    /**
     * 退出时如实说出队列里还剩什么
     * <p>
     * 队列在进程内，重启即丢。<b>丢了要说</b>——否则这些告警就成了既没送到、
     * 也没人知道它存在过的东西。
     */
    @EventListener(ContextClosedEvent.class)
    public void onContextClosedEvent() {
        scheduler.shutdownNow();

        List<PendingAlert> lost;
        synchronized (pending) {
            if (pending.isEmpty()) {
                return;
            }
            lost = new ArrayList<>(pending);
            pending.clear();
        }

        log.error("退出时还有 {} 条告警没送出去, 队列只在进程内, 这些告警将随本次退出丢失:", lost.size());
        for (PendingAlert alert : lost) {
            log.error("  未送出: [{}] {} （发生于 {}, 已尝试 {} 次）",
                    alert.key(), alert.subject(), alert.occurredAtText(), alert.attempts());
        }
    }

    /**
     * 发送告警
     * @param key 问题标识，同一标识在收敛间隔内只会告警一次
     * @param subject 标题
     * @param content 内容
     */
    public void alert(String key, String subject, String content) {
        if (!properties.getAlert().isEnabled()) {
            return;
        }

        if (!shouldSend(key)) {
            log.debug("告警 {} 处于收敛期内, 本次不再发送", key);
            return;
        }

        Instant now = Instant.now();
        Delivery delivery = deliver(subject, content);

        if (delivery.delivered()) {
            return;
        }

        if (!delivery.attempted()) {
            log.warn("未配置任何可用的告警通道, 以下问题仅记录在日志中: {} - {}", subject, content);
            return;
        }

        enqueue(new PendingAlert(key, subject, content, now, 1));
    }

    /**
     * 往某一路通道发一条测试告警
     * <p>
     * <b>走的是这条通道真正的发送路径</b>，不是模拟。「配好了没有」这件事只有真发一条才答得出：
     * Webhook 地址填错一个字母、发件授权码过期、机器人被踢出那个群，三者在配置文件上
     * 都长得完全正常，而它们的共同后果是<b>真出事的那天没有人收到告警</b>。
     * <p>
     * 不过收敛闸门，也不入重投队列：这是使用者主动按下的一次动作，他要的就是当场的结果——
     * 收敛会让第二次点击悄无声息，而入队重投会让「没发出去」变成一句延后的、他看不见的话。
     * @param id 通道标识，见 {@link AlertChannel#id()}
     * @return 结果
     */
    /**
     * 已登记的告警通道，顺序即申报顺序。
     * <p>
     * 首页药丸遍历这一份：没有登记的通道不会出现在输出键集里，
     * 而不是以 false 占一个键。
     * @return 通道清单
     */
    public List<AlertChannel> declaredChannels() {
        return channels.orderedStream().toList();
    }

    /**
     * 某一路通道当前是否可用
     * <p>
     * 首页「这一路配好了没有」与测试发送共用同一条判定：按 id 取通道，再问 {@link AlertChannel#isAvailable()}。
     * 没有这一路时按不可用算，不另编一个「没装插件」态——首页那三张卡要的是能不能叫到人。
     * @param id 通道标识，见 {@link AlertChannel#id()}
     * @return 通道在且可用
     */
    public boolean isChannelAvailable(String id) {
        AlertChannel channel = channel(id);
        return channel != null && channel.isAvailable();
    }

    private AlertChannel channel(String id) {
        return channels.orderedStream()
                .filter(item -> item.id().equals(id))
                .findFirst()
                .orElse(null);
    }

    public TestResult test(String id) {
        AlertChannel channel = channel(id);

        if (channel == null) {
            return new TestResult(TestResult.Status.UNKNOWN, id, null, "没有这一路告警通道");
        }

        if (!channel.isAvailable()) {
            return new TestResult(TestResult.Status.NOT_CONFIGURED, id, channel.name(),
                    channel.name() + " 这一路还没配好，先把上面几栏填完并保存。");
        }

        try {
            channel.send(TEST_SUBJECT, TEST_CONTENT);
            return new TestResult(TestResult.Status.DELIVERED, id, channel.name(),
                    "已经往 " + channel.name() + " 发了一条测试告警，去看看收到没有。");
        } catch (Exception e) {
            log.error("测试 {} 告警通道失败", channel.name(), e);
            return new TestResult(TestResult.Status.FAILED, id, channel.name(),
                    channel.name() + " 这一路发不出去：" + e.getMessage());
        }
    }

    /**
     * 一次测试的结果
     * <p>
     * 四个态分开而不是一个 {@code boolean}：「没这一路」「还没配」「发不出去」「发出去了」
     * 的下一步各不相同——分别是查参数、去把栏填完、看错误信息、去手机上看。
     * 压成一个「失败」的话，界面只能给出一句对三种情况都不痛不痒的话。
     *
     * @param status 结果
     * @param id 通道标识
     * @param name 通道名称，通道不存在时为 null
     * @param message 给使用者看的一句话
     */
    public record TestResult(Status status, String id, String name, String message) {
        public enum Status {
            /**
             * 没有这一路通道
             */
            UNKNOWN,
            /**
             * 通道在，但还没配好
             */
            NOT_CONFIGURED,
            /**
             * 发送时抛了
             */
            FAILED,
            /**
             * 发出去了
             */
            DELIVERED
        }

        /**
         * 是不是真的发出去了
         * @return 发出去了返回 true
         */
        public boolean delivered() {
            return status == Status.DELIVERED;
        }
    }

    /**
     * 问题已恢复，清除其收敛记录
     * <p>
     * 不清除的话，故障恢复后短时间内再次出现将被收敛掉，从而错过第二次告警。
     * @param key 问题标识
     */
    public void resolve(String key) {
        lastAlertAt.remove(key);
    }

    /**
     * 待重投的告警条数，供健康探针与测试观察
     * @return 队列长度
     */
    public int pendingCount() {
        synchronized (pending) {
            return pending.size();
        }
    }

    /**
     * 走一轮重投
     * <p>
     * <b>刻意是包内可见而不是私有</b>：测试要能确定地驱动一轮，而不是等定时任务。
     * <p>
     * 重投<b>不过收敛闸门</b>——这条告警的收敛记录在首次尝试时就已经打上了，
     * 再过一次只会把自己的重投拦掉。
     */
    void retryPending() {
        List<PendingAlert> batch;
        synchronized (pending) {
            if (pending.isEmpty()) {
                return;
            }
            batch = new ArrayList<>(pending);
            pending.clear();
        }

        Instant now = Instant.now();
        int sent = 0;
        for (PendingAlert alert : batch) {
            Delivery delivery = deliver(alert.subject(), alert.contentForRedelivery(now));
            if (delivery.delivered()) {
                sent++;
                continue;
            }

            PendingAlert retried = alert.retried();
            if (retried.attempts() > properties.getAlert().getRetryMaxAttempts()) {
                log.error("告警 [{}] {} 重投 {} 次仍失败, 放弃。它发生于 {}, 始终没能送出去",
                        alert.key(), alert.subject(), alert.attempts(), alert.occurredAtText());
                continue;
            }
            enqueue(retried);
        }

        if (sent > 0) {
            log.info("告警通道已恢复, 补发成功 {} 条, 队列剩余 {} 条", sent, pendingCount());
        }
    }

    /**
     * 投一次，返回「有没有通道可试」与「有没有成功」
     * <p>
     * 时间线<b>一路一条</b>，不是一次投递一条：邮件通了而 Webhook 挂了这种情形，
     * 汇总成一条「发出去了」会把挂掉的那一路藏起来——而它挂了多久没人知道。
     * 一条通道都没配好时另记一条，那一种同样是「没人会收到」，
     * 却不属于任何一路通道，按通道记的话它一行都不会出现。
     */
    private Delivery deliver(String subject, String content) {
        boolean attempted = false;
        boolean delivered = false;

        for (AlertChannel channel : channels.orderedStream().toList()) {
            if (!channel.isAvailable()) {
                continue;
            }

            attempted = true;
            try {
                channel.send(subject, content);
                delivered = true;
                timeline.record(TimelineEvent.of(TimelineEventType.ALERT_SENT, TimelineEvent.Level.INFO)
                        .channel(channel.name())
                        .text(channel.name() + "已报出：" + shorten(subject))
                        .detail("subject", subject)
                        .build());
            } catch (Exception e) {
                // 单个通道失败不应影响其他通道
                log.error("通过 {} 通道发送告警失败", channel.name(), e);
                timeline.record(TimelineEvent.of(TimelineEventType.ALERT_FAILED, TimelineEvent.Level.ERROR)
                        .channel(channel.name())
                        .text(channel.name() + "发不出去：" + shorten(subject))
                        .detail("subject", subject)
                        .detail("reason", e.toString())
                        .build());
            }
        }

        if (!attempted) {
            timeline.record(TimelineEvent.of(TimelineEventType.ALERT_FAILED, TimelineEvent.Level.WARN)
                    .text("没有配好的告警通道：" + shorten(subject))
                    .detail("subject", subject)
                    .build());
        }

        return new Delivery(attempted, delivered);
    }

    /**
     * 记进时间线的标题最多留几个字
     * <p>
     * 标题由调用方拼，长度不受约束（问题标识本身可以很长）。日志页那一行是一句人话，
     * 让它撑成一整段的话，同屏能看见的记录就只剩两三条了。全文留在补充键值里。
     */
    private static final int SUBJECT_IN_RECORD = 24;

    private static String shorten(String subject) {
        return subject.length() <= SUBJECT_IN_RECORD ? subject : subject.substring(0, SUBJECT_IN_RECORD) + "…";
    }

    /**
     * 入队，满了丢最旧的并说明丢了哪一条
     */
    private void enqueue(PendingAlert alert) {
        PendingAlert dropped = null;
        int size;

        synchronized (pending) {
            while (pending.size() >= Math.max(1, properties.getAlert().getRetryQueueSize())) {
                dropped = pending.pollFirst();
            }
            pending.addLast(alert);
            size = pending.size();
        }

        if (dropped != null) {
            log.error("待重投队列已满, 丢弃最旧的一条: [{}] {} （发生于 {}）",
                    dropped.key(), dropped.subject(), dropped.occurredAtText());
        }
        log.warn("告警 [{}] {} 发送失败, 已入队等待重投, 队列 {} 条", alert.key(), alert.subject(), size);
    }

    /**
     * 判断是否应当发送
     */
    private boolean shouldSend(String key) {
        Instant last = lastAlertAt.get(key);
        Instant now = Instant.now();

        if (last != null && Duration.between(last, now).getSeconds() < properties.getAlert().getConvergenceInterval()) {
            return false;
        }

        lastAlertAt.put(key, now);
        return true;
    }

    /**
     * 一次投递的结果
     * @param attempted 是否至少有一个通道可用、被试过
     * @param delivered 是否至少有一个通道成功
     */
    private record Delivery(boolean attempted, boolean delivered) {
    }
}
