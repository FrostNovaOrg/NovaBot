package org.frostnova.nova.core.sender;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.alert.AlertBlockedException;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.health.PushActivityRecorder;
import org.frostnova.nova.core.model.Message;
import org.frostnova.nova.core.model.MessagePlaceholders;
import org.frostnova.nova.core.model.Sender;
import org.frostnova.nova.core.service.AtAllQuotaService;
import org.frostnova.nova.core.service.NovaSenderService;
import org.frostnova.nova.core.timeline.TimelineEvent;
import org.frostnova.nova.core.timeline.TimelineEventType;
import org.frostnova.nova.core.timeline.TimelineWriter;
import org.frostnova.nova.core.util.HttpUtil;
import org.frostnova.nova.core.lang.StringUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/**
 * NovaBot 消息发送器
 */
@Slf4j
@Service
public class NovaMessageSender {
    private final HttpUtil http;

    private final NovaSenderService senderService;

    private final PushActivityRecorder activityRecorder;

    /**
     * 推送闸门
     * <p>
     * 作用于 {@link #send(Message)} 与 {@link #sendAlert(Message)}：前者两道都拦，
     * 后者只拦全局开关——静音挡的是打扰，告警正是要叫人的那一条。
     * {@link #sendNow(Message)} 不受它管：那是使用者主动发起的测试消息，
     * 若也被静音拦下，只会让人以为「配置又出问题了」，与验证配置的初衷相悖。
     */
    private final PushGate pushGate;

    /**
     * 事件时间线。只写不读，写失败由它自己吞掉
     */
    private final TimelineWriter timeline;

    private final AtAllQuotaService atAllQuota;

    private List<PlatformDeliveryGate> platformGates = List.of();

    @Autowired(required = false)
    public void setPlatformGates(@Lazy List<PlatformDeliveryGate> gates) {
        platformGates = gates;
    }

    private String platformBlock(Message message) {
        return platformBlock(message.getPlatform(), message.getCreateTime());
    }

    /** 重投按原消息或告警的发生时间判定，避免重开通道后补发旧消息。 */
    public String platformBlock(String platform, Instant createdAt) {
        for (PlatformDeliveryGate gate : platformGates) {
            String reason = gate.blockedReason(platform, createdAt);
            if (reason != null) return reason;
        }
        return null;
    }

    /**
     * 首次推送后的用法提示
     */
    private final FirstPushTipService firstPushTip;

    /**
     * @全体成员 的权限判定，由推送平台适配器提供；没有实现时一律放行
     */
    private final ObjectProvider<AtAllPermissionResolver> atAllPermissionResolvers;

    /**
     * 单个平台的发送队列容量
     * <p>
     * 取值需容得下一次开播高峰（数十个主播同时开播、每人多条分段消息），
     * 又不至于在 OneBot 长时间掉线时把内存吃光。
     */
    private static final int QUEUE_CAPACITY = 500;

    /**
     * 停机时等待队列排空的时长上限
     */
    private static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(5);

    /**
     * 单条消息的最大投递次数
     */
    private static final int SEND_MAX_ATTEMPTS = 3;

    /**
     * 重试基准间隔，实际间隔按次数线性放大
     */
    private static final long SEND_RETRY_INTERVAL_MILLIS = 500;

    private final ExecutorService executor = Executors.newCachedThreadPool();

    // 被挤掉的消息属于其他调用方，回调不能借用本次发送线程持有的服务锁。
    private final ExecutorService droppedCallbacks = Executors.newSingleThreadExecutor(
            task -> new Thread(task, "sender-dropped-callback"));

    private final Map<String, BlockingQueue<Message>> queueMap = new ConcurrentHashMap<>();

    private final Map<String, Future<?>> platformTasks = new ConcurrentHashMap<>();

    private final AtomicLong droppedCount = new AtomicLong();

    @Autowired
    public NovaMessageSender(HttpUtil http, NovaSenderService senderService,
                                PushActivityRecorder activityRecorder, PushGate pushGate,
                                TimelineWriter timeline, AtAllQuotaService atAllQuota,
                                ObjectProvider<AtAllPermissionResolver> atAllPermissionResolvers,
                                FirstPushTipService firstPushTip) {
        this.http = http;
        this.senderService = senderService;
        this.activityRecorder = activityRecorder;
        this.pushGate = pushGate;
        this.timeline = timeline;
        this.atAllQuota = atAllQuota;
        this.atAllPermissionResolvers = atAllPermissionResolvers;
        this.firstPushTip = firstPushTip;
    }

    /**
     * 将消息加入至消息队列
     * <p>
     * 静音时段与全局开关关着时丢弃并记时间线，普通推送、命令回复、首推提示都走这一口。
     * @param message 消息
     */
    public void send(Message message) {
        if (discardIfPlatformBlocked(message) != null) return;
        // 按这条消息发往的会话判：会话可以有自己的静音时段或不静音
        if (!pushGate.allowedFor(message)) {
            PushGate.Block block = pushGate.blockedBy();
            log.info("{}, 已丢弃消息: [{}] {}: {}", block.getDescription(),
                    message.getType().getStr(), message.getNum(), summary(message));

            // 被丢掉的那条正是使用者最想知道的一条——日志里它只有一行 INFO，
            // 默认级别下人还得先知道去哪儿翻才找得到。
            //
            // 走事件分发那条路的推送在更早一层就被拦下了（{@link NovaHandlerListener}），
            // 那一层记的是一整场的聚合条目（谁的通知、几个会话）。到得了这里的是
            // <b>不经过事件分发的那些</b>：命令回复、首推提示——它们没有「一场」可归，
            // 因此逐条记；两层各记各的，不会为同一条消息记两遍
            timeline.record(TimelineEvent.of(block.timelineType(), TimelineEvent.Level.WARN)
                    .channel(describeTarget(message))
                    .text(block.getDescription() + "，丢弃了发往" + describeTarget(message) + "的一条消息")
                    .detail("summary", summary(message))
                    .detail("platform", message.getPlatform())
                    .build());
            return;
        }

        enqueue(message);
    }

