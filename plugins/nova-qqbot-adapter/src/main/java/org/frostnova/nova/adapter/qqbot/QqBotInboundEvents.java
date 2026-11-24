package org.frostnova.nova.adapter.qqbot;

import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.frostnova.nova.core.event.remote.NovaRemoteMessageEvent;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.frostnova.nova.core.timeline.TimelineEvent;
import org.frostnova.nova.core.timeline.TimelineEventType;
import org.frostnova.nova.core.timeline.TimelineWriter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.regex.Pattern;

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
    private static final Pattern WORD = Pattern.compile("\\P{IsWhite_Space}+");
    private static final String INVALID_CODE = "认领码不对或已过期";

    private final QqBotDirectory directory;
    private final QqBotSettings settings;
    private final QqBotMembers members;
    private final QqBotPassiveReplies replies;
    private final ObjectProvider<TimelineWriter> timeline;
    private final ApplicationEventPublisher publisher;
    private final QqBotGatewayScheduler scheduler;
    private final LinkedHashMap<EventKey, Long> recentEvents = new LinkedHashMap<>();

    QqBotInboundEvents(QqBotDirectory directory, QqBotSettings settings, QqBotMembers members,
                       QqBotPassiveReplies replies, ObjectProvider<TimelineWriter> timeline,
                       ApplicationEventPublisher publisher, QqBotGatewayScheduler scheduler) {
        this.directory = directory;
        this.settings = settings;
        this.members = members;
        this.replies = replies;
        this.timeline = timeline;
        this.publisher = publisher;
        this.scheduler = scheduler;
    }

    @Override
    public void onEvent(String name, Object data, String eventId, Long sequence) {
        onEventForApp(settings.snapshot().appId(), name, data, eventId, sequence);
    }

    @Override
    public void onEventForApp(String appId, String name, Object data, String eventId, Long sequence) {
        var credentials = settings.snapshot().credentials();
        if (!credentials.configured() || !credentials.appId().equals(appId)) return;
        try {
            switch (name) {
                case "GROUP_AT_MESSAGE_CREATE" -> publishGroupMessage(credentials, requireObject(data));
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

    private void publishGroupMessage(QqBotSettings.Credentials credentials, JSONObject message) {
        String id = requireText(message, "id");
        if (!remember(new EventKey("message", id, null))) {
            return;
        }
        JSONObject author = requireObject(message.get("author"));
        if (Boolean.TRUE.equals(author.get("bot"))) {
            return;
        }
        Instant at = Instant.parse(requireText(message, "timestamp"));
        if (at.isBefore(Instant.now().minus(MESSAGE_MAX_AGE))) {
            log.info("忽略超过 30 分钟的 QQ 官方机器人群消息");
            return;
        }
        String groupOpenId = requireText(message, "group_openid");
        String memberOpenId = requireText(author, "member_openid");
        String appId = credentials.appId();
        long group = directory.groupSeen(appId, groupOpenId, at);
        long member = directory.memberSeen(appId, memberOpenId, at);
        String nickname = nickname(author, groupOpenId, memberOpenId);
        members.observe(appId, group, member, nickname, at);
        if (settings.snapshot().credentials() != credentials) return;

        String content = text(message.get("content"));
        String candidate = claimCandidate(content);
        if (candidate != null) {
            replyToClaim(credentials, group, groupOpenId, member, nickname, candidate, id);
            return;
        }
        var event = new NovaRemoteMessageEvent(QqBotPluginAutoConfiguration.PLATFORM, "group", group, member,
                content == null ? "" : content.strip(), text(author.get("member_role")), true);
        event.setMessageId(id);
        publisher.publishEvent(event);
    }

    private void replyToClaim(QqBotSettings.Credentials credentials, long group, String groupOpenId, long member,
                              String nickname, String candidate, String messageId) {
        String appId = credentials.appId();
        QqBotMembers.Claim claim;
        try {
            claim = members.redeem(credentials, group, member, nickname, candidate);
        } catch (IllegalStateException e) {
            log.warn("无法保存官方机器人的超级管理员认领");
            replies.submit(appId, groupOpenId, messageId, "暂时无法保存认领结果，请稍后重试");
            return;
        }
        if (claim == null) {
            replies.submit(appId, groupOpenId, messageId, INVALID_CODE);
            return;
        }
        replies.submit(appId, groupOpenId, messageId, "已认领超级管理员：" + claim.nickname());
        String groupName = directory.groupDisplayName(appId, group);
        timeline.ifAvailable(writer -> writer.record(TimelineEvent
                .of(TimelineEventType.SUPER_ADMIN_CLAIMED, TimelineEvent.Level.INFO)
                .at(claim.claimedAt())
                .text("群『" + groupName + "』的『" + claim.nickname() + "』认领了超级管理员")
                .build()));
    }

    private static String claimCandidate(String content) {
        if (content == null) return null;
        var words = WORD.matcher(content);
        if (!words.find() || !"认领".equals(words.group())) return null;
        String candidate = words.find() ? words.group() : "";
        return words.find() ? "" : candidate;
    }

    private static String nickname(JSONObject author, String groupOpenId, String memberOpenId) {
        String name = text(author.get("member_nickname"));
        if (name == null || name.isBlank()) name = text(author.get("username"));
        if (name == null || name.isBlank()) return null;
        return name.strip().replace(groupOpenId, "…").replace(memberOpenId, "…");
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
            case "GROUP_ADD_ROBOT" -> directory.groupJoined(groupOpenId, at);
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
