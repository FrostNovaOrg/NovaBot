package com.starlwr.bot.core.sender;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.health.PushActivityRecorder;
import com.starlwr.bot.core.model.Message;
import com.starlwr.bot.core.model.Sender;
import com.starlwr.bot.core.service.StarBotSenderService;
import com.starlwr.bot.core.util.HttpUtil;
import com.starlwr.bot.core.util.StringUtil;
import lombok.extern.slf4j.Slf4j;
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
                                PushActivityRecorder activityRecorder, PushGate pushGate) {
        this.http = http;
        this.senderService = senderService;
        this.activityRecorder = activityRecorder;
        this.pushGate = pushGate;
    }

    /**
     * 将消息加入至消息队列
     * @param message 消息
     */
    public void send(Message message) {
        if (!pushGate.allowed()) {
            log.info("{}, 已丢弃消息: [{}] {}: {}", pushGate.blockReason(),
                    message.getType().getStr(), message.getNum(), message.getDisplay());
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

        return doSend(sender, message);
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
                JSONObject result = http.postJson(sender.getUrl(), headers, params);
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
                    doSend(sender, message);
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
     * @return 推送接口的原始响应；被拦截器取消发送时返回 null
     */
    private JSONObject doSend(Sender sender, Message message) {
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
        if (Integer.valueOf(0).equals(result.getInteger("code"))) {
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
        }

        return result;
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
