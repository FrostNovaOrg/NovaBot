package org.frostnova.nova.adapter.qqbot;

import org.frostnova.nova.core.plugin.NovaComponent;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/** 记录原消息时间与已用回复数。排队中的消息仍可引用已过期的窗口。 */
@NovaComponent
final class QqBotReplyWindows {
    private static final Duration VALIDITY = Duration.ofMinutes(55);
    private final Map<String, Map<String, Window>> byApp = new HashMap<>();
    private final ArrayDeque<RecentId> recent = new ArrayDeque<>();

    synchronized Window get(String appId, String id, Instant original, Instant now) {
        while (!recent.isEmpty() && !now.isBefore(recent.getFirst().until())) recent.removeFirst();
        var messages = byApp.computeIfAbsent(appId, ignored -> new WeakHashMap<>());
        return messages.computeIfAbsent(id, ignored -> {
            // 有效期内即使暂时没有回复在排队，也必须保留同一编号的计数。
            recent.addLast(new RecentId(id, now.plus(VALIDITY)));
            return new Window(original.plus(VALIDITY));
        });
    }

    static final class Window {
        private final Instant expires;
        private int used;

        private Window(Instant expires) {
            this.expires = expires;
        }

        int nextSequence(Instant now) {
            return used < 5 && now.isBefore(expires) ? used + 1 : 0;
        }

        void delivered() {
            used++;
        }

        void exhausted() {
            used = 5;
        }
    }

    private record RecentId(String id, Instant until) {}
}
