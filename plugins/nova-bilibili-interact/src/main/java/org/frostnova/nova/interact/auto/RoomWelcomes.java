package org.frostnova.nova.interact.auto;

import org.frostnova.nova.bilibili.enums.RoomGuardLevel;

import java.util.HashSet;
import java.util.Set;

/** 两种欢迎共享一份同场名单，进房事件与进场特效也使用同一个观众标识。 */
final class RoomWelcomes {
    static final int PRIORITY = 2;
    private static final String SEEN = "welcome";
    private final InteractStore store;
    private final long streamerUid;
    private final long broadcastStart;
    private final Set<String> seen;

    RoomWelcomes(InteractStore store, long streamerUid, long broadcastStart) {
        this.store = store;
        this.streamerUid = streamerUid;
        this.broadcastStart = broadcastStart;
        this.seen = broadcastStart > 0 ? store.thanked(streamerUid, broadcastStart, SEEN) : new HashSet<>();
    }

    record Greeting(String feature, String text, String identity) {}

    Greeting greeting(RoomSettings settings, String streamer, Long uid, String name, RoomGuardLevel level) {
        boolean identified = uid != null && uid > 0;
        if (!identified && DanmakuTemplate.blank(name)) return null;
        String identity = identified ? "u:" + uid : "n:" + name;
        if (seen.contains(identity)) return null;
        String viewer = DanmakuTemplate.blank(name) ? "UID " + uid : RoomThanks.shortName(name);
        WelcomeSettings welcome = settings.welcome();
        if (identified && settings.allows(WelcomeSettings.LISTED)) {
            String id = uid.toString();
            for (var person : welcome.people()) {
                if (!person.uid().equals(id)) continue;
                if (!DanmakuTemplate.blank(person.alias())) viewer = RoomThanks.shortName(person.alias());
                String template = person.template().isEmpty() ? welcome.listedTemplate() : person.template();
                return new Greeting(WelcomeSettings.LISTED, WelcomeSettings.LISTED_TEXT.render(template, viewer, streamer, ""), identity);
            }
        }
        if (!settings.allows(WelcomeSettings.GUARD) || level == null) return null;
        String rank = switch (level) {
            case GOVERNOR -> "总督";
            case COMMANDER -> welcome.minimumLevel() != RoomGuardLevel.GOVERNOR ? "提督" : null;
            case CAPTAIN -> welcome.minimumLevel() == RoomGuardLevel.CAPTAIN ? "舰长" : null;
            default -> null;
        };
        return rank == null ? null : new Greeting(WelcomeSettings.GUARD,
                WelcomeSettings.GUARD_TEXT.render(welcome.guardTemplate(), viewer, streamer, rank), identity);
    }

    void remember(String identity) {
        seen.add(identity);
        if (broadcastStart > 0) store.rememberThanked(streamerUid, broadcastStart, SEEN, Set.of(identity));
    }
}
