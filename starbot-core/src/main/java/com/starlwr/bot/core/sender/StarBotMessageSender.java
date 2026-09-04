package com.starlwr.bot.core.sender;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.enums.PushTargetType;
import com.starlwr.bot.core.health.PushActivityRecorder;
import com.starlwr.bot.core.model.Message;
import com.starlwr.bot.core.model.MessagePlaceholders;
import com.starlwr.bot.core.model.Sender;
import com.starlwr.bot.core.service.AtAllQuotaService;
import com.starlwr.bot.core.service.StarBotSenderService;
import com.starlwr.bot.core.timeline.TimelineEvent;
import com.starlwr.bot.core.timeline.TimelineEventType;
import com.starlwr.bot.core.timeline.TimelineWriter;
import com.starlwr.bot.core.util.HttpUtil;
import com.starlwr.bot.core.util.StringUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/**
 * StarBot 消息发送器
 */
@Slf4j
@Service
public class StarBotMessageSender {
    private final HttpUtil http;

    private final StarBotSenderService senderService;

    private final PushActivityRecorder activityRecorder;

    /**
     * 推送闸门
     * <p>
     * 只作用于 {@link #send(Message)}：{@link #sendNow(Message)} 是使用者主动发起的测试消息，
     * 若也被静音拦下，只会让人以为「配置又出问题了」，与验证配置的初衷相悖。
     */
    private final PushGate pushGate;

    /**
     * 事件时间线。只写不读，写失败由它自己吞掉
     */
    private final TimelineWriter timeline;

    private final AtAllQuotaService atAllQuota;

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

    private final Map<String, BlockingQueue<Message>> queueMap = new ConcurrentHashMap<>();

    private final Map<String, Future<?>> platformTasks = new ConcurrentHashMap<>();

    private final AtomicLong droppedCount = new AtomicLong();

