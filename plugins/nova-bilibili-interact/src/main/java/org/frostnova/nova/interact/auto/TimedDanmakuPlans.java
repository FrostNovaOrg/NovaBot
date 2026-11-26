package org.frostnova.nova.interact.auto;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/** 同一场直播共用观众发言计数，每条计划单独计时、轮换。由队列在房间状态的锁内调用。 */
final class TimedDanmakuPlans {
    static final class Timer {
        final RoomSettings.Plan plan;
        final long audienceAtStart;
        Instant due;
        int cursor;

        Timer(RoomSettings.Plan plan, Instant now, long audienceAtStart) {
            this.plan = plan;
            this.audienceAtStart = audienceAtStart;
            schedule(now);
        }

        void schedule(Instant now) {
            due = now.plus(plan.minutes(), ChronoUnit.MINUTES);
        }

        String text(String streamer) {
            var texts = plan.texts();
            int index = plan.random() ? ThreadLocalRandom.current().nextInt(texts.size()) : cursor;
            return texts.get(index).replace("{主播}", streamer);
        }
    }

    private final Map<String, Timer> timers = new LinkedHashMap<>();
    private long viewerMessages;
    private long atLastSend;

    void configure(RoomSettings settings, Instant now) {
        Map<String, Timer> active = new LinkedHashMap<>();
        if (settings.allows(RoomSettings.TIMED)) {
            for (RoomSettings.Plan plan : settings.plans()) {
                if (!plan.enabled()) continue;
                Timer previous = timers.get(plan.id());
                active.put(plan.id(), previous != null && plan.equals(previous.plan) ? previous : new Timer(plan, now, viewerMessages));
            }
        }
        timers.clear();
        timers.putAll(active);
    }

    boolean current(Timer timer) {
        return timers.get(timer.plan.id()) == timer;
    }

    void due(Instant now, Consumer<Timer> action) {
        for (Timer timer : timers.values()) {
            if (now.isBefore(timer.due)) continue;
            timer.schedule(now);
            action.accept(timer);
        }
    }

    void viewerSpoke() {
        viewerMessages++;
    }

    boolean quiet(Timer timer) {
        return viewerMessages == atLastSend || viewerMessages == timer.audienceAtStart;
    }

    long audienceCount() {
        return viewerMessages;
    }

    void sent(Timer timer, boolean firstPart, long audienceAtSend) {
        atLastSend = audienceAtSend;
        if (firstPart) timer.cursor = (timer.cursor + 1) % timer.plan.texts().size();
    }
}
