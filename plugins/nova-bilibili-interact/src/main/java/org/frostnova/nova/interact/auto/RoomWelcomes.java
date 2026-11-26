package org.frostnova.nova.interact.auto;

import org.frostnova.nova.bilibili.enums.RoomGuardLevel;

import java.time.Instant;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

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

    private record Pending(Entry entry, boolean effect, Instant until, boolean welcomeOnTimeout) {}

    void accept(Entry entry, boolean effect, Instant now, boolean paused, RoomSettings settings, Consumer<Entry> ready) {
        if (entry.maskedName && (DanmakuTemplate.blank(entry.name) || !entry.hasRank())) return;
        if (paused) {
            ready.accept(entry);
            return;
        }
        flush(now, ready);
        Pending other = counterpart(entry, effect);
        if (other != null) {
            ready.accept(effect ? other.entry.withEffect(entry) : entry.withEffect(other.entry));
            return;
        }
        if (!entry.maskedName) {
            ready.accept(entry);
            // 名单称呼可直接用于特效。有名字但未知等级的进房消息则留给特效补齐。
            if (!effect && (entry.hasRank() || seen.contains(entry.identity()))) return;
        }
        String key = effect ? "e:" + entry.uid : entry.identity();
        boolean welcomeOnTimeout = entry.maskedName && settings.allows(WelcomeSettings.GUARD);
        waiting.putIfAbsent(key, new Pending(entry, effect, now.plusSeconds(PAIR_SECONDS), welcomeOnTimeout));
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

    void flush(Instant now, Consumer<Entry> ready) {
        for (Iterator<Pending> it = waiting.values().iterator(); it.hasNext();) {
            Pending pending = it.next();
            if (pending.until.isAfter(now)) break;
            it.remove();
            if (pending.welcomeOnTimeout) ready.accept(pending.entry);
        }
    }

    void configure(RoomSettings settings) {
        if (settings.allows(WelcomeSettings.GUARD)) return;
        if (settings.allows(WelcomeSettings.LISTED)) {
            // 打码名字仍能与名单 UID 配对，但这条消息不能再单独发大航海欢迎。
            waiting.replaceAll((key, p) -> p.welcomeOnTimeout ? new Pending(p.entry, p.effect, p.until, false) : p);
        } else {
            waiting.clear();
        }
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