    /** 周报仅在过闸时入队；被静音拦下时交还调度器顺延，不丢弃。 */
    public PushGate.Block sendWeeklyReport(Message message) {
        if (discardIfPlatformBlocked(message) != null) return null;
        if (!pushGate.allowedFor(message)) {
            return pushGate.blockedBy();
        }
        enqueue(message);
        return null;
    }

    /**
     * 将告警消息加入发送队列
     * <p>
     * 与 {@link #send(Message)} 只差在闸门：告警不受静音时段拦——静音挡的是打扰，
     * 而告警恰恰是出了事要叫人的那一条，半夜也得发。全局开关照旧拦：
     * 「关闭后所有推送都会被丢弃」说的就是全部，不含告警这个例外。
     * <p>
     * 被拦下时<b>抛出</b>，而不是像 {@link #send(Message)} 那样丢弃加记时间线：
     * 告警的调用方（{@link org.frostnova.nova.core.alert.AlertService}）要向使用者
     * 如实报告「没发出去、为什么」，静默返回只会让界面与时间线都写着已报出——
     * 两头各记各的，不会为同一条告警记两遍。
     * <p>
     * 抛的是 {@link AlertBlockedException}：告警服务认这个类型，把「被开关拦下」
     * 当作这一次的最终失败——记一条没发出去及原因，不进重投队列，开关打开后也不补发。
     * @param message 告警消息
     */
    public void sendAlert(Message message) {
        String reason = platformBlock(message);
        if (reason != null) throw new AlertBlockedException(reason);
        if (!pushGate.alertsAllowed()) {
            throw new AlertBlockedException(pushGate.blockReason() + "，这条告警没有发出");
        }

        // 标成告警：告警送达后不跟首推用法提示——提示教的是「推送怎么用」，
        // 而此刻收件人要处理的是出了的事，两件事不该挨在一起
        message.setAlert(true);
        enqueue(message);
    }

    /**
     * 把过闸的消息排进平台队列：找不到平台配置的丢弃，队列满了丢最旧的
     */
    private void enqueue(Message message) {
        Optional<Sender> optionalSender = senderService.getSender(message.getPlatform());
        if (optionalSender.isEmpty()) {
            log.warn("未找到 {} 推送平台配置, 请检查配置文件是否正确配置, 已丢弃消息: [{}] {}: {}", message.getPlatform(), message.getType().getStr(), message.getNum(), summary(message));
            message.setFailureReason("未找到推送平台「" + message.getPlatform() + "」配置");
            runEach(message.markFailed(), message, "发送失败");
            return;
        }

        BlockingQueue<Message> queue = queueMap.computeIfAbsent(message.getPlatform(), k -> {
            BlockingQueue<Message> newQueue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
            startPlatformThread(optionalSender.get(), newQueue);
            return newQueue;
        });

        // 队列有容量上限：OneBot 掉线时消息会持续堆积，无界队列在小内存机器上迟早耗尽内存。
        // 满时丢弃最旧的一条——通知类消息越旧价值越低，保留新的比保留旧的合理
        while (!queue.offer(message)) {
            Message dropped = queue.poll();
            if (dropped == null) {
                continue;
            }

            String pause = platformBlock(dropped);
            if (pause == null) {
                dropped.setFailureReason("队列已满, 被挤掉");
                log.warn("{} 平台的发送队列已满({} 条), 丢弃最旧的一条消息: [{}] {}: {}（累计丢弃 {} 条）",
                        message.getPlatform(), QUEUE_CAPACITY, dropped.getType().getStr(),
                        dropped.getNum(), summary(dropped), droppedCount.incrementAndGet());
            }
            try {
                droppedCallbacks.execute(() -> {
                    if (pause == null) runEach(dropped.markFailed(), dropped, "发送失败");
                    else discardForPlatform(dropped, pause);
                });
            } catch (RejectedExecutionException e) {
                log.warn("停机时丢弃被挤掉消息的失败回调: [{}]{}", dropped.getSequence(), summary(dropped));
            }
        }
    }

    /**
     * 累计因队列积压被丢弃的消息数
     * @return 丢弃数
     */
    public long getDroppedCount() {
        return droppedCount.get();
    }

    /**
     * 当前各平台队列中积压的消息数之和
     * @return 积压数
     */
    public int getPendingCount() {
        return queueMap.values().stream().mapToInt(BlockingQueue::size).sum();
    }

    /**
     * 立即发送一条消息并返回推送接口的原始响应
     * <p>
     * 与 {@link #send(Message)} 的区别在于绕过队列、同步返回结果，供配置界面的「发送测试消息」使用：
     * 配置完成后若没有任何办法当场验证，群号写错、Token 不匹配、OneBot 未启动、机器人不在群里
     * 这四类错误的表现完全一样——什么都不发生，只能等到真实事件发生时才发现配错了。
     * @param message 消息
     * @return 推送接口的原始响应
     */
    public JSONObject sendNow(Message message) {
        JSONObject blocked = discardIfPlatformBlocked(message);
        if (blocked != null) return blocked;
        Sender sender = senderService.getSender(message.getPlatform())
                .orElseThrow(() -> new IllegalArgumentException("未找到推送平台 " + message.getPlatform()));

        // 不当作推送：这是「发送测试消息」按钮，一次点击就该只出去一条。
        // 首推提示跟在它后面的话，按钮的名字与它干的事就对不上了
        return doSend(sender, message, false);
    }

