package com.starlwr.bot.core.alert;

import com.starlwr.bot.core.config.StarBotCoreProperties;
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
    private final StarBotCoreProperties properties;

    private final ObjectProvider<AlertChannel> channels;

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
    public AlertService(StarBotCoreProperties properties, ObjectProvider<AlertChannel> channels) {
        this.properties = properties;
        this.channels = channels;
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
            } catch (Exception e) {
                // 单个通道失败不应影响其他通道
                log.error("通过 {} 通道发送告警失败", channel.name(), e);
            }
        }

        return new Delivery(attempted, delivered);
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
