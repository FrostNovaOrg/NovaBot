package org.frostnova.nova.interact.auto;

import org.frostnova.nova.bilibili.model.FansMedal;
import org.frostnova.nova.bilibili.service.BilibiliPkOpponentService.Profile;
import org.frostnova.nova.core.event.live.common.PkBattleEvent.Opponent;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/** 一场正在进行的 PK。仅在内存中保留对手、资料查询和已占欢迎名额的观众。 */
final class PkFanWelcomes {
    static final String FEATURE = "pkFanWelcome";
    static final DanmakuTemplate TEXT = new DanmakuTemplate("PK 串门欢迎", "欢迎 {用户} 来串门", "对手", "牌子");
    static final int DEFAULT_LIMIT = 5;

    final long id;
    final Instant ends;
    private final Set<String> viewers = new HashSet<>();
    int sentCount;
    private final Map<Long, Opponent> byUid = new HashMap<>();
    private final Map<Long, Opponent> byRoom = new HashMap<>();
    private final Map<Long, CompletableFuture<Profile>> profiles = new HashMap<>();
    private final Function<Opponent, CompletableFuture<Profile>> lookup;

    PkFanWelcomes(long id, Instant ends, Function<Opponent, CompletableFuture<Profile>> lookup) {
        this.id = id;
        this.ends = ends;
        this.lookup = lookup;
    }

    void addOpponents(List<Opponent> listed) {
        for (Opponent opponent : listed) {
            if (opponent == null) continue;
            if (positive(opponent.uid())) byUid.put(opponent.uid(), opponent);
            if (positive(opponent.roomId())) byRoom.put(opponent.roomId(), opponent);
        }
    }

    Opponent match(FansMedal medal) {
        Opponent owner = byUid.get(medal.getUid());
        return owner != null ? owner : byRoom.get(medal.getRoomId());
    }

    CompletableFuture<Profile> profile(Opponent opponent) {
        if (!positive(opponent.uid())) return CompletableFuture.completedFuture(null);
        return profiles.computeIfAbsent(opponent.uid(), uid -> lookup.apply(opponent));
    }

    boolean hasSlot(String identity, int limit) {
        return viewers.size() < limit && !viewers.contains(identity);
    }

    record Visit(PkFanWelcomes battle, String identity, CompletableFuture<String> text) {
        void release() {
            battle.viewers.remove(identity);
        }
    }

    Visit reserve(RoomWelcomes.Entry entry, Opponent opponent, String template, String streamer, String medal, String fallback) {
        String identity = entry.identity();
        viewers.add(identity);
        var text = template.contains("{对手}")
                ? profile(opponent).handle((profile, failure) -> render(template, entry.name(), streamer, medal, profile))
                : CompletableFuture.completedFuture(fallback);
        return new Visit(this, identity, text);
    }

    private static boolean positive(Long id) {
        return id != null && id > 0;
    }

    static String render(String template, String viewer, String streamer, String medal, Profile profile) {
        return TEXT.render(template, field -> switch (field) {
            case "用户" -> RoomThanks.shortName(viewer);
            case "主播" -> RoomThanks.shortName(streamer);
            case "牌子" -> medal == null ? "" : medal;
            case "对手" -> profile == null || DanmakuTemplate.blank(profile.nickname()) ? "对面主播" : RoomThanks.shortName(profile.nickname());
            default -> throw new IllegalArgumentException("未知的串门欢迎占位符");
        });
    }
}
