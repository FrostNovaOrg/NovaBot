package org.frostnova.nova.interact.auto;

import org.frostnova.nova.bilibili.event.live.BilibiliCaptainEvent;
import org.frostnova.nova.bilibili.event.live.BilibiliCommanderEvent;
import org.frostnova.nova.bilibili.event.live.BilibiliFreeGiftEvent;
import org.frostnova.nova.core.event.live.NovaBaseLiveEvent;
import org.frostnova.nova.core.event.live.base.NovaLiveGiftEvent;
import org.frostnova.nova.core.model.GiftInfo;
import org.frostnova.nova.core.model.UserInfo;
import org.frostnova.nova.interact.danmaku.LiveDanmakuService.AccountStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** 一场直播的合并窗口与已答谢观众，只在房间所属的队列锁内使用。 */
final class RoomThanks {
    interface Output {
        void enqueue(ThanksKind kind, String text, Instant expires);
    }

    private final InteractStore store;
    private final long uid;
    private final long broadcastStart;
    private final Output output;
    private final Map<String, Gifts> gifts = new LinkedHashMap<>();
    private final Map<ThanksKind, Audience> audiences = new EnumMap<>(ThanksKind.class);
    private final Map<ThanksKind, Set<String>> thanked = new EnumMap<>(ThanksKind.class);
    private AccountStatus account;

    RoomThanks(InteractStore store, long uid, long broadcastStart, Output output) {
        this.store = store;
        this.uid = uid;
        this.broadcastStart = broadcastStart;
        this.output = output;
    }

    private static String viewerId(UserInfo sender) {
        return sender.getUid() != null && sender.getUid() > 0 ? "u:" + sender.getUid() : "n:" + sender.getUname();
    }

    private Set<String> thanked(ThanksKind kind) {
        return thanked.computeIfAbsent(kind, k ->
                broadcastStart > 0 ? store.thanked(uid, broadcastStart, k.key) : new HashSet<>());
    }

    private static final class Gifts {
        final Instant first;
        final String viewer;
        final Map<String, Long> paid = new LinkedHashMap<>();
        Map<String, Long> includingFree;
        BigDecimal yuan = BigDecimal.ZERO;

        Gifts(Instant first, String viewer) {
            this.first = first;
            this.viewer = viewer;
        }

        void add(GiftInfo gift, Double value, boolean free) {
            if (free && includingFree == null) includingFree = new LinkedHashMap<>(paid);
            if (includingFree != null) count(includingFree, gift);
            if (!free) count(paid, gift);
            if (value != null && Double.isFinite(value)) {
                yuan = yuan.add(BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP));
            }
        }

        private static void count(Map<String, Long> counts, GiftInfo gift) {
            // 第四种只用于判断是否需要“等”。
            if (counts.size() < 4 || counts.containsKey(gift.getName())) {
                counts.merge(gift.getName(), gift.getCount().longValue(), Long::sum);
            }
        }

