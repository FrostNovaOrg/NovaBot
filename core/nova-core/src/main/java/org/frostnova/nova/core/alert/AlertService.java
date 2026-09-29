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
import java.util.function.Consumer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
 *     <li><b>有一路被全局推送开关拦下</b>（抛的是 {@link AlertBlockedException}）→ <b>不重投</b>。
 *         开关是使用者自己关的，那是「先别发」，不是「等会儿再发」：攒到开关打开的
 *         那一刻集中补发，正是 {@link org.frostnova.nova.core.sender.PushGate} 注释里
 *         说不许的那种轰炸。它算<b>这一次的最终失败</b>——记一条没发出去及原因，
 *         工程日志一行、不打栈</li>
 *     <li><b>一个通道成功、另一个失败</b> → <b>不重投</b>。人已经收到了这条告警，
 *         重投只会让他收到第二条一样的</li>
 *     <li><b>压根没有配置任何通道</b> → 不入队。没有出口，重投一万次也是失败，
 *         队列只会被填满然后开始丢东西</li>
 *     <li><b>送达不明</b>（请求已交出去、没等到回包）→ <b>不重投</b>。
 *         对端可能已经发进去了，重发就是两条</li>
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

    /**
     * 「发一条测试」等真结果的时长上限
     * <p>
     * QQ 那一路走推送队列，入队后要等发送线程跑完才知道结果。等是值得的——
     * 「发一条测试」的意义就是当场知道通不通。但等待必须有上限：
     * 队列里排着几百条时，让使用者对着一个转圈的按钮等十分钟同样是坏事。
     */
    private final Duration testWaitTimeout;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "alert-retry");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * 已进入退出流程
     * <p>
     * 退出开始后失败回调不再往重投队列塞——那时已经没人报了，塞进去的只会凭空消失。
     * 改为在工程日志里逐条写「随本次退出丢失」。
     */
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    @Autowired
    public AlertService(NovaCoreProperties properties, ObjectProvider<AlertChannel> channels,
                        TimelineWriter timeline) {
        this(properties, channels, timeline, Duration.ofSeconds(10));
    }

    AlertService(NovaCoreProperties properties, ObjectProvider<AlertChannel> channels,
                 TimelineWriter timeline, Duration testWaitTimeout) {
        this.properties = properties;
        this.channels = channels;
        this.timeline = timeline;
        this.testWaitTimeout = testWaitTimeout;
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
        shuttingDown.set(true);
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
        Delivery delivery = deliver(new PendingAlert(key, subject, content, now, 0), content);

        if (!delivery.attempted()) {
            log.warn("未配置任何可用的告警通道, 以下问题仅记录在日志中: {} - {}", subject, content);
        }
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

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<AlertChannel.SendResult> result = new AtomicReference<>();
        try {
            channel.sendReporting(TEST_SUBJECT, TEST_CONTENT, r -> {
                result.set(r);
                latch.countDown();
            });
        } catch (Exception e) {
            log.error("测试 {} 告警通道失败", channel.name(), e);
            return new TestResult(TestResult.Status.FAILED, id, channel.name(),
                    channel.name() + " 这一路发不出去：" + e.getMessage());
        }

        try {
            if (!latch.await(testWaitTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                return new TestResult(TestResult.Status.UNCERTAIN, id, channel.name(),
                        channel.name() + " 这一路还没等到结果。请到 " + channel.name()
                                + " 上看一眼，或到日志页「推送」里查看。");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new TestResult(TestResult.Status.UNCERTAIN, id, channel.name(),
                    channel.name() + " 这一路的等待被打断了，还没等到结果。");
        }

        AlertChannel.SendResult r = result.get();
        return switch (r.status()) {
            case SENT -> new TestResult(TestResult.Status.DELIVERED, id, channel.name(),
                    "已经往 " + channel.name() + " 发了一条测试告警，去看看收到没有。");
            case BLOCKED -> {
                // 被总开关拦下是使用者自己按下的「先别发」，不是故障：工程日志一行、不打栈。
                // 界面回话与别的失败同一句——点的人要听到的正是「没发出去」及原因
                log.warn("测试 {} 告警通道被全局推送开关拦下, 这一次没发出去: {}", channel.name(), r.reason());
                yield new TestResult(TestResult.Status.FAILED, id, channel.name(),
                        channel.name() + " 这一路发不出去：" + r.reason());
            }
            case FAILED -> {
                log.error("测试 {} 告警通道失败: {}", channel.name(), r.reason(), r.cause());
                yield new TestResult(TestResult.Status.FAILED, id, channel.name(),
                        channel.name() + " 这一路发不出去：" + r.reason());
            }
            case UNCERTAIN -> new TestResult(TestResult.Status.UNCERTAIN, id, channel.name(),
                    "请求已经发出去了，没等到 " + channel.name() + " 回话，送没送到说不准，请到 "
                            + channel.name() + " 上看一眼。");
        };
    }

    /**
     * 一次测试的结果
     * <p>
     * 五个态分开而不是一个 {@code boolean}：「没这一路」「还没配」「发不出去」「发出去了」「送达不明」
     * 的下一步各不相同——分别是查参数、去把栏填完、看错误信息、去手机上看、去那一路上看一眼。
     * 压成一个「失败」的话，界面只能给出一句对四种情况都不痛不痒的话。
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
            DELIVERED,
            /**
             * 送达不明：请求发出去了、没等到回话，或还没等到结果，送没送到说不准
             */
            UNCERTAIN
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
        for (PendingAlert alert : batch) {
            deliver(alert, alert.contentForRedelivery(now));
        }
    }

    /**
     * 投一次，结果可能当场定下、也可能稍后由回调补上
     * <p>
     * 时间线<b>一路一条</b>，不是一次投递一条：邮件通了而 Webhook 挂了这种情形，
     * 汇总成一条「发出去了」会把挂掉的那一路藏起来——而它挂了多久没人知道。
     * 一条通道都没配好时另记一条，那一种同样是「没人会收到」，
     * 却不属于任何一路通道，按通道记的话它一行都不会出现。
     * <p>
     * <b>「已报出」等真发出才记。</b>QQ 那一路入队后还要等发送线程跑完才知道结果；
     * 入队时就记「已报出」，失败后时间线上会同时留下「已报出」和「发不出去」两条
     * 互相打架的记录——而时间线只能追加、改不了已经写下的那条。
     * <p>
     * 重投也在这里定：全部路都没发成、也没被开关拦下时入队。
     * 这一步可能发生在回调里（异步路的结果回来之后），此时本方法已经返回。
     */
    private Delivery deliver(PendingAlert alert, String sendContent) {
        List<AlertChannel> available = channels.orderedStream().filter(AlertChannel::isAvailable).toList();

        if (available.isEmpty()) {
            // 标题不截断：这一条是「压根没发出去」，看的人要照着它去查那条告警说的是哪件事；
            // 截成前二十几个字的话，几条不同的告警在时间线里长得一模一样
            timeline.record(TimelineEvent.of(TimelineEventType.ALERT_FAILED, TimelineEvent.Level.WARN)
                    .text("没有配置告警通道，没发出去：" + alert.subject())
                    .detail("subject", alert.subject())
                    .build());
            // 重投撞上「一条通道都没了」：通道可能稍后回来，仍在上限内就留在队里。
            // 先记上这一次再比上限，到了就放弃，不再入队。
            if (alert.attempts() > 0) {
                PendingAlert retried = alert.retried();
                if (retried.attempts() > properties.getAlert().getRetryMaxAttempts()) {
                    log.error("告警 [{}] {} 重投 {} 次仍失败, 放弃。它发生于 {}, 始终没能送出去",
                            alert.key(), alert.subject(), alert.attempts(), alert.occurredAtText());
                } else {
                    enqueueOrLose(retried);
                }
            }
            return new Delivery(false, false, false, false);
        }

        AtomicInteger remaining = new AtomicInteger(available.size());
        AtomicBoolean anySuccess = new AtomicBoolean(false);
        AtomicBoolean anyBlocked = new AtomicBoolean(false);
        AtomicBoolean anyUncertain = new AtomicBoolean(false);

        for (AlertChannel channel : available) {
            String channelName = channel.name();
            // 这一路的结果只收一次：自己抛错时按失败记，已经报过的不再记第二笔
            AtomicBoolean counted = new AtomicBoolean(false);
            Consumer<AlertChannel.SendResult> onResult = result -> {
                if (!counted.compareAndSet(false, true)) {
                    return;
                }
                switch (result.status()) {
                    case SENT -> {
                        anySuccess.set(true);
                        if (alert.attempts() > 0) {
                            log.info("告警通道已恢复, 补发成功: [{}] {} （发生于 {}, 已尝试 {} 次）",
                                    alert.key(), alert.subject(), alert.occurredAtText(), alert.attempts());
                        }
                        timeline.record(TimelineEvent.of(TimelineEventType.ALERT_SENT, TimelineEvent.Level.INFO)
                                .channel(channelName)
                                .text(channelName + "已报出：" + shorten(alert.subject()))
                                .detail("subject", alert.subject())
                                .build());
                    }
                    case BLOCKED -> {
                        // 被总开关拦下是预期内的情形，不是故障：工程日志一行、不打栈。
                        // 仍要记——「没收到告警」在使用者眼里与「没出事」长得一样
                        anyBlocked.set(true);
                        log.warn("{} 告警被全局推送开关拦下, 这一次算没发出去, 不再重投: {}",
                                channelName, result.reason());
                        timeline.record(TimelineEvent.of(TimelineEventType.ALERT_FAILED, TimelineEvent.Level.WARN)
                                .channel(channelName)
                                .text(channelName + "发不出去（" + result.reason() + "）：" + shorten(alert.subject()))
                                .detail("subject", alert.subject())
                                .detail("reason", result.reason())
                                .build());
                    }
                    case FAILED -> {
                        log.error("通过 {} 通道发送告警失败: {}", channelName, result.reason(), result.cause());
                        timeline.record(TimelineEvent.of(TimelineEventType.ALERT_FAILED, TimelineEvent.Level.ERROR)
                                .channel(channelName)
                                .text(channelName + "发不出去：" + shorten(alert.subject()))
                                .detail("subject", alert.subject())
                                .detail("reason", result.reason())
                                .build());
                    }
                    case UNCERTAIN -> {
                        anyUncertain.set(true);
                        log.warn("通过 {} 通道发送告警送达不明: {}", channelName, result.reason());
                        timeline.record(TimelineEvent.of(TimelineEventType.ALERT_UNCERTAIN, TimelineEvent.Level.WARN)
                                .channel(channelName)
                                .text(channelName + "送达不明：" + shorten(alert.subject()))
                                .detail("subject", alert.subject())
                                .detail("reason", result.reason())
                                .build());
                    }
                }

                if (remaining.decrementAndGet() > 0) {
                    return;
                }
                if (anySuccess.get()) {
                    return;
                }
                if (anyBlocked.get()) {
                    // 重投撞上开关关闭：这一条从队列里拿掉，不再放回去。
                    // 留在队里的话，开关一打开它就会带着【补发】标记涌出去
                    if (alert.attempts() > 0) {
                        log.warn("告警 [{}] {} 重投被全局推送开关拦下, 不再重投（发生于 {}）",
                                alert.key(), alert.subject(), alert.occurredAtText());
                    }
                    return;
                }
                if (anyUncertain.get()) {
                    // 送达不明不算发不出去：对端可能已经发进去了，重发就是两条
                    return;
                }
                if (alert.attempts() > 0 && alert.attempts() + 1 > properties.getAlert().getRetryMaxAttempts()) {
                    log.error("告警 [{}] {} 重投 {} 次仍失败, 放弃。它发生于 {}, 始终没能送出去",
                            alert.key(), alert.subject(), alert.attempts(), alert.occurredAtText());
                    return;
                }
                enqueueOrLose(alert.retried());
            };
            try {
                channel.sendReporting(alert.subject(), sendContent, onResult);
            } catch (Exception e) {
                String reason = e.getMessage() != null ? e.getMessage() : e.toString();
                onResult.accept(AlertChannel.SendResult.failed(reason, e));
            }
        }

        return new Delivery(true, anySuccess.get(), anyBlocked.get(), remaining.get() > 0);
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
     * 入队重投；已进入退出流程时不再入队，改为在工程日志里逐条写「随本次退出丢失」
     */
    private void enqueueOrLose(PendingAlert alert) {
        if (shuttingDown.get()) {
            log.error("  随本次退出丢失: [{}] {} （发生于 {}, 已尝试 {} 次）",
                    alert.key(), alert.subject(), alert.occurredAtText(), alert.attempts());
            return;
        }
        enqueue(alert);
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
     * @param delivered 是否至少有一个通道<b>已确认</b>成功（异步路的结果可能还没回来）
     * @param blocked 是否有一路被全局推送开关拦下——被拦下的告警算最终失败，不入队也不重投
     * @param pending 是否还有异步路的结果没回来——回来之前不入重投，免得把还没定的事判成失败
     */
    private record Delivery(boolean attempted, boolean delivered, boolean blocked, boolean pending) {
    }
}
