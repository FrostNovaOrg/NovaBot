package org.frostnova.nova.adapter.qqbot;

import com.alibaba.fastjson2.JSONObject;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.model.MessagePlaceholders;
import org.frostnova.nova.core.model.Sender;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.frostnova.nova.core.service.NovaSenderService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Slf4j
@NovaComponent
final class QqBotPassiveReplies {
    private final QqBotApiClient api;
    private final QqBotDirectory directory;
    private final QqBotSettings settings;
    private final ObjectProvider<NovaSenderService> senders;

    private final ThreadPoolExecutor claimReplies = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(100), task -> {
        Thread thread = new Thread(task, "qqbot-claim-reply");
        thread.setDaemon(true);
        return thread;
    });

    QqBotPassiveReplies(QqBotApiClient api, QqBotDirectory directory, QqBotSettings settings,
                        ObjectProvider<NovaSenderService> senders) {
        this.api = api;
        this.directory = directory;
        this.settings = settings;
        this.senders = senders;
    }

    @EventListener(ApplicationReadyEvent.class)
    void registerSender() {
        var platform = QqBotPluginAutoConfiguration.PLATFORM;
        senders.ifAvailable(registry -> {
            if (registry.getSender(platform).isPresent()) return;
            var sender = new Sender(platform, null, "", 0, this::deliver) {
                @Override
                public int getDelay() {
                    return Math.toIntExact(settings.snapshot().intervalMillis());
                }
            };
            registry.addSender(sender, QqBotPluginAutoConfiguration.DISPLAY_NAME);
        });
    }

    /** 认领回复由网关事件触发，网络等待放到发送线程。 */
    void submit(String appId, String groupOpenId, String messageId, String content) {
        try {
            claimReplies.execute(() -> {
                try {
                    send(appId, groupOpenId, messageId, content);
                } catch (RuntimeException e) {
                    log.warn("官方机器人认领回复发送失败");
                }
            });
        } catch (RejectedExecutionException e) {
            log.warn("官方机器人认领回复发送队列不可用");
        }
    }

    @PreDestroy
    void shutdown() {
        claimReplies.shutdownNow();
    }

    private JSONObject deliver(Map<String, String> headers, Map<String, Object> params) {
        if (!Integer.valueOf(PushTargetType.GROUP.getCode()).equals(params.get("type"))
                || !(params.get("num") instanceof Long group)
                || !(params.get("reply_to") instanceof String messageId) || messageId.isBlank()
                || !(params.get("content") instanceof String content)) {
            return JSONObject.of("code", -1, "message", "官方机器人当前只支持回复群消息");
        }
        try {
            String text = MessagePlaceholders.stripImages(content);
            if (text.isBlank()) {
                return JSONObject.of("code", -1, "message", "官方机器人回复没有可发送的文字");
            }
            String appId = settings.snapshot().appId();
            var groupOpenId = directory.groupOpenId(appId, group);
            if (groupOpenId.isEmpty()) {
                return JSONObject.of("code", -1, "message", "官方机器人群不可用");
            }
            var response = send(appId, groupOpenId.get(), messageId, text);
            return JSONObject.of("code", 0, "id", response instanceof JSONObject json ? json.getString("id") : null);
        } catch (RuntimeException e) {
            return JSONObject.of("code", -1, "message", "官方机器人回复发送失败");
        }
    }

    private Object send(String appId, String groupOpenId, String messageId, String content) {
        String group = UriUtils.encodePathSegment(groupOpenId, StandardCharsets.UTF_8);
        return api.request("POST", "/v2/groups/" + group + "/messages",
                JSONObject.of("msg_type", 0, "msg_id", messageId, "content", content), appId);
    }
}
