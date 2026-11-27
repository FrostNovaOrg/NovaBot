package org.frostnova.nova.interact.auto;

import org.frostnova.nova.bilibili.enums.RoomGuardLevel;

import java.time.Instant;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** 一场直播内配对进房消息与进场特效，并记录已经排过欢迎的观众。 */
final class RoomWelcomes {
    static final int PRIORITY = 2;
    private static final int PAIR_SECONDS = 3;
    private static final String SEEN = "welcome";
    private final InteractStore store;
    private final long streamerUid;
    private final long broadcastStart;
    private final Set<String> seen;
    private final Map<String, Pending> waiting = new LinkedHashMap<>();

    RoomWelcomes(InteractStore store, long streamerUid, long broadcastStart) {
        this.store = store;
        this.streamerUid = streamerUid;
        this.broadcastStart = broadcastStart;
        this.seen = broadcastStart > 0 ? store.thanked(streamerUid, broadcastStart, SEEN) : new HashSet<>();
    }

    record Entry(Long uid, String name, RoomGuardLevel level, Instant entered, boolean maskedName) {
        Entry(Long uid, String name, RoomGuardLevel level, Instant entered) {
            this(uid, name, level, entered, uid == null || uid <= 0);
        }

        boolean identified() {
            return uid != null && uid > 0;
        }

        String identity() {
            return identified() ? "u:" + uid : "n:" + name;
        }

        boolean hasRank() {
            return level == RoomGuardLevel.CAPTAIN || level == RoomGuardLevel.COMMANDER || level == RoomGuardLevel.GOVERNOR;
        }

        Entry withEffect(Entry effect) {
            return new Entry(effect.uid, name, effect.level, entered.isBefore(effect.entered) ? entered : effect.entered, maskedName);
        }
    }

    private record Pending(Entry entry, boolean effect, Instant until, boolean welcomeOnTimeout,
                           boolean greeted, Consumer<Long> visitor) {}

    void accept(Entry entry, boolean effect, Instant now, boolean paused, RoomSettings settings,
                Predicate<Entry> welcome, Consumer<Long> visitor) {
        if (entry.maskedName && (DanmakuTemplate.blank(entry.name) || !entry.hasRank())) {
            if (visitor != null) visitor.accept(entry.uid);
            return;
        }
        if (paused) {
            welcome.test(entry);
            return;
        }
        flush(now, welcome);
        Pending other = counterpart(entry, effect);
        if (other != null) {
            Entry paired = effect ? other.entry.withEffect(entry) : entry.withEffect(other.entry);
            Consumer<Long> fallback = effect ? other.visitor : visitor;
            if (!welcome.test(paired) && !other.greeted && fallback != null) fallback.accept(paired.uid);
            return;
        }
        boolean greeted = !entry.maskedName && welcome.test(entry);
        if (greeted) visitor = null;
        // 等特效期间把串门欢迎留作后备，名单或大航海欢迎优先。
        if (!effect && !entry.maskedName && (entry.hasRank() || seen.contains(entry.identity()))) {
            if (visitor != null) visitor.accept(entry.uid);
            return;
        }
        String key = effect ? "e:" + entry.uid : entry.identity();
        boolean welcomeOnTimeout = entry.maskedName && settings.allows(WelcomeSettings.GUARD);
        Pending previous = waiting.putIfAbsent(key,
                new Pending(entry, effect, now.plusSeconds(PAIR_SECONDS), welcomeOnTimeout, greeted, visitor));
        if (previous != null && !previous.greeted && visitor != null) {
            // 仍用第一条进房消息配对特效，串门资格取自后来确实戴牌进房的那一次。
            waiting.put(key, new Pending(previous.entry, previous.effect, previous.until,
                    previous.welcomeOnTimeout, false, visitor));
        }
    }