        String description() {
            var counts = includingFree == null ? paid : includingFree;
            return counts.entrySet().stream().limit(3).map(e -> e.getKey() + "x" + e.getValue())
                    .collect(Collectors.joining("、")) + (counts.size() > 3 ? "等" : "");
        }
    }

    private static final class Audience {
        final Instant first;
        final Set<String> viewers = new HashSet<>();
        String names = "";

        Audience(Instant first) {
            this.first = first;
        }

        void add(String viewer, String name) {
            if (!viewers.add(viewer)) return;
            if (viewers.size() == 1) names = name;
            if (viewers.size() == 2) names += "、" + name;
        }

        String description() {
            return viewers.size() == 1 ? names : names + "等 " + viewers.size() + " 人";
        }
    }

    void configure(RoomSettings settings) {
        if (!settings.allows(ThanksKind.GIFT.key)) {
            gifts.clear();
        } else if (!settings.thanks().giftIncludeFree()) {
            gifts.values().removeIf(round -> {
                round.includingFree = null;
                return round.paid.isEmpty();
            });
        }
        audiences.keySet().removeIf(kind -> !settings.allows(kind.key));
    }

    boolean hasPending() {
        return !gifts.isEmpty() || !audiences.isEmpty();
    }

    void discardPending() {
        gifts.clear();
        audiences.clear();
    }

    boolean uses(AccountStatus other) {
        return account != null && other.choice() == account.choice() && Objects.equals(other.uid(), account.uid());
    }

    boolean checkAccount(AccountStatus current) {
        boolean usable = current.usable() && current.uid() != null && current.pausedFor() == null;
        if (!usable || !uses(current)) {
            discardPending();
        }
        account = current;
        return usable;
    }

    void accept(ThanksKind kind, NovaBaseLiveEvent event, UserInfo sender, ThanksSettings settings, String streamer, Instant now) {
        if (sender == null || sender.getUname() == null) return;
        if (kind != ThanksKind.MEMBERSHIP && kind != ThanksKind.SUPER_CHAT
                && account.uid() > 0 && account.uid().equals(sender.getUid())) return;
        flush(settings, streamer, now);
        String name = shortName(sender.getUname());
        switch (kind) {
            case GIFT -> {
                boolean free = event instanceof BilibiliFreeGiftEvent;
                if (free && !settings.giftIncludeFree()) return;
                var gift = (NovaLiveGiftEvent) event;
                GiftInfo info = gift.getGiftInfo();
                if (info == null || info.getName() == null || info.getCount() == null || info.getCount() <= 0) return;
                gifts.computeIfAbsent(viewerId(sender), unused -> new Gifts(now, name)).add(info, gift.getValue(), free);
            }
            case FOLLOW, SHARE -> {
                String viewer = viewerId(sender);
                if (thanked(kind).contains(viewer)) return;
                audiences.computeIfAbsent(kind, unused -> new Audience(now)).add(viewer, name);
            }
            case MEMBERSHIP -> {
                String level = event instanceof BilibiliCaptainEvent ? "舰长" : event instanceof BilibiliCommanderEvent ? "提督" : "总督";
                output.enqueue(kind, kind.render(settings, name, streamer, level), now.plusSeconds(60));
            }
            case SUPER_CHAT -> output.enqueue(kind, kind.render(settings, name, streamer, ""), now.plusSeconds(60));
        }
    }

    private static String shortName(String name) {
        return name.codePointCount(0, name.length()) <= 10 ? name : name.substring(0, name.offsetByCodePoints(0, 10)) + "…";
    }

    void flush(ThanksSettings settings, String streamer, Instant now) {
        // 窗口按首份礼物到达的顺序排列。同一房间共用窗口长度，遇到未到期的就可以停。
        var rounds = gifts.values().iterator();
        while (rounds.hasNext()) {
            Gifts round = rounds.next();
            Instant end = round.first.plusSeconds(settings.giftWindowSeconds());
            if (now.isBefore(end)) break;
            rounds.remove();
            if (round.yuan.compareTo(settings.giftMinYuan()) >= 0) {
                String text = ThanksKind.GIFT.render(settings, round.viewer, streamer, round.description());
                output.enqueue(ThanksKind.GIFT, text, end.plusSeconds(60));
            }
        }
        var groups = audiences.entrySet().iterator();
        while (groups.hasNext()) {
            var entry = groups.next();
            var kind = entry.getKey();
            var group = entry.getValue();
            int seconds = kind == ThanksKind.FOLLOW ? settings.followWindowSeconds() : settings.shareWindowSeconds();
            Instant end = group.first.plusSeconds(seconds);
            if (now.isBefore(end)) continue;
            groups.remove();
            thanked(kind).addAll(group.viewers);
            if (broadcastStart > 0) store.rememberThanked(uid, broadcastStart, kind.key, group.viewers);
            String text = kind.render(settings, group.description(), streamer, String.valueOf(group.viewers.size()));
            output.enqueue(kind, text, end.plusSeconds(60));
        }
    }
}