    @Autowired
    public StarBotMessageSender(HttpUtil http, StarBotSenderService senderService,
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
     * @param message 消息
     */
    public void send(Message message) {
        if (!pushGate.allowed()) {
            PushGate.Block block = pushGate.blockedBy();
            log.info("{}, 已丢弃消息: [{}] {}: {}", block.getDescription(),
                    message.getType().getStr(), message.getNum(), message.getDisplay());

            // 被丢掉的那条正是使用者最想知道的一条——日志里它只有一行 INFO，
            // 默认级别下人还得先知道去哪儿翻才找得到
            timeline.record(TimelineEvent.of(timelineType(block), TimelineEvent.Level.WARN)
                    .channel(describeTarget(message))
                    .text(block.getDescription() + "，丢弃了发往" + describeTarget(message) + "的一条消息")
                    .detail("summary", message.getDisplay())
                    .detail("platform", message.getPlatform())
                    .build());
            return;
        }

        Optional<Sender> optionalSender = senderService.getSender(message.getPlatform());
        if (optionalSender.isEmpty()) {
            log.warn("未找到 {} 推送平台配置, 请检查配置文件是否正确配置, 已丢弃消息: [{}] {}: {}", message.getPlatform(), message.getType().getStr(), message.getNum(), message.getDisplay());
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

            long total = droppedCount.incrementAndGet();
            log.warn("{} 平台的发送队列已满({} 条), 丢弃最旧的一条消息: [{}] {}: {}（累计丢弃 {} 条）",
                    message.getPlatform(), QUEUE_CAPACITY, dropped.getType().getStr(),
                    dropped.getNum(), dropped.getDisplay(), total);
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
        Sender sender = senderService.getSender(message.getPlatform())
                .orElseThrow(() -> new IllegalArgumentException("未找到推送平台 " + message.getPlatform()));

        // 不当作推送：这是「发送测试消息」按钮，一次点击就该只出去一条。
        // 首推提示跟在它后面的话，按钮的名字与它干的事就对不上了
        return doSend(sender, message, false);
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
     * 而 @ 块拼出来的正是「{@code {at=all}} + 分条 + 正文」，于是占位符
     * <b>往往独占一条消息</b>。这种情况必须整条不发，否则群里会收到一条空消息。
     * @return 是否还应发送这条消息
     */
    private boolean applyAtAllQuota(Message message) {
        if (message.getContent() == null || !message.getContent().contains(AT_ALL)) {
            return true;
        }
        // 私聊不存在 @全体成员，不占配额
        if (PushTargetType.GROUP != message.getType()) {
            return true;
        }

        // 权限判定必须排在配额之前：没权限的那次本就发不出 @，
        // 若先扣配额，等于让一个注定被摘掉的 @ 吃掉账号那份全局额度。
        // 两支分开写而不是合成一个或运算，是因为退回时要说得出是哪一件——
        // 「机器人不是管理员」要人去改群权限，「额度用完了」明天自己就好了
        if (!canAtAll(message)) {
            return stripAtAll(message, "机器人不是群主或管理员");
        }
        if (!atAllQuota.tryConsume(message.getPlatform(), message.getNum())) {
            return stripAtAll(message, "今日的 @全体成员 额度已用完");
        }
        return true;
    }

    /**
     * 询问平台适配器：机器人在这个会话里能不能 @全体成员
     * <p>
     * 没有任何适配器认领该平台时放行，保持没有这层判定之前的行为。
     */
    private boolean canAtAll(Message message) {
        for (AtAllPermissionResolver resolver : atAllPermissionResolvers) {
            if (resolver.supports(message.getPlatform())) {
                return resolver.canAtAll(message.getPlatform(), message.getNum());
            }
        }
        return true;
    }

    /**
     * 摘掉 @全体成员；备了替代文本就换成它，摘完为空则整条不发
     * <p>
     * <b>两支都记一条时间线</b>，因为两支在使用者那里是同一个问题——「说好的 @全体成员 呢」。
     * 日志里那行只有运维看得见，而这件事是配置的人要知道的：不是管理员要去改群权限，
     * 额度用尽则说明这个群今天已经 @ 过太多次。
     * @param reason 没发出去的原因，进时间线正文与补充键值
     * @return 是否还应发送这条消息
     */
    private boolean stripAtAll(Message message, String reason) {
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
            return false;
        }

        message.setContent(stripped);
        return true;
    }

    /**
     * 带退避重试的投递
     * <p>
     * 此前一次网络抖动就会丢掉一条推送。仅对「请求本身失败」重试；
     * 服务端已明确返回业务失败（如群号不存在）时重试没有意义，只会重复打扰。
     * @return 推送接口的响应；始终非空，彻底失败时返回一个带错误信息的对象
     */
    private JSONObject postWithRetry(Sender sender, Map<String, String> headers, Map<String, Object> params, Message message) {
        RuntimeException last = null;

        for (int attempt = 1; attempt <= SEND_MAX_ATTEMPTS; attempt++) {
            try {
                // 适配器与核心同进程时直接交过去，不绕本机 HTTP。
                // 绕一圈的代价不是那点开销，而是把投递压在自己服务端口的几个工作线程上：
                // 下游一慢线程就被占满，没有线程去读请求体，几百 KB 的图片因此卡满超时被丢弃，
                // 而体积小的文字一次写进 socket 缓冲区就完事、照常送达——
                // 「文字能发、图片发不出去」正是这么来的
                JSONObject result = sender.getLocalDelivery() == null
                        ? http.postJson(sender.getUrl(), headers, params)
                        : sender.getLocalDelivery().deliver(headers, params);
                if (result != null) {
                    return result;
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

        return new JSONObject()
                .fluentPut("code", -1)
                .fluentPut("message", "投递失败: " + (last == null ? "未知原因" : last.getMessage()));
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
            long delay = sender.getDelay();
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Message message = queue.take();
                    doSend(sender, message, true);
                    Thread.sleep(delay);
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

    /**
     * 停机时先尽力把队列中已有的消息发完，再关闭线程池
     * <p>
     * 此前这个线程池既非 Spring 托管也无人关闭，停机时队列里待发的消息直接随进程消失。
     * 等待设有上限：停机不能因为某个平台一直发不出去而无限期拖下去。
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
            }
        }

        executor.shutdownNow();
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
        if (!applyAtAllQuota(message)) {
            return null;
        }

        Map<String, String> headers = new HashMap<>();
        if (StringUtil.isNotBlank(sender.getToken())) {
            headers.put("Authorization", "Bearer " + sender.getToken());
        }

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("platform", message.getPlatform());
        params.put("type", message.getType().getCode());
        params.put("num", message.getNum());
        params.put("content", message.getContent());
        params.put("sequence", message.getSequence());
        params.put("create_time", message.getCreateTime().toEpochMilli());

        for (Predicate<Message> interceptor : message.getOnBeforeSendInterceptors()) {
            if (!interceptor.test(message)) {
                log.info("已取消发送消息: NovaBot -> {} ([{}] {}) [{}]: {}", sender.getName(), message.getType().getStr(), message.getNum(), message.getSequence(), message.getDisplay());
                return null;
            }
        }

        JSONObject result = postWithRetry(sender, headers, params, message);
        message.setCompleteTime(Instant.now());

        for (Runnable callback : message.getOnCompleteCallbacks()) {
            try {
                callback.run();
            } catch (Exception e) {
                log.error("执行消息发送完毕回调异常: [{}]{}", message.getSequence(), message.getDisplay(), e);
            }
        }

        // 响应缺少 code 字段时 getInteger 返回 null，直接与 0 比较会因自动拆箱抛出 NPE，
        // 表现为消息静默丢失而日志指向别处
        boolean delivered = Integer.valueOf(0).equals(result.getInteger("code"));
        if (delivered) {
            message.setId(result.getString("id"));
            activityRecorder.recordSuccess(sender.getName(), describeTarget(message), message.getDisplay());
            log.info("NovaBot -> {} ([{}] {}) [{}]: {}", sender.getName(), message.getType().getStr(), message.getNum(), message.getSequence(), message.getDisplay());

            for (Runnable callback : message.getOnSuccessCallbacks()) {
                try {
                    callback.run();
                } catch (Exception e) {
                    log.error("执行消息发送成功回调异常: [{}]{}", message.getSequence(), message.getDisplay(), e);
                }
            }
        } else {
            activityRecorder.recordFailure(sender.getName(), describeTarget(message), message.getDisplay(), result.getString("message"));
            log.error("消息发送失败 ({}): NovaBot -> {} ([{}] {}) [{}]: {}", result.getString("message"), sender.getName(), message.getType().getStr(), message.getNum(), message.getSequence(), message.getDisplay());

            for (Runnable callback : message.getOnFailureCallbacks()) {
                try {
                    callback.run();
                } catch (Exception e) {
                    log.error("执行消息发送失败回调异常: [{}]{}", message.getSequence(), message.getDisplay(), e);
                }
            }

            delivered = fallbackWithoutImages(sender, headers, params, message, result);
        }

        // 文字到了才算「这个会话听见过机器人说话」，图片降级那一路同样算
        if (push && delivered) {
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
     * <b>触发三条件缺一不可</b>：发送失败 + 含图片段 + 剥掉之后还剩东西。
     * 少了第二条，纯文字消息失败也会白重发一次，等于把所有失败的重试次数翻倍；
     * 少了第三条，图片独占的那一条（开播模板第二条就是）会发出一条空消息。
     * <p>
     * ⚠️ <b>刻意不判断「失败是不是图片引起的」。</b> 最容易想到的做法是解析错误文案
     * （{@code 下载文件失败: Not Found}）来确认，但那是 NapCat 的措辞，
     * <b>版本一改判据就静默失效，而失效方向是「再也不降级」</b>——与「守卫写下了但没在跑」同族。
     * 这个形态本来就不需要知道原因：非图片故障（被禁言、被踢、群号错、Token 错、出网故障）
     * 下纯文字重发<b>同样会失败</b>，所以双发不成立。
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
     * @return 纯文字是否送达
     */
    private boolean fallbackWithoutImages(Sender sender, Map<String, String> headers, Map<String, Object> params,
                                          Message message, JSONObject failure) {
        if (!MessagePlaceholders.containsImage(message.getContent())) {
            return false;
        }

        String deliveredId = failure.getString("id");
        if (StringUtil.isNotBlank(deliveredId)) {
            log.warn("推送含图片的消息失败, 但响应带着消息 id {}, 无法排除其实已送达, 不重发纯文字: [{}]: {}",
                    deliveredId, message.getSequence(), message.getDisplay());
            return false;
        }

        String textOnly = MessagePlaceholders.stripImages(message.getContent()).trim();
        if (StringUtil.isBlank(textOnly)) {
            // 这一条是设计如此，不是异常：开播模板的第二条本就只有封面
            log.debug("消息只含图片段, 剥除后无内容可发, 不重发: [{}]", message.getSequence());
            return false;
        }

        Map<String, Object> textParams = new LinkedHashMap<>(params);
        textParams.put("content", textOnly);

        JSONObject result;
        try {
            result = sender.getLocalDelivery() == null
                    ? http.postJson(sender.getUrl(), headers, textParams)
                    : sender.getLocalDelivery().deliver(headers, textParams);
        } catch (RuntimeException e) {
            result = new JSONObject().fluentPut("code", -1).fluentPut("message", "投递失败: " + e.getMessage());
        }

        // 静默降级是看不见的谎言：三种结局各出一行，
        // 且都要说清「图没送到」，并带上原始失败原因——降级不能掩盖根因
        boolean textDelivered = result != null && Integer.valueOf(0).equals(result.getInteger("code"));
        if (textDelivered) {
            activityRecorder.recordSuccess(sender.getName(), describeTarget(message), message.getDisplay());
            log.warn("推送含图片的消息失败, 已剥除图片段重发纯文字并送达（图片未送达）: NovaBot -> {} ([{}] {}) [{}]: {}；原始失败: {}",
                    sender.getName(), message.getType().getStr(), message.getNum(), message.getSequence(),
                    textOnly, failure.getString("message"));

            for (Runnable callback : message.getOnImageDegradedCallbacks()) {
                try {
                    callback.run();
                } catch (Exception e) {
                    log.error("执行图片降级回调异常: [{}]{}", message.getSequence(), message.getDisplay(), e);
                }
            }
        } else {
            String reason = result == null ? "推送接口未返回任何内容" : result.getString("message");
            log.warn("剥除图片段后重发仍然失败, 本条消息完全未送达: NovaBot -> {} ([{}] {}) [{}]: {}；原始失败: {}；重发失败: {}",
                    sender.getName(), message.getType().getStr(), message.getNum(), message.getSequence(),
                    textOnly, failure.getString("message"), reason);
        }
        return textDelivered;
    }

    /**
     * 向某个会话推出第一条之后，紧跟一句用法提示
     * <p>
     * <b>紧跟</b>是这件事的一半：它解释的是刚刚那条通知从哪来、还能问点什么，
     * 隔着几条别的推送再来就成了没头没尾的一句。因此走 {@link #doSend} 直投而不重新入队——
     * 队列里排着谁不归这里管，而排在后面就不叫紧跟了。
     * <p>
     * <b>静音时段与推送开关这里不再判一遍。</b> 能走到这里，说明刚刚有一条推送
     * 过了 {@link #send} 那道闸<b>并且真的送达了</b>——提示跟的就是它。
     * 在这里补判一次是一道恒真的闸：拦不住任何东西，却让提示与它所解释的那条推送
     * 在积压补发那种情形下各行其是（通知发出去了，解释通知的那句没有）。
     * <p>
     * 剩下的两道判断各有各的事：
     * <ul>
     *     <li><b>回复不算</b>——收件人刚发过一条命令，再教他一遍怎么发命令只是打扰。</li>
     *     <li><b>认领成功才发</b>——认领与「记下已提示过」是同一个动作，同一时刻推给同一个群的
     *         两条消息不会各发一句。</li>
     * </ul>
     */
    private void tipAfterFirstPush(Sender sender, Message message) {
        if (message.isReply()) {
            return;
        }
        if (!firstPushTip.claim(message.getPlatform(), message.getType(), message.getNum())) {
            return;
        }

        String text = firstPushTip.text(message.getType());
        for (Message tip : Message.create(message.getPlatform(), message.getType(), message.getNum(), text)) {
            // 提示自己不算推送：它引不出第二句提示，也不必再走一遍这一整套判断
            doSend(sender, tip, false);
        }
    }

    /**
     * 描述推送目标，用于推送记录的展示
     * @param message 消息
     * @return 目标描述，例如「群 12345」
     */
    private String describeTarget(Message message) {
        return message.getType().getStr() + " " + message.getNum();
    }

    /**
     * 拦截原因对应的时间线事件类型
     * <p>
     * 写成 switch 表达式且<b>不给 default</b>：{@link PushGate.Block} 日后多一项时，
     * 这里会编译不过，逼着加的那个人当场决定它算哪一类。给了 default 的话，
     * 新的那一类会被静默归进现有的某一类，而界面上「静音丢弃」的条数就此开始虚高。
     */
    private static TimelineEventType timelineType(PushGate.Block block) {
        return switch (block) {
            case QUIET_HOURS -> TimelineEventType.PUSH_MUTED;
            case DISABLED -> TimelineEventType.PUSH_PAUSED;
        };
    }
}
