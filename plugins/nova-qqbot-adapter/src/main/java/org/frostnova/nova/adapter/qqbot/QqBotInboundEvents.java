package org.frostnova.nova.adapter.qqbot;

import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.frostnova.nova.core.event.remote.NovaRemoteMessageEvent;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;

import static org.frostnova.nova.adapter.qqbot.QqBotDirectory.ProactiveStatus.DISABLED;
import static org.frostnova.nova.adapter.qqbot.QqBotDirectory.ProactiveStatus.ENABLED;
import static org.frostnova.nova.adapter.qqbot.QqBotDirectory.SwitchSource.EVENT;

/** 将开放平台群事件接入群对照表和核心命令。 */
@Slf4j
@NovaComponent
final class QqBotInboundEvents implements QqBotEventListener {
    private static final Duration MESSAGE_MAX_AGE = Duration.ofMinutes(30);
    private static final long REPLAY_WINDOW_MILLIS = Duration.ofHours(1).toMillis();
    private static final int MAX_RECENT_EVENTS = 5000;

    private final QqBotDirectory directory;
    private final ApplicationEventPublisher publisher;
    private final QqBotGatewayScheduler scheduler;
    private final QqBotSettings settings;
    private final QqBotReplyWindows replies;
    private final QqBotWelcome welcome;
    private final LinkedHashMap<EventKey, Long> recentEvents = new LinkedHashMap<>();

    QqBotInboundEvents(QqBotDirectory directory, ApplicationEventPublisher publisher, QqBotGatewayScheduler scheduler,
                       QqBotSettings settings, QqBotReplyWindows replies, QqBotWelcome welcome) {
        this.directory = directory;
        this.publisher = publisher;
        this.scheduler = scheduler;
        this.settings = settings;
        this.replies = replies;
        this.welcome = welcome;
    }

    @Override
    public void onEvent(String name, Object data, String eventId, Long sequence) {
        try {
            switch (name) {
                case "GROUP_AT_MESSAGE_CREATE" -> publishGroupMessage(requireObject(data));
                case "GROUP_ADD_ROBOT", "GROUP_DEL_ROBOT", "GROUP_MSG_RECEIVE", "GROUP_MSG_REJECT" ->
                        updateDirectory(name, requireObject(data), eventId);
                case "FRIEND_ADD", "FRIEND_DEL", "C2C_MESSAGE_CREATE", "C2C_MSG_REJECT", "C2C_MSG_RECEIVE" ->
                        log.debug("忽略 QQ 开放平台好友或单聊事件 {}", name);
                default -> { }
            }
        } catch (RuntimeException e) {
            // 异常信息可能带有报文内容。
            log.warn("处理 QQ 开放平台事件 {} 失败（{}）", name, e.getClass().getSimpleName());
        }
    }

    private void publishGroupMessage(JSONObject message) {
        String id = requireText(message, "id");
        if (!remember(new EventKey("message", id, null))) {
            return;
        }
        JSONObject author = requireObject(message.get("author"));
        if (Boolean.TRUE.equals(author.get("bot"))) {
            return;
        }
        Instant at = Instant.parse(requireText(message, "timestamp"));
        Instant now = Instant.now();
        if (at.isBefore(now.minus(MESSAGE_MAX_AGE))) {
            log.info("忽略超过 30 分钟的 QQ 官方机器人群消息");
            return;
        }
        String groupOpenId = requireText(message, "group_openid");
        String memberOpenId = requireText(author, "member_openid");
        String appId = settings.snapshot().appId();
        long group = directory.groupSeen(groupOpenId, at);
        long member = directory.memberSeen(memberOpenId, at);
        String content = text(message.get("content"));
        var event = new NovaRemoteMessageEvent(QqBotPluginAutoConfiguration.PLATFORM, "group", group, member,
                content == null ? "" : content.strip(), text(author.get("member_role")), true);
        event.setMessageId(id);
        replies.get(appId, id, at, now);
        publisher.publishEvent(event);
    }

    private void updateDirectory(String name, JSONObject event, String eventId) {
        String groupOpenId = requireText(event, "group_openid");
        Instant at = groupEventTime(event.getString("timestamp"));
        EventKey key = eventId == null || eventId.isBlank()
                ? new EventKey(name, groupOpenId, at) : new EventKey("dispatch", eventId, null);
        if (!remember(key)) {
            return;
        }
        switch (name) {
            case "GROUP_ADD_ROBOT" -> {
                String appId = settings.snapshot().appId();
                long group = directory.groupJoined(groupOpenId, at);
                welcome.send(appId, group, groupOpenId, eventId);
            }
            case "GROUP_DEL_ROBOT" -> directory.groupRemoved(groupOpenId, at);
            case "GROUP_MSG_RECEIVE", "GROUP_MSG_REJECT" -> {
                long group = directory.groupSeen(groupOpenId, at);
                directory.recordProactive(group, name.equals("GROUP_MSG_RECEIVE") ? ENABLED : DISABLED, EVENT, at);
            }
        }
    }

    private boolean remember(EventKey key) {
        long now = scheduler.nowMillis();
        var arrivals = recentEvents.values().iterator();
        while (arrivals.hasNext() && now - arrivals.next() >= REPLAY_WINDOW_MILLIS) {
            arrivals.remove();
        }
        if (recentEvents.containsKey(key)) {
            return false;
        }
        if (recentEvents.size() == MAX_RECENT_EVENTS) {
            var oldest = recentEvents.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
        recentEvents.put(key, now);
        return true;
    }

    private static Instant groupEventTime(String timestamp) {
        try {
            return Instant.ofEpochSecond(Long.parseLong(timestamp));
        } catch (NumberFormatException e) {
            return Instant.parse(timestamp);
        }
    }

    private static JSONObject requireObject(Object value) {
        if (value instanceof JSONObject object) {
            return object;
        }
        throw new IllegalArgumentException("事件字段不是对象");
    }

    private static String requireText(JSONObject object, String field) {
        String value = text(object.get(field));
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("事件缺少字段 " + field);
        }
        return value;
    }

    private static String text(Object value) {
        return value instanceof String string ? string : null;
    }

    private record EventKey(String type, String id, Instant timestamp) {}
}