    /**
     * 替外部调用方当场发一条，交回这一次的结果
     * <p>
     * 给程序接口用：调用方要按「发出了／没发出／不明」决定下一步，所以结果原样交回，
     * 由调用方自己去记。与 {@link #sendNow} 相同的是不排队、不看全局推送开关与静音时段——
     * 这是调用方明确要发的一条，与对外的推送接口同一个口径；去向被停用时照样不发。
     * <p>
     * 与 {@link #sendNow} 不同的几处，都是为了交回去的结果就是这一条的实情：
     * <ul>
     *   <li>不记推送记录：那里记的是完整号码与正文摘要，调用方按自己的口径记（遮号码、不记正文）；
     *       也不让外部程序的成败去左右推送健康的判断。</li>
     *   <li>含图消息没发出时不剥图补发纯文字：补发出去的是另一条（没有图），再回「发出了」就不是实话。</li>
     *   <li>不跟首推提示，不跑消息上的回调。</li>
     * </ul>
     * 每一次都没交到去向手里（去向没连上）时：进程内的去向按约定只在请求没离开本机时才抛，
     * 回 {@link DeliveryReceipt.Outcome#UNREACHABLE}；走 HTTP 的去向分不清是没连上还是等回包超时，
     * 回 {@link DeliveryReceipt.Outcome#UNKNOWN}，宁可让调用方不重发。
     * @param message 一条消息，不含 {@code {next}}
     * @return 结果
     */
    public DeliveryReceipt deliverForCaller(Message message) {
        String blocked = platformBlock(message);
        if (blocked != null) {
            return DeliveryReceipt.of(DeliveryReceipt.Outcome.BLOCKED, blocked);
        }
        Sender sender = senderService.getSender(message.getPlatform()).orElse(null);
        if (sender == null) {
            return DeliveryReceipt.of(DeliveryReceipt.Outcome.MISSING, "没有这个去向");
        }

        AtAllDecision quota = sender.getLocalDelivery() instanceof Sender.DeferredDelivery
                ? AtAllDecision.pass() : applyAtAllQuota(message);
        if (!quota.send()) {
            return DeliveryReceipt.of(DeliveryReceipt.Outcome.EMPTY, "摘掉 @全体成员 后没有内容");
        }
        AtomicReference<LocalDate> chargedOn = new AtomicReference<>(quota.chargedOn());
        try {
            Attempts attempts = attempt(sender, headers(sender), params(message), message);
            JSONObject result = attempts.result();
            if (result == null) {
                String reason = attempts.error() == null ? "未知原因" : attempts.error().getMessage();
                if (sender.getLocalDelivery() == null) {
                    // 可能已经发出（例如等回包超时）：额度不退，那一次可能已经 @ 过
                    chargedOn.set(null);
                    return DeliveryReceipt.of(DeliveryReceipt.Outcome.UNKNOWN, reason);
                }
                releaseOnce(message, chargedOn);
                return DeliveryReceipt.of(DeliveryReceipt.Outcome.UNREACHABLE, reason);
            }
            if (result.getBooleanValue(PlatformDeliveryGate.BLOCKED)) {
                releaseOnce(message, chargedOn);
                return DeliveryReceipt.of(DeliveryReceipt.Outcome.BLOCKED, result.getString("message"));
            }
            message.setCompleteTime(Instant.now());
            if (Integer.valueOf(0).equals(result.getInteger("code"))) {
                chargedOn.set(null);
                return new DeliveryReceipt(DeliveryReceipt.Outcome.SENT, result.getString("id"), null);
            }
            if (Boolean.TRUE.equals(result.getBoolean(Sender.LocalDelivery.DELIVERY_UNKNOWN))) {
                chargedOn.set(null);
                return DeliveryReceipt.of(DeliveryReceipt.Outcome.UNKNOWN, result.getString("message"));
            }
            releaseOnce(message, chargedOn);
            return DeliveryReceipt.of(DeliveryReceipt.Outcome.FAILED, result.getString("message"));
        } catch (RuntimeException ex) {
            releaseOnce(message, chargedOn);
            throw ex;
        }
    }

    /**
     * @全体成员 的占位符
     *
     * @see MessagePlaceholders 占位符的单一来源
     */
    private static final String AT_ALL = MessagePlaceholders.AT_ALL;

    /**
     * 按权限与每日配额处理消息中的 @全体成员
     * <p>
     * 发不出去时把占位符摘掉，退化为普通消息——开播通知本身仍然该发，只是不再 @ 全体。
     * 配置里选了「@全体成员，不行就 @订阅的人」的那一档，摘掉的位置换成
     * {@link Message#getAtAllFallback() 备好的替代文本}。
     * <p>
     * <b>摘完可能什么都不剩。</b>{@code Message.create} 在 {@code {next}} 处就把消息拆开了，
     * 模板写成「{@code {at=all}}{@code {next}}正文」的那一份里，占位符<b>独占一条消息</b>，
     * 摘掉之后那一条就空了。这种情况必须整条不发，否则群里会收到一条空消息。
     * （推送处理器按 @ 模式补的那一块已经并进正文首行，不再造出这种分条；
     * 走到这一步的都是使用者自己在模板里写的。）
     * @return 是否还应发送，以及这次有没有扣到额度、扣在哪一天
     */
    private AtAllDecision applyAtAllQuota(Message message) {
        if (message.getContent() == null || !message.getContent().contains(AT_ALL)) {
            return AtAllDecision.pass();
        }
        // 私聊不存在 @全体成员，不占配额
        if (PushTargetType.GROUP != message.getType()) {
            return AtAllDecision.pass();
        }

        // 权限判定必须排在配额之前：没权限的那次本就发不出 @，
        // 若先扣配额，等于让一个注定被摘掉的 @ 吃掉账号那份全局额度。
        // 两支分开写而不是合成一个或运算，是因为退回时要说得出是哪一件——
        // 「机器人不是管理员」要人去改群权限，「额度用完了」明天自己就好了
        String reason = atAllDenyReason(message);
        if (reason != null) {
            return stripAtAll(message, reason);
        }
        LocalDate chargedOn = atAllQuota.consume(message.getPlatform(), message.getNum());
        if (chargedOn == null) {
            return stripAtAll(message, "今日的 @全体成员 额度已用完");
        }
        return AtAllDecision.charged(chargedOn);
    }