    private Pending counterpart(Entry entry, boolean effect) {
        if (!entry.maskedName) {
            Pending exact = waiting.remove(effect ? entry.identity() : "e:" + entry.uid);
            if (exact != null || !effect) return exact;
        }
        // 普通进房缺 UID 时，只能按本房等级配对。
        for (Iterator<Pending> it = waiting.values().iterator(); it.hasNext();) {
            Pending candidate = it.next();
            if (candidate.effect != effect && candidate.entry.level == entry.level
                    && (entry.maskedName || candidate.entry.maskedName)) {
                it.remove();
                return candidate;
            }
        }
        return null;
    }

    void flush(Instant now, Predicate<Entry> welcome) {
        for (Iterator<Pending> it = waiting.values().iterator(); it.hasNext();) {
            Pending pending = it.next();
            if (pending.until.isAfter(now)) break;
            it.remove();
            boolean greeted = pending.welcomeOnTimeout && welcome.test(pending.entry);
            if (!greeted && pending.visitor != null) pending.visitor.accept(pending.entry.uid);
        }
    }

    void configure(RoomSettings settings) {
        boolean guards = settings.allows(WelcomeSettings.GUARD);
        boolean listed = settings.allows(WelcomeSettings.LISTED);
        boolean visitors = settings.allows(PkFanWelcomes.FEATURE);
        waiting.replaceAll((key, p) -> new Pending(p.entry, p.effect, p.until, guards && p.welcomeOnTimeout,
                p.greeted, visitors ? p.visitor : null));
        if (!guards && !listed) waiting.values().removeIf(p -> p.visitor == null);
    }

    record Greeting(String feature, String text) {}

    Greeting greeting(RoomSettings settings, String streamer, Entry entry) {
        if (alreadyWelcomed(entry)) {
            if (entry.maskedName && entry.identified()) remember(entry);
            return null;
        }
        String viewer = DanmakuTemplate.blank(entry.name) ? null : RoomThanks.shortName(entry.name);
        WelcomeSettings welcome = settings.welcome();
        if (entry.identified() && settings.allows(WelcomeSettings.LISTED)) {
            String id = entry.uid.toString();
            for (var person : welcome.people()) {
                if (!person.uid().equals(id)) continue;
                if (!DanmakuTemplate.blank(person.alias())) viewer = RoomThanks.shortName(person.alias());
                if (viewer == null) return null;
                String template = person.template().isEmpty() ? welcome.listedTemplate() : person.template();
                return new Greeting(WelcomeSettings.LISTED, WelcomeSettings.LISTED_TEXT.render(template, viewer, streamer, ""));
            }
        }
        if (viewer == null || !settings.allows(WelcomeSettings.GUARD) || entry.level == null) return null;
        String rank = switch (entry.level) {
            case GOVERNOR -> "总督";
            case COMMANDER -> welcome.minimumLevel() != RoomGuardLevel.GOVERNOR ? "提督" : null;
            case CAPTAIN -> welcome.minimumLevel() == RoomGuardLevel.CAPTAIN ? "舰长" : null;
            default -> null;
        };
        return rank == null ? null : new Greeting(WelcomeSettings.GUARD,
                WelcomeSettings.GUARD_TEXT.render(welcome.guardTemplate(), viewer, streamer, rank));
    }

    private boolean alreadyWelcomed(Entry entry) {
        if (seen.contains(entry.identity())) return true;
        if (!entry.maskedName) return false;
        boolean pairedBefore = seen.contains("paired:" + entry.name);
        if (!entry.identified()) return pairedBefore;
        // 只按名字欢迎过的观众，第一次配到特效时补记 UID。之后能分清同名的其他 UID。
        return !pairedBefore && seen.contains("n:" + entry.name);
    }

    void remember(Entry entry) {
        Set<String> added = new HashSet<>();
        if (seen.add(entry.identity())) added.add(entry.identity());
        if (entry.maskedName && entry.identified()) {
            String nameKey = "paired:" + entry.name;
            if (seen.add(nameKey)) added.add(nameKey);
        }
        if (broadcastStart > 0 && !added.isEmpty()) store.rememberThanked(streamerUid, broadcastStart, SEEN, added);
    }
}
