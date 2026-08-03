package com.starlwr.bot.core.sender;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.model.Message;
import com.starlwr.bot.core.model.Sender;
import com.starlwr.bot.core.service.StarBotSenderService;
import com.starlwr.bot.core.util.HttpUtil;
import com.starlwr.bot.core.util.StringUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.function.Predicate;

/**
 * StarBot 消息发送器
 */
@Slf4j
@Service
public class StarBotMessageSender {
    private final HttpUtil http;

    private final StarBotSenderService senderService;

    private final ExecutorService executor = Executors.newCachedThreadPool();

    private final Map<String, BlockingQueue<Message>> queueMap = new ConcurrentHashMap<>();

    private final Map<String, Future<?>> platformTasks = new ConcurrentHashMap<>();

    @Autowired
    public StarBotMessageSender(HttpUtil http, StarBotSenderService senderService) {
        this.http = http;
        this.senderService = senderService;
    }

    /**
     * 将消息加入至消息队列
     * @param message 消息
     */
    public void send(Message message) {
        Optional<Sender> optionalSender = senderService.getSender(message.getPlatform());
        if (optionalSender.isEmpty()) {
            log.warn("未找到 {} 推送平台配置, 请检查配置文件是否正确配置, 已丢弃消息: [{}] {}: {}", message.getPlatform(), message.getType().getStr(), message.getNum(), message.getDisplay());
            return;
        }

        BlockingQueue<Message> queue = queueMap.computeIfAbsent(message.getPlatform(), k -> {
            BlockingQueue<Message> newQueue = new LinkedBlockingQueue<>();
            startPlatformThread(optionalSender.get(), newQueue);
            return newQueue;
        });

        try {
            queue.put(message);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("添加消息: {} 到队列时被中断", JSON.toJSONString(message), e);
        }
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
                    Thread.currentThread().interrupt();
                    log.error("{} 平台发送线程中断", sender.getName(), e);
                } catch (Exception e) {
                    log.error("{} 平台消息发送异常", sender.getName(), e);
                }
            }
            return null;
        }));
    }

    /**
     * 发送消息
     * @param sender 推送平台信息
     * @param message 消息
     */
    private void doSend(Sender sender, Message message) {
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
                log.info("已取消发送消息: StarBot -> {} ([{}] {}) [{}]: {}", sender.getName(), message.getType().getStr(), message.getNum(), message.getSequence(), message.getDisplay());
                return;
            }
        }

        JSONObject result = http.postJson(sender.getUrl(), headers, params);
        message.setCompleteTime(Instant.now());

        for (Runnable callback : message.getOnCompleteCallbacks()) {
            try {
                callback.run();
            } catch (Exception e) {
                log.error("执行消息发送完毕回调异常: [{}]{}", message.getSequence(), message.getDisplay(), e);
            }
        }

        if (result.getInteger("code") == 0) {
            message.setId(result.getString("id"));
            log.info("StarBot -> {} ([{}] {}) [{}]: {}", sender.getName(), message.getType().getStr(), message.getNum(), message.getSequence(), message.getDisplay());

            for (Runnable callback : message.getOnSuccessCallbacks()) {
                try {
                    callback.run();
                } catch (Exception e) {
                    log.error("执行消息发送成功回调异常: [{}]{}", message.getSequence(), message.getDisplay(), e);
                }
            }
        } else {
            log.error("消息发送失败 ({}): StarBot -> {} ([{}] {}) [{}]: {}", result.getString("message"), sender.getName(), message.getType().getStr(), message.getNum(), message.getSequence(), message.getDisplay());

            for (Runnable callback : message.getOnFailureCallbacks()) {
                try {
                    callback.run();
                } catch (Exception e) {
                    log.error("执行消息发送失败回调异常: [{}]{}", message.getSequence(), message.getDisplay(), e);
                }
            }
        }
    }
}