    /**
     * 询问平台适配器：机器人在这个会话里能不能 @全体成员
     * <p>
     * 没有任何适配器认领该平台时放行，保持没有这层判定之前的行为。
     */
    private String atAllDenyReason(Message message) {
        for (AtAllPermissionResolver resolver : atAllPermissionResolvers) {
            if (resolver.supports(message.getPlatform())) {
                if (resolver.canAtAll(message.getPlatform(), message.getNum())) return null;
                String reason = resolver.denyReason(message.getPlatform(), message.getNum());
                return reason == null ? "机器人不是群主或管理员" : reason;
            }
        }
        return null;
    }

    /**
     * 摘掉 @全体成员；备了替代文本就换成它，摘完为空则整条不发
     * <p>
     * <b>两支都记一条时间线</b>，因为两支在使用者那里是同一个问题——「说好的 @全体成员 呢」。
     * 日志里那行只有运维看得见，而这件事是配置的人要知道的：不是管理员要去改群权限，
     * 额度用尽则说明这个群今天已经 @ 过太多次。
     * @param reason 没发出去的原因，进时间线正文与补充键值
     * @return 是否还应发送这条消息。这条没扣过额度
     */
    private AtAllDecision stripAtAll(Message message, String reason) {
        String fallback = StringUtil.isBlank(message.getAtAllFallback()) ? "" : message.getAtAllFallback();
        String stripped = message.getContent().replace(AT_ALL, fallback).trim();

        timeline.record(TimelineEvent.of(TimelineEventType.AT_ALL_SKIPPED, TimelineEvent.Level.WARN)
                .channel(describeTarget(message))
                .text("发往" + describeTarget(message) + "的 @全体成员 未发出（" + reason + "），"
                        + (fallback.isEmpty() ? "本条不再 @ 人" : "已改为 @ 订阅了提醒的人"))
                .detail("reason", reason)
                .detail("platform", message.getPlatform())
                .build());

        if (StringUtil.isBlank(stripped)) {
            log.info("会话 {} 的 @全体成员 未发出（{}）, 摘掉后这一条没有内容了, 整条跳过", message.getNum(), reason);
            return AtAllDecision.drop();
        }

        message.setContent(stripped);
        return AtAllDecision.pass();
    }

    /**
     * 这次 @全体成员 走没走成
     * @param send 正文摘完还有东西、这条还要投递
     * @param chargedOn 扣到的那一天；没扣过则为 null，不能退
     */
    private record AtAllDecision(boolean send, LocalDate chargedOn) {
        static AtAllDecision pass() {
            return new AtAllDecision(true, null);
        }

        static AtAllDecision drop() {
            return new AtAllDecision(false, null);
        }

        static AtAllDecision charged(LocalDate day) {
            return new AtAllDecision(true, day);
        }
    }

    private void releaseAtAll(Message message, LocalDate chargedOn) {
        if (chargedOn == null) {
            return;
        }
        atAllQuota.release(message.getPlatform(), message.getNum(), chargedOn);
    }

    /**
     * 退一次并清掉标记，避免同一笔又在异常出口退第二次
     */
    private void releaseOnce(Message message, AtomicReference<LocalDate> chargedOn) {
        releaseAtAll(message, chargedOn.getAndSet(null));
    }

    /**
     * 带退避重试的投递
     * <p>
     * 此前一次网络抖动就会丢掉一条推送。仅对「请求本身失败」重试；
     * 服务端已明确返回业务失败（如群号不存在）时重试没有意义，只会重复打扰。
     * @return 推送接口的响应；始终非空，彻底失败时返回一个带错误信息的对象
     */
    private JSONObject postWithRetry(Sender sender, Map<String, String> headers, Map<String, Object> params, Message message) {
        Attempts attempts = attempt(sender, headers, params, message);
        if (attempts.result() != null) {
            return attempts.result();
        }
        RuntimeException last = attempts.error();
        return new JSONObject()
                .fluentPut("code", -1)
                .fluentPut("message", "投递失败: " + (last == null ? "未知原因" : last.getMessage()));
    }

    /**
     * 重试走完之后的样子
     * @param result 去向的回包；每一次都抛了或什么也没回时为 null
     * @param error  最后一次的异常，{@code result} 非空时为 null
     */
    private record Attempts(JSONObject result, RuntimeException error) {
    }

