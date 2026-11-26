package org.frostnova.nova.interact.auto;

import org.frostnova.nova.core.model.UserInfo;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiPredicate;

/** 每个房间单独计时，只有成功排队才占用冷却和轮流回复的位置。 */
final class RoomKeywordReplies {
    private record Used(Instant nextAllowed, int nextReply) {}
    private final Map<KeywordRule, Used> rules = new HashMap<>();
    private final Map<String, Instant> viewers = new HashMap<>();

    void configure(List<KeywordRule> current) {
        rules.keySet().retainAll(current);
    }

    void accept(List<KeywordRule> current, String message, UserInfo viewer, String streamer, Instant now,
                BiPredicate<KeywordRule, String> enqueue) {
        if (DanmakuTemplate.blank(message)) return;
        String text = KeywordRule.trim(message).toLowerCase(Locale.ROOT);
        KeywordRule rule = current.stream().filter(r -> r.enabled() && r.matches(text)).findFirst().orElse(null);
        if (rule == null) return;
        Used last = rules.get(rule);
        if (last != null && now.isBefore(last.nextAllowed())) return;

        viewers.values().removeIf(end -> !now.isBefore(end));
        String identity = viewer.getUid() != null && viewer.getUid() > 0 ? "u:" + viewer.getUid() : "n:" + viewer.getUname();
        if (viewers.containsKey(identity)) return;

        int size = rule.replies().size();
        int index = last == null ? 0 : last.nextReply();
        if (rule.random()) index = ThreadLocalRandom.current().nextInt(size);
        String template = rule.replies().get(index);
        String viewerName = DanmakuTemplate.blank(viewer.getUname()) ? "" : RoomThanks.shortName(viewer.getUname());
        if (viewerName.isEmpty() && template.contains("{用户}")) return;
        String reply = KeywordRule.TEXT.render(template, viewerName, RoomThanks.shortName(streamer), "");
        if (enqueue.test(rule, reply)) {
            rules.put(rule, new Used(now.plusSeconds(rule.cooldownSeconds()), (index + 1) % size));
            viewers.put(identity, now.plusSeconds(30));
        }
    }
}