    /**
     * {@link #postWithRetry} 的本体：同样的重试，只是把「每一次都没交出去」原样交回，不包成失败回包
     */
    private Attempts attempt(Sender sender, Map<String, String> headers, Map<String, Object> params, Message message) {
        RuntimeException last = null;

        for (int attempt = 1; attempt <= SEND_MAX_ATTEMPTS; attempt++) {
            String reason = platformBlock(message);
            if (reason != null) return new Attempts(PlatformDeliveryGate.blockedResponse(reason), null);
            try {
                // 适配器与核心同进程时直接交过去，不绕本机 HTTP。
                // 绕一圈的代价不是那点开销，而是把投递压在自己服务端口的几个工作线程上：
                // 下游一慢线程就被占满，没有线程去读请求体，几百 KB 的图片因此卡满超时被丢弃，
                // 而体积小的文字一次写进 socket 缓冲区就完事、照常送达——
                // 「文字能发、图片发不出去」正是这么来的
                JSONObject result;
                var local = sender.getLocalDelivery();
                if (local instanceof Sender.DeferredDelivery deferred) {
                    result = deferred.deliver(headers, params, message.deliveryOrigin());
                } else {
                    result = local == null ? http.postJson(sender.getUrl(), headers, params) : local.deliver(headers, params);
                }
                if (result != null) {
                    return new Attempts(result, null);
                }
                last = new IllegalStateException("推送接口未返回任何内容");
            } catch (RuntimeException e) {
                last = e;
            }

            if (attempt < SEND_MAX_ATTEMPTS) {
                log.warn("第 {} 次投递 [{}] 失败, {} 毫秒后重试: {}", attempt, message.getSequence(),
                        SEND_RETRY_INTERVAL_MILLIS * attempt, last.getMessage());
                try {
                    Thread.sleep(SEND_RETRY_INTERVAL_MILLIS * attempt);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        return new Attempts(null, last);
    }

    /**
     * 启动平台发送线程
     * @param sender 推送平台信息
     * @param queue 消息队列
     */
    private void startPlatformThread(Sender sender, BlockingQueue<Message> queue) {
        platformTasks.computeIfAbsent(sender.getName(), p -> executor.submit(() -> {
            Thread.currentThread().setName("sender-" + sender.getName());
            log.info("{} 平台消息发送线程已启动", sender.getName());
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Message message = queue.take();
                    try {
                        // 出队复查平台和全局开关，静音仍只在入队时判断。
                        if (discardIfPlatformBlocked(message) != null) continue;
                        if (!pushGate.alertsAllowed()) {
                            discardQueuedMessage(message);
                            continue;
                        }
                        doSend(sender, message, true);
                    } catch (Exception e) {
                        log.error("{} 平台消息发送异常", sender.getName(), e);
                        message.setFailureReason("发送异常: " + e.getMessage());
                        runEach(message.markFailed(), message, "发送失败");
                    }
                    waitForNextMessage(Math.max(0, sender.getDelay()));
                } catch (InterruptedException e) {
                    // 中断即视为停机：恢复标志后由循环条件退出，此处不必记为错误
                    Thread.currentThread().interrupt();
                    log.info("{} 平台发送线程已停止", sender.getName());
                } catch (Exception e) {
                    log.error("{} 平台消息发送异常", sender.getName(), e);
                }
            }
            return null;
        }));
    }

    void waitForNextMessage(long delay) throws InterruptedException {
        Thread.sleep(delay);
    }

    private void discardQueuedMessage(Message message) {
        PushGate.Block block = PushGate.Block.DISABLED;
        log.info("{}, 已丢弃消息: [{}] {}: {}", block.getDescription(),
                message.getType().getStr(), message.getNum(), summary(message));
        if (!message.isAlert()) {
            timeline.record(TimelineEvent.of(block.timelineType(), TimelineEvent.Level.WARN)
                    .channel(describeTarget(message))
                    .text(block.getDescription() + "，丢弃了发往" + describeTarget(message) + "的一条消息")
                    .detail("summary", summary(message))
                    .detail("platform", message.getPlatform())
                    .build());
        }
        finishBlocked(message, block.getDescription() + (message.isAlert() ? "，这条告警没有发出" : "，这条消息没有发出"));
    }

    private JSONObject discardIfPlatformBlocked(Message message) {
        String reason = platformBlock(message);
        if (reason == null) return null;
        discardForPlatform(message, reason);
        return PlatformDeliveryGate.blockedResponse(reason);
    }

    private void discardForPlatform(Message message, String reason) {
        if (!message.isAlert()) {
            recordPlatformPause(message.getPlatform(), message.getType(), message.getNum(), summary(message), reason);
        }
        finishBlocked(message, reason);
    }

    /** 计划中的推送也可能在生成消息前被停用，和队列中的消息记在同一类时间线里。 */
    public void recordPlatformPause(String platform, PushTargetType type, Long num, String summary, String reason) {
        timeline.record(TimelineEvent.of(TimelineEventType.PUSH_PAUSED, TimelineEvent.Level.WARN)
                .channel(type.getStr() + " " + num).text(reason + "，没有发出")
                .detail("summary", summary).detail("platform", platform).build());
    }

    private void finishBlocked(Message message, String reason) {
        // 告警由通道回报 BLOCKED，告警服务只记失败及原因，不入重投队列。
        message.setBlockedByPushSwitch(true);
        message.setFailureReason(reason);
        message.setCompleteTime(Instant.now());
        runEach(message.markFailed(), message, "发送失败");
        runEach(message.getOnCompleteCallbacks(), message, "发送完毕");
    }

    /**
     * 停机时先尽力把队列中已有的消息发完，再关闭线程池
     * <p>
     * 此前这个线程池既非 Spring 托管也无人关闭，停机时队列里待发的消息直接随进程消失。
     * 等待设有上限：停机不能因为某个平台一直发不出去而无限期拖下去。
     * 被挤掉消息的失败回调另外最多等同样的时长；超时中断正在执行的回调，
     * 丢弃尚未开始的回调并记日志，不借用停机线程同步补跑。
     */
    @PreDestroy
    public void shutdown() {
        int pending = getPendingCount();
        if (pending > 0) {
            log.info("停机前尝试发完队列中剩余的 {} 条消息", pending);

            Instant deadline = Instant.now().plus(DRAIN_TIMEOUT);
            while (getPendingCount() > 0 && Instant.now().isBefore(deadline)) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            int remaining = getPendingCount();
            if (remaining > 0) {
                log.warn("仍有 {} 条消息未能发出, 已放弃等待", remaining);
                for (BlockingQueue<Message> queue : queueMap.values()) {
                    Message left;
                    while ((left = queue.poll()) != null) {
                        left.setFailureReason("停机时未能发出");
                        runEach(left.markFailed(), left, "发送失败");
                    }
                }
            }
        }

        executor.shutdownNow();
        droppedCallbacks.shutdown();
        try {
            if (!droppedCallbacks.awaitTermination(DRAIN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                int abandoned = droppedCallbacks.shutdownNow().size();
                log.warn("停机时被挤掉消息的失败回调仍未完成, 已中断执行并丢弃 {} 个待执行回调", abandoned);
            }
        } catch (InterruptedException e) {
            int abandoned = droppedCallbacks.shutdownNow().size();
            log.warn("停机等待被挤掉消息的失败回调被中断, 丢弃 {} 个待执行回调", abandoned);
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 发送消息
     * @param sender 推送平台信息
     * @param message 消息
     * @param push 这一条是否走的推送路（{@link #send}）。测试消息与首推提示自己不算，
     *             它们不该再引出一条首推提示
     * @return 推送接口的原始响应；被拦截器取消发送时返回 null
     */
    private JSONObject doSend(Sender sender, Message message, boolean push) {
        // 配额检查放在这里而非各推送处理器里：处理器只经手 at_all 参数，
        // 而模板里手写的 {at=all} 同样会 @ 全体。所有消息最终都汇到这一处，
        // 只有在这里拦才拦得全
        AtAllDecision quota = sender.getLocalDelivery() instanceof Sender.DeferredDelivery
                ? AtAllDecision.pass() : applyAtAllQuota(message);
        if (!quota.send()) {
            message.setFailureReason("摘掉 @全体成员 后没有内容");
            runEach(message.markFailed(), message, "发送失败");
            return null;
        }
        AtomicReference<LocalDate> chargedOn = new AtomicReference<>(quota.chargedOn());
        try {
            return deliver(sender, message, push, chargedOn);
        } catch (RuntimeException ex) {
            releaseOnce(message, chargedOn);
            throw ex;
        }
    }

    /** 交给去向时带的请求头：只有去向配了推送接口 Token 时才带 */
    private static Map<String, String> headers(Sender sender) {
        Map<String, String> headers = new HashMap<>();
        if (StringUtil.isNotBlank(sender.getToken())) {
            headers.put("Authorization", "Bearer " + sender.getToken());
        }
        return headers;
    }

    /** 交给去向的消息参数，与推送接口的请求体字段一致 */
    private static Map<String, Object> params(Message message) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("platform", message.getPlatform());
        params.put("type", message.getType().getCode());
        params.put("num", message.getNum());
        params.put("content", message.getContent());
        params.put("sequence", message.getSequence());
        params.put("create_time", message.getCreateTime().toEpochMilli());
        String replyTo = message.getReplyTo();
        if (replyTo != null && !replyTo.isBlank()) {
            params.put("reply_to", replyTo);
        }
        return params;
    }

    /**
     * 投递一条已经决定要发的消息。没送达时把扣过的额度退回
     */
    private JSONObject deliver(Sender sender, Message message, boolean push, AtomicReference<LocalDate> chargedOn) {
        Map<String, String> headers = headers(sender);
        Map<String, Object> params = params(message);

        for (Predicate<Message> interceptor : message.getOnBeforeSendInterceptors()) {
            if (!interceptor.test(message)) {
                log.info("已取消发送消息: NovaBot -> {} ([{}] {}) [{}]: {}", sender.getName(), message.getType().getStr(), message.getNum(), message.getSequence(), summary(message));
                releaseOnce(message, chargedOn);
                message.setFailureReason("发送被拦截");
                runEach(message.markFailed(), message, "发送失败");
                return null;
            }
        }

        // 「推送慢不慢」问的是投递本身花了多久，所以从这里起算而不是从消息创建起算：
        // 后者含排队与平台间隔，那两段在队列积压时会盖过投递耗时，让每一条看起来都很慢
        long startedAt = System.nanoTime();
        JSONObject result = postWithRetry(sender, headers, params, message);
        long elapsedMillis = elapsedMillisSince(startedAt);
        if (result.getBooleanValue(PlatformDeliveryGate.BLOCKED)) {
            releaseOnce(message, chargedOn);
            discardForPlatform(message, result.getString("message"));
            return result;
        }
        message.setCompleteTime(Instant.now());

        for (Runnable callback : message.getOnCompleteCallbacks()) {
            try {
                callback.run();
            } catch (Exception e) {
                log.error("执行消息发送完毕回调异常: [{}]{}", message.getSequence(), summary(message), e);
            }
        }

        // 响应缺少 code 字段时 getInteger 返回 null，直接与 0 比较会因自动拆箱抛出 NPE，
        // 表现为消息静默丢失而日志指向别处
        boolean delivered = Integer.valueOf(0).equals(result.getInteger("code"));
        if (delivered) {
            // 已经送达，后面的记账或提示再出错也不能把这次额度退掉。
            // 编号与「送达」在消息里一起定下来，这之后才登记的成功回调会当场执行
            chargedOn.set(null);
            List<Runnable> succeeded = message.markDelivered(result.getString("id"));
            boolean imageDegraded = Boolean.TRUE.equals(result.get(Sender.LocalDelivery.IMAGE_DEGRADED));
            if (imageDegraded) {
                String reason = result.getString("message");
                activityRecorder.recordImageDegraded(sender.getName(), describeTarget(message), summary(message),
                        reason, elapsedMillis, message.getChannel());
                log.warn("推送已送达（部分图片未送达）: NovaBot -> {} ([{}] {}) [{}]: {}，原因: {}",
                        sender.getName(), message.getType().getStr(), message.getNum(), message.getSequence(),
                        summary(message), reason);
            } else {
                activityRecorder.recordSuccess(sender.getName(), describeTarget(message), summary(message), elapsedMillis, message.getChannel());
                log.info("NovaBot -> {} ([{}] {}) [{}]: {}", sender.getName(), message.getType().getStr(), message.getNum(), message.getSequence(), summary(message));
            }
            runEach(succeeded, message, "发送成功");
            if (imageDegraded) {
                notifyImageDegraded(message);
            }
        } else if (Boolean.TRUE.equals(result.getBoolean(Sender.LocalDelivery.DELIVERY_UNKNOWN))) {
            // 请求已经交出去、没等到回包：对端可能已经发进群。剥图重发纯文字会让群里多一条，
            // 带 @全体成员 的连 @ 一起再发一遍，所以不重发。也不当没发出去：额度不退（那次可能已经 @ 过），
            // 不记进失败次数。跑送达不明回调而不是失败回调——失败回调会让上层按「发不出去」入重投，
            // 群里就多一条一样的。手里没有编号，靠编号的后续（挂群待办）本来就做不成
            chargedOn.set(null);
            activityRecorder.recordUncertain(sender.getName(), describeTarget(message), summary(message), result.getString("message"), elapsedMillis, message.getChannel());
            log.warn("消息送达不明 ({}), 不重发: NovaBot -> {} ([{}] {}) [{}]: {}", result.getString("message"), sender.getName(), message.getType().getStr(), message.getNum(), message.getSequence(), summary(message));
            message.setFailureReason(result.getString("message"));
            runEach(message.markUncertain(), message, "送达不明");
        } else {
            activityRecorder.recordFailure(sender.getName(), describeTarget(message), summary(message), result.getString("message"), elapsedMillis, message.getChannel());
            log.error("消息发送失败 ({}): NovaBot -> {} ([{}] {}) [{}]: {}", result.getString("message"), sender.getName(), message.getType().getStr(), message.getNum(), message.getSequence(), summary(message));

            // 先试去掉图片再发一次文字。文字送到了就按送达算：写下那条文字的编号，跑成功回调。
            // 失败回调只在文字也没送到时才跑，避免群里已经有这条文字，却被记成没发出去。
            // 文字送达不明的那一支同样不当没发出去，回调已在降级那一路跑成送达不明
            FallbackOutcome fallback = fallbackWithoutImages(sender, headers, params, message, result);
            delivered = fallback == FallbackOutcome.DELIVERED;
            if (fallback == FallbackOutcome.UNCERTAIN) {
                // 与首次投递的送达不明同一处理：额度不退——那条纯文字可能已经带着 @ 进了群
                chargedOn.set(null);
            } else if (!delivered) {
                message.setFailureReason(result.getString("message"));
                runEach(message.markFailed(), message, "发送失败");
            }
        }

        // 文字到了才算数，图片降级那一路同样算；没送到的把这次扣的退回扣减当天
        if (delivered) {
            chargedOn.set(null);
        } else {
            releaseOnce(message, chargedOn);
        }

        // 文字到了才算「这个会话听见过机器人说话」，图片降级那一路同样算；
        // 告警不算——它不是推送，跟着出事的通报教人怎么用机器人，说的是另一件事
        if (push && delivered && !message.isAlert() && !(sender.getLocalDelivery() instanceof Sender.DeferredDelivery)) {
            tipAfterFirstPush(sender, message);
        }

        return result;
    }

    /**
     * 含图消息失败后，剥掉图片段重发一次纯文字
     * <p>
     * 要求：<b>推送文字的可达性不得依赖图片的可取性，任何模板写法下都必须成立。</b>
     * 实测（2026-08-11，NapCat）一条消息里图片下载失败会让<b>整条发送失败</b>而不是只丢图，
     * 于是封面拉不到的那一次，开播通知整条消失。
     * <p>
     * 放在这里而不是各推送处理器里，理由与 {@link #applyAtAllQuota} 相同：
     * 图片占位符可以来自处理器的 {@code {cover}}／{@code {picture}}，
     * <b>也可以是使用者在模板里手写的</b>，只有汇合点拦得全——而「任何模板写法下都成立」正是要求本身。
     * <p>
     * 适配器可以用 {@link Sender.LocalDelivery#SKIP_IMAGE_FALLBACK} 表明纯文字同样发不出去。
     * 缺省时，只要含图消息发送失败且剥图后还有正文，就尝试补发。核心无需解析平台的错误文案。
     * <p>
     * ⚠️ <b>唯一可能双发的是「接口谎报失败」</b>：回了错误码但消息其实送到了。
     * 已收成一个有定义的条件——<b>只在响应没带消息 id 时才重发</b>。
     * 2026-08-11 实测的两次失败响应都是 {@code {"code":2, ..., "id":null}} 且群里一条都没出现，
     * 「失败且 id 为空」这一形态已验证等于未送达。响应既报错又带着 id 的属模糊态，只记日志不重发。
     * 残余风险如实记：<b>NapCat 换版本后响应形状可能变，这属于「已知边界」而不是「已解决」</b>，
     * 但它的坏结果（偶尔多发一条纯文字）远轻于现状（封面坏掉就整条不发）。
     * <p>
     * 降级<b>只发一次、不走 {@link #postWithRetry} 的重试</b>，
     * 于是最坏情形是「原内容 N 次 + 纯文字 1 次」而不是 2N 次。
     *
     * @return 纯文字的投递结果：送达／送达不明／没送达
     */
    private FallbackOutcome fallbackWithoutImages(Sender sender, Map<String, String> headers, Map<String, Object> params,
                                                  Message message, JSONObject failure) {
        if (sender.getLocalDelivery() instanceof Sender.DeferredDelivery || platformBlock(message) != null
                || Boolean.TRUE.equals(failure.get(Sender.LocalDelivery.SKIP_IMAGE_FALLBACK))
                || !MessagePlaceholders.containsImage(message.getContent())) {
            return FallbackOutcome.FAILED;
        }

        String deliveredId = failure.getString("id");
        if (StringUtil.isNotBlank(deliveredId)) {
            log.warn("推送含图片的消息失败, 但响应带着消息 id {}, 无法排除其实已送达, 不重发纯文字: [{}]: {}",
                    deliveredId, message.getSequence(), summary(message));
            return FallbackOutcome.FAILED;
        }

        String textOnly = MessagePlaceholders.stripImages(message.getContent()).trim();
        if (StringUtil.isBlank(textOnly)) {
            // 这一条是设计如此，不是异常：开播模板的第二条本就只有封面
            log.debug("消息只含图片段, 剥除后无内容可发, 不重发: [{}]", message.getSequence());
            return FallbackOutcome.FAILED;
        }

        Map<String, Object> textParams = new LinkedHashMap<>(params);
        textParams.put("content", textOnly);

        long startedAt = System.nanoTime();
        JSONObject result;
        try {
            result = sender.getLocalDelivery() == null
                    ? http.postJson(sender.getUrl(), headers, textParams)
                    : sender.getLocalDelivery().deliver(headers, textParams);
        } catch (RuntimeException e) {
            result = new JSONObject().fluentPut("code", -1).fluentPut("message", "投递失败: " + e.getMessage());
        }
        long elapsedMillis = elapsedMillisSince(startedAt);

        // 静默降级是看不见的谎言：三种结局各出一行，
        // 且都要说清「图没送到」，并带上原始失败原因——降级不能掩盖根因
        boolean textDelivered = result != null && Integer.valueOf(0).equals(result.getInteger("code"));
        boolean textUncertain = !textDelivered && result != null
                && Boolean.TRUE.equals(result.getBoolean(Sender.LocalDelivery.DELIVERY_UNKNOWN));
        if (textDelivered) {
            activityRecorder.recordSuccess(sender.getName(), describeTarget(message), summary(message), elapsedMillis, message.getChannel());
            log.warn("推送含图片的消息失败, 已剥除图片段重发纯文字并送达（图片未送达）: NovaBot -> {} ([{}] {}) [{}]: {}；原始失败: {}",
                    sender.getName(), message.getType().getStr(), message.getNum(), message.getSequence(),
                    textOnly, failure.getString("message"));

            runEach(message.markDelivered(result.getString("id")), message, "发送成功");

            notifyImageDegraded(message);
            return FallbackOutcome.DELIVERED;
        } else if (textUncertain) {
            // 与首次投递的送达不明同一处理：这条纯文字可能已经进群，重发就是两条；
            // 也不当没发出去——额度不退、不记进失败，跑送达不明回调而不是失败回调，
            // 失败回调会让上层按「发不出去」入重投，群里就多一条一样的
            String reason = result.getString("message");
            activityRecorder.recordUncertain(sender.getName(), describeTarget(message), summary(message), reason, elapsedMillis, message.getChannel());
            log.warn("剥除图片段后重发的纯文字送达不明（图片未送达, 不重发）: NovaBot -> {} ([{}] {}) [{}]: {}；原始失败: {}；重发送达不明: {}",
                    sender.getName(), message.getType().getStr(), message.getNum(), message.getSequence(),
                    textOnly, failure.getString("message"), reason);
            message.setFailureReason(reason);
            runEach(message.markUncertain(), message, "送达不明");
            return FallbackOutcome.UNCERTAIN;
        } else {
            String reason = result == null ? "推送接口未返回任何内容" : result.getString("message");
            log.warn("剥除图片段后重发仍然失败, 本条消息完全未送达: NovaBot -> {} ([{}] {}) [{}]: {}；原始失败: {}；重发失败: {}",
                    sender.getName(), message.getType().getStr(), message.getNum(), message.getSequence(),
                    textOnly, failure.getString("message"), reason);
            return FallbackOutcome.FAILED;
        }
    }

    private void notifyImageDegraded(Message message) {
        for (Runnable callback : message.getOnImageDegradedCallbacks()) {
            try {
                callback.run();
            } catch (Exception e) {
                log.error("执行图片降级回调异常: [{}]{}", message.getSequence(), summary(message), e);
            }
        }
    }

    /**
     * 剥图重发那一路的结局，与首次投递同样分三种
     */
    private enum FallbackOutcome {
        /**
         * 纯文字已送达
         */
        DELIVERED,
        /**
         * 纯文字送达不明：请求已交出去、没等到回包，可能已经进群
         */
        UNCERTAIN,
        /**
         * 纯文字没送达（或压根没重发）
         */
        FAILED
    }

    /**
     * 跑一批回调。其中一个抛了，记下来，剩下的照样跑，免得一次记账出错把后面的发送收尾冲掉
     */
    private void runEach(List<Runnable> callbacks, Message message, String name) {
        for (Runnable callback : callbacks) {
            try {
                callback.run();
            } catch (Exception e) {
                log.error("执行消息{}回调异常: [{}]{}", name, message.getSequence(), summary(message), e);
            }
        }
    }

    /**
     * 向某个会话推出第一条之后，紧跟一句用法提示
     * <p>
     * <b>紧跟</b>是这件事的一半：它解释的是刚刚那条通知从哪来、还能问点什么，
     * 隔着几条别的推送再来就成了没头没尾的一句。因此走 {@link #doSend} 直投而不重新入队——
     * 队列里排着谁不归这里管，而排在后面就不叫紧跟了。
     * <p>
     * 提示发送前按原推送所属的通道再判一次，静音时不认领这次提示。
     * <p>
     * 剩下的几道判断各有各的事：
     * <ul>
     *     <li><b>回复不算</b>——收件人刚发过一条命令，再教他一遍怎么发命令只是打扰。</li>
     *     <li><b>开关关着不发、也不认领</b>——关掉是「先别教」，不是「当作已经教过」；
     *         认领了，打开之后的第一条也不会再来。</li>
     *     <li><b>认领成功才发</b>——认领与「记下已提示过」是同一个动作，同一时刻推给同一个群的
     *         两条消息不会各发一句。</li>
     * </ul>
     */
    private void tipAfterFirstPush(Sender sender, Message message) {
        if (message.isReply()) {
            return;
        }
        if (!firstPushTip.enabled()) {
            return;
        }
        if (!pushGate.allowedFor(message) || platformBlock(message) != null) return;
        if (!firstPushTip.claim(message.getPlatform(), message.getType(), message.getNum())) {
            return;
        }

        String text = firstPushTip.text(message.getType());
        for (Message tip : Message.create(message.getPlatform(), message.getType(), message.getNum(), text)) {
            tip.setChannel(message.getChannel());
            // 提示自己不算推送：它引不出第二句提示，也不必再走一遍这一整套判断
            doSend(sender, tip, false);
        }
    }

    /**
     * 从一个 {@link System#nanoTime()} 取样起算的毫秒数
     * <p>
     * 用 {@code nanoTime} 而不是两次 {@link Instant#now()} 相减：后者读的是墙上时钟，
     * 校时或夏令时切换的那一刻会给出负数或几小时的耗时，而那种数只在一年里的某几秒出现，
     * 事后既复现不了也说不清。
     */
    private static long elapsedMillisSince(long startedAtNanos) {
        return Duration.ofNanos(System.nanoTime() - startedAtNanos).toMillis();
    }

    private String summary(Message message) {
        var sender = senderService.getSender(message.getPlatform());
        if (sender.isPresent() && sender.get().getLocalDelivery() instanceof Sender.DeferredDelivery) {
            return "外发消息";
        }
        return message.getDisplay();
    }

    /**
     * 描述推送目标，用于推送记录的展示
     * @param message 消息
     * @return 目标描述，例如「群 12345」
     */
    private String describeTarget(Message message) {
        return message.getType().getStr() + " " + message.getNum();
    }
}
