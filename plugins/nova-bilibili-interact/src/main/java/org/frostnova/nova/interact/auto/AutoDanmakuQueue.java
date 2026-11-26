package org.frostnova.nova.interact.auto;

import org.frostnova.nova.bilibili.BilibiliPlatform;
import org.frostnova.nova.bilibili.enums.RoomGuardLevel;
import org.frostnova.nova.bilibili.event.live.BilibiliDanmuEvent;
import org.frostnova.nova.bilibili.event.live.BilibiliGuardEntryEffectEvent;
import org.frostnova.nova.bilibili.event.live.BilibiliPkBattleEvent;
import org.frostnova.nova.bilibili.service.BilibiliPkOpponentService.Profile;
import org.frostnova.nova.core.event.live.NovaBaseLiveEvent;
import org.frostnova.nova.core.event.live.base.NovaLiveMessageEvent;
import org.frostnova.nova.core.model.UserInfo;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.frostnova.nova.core.service.LiveDataService;
import org.frostnova.nova.interact.auto.RoomActivities.Pause;
import org.frostnova.nova.interact.danmaku.DanmakuFailure;
import org.frostnova.nova.interact.danmaku.DanmakuResult;
import org.frostnova.nova.interact.danmaku.LiveDanmakuService;
import org.frostnova.nova.interact.danmaku.LiveDanmakuService.AccountStatus;
import org.frostnova.nova.interact.danmaku.LiveDanmakuService.Room;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** 自动发送按房间限量，所有房间共用一个出队线程。查询字数上限和平台发送都在队列锁外进行。 */
@NovaComponent
public class AutoDanmakuQueue {
    private static final int CAPACITY = 20;
    private static final Comparator<Item> NEXT = Comparator.comparingInt((Item i) -> i.priority).reversed()
            .thenComparingLong(i -> i.sequence);
    // 房间已经发出了一部分时，先完成整条，再按优先级取下一条。
    private static final Comparator<Item> ROOM_ORDER = Comparator.comparing((Item i) -> i.part == 0).thenComparing(NEXT);
    private static final Comparator<Item> EVICT = Comparator.comparingInt((Item i) -> i.priority)
            .thenComparingLong(i -> i.sequence);

    private final LiveDanmakuService sender;
    private final LiveDataService live;
    private final InteractStore store;
    private final Supplier<Instant> clock;
    private final Map<Long, RoomState> rooms = new HashMap<>();
    private long sequence;
    private boolean stopped;

    private static final class RoomState {
        Room streamer;
        RoomSettings settings;
        final List<Item> pending = new ArrayList<>();
        final ArrayDeque<Instant> recent = new ArrayDeque<>();
        Pause pause;
        boolean awaitingOfflineStatus;
        TimedDanmakuPlans session;
        RoomThanks thanks;
        RoomWelcomes welcomes;
        RoomActivities activities;
        final RoomKeywordReplies keywordReplies = new RoomKeywordReplies();
        Set<String> pkIds;
        long pkGeneration;

        RoomState(Room room, RoomSettings settings) {
            this.streamer = room;
            this.settings = settings;
        }

        boolean hasCapacity(Instant now) {
            Instant cutoff = now.minusSeconds(60);
            while (!recent.isEmpty() && !recent.peekFirst().isAfter(cutoff)) recent.removeFirst();
            return recent.size() < settings.perMinute();
        }
    }

    private static final class Item {
        final RoomState room;
        final AccountStatus account;
        final String feature;
        final String text;
        final int priority;
        final long sequence;
        final Instant expires;
        final TimedDanmakuPlans.Timer timer;
        KeywordRule keywordRule;
        List<String> parts;
        int part;
        TimedDanmakuPlans sendingSession;
        long audienceAtSend;
        Instant started;

        Item(RoomState room, AccountStatus account, String feature, String text, int priority, long sequence,
             Instant expires, TimedDanmakuPlans.Timer timer) {
            this.room = room;
            this.account = account;
            this.feature = feature;
            this.text = text;
            this.priority = priority;
            this.sequence = sequence;
            this.expires = expires;
            this.timer = timer;
        }
    }

    @Autowired
    public AutoDanmakuQueue(LiveDanmakuService sender, LiveDataService live, InteractStore store) {
        this(sender, live, store, Instant::now);
    }

    AutoDanmakuQueue(LiveDanmakuService sender, LiveDataService live, InteractStore store, Supplier<Instant> clock) {
        this.sender = sender;
        this.live = live;
        this.store = store;
        this.clock = clock;
    }

    synchronized void register(Room room) {
        if (stopped) return;
        RoomState state = rooms.get(room.uid());
        if (state != null && state.streamer.roomId() == room.roomId()) {
            state.streamer = room;
            return;
        }
        if (state != null) {
            clear(state, "主播的直播间已更换");
            store.forgetThanked(room.uid());
        }
        state = new RoomState(room, store.settings(room.uid()));
        rooms.put(room.uid(), state);
        syncLive(state, clock.get());
    }

    synchronized void forgetRoom(long uid, String reason) {
        RoomState state = rooms.remove(uid);
        if (state != null) {
            clear(state, reason);
            store.forgetThanked(uid);
        }
    }

    synchronized void stop() {
        stopped = true;
        rooms.values().forEach(room -> clear(room, "程序停止发送"));
        rooms.clear();
    }

    public synchronized void save(long uid, RoomSettings settings) {
        RoomState room = rooms.get(uid);
        if (room == null) throw new IllegalArgumentException("请选一位已添加的主播");
        store.save(uid, settings);
        room.settings = settings;
        room.keywordReplies.configure(settings.keywordRules());
        if (!settings.allows(PkAnnouncement.FEATURE)) room.pkGeneration++;
        if (room.thanks != null) room.thanks.configure(settings);
        if (room.welcomes != null) room.welcomes.configure(settings);
        Instant now = clock.get();
        if (!settings.allows(RoomSettings.PAUSE_ACTIVITIES) && room.activities != null) {
            pauseRoom(room.streamer.roomId(), null, now);
        }
        syncLive(room, now);
        if (!settings.enabled()) room.session = null;
        else if (room.session != null) room.session.configure(settings, now);
        for (Item item : List.copyOf(room.pending)) {
            String reason = settingsReason(item);
            if (reason != null) drop(item, reason);
        }
    }

    synchronized void liveOn(long uid, boolean reconnect) {
        RoomState room = rooms.get(uid);
        if (room != null) {
            room.awaitingOfflineStatus = false;
            if (!reconnect) {
                room.pkIds = null;
                room.thanks = null;
                room.welcomes = null;
                store.forgetThanked(uid);
            }
            if (!reconnect && room.session != null) {
                clear(room, "上一场直播已结束");
                room.session = null;
            }
            syncLive(room, clock.get());
        }
    }

    synchronized void liveOff(long uid) {
        RoomState room = rooms.get(uid);
        if (room != null) {
            room.awaitingOfflineStatus = live.getLiveStatus(BilibiliPlatform.BILIBILI.id(), uid).orElse(false);
            endSession(room);
        }
    }

    private void syncLive(RoomState room, Instant now) {
        boolean living = live.getLiveStatus(BilibiliPlatform.BILIBILI.id(), room.streamer.uid()).orElse(false);
        if (!living) {
            room.awaitingOfflineStatus = false;
            if (room.session != null || room.thanks != null || room.welcomes != null || !room.pending.isEmpty()) endSession(room);
        } else if (room.session == null && !room.awaitingOfflineStatus && room.settings.enabled()) {
            room.session = new TimedDanmakuPlans();
            room.session.configure(room.settings, now);
        }
    }

    private void endSession(RoomState room) {
        room.pkIds = null;
        room.welcomes = null;
        room.thanks = null;
        clear(room, "主播未开播");
        room.session = null;
    }

    synchronized void viewer(NovaLiveMessageEvent event) {
        if (event.getSource() == null || event.getSender() == null
                || event.getSender().getUid() == null || event.getSender().getUid() < 0) return;
        RoomState room = rooms.get(event.getSource().getUid());
        if (room != null && room.session != null && room.settings.allows(RoomSettings.TIMED)
                && !sender.isOwnDanmaku(event)) room.session.viewerSpoke();
    }

    private RoomState eventRoom(NovaBaseLiveEvent event) {
        if (event.getSource() == null) return null;
        RoomState room = rooms.get(event.getSource().getUid());
        Long sourceRoom = event.getSource().getRoomId();
        return room != null && (sourceRoom == null || sourceRoom == room.streamer.roomId()) ? room : null;
    }

    private RoomState liveFeatureRoom(NovaBaseLiveEvent event, String feature, Instant now) {
        RoomState room = eventRoom(event);
        if (room == null || !room.settings.allows(feature)) return null;
        syncLive(room, now);
        return room.session == null ? null : room;
    }

    private static Instant eventTime(NovaBaseLiveEvent event, Instant now) {
        Instant occurred = Instant.ofEpochMilli(event.getTimestamp());
        return occurred.isAfter(now) ? now : occurred;
    }

    synchronized void keyword(BilibiliDanmuEvent event) {
        Instant now = clock.get();
        RoomState room = liveFeatureRoom(event, KeywordRule.FEATURE, now);
        if (room == null || room.settings.keywordRules().isEmpty() || event.getSender() == null || sender.isOwnDanmaku(event)) return;
        Instant expires = eventTime(event, now).plusSeconds(30);
        room.keywordReplies.accept(room.settings.keywordRules(), event.getContentText(), event.getSender(),
                room.streamer.name(), now, (rule, text) -> {
                    Item item = offer(room, KeywordRule.FEATURE, text, KeywordRule.PRIORITY, expires, null, sender.automaticAccountStatus());
                    if (item == null) return false;
                    item.keywordRule = rule;
                    return true;
                });
    }

    /** 在请求资料前认领场次，返回的回调只对这次直播和开关有效。 */
    synchronized Consumer<Profile> beginPk(BilibiliPkBattleEvent event) {
        Instant now = clock.get();
        RoomState room = liveFeatureRoom(event, PkAnnouncement.FEATURE, now);
        if (room == null || room.pause != null && now.isBefore(room.pause.until())) return null;
        Instant start = eventTime(event, now);
        Long planStart = event.getPlanStart();
        if (planStart != null && planStart > 0 && planStart < start.getEpochSecond()) start = Instant.ofEpochSecond(planStart);
        Instant expires = start.plusSeconds(60);
        if (!now.isBefore(expires)) return null;

        long broadcast = live.getLiveStartTime(BilibiliPlatform.BILIBILI.id(), room.streamer.uid()).orElse(0L);
        if (room.pkIds == null) {
            room.pkIds = broadcast > 0 ? store.thanked(room.streamer.uid(), broadcast, PkAnnouncement.FEATURE) : new HashSet<>();
        }
        String id = event.getPkId().toString();
        if (!room.pkIds.add(id)) return null;
        if (broadcast > 0) store.rememberThanked(room.streamer.uid(), broadcast, PkAnnouncement.FEATURE, Set.of(id));

        var session = room.session;
        long generation = room.pkGeneration;
        Instant deadline = now.plusSeconds(10);
        return profile -> {
            synchronized (this) {
                if (rooms.get(room.streamer.uid()) != room || room.pkGeneration != generation
                        || room.session != session || !clock.get().isBefore(deadline)) return;
                String text = PkAnnouncement.render(room.settings.pkTemplate(), room.streamer.name(), profile);
                if (text != null) enqueue(room.streamer.uid(), PkAnnouncement.FEATURE, text, PkAnnouncement.PRIORITY, expires);
            }
        };
    }

    synchronized void welcome(NovaBaseLiveEvent event, Long viewerUid, String viewerName, RoomGuardLevel level) {
        RoomState room = eventRoom(event);
        if (room == null || !(room.settings.allows(WelcomeSettings.GUARD) || room.settings.allows(WelcomeSettings.LISTED))) return;
        Instant now = clock.get();
        syncLive(room, now);
        if (room.session == null) return;
        if (room.welcomes == null) {
            long start = live.getLiveStartTime(BilibiliPlatform.BILIBILI.id(), room.streamer.uid()).orElse(0L);
            room.welcomes = new RoomWelcomes(store, room.streamer.uid(), start);
        }
        Instant occurred = Instant.ofEpochMilli(event.getTimestamp());
        var entry = new RoomWelcomes.Entry(viewerUid, viewerName, level, occurred.isAfter(now) ? now : occurred);
        boolean paused = room.pause != null && now.isBefore(room.pause.until());
        room.welcomes.accept(entry, event instanceof BilibiliGuardEntryEffectEvent, now, paused, room.settings,
                ready -> offerWelcome(room, ready));
    }

    private void offerWelcome(RoomState room, RoomWelcomes.Entry entry) {
        var greeting = room.welcomes.greeting(room.settings, room.streamer.name(), entry);
        if (greeting == null) return;
        AccountStatus account = sender.automaticAccountStatus();
        if (entry.identified() && entry.uid().equals(account.uid())) return;
        Instant expires = entry.entered().plusSeconds(30);
        if (offer(room, greeting.feature(), greeting.text(), RoomWelcomes.PRIORITY, expires, null, account) != null) {
            room.welcomes.remember(entry);
        }
    }

    synchronized void activity(NovaBaseLiveEvent event, BiConsumer<RoomActivities, Instant> change) {
        RoomState room = eventRoom(event);
        if (room == null || !room.settings.allows(RoomSettings.PAUSE_ACTIVITIES)) return;
        Instant now = clock.get();
        RoomActivities activities = room.activities == null ? new RoomActivities() : room.activities;
        activities.expire(now);
        Instant occurred = Instant.ofEpochMilli(event.getTimestamp());
        change.accept(activities, occurred.isAfter(now) ? now : occurred);
        Pause pause = activities.pauseAt(now);
        if (pause == null) {
            if (room.activities != null) pauseRoom(room.streamer.roomId(), null, now);
        } else {
            dropThanksForActivity(room, pause.reason(), now);
            pauseRoom(room.streamer.roomId(), pause.reason(), pause.until());
            room.activities = activities;
        }
    }

    private void dropThanksForActivity(RoomState room, String reason, Instant now) {
        if (room.thanks != null && room.thanks.hasPending()) {
            room.thanks.dropForActivity(room.settings.thanks(), room.streamer.name(),
                    (kind, text) -> store.drop(room.streamer, kind.key, text, reason, now));
        }
    }

    synchronized void thank(ThanksKind kind, NovaBaseLiveEvent event, UserInfo viewer) {
        RoomState room = eventRoom(event);
        if (room == null || !room.settings.allows(kind.key)) return;
        Instant now = clock.get();
        syncLive(room, now);
        boolean paused = room.pause != null && now.isBefore(room.pause.until());
        if (room.session == null || (paused && room.activities == null)) return;
        if (room.thanks == null) {
            long start = live.getLiveStartTime(BilibiliPlatform.BILIBILI.id(), room.streamer.uid()).orElse(0L);
            room.thanks = new RoomThanks(store, room.streamer.uid(), start, (feature, text, expires) ->
                    enqueue(room.streamer.uid(), feature.key, text, feature.priority, expires));
        }
        if (room.thanks.checkAccount(sender.automaticAccountStatus())) {
            room.thanks.accept(kind, event, viewer, room.settings.thanks(), room.streamer.name(), now);
            if (paused) dropThanksForActivity(room, room.pause.reason(), now);
        }
    }

    /** 数值越大越优先，expiresAt 为绝对截止时间，到点即丢弃。 */
    public synchronized void enqueue(long streamerUid, String feature, String text, int priority, Instant expiresAt) {
        RoomState room = rooms.get(streamerUid);
        if (room == null) {
            String reason = stopped ? "程序停止发送" : "主播已移除或没有直播间";
            store.drop(new Room(streamerUid, "UID " + streamerUid, 0), feature, text, reason, clock.get());
            return;
        }
        syncLive(room, clock.get());
        offer(room, feature, text, priority, expiresAt, null, sender.automaticAccountStatus());
    }

    private Item offer(RoomState room, String feature, String text, int priority, Instant expires,
                          TimedDanmakuPlans.Timer timer, AccountStatus account) {
        Item item = new Item(room, account, feature, text, priority, ++sequence, Objects.requireNonNull(expires), timer);
        Instant now = clock.get();
        // 入队也检查现有条目，避免暂停期间积压，或过期条目占满房间。
        purge(account, now);
        String reason = reason(item, account, now);
        if (reason == null && (text == null || text.isBlank() || text.contains("\n") || text.contains("\r"))) {
            reason = "文字为空或含换行";
        }
        if (reason != null) {
            drop(item, reason);
            return null;
        }
        room.pending.add(item);
        if (room.pending.size() > CAPACITY) {
            room.pending.stream().filter(i -> i.started == null).min(EVICT).ifPresent(i -> drop(i, "排队已满"));
            return room.pending.contains(item) ? item : null;
        }
        return item;
    }

    /** 暂停指定房间，到 until 自动解除；传入当前或过去的时间立即解除。 */
    public synchronized void pauseRoom(long roomId, String reason, Instant until) {
        for (RoomState room : rooms.values()) {
            if (room.streamer.roomId() != roomId) continue;
            finishActivityPause(room, clock.get());
            if (until.isAfter(clock.get())) {
                room.pkGeneration++;
                room.pause = new Pause(reason == null || reason.isBlank() ? "房间暂停发送" : reason, until);
                if (room.welcomes != null) room.welcomes.flush(Instant.MAX, entry -> offerWelcome(room, entry));
                if (room.thanks != null) room.thanks.discardPending();
                clear(room, room.pause.reason());
            } else {
                room.pause = null;
            }
        }
    }

    private void finishActivityPause(RoomState room, Instant now) {
        if (room.activities == null) return;
        // 发送线程可能正在等待别房间的平台响应，先跳过暂停期间已经到点的计划。
        if (room.session != null) {
            Instant end = now.isBefore(room.pause.until()) ? now : room.pause.until();
            room.session.due(end.minusNanos(1), timer -> store.drop(room.streamer, RoomSettings.TIMED,
                    timer.text(room.streamer.name()), room.pause.reason(), now));
        }
        room.activities = null;
    }

    private String settingsReason(Item item) {
        RoomState room = item.room;
        if (!room.settings.allows(item.feature)) return "自动发弹幕或功能开关已关闭";
        if (item.keywordRule != null && !room.settings.keywordRules().contains(item.keywordRule)) return "关键词规则已更改或停用";
        if (item.timer != null && (room.session == null || !room.session.current(item.timer))) return "定时计划已更改或停用";
        return null;
    }

    private String reason(Item item, AccountStatus account, Instant now) {
        RoomState room = item.room;
        if (room.session == null) return room.settings.enabled() ? "主播未开播" : "自动发弹幕已关闭";
        String settings = settingsReason(item);
        if (settings != null) return settings;
        if (room.pause != null && now.isBefore(room.pause.until())) return room.pause.reason();
        if (!now.isBefore(item.expires)) return "已过发送时限";
        if (!account.usable() || account.uid() == null) {
            return account.failure() == null ? "发送账号不可用" : account.failure().description();
        }
        if (account.pausedFor() != null) return "账号暂停发送中";
        if (account.choice() != item.account.choice() || !account.uid().equals(item.account.uid())) return "发送账号已更换";
        if (item.timer != null && item.part == 0 && item.timer.plan.skipQuiet() && room.session.quiet(item.timer)) return "没有观众发言";
        return null;
    }

    private void clear(RoomState room, String reason) {
        for (Item item : List.copyOf(room.pending)) drop(item, reason);
    }

    private void drop(Item item, String reason) {
        item.room.pending.remove(item);
        if (item.parts == null) {
            store.drop(item.room.streamer, item.feature, item.text, reason, clock.get());
        } else {
            for (int i = item.part + (item.started != null ? 1 : 0); i < item.parts.size(); i++) {
                store.drop(item.room.streamer, item.feature, item.parts.get(i), reason, clock.get());
            }
        }
    }

    private void purge(AccountStatus account, Instant now) {
        for (RoomState room : rooms.values()) {
            for (Item item : List.copyOf(room.pending)) {
                String reason = reason(item, account, now);
                if (reason != null) drop(item, reason);
            }
        }
    }

    /** 只由一个发送线程调用，每次最多向平台交一段。 */
    void tick() {
        Item item = next();
        if (item == null) return;
        if (item.parts == null) {
            var limit = sender.lengthLimit(item.room.streamer.roomId());
            synchronized (this) {
                if (limit.loginRejection() != null) {
                    if (item.room.pending.contains(item)) drop(item, limit.loginRejection());
                    purgeAfterFailure();
                    return;
                }
                if (!item.room.pending.contains(item)) return;
                item.parts = split(item.text.strip(), limit.max());
            }
        }
        String text = item.parts.get(item.part);
        Room room = item.room.streamer;
        Optional<DanmakuResult> result = sender.sendQueued(room.roomId(), text, room.uid(), item.account, reserve -> admit(item, reserve));
        result.ifPresent(r -> finish(item, r));
    }

    private synchronized Item next() {
        Instant now = clock.get();
        for (RoomState room : rooms.values()) {
            if (!room.settings.enabled()) continue;
            syncLive(room, now);
            if (room.welcomes != null) room.welcomes.flush(now, entry -> offerWelcome(room, entry));
            if (room.thanks != null && room.thanks.hasPending() && room.thanks.checkAccount(sender.automaticAccountStatus())) {
                room.thanks.flush(room.settings.thanks(), room.streamer.name(), now);
            }
            if (room.activities != null && !now.isBefore(room.pause.until())) finishActivityPause(room, now);
            if (room.session != null) {
                room.session.due(now, timer -> offer(room, RoomSettings.TIMED, timer.text(room.streamer.name()), 0,
                        timer.due, timer, sender.automaticAccountStatus()));
            }
        }
        if (rooms.values().stream().allMatch(r -> r.pending.isEmpty())) return null;
        AccountStatus status = sender.automaticAccountStatus();
        purge(status, now);
        if (!sender.intervalElapsed(status)) return null;
        return rooms.values().stream().filter(r -> r.hasCapacity(now))
                .map(r -> r.pending.stream().min(ROOM_ORDER)).flatMap(Optional::stream).min(NEXT).orElse(null);
    }

    /** 平台字数查询和账号间隔检查之后、真正占用发送名额之前再核对一次。 */
    private synchronized boolean admit(Item item, BooleanSupplier reserve) {
        RoomState room = item.room;
        Instant now = clock.get();
        syncLive(room, now);
        if (!room.pending.contains(item)) return false;
        String reason = reason(item, sender.automaticAccountStatus(), now);
        if (reason != null) {
            drop(item, reason);
            return false;
        }
        if (!room.hasCapacity(now) || !reserve.getAsBoolean()) return false;
        item.sendingSession = room.session;
        item.started = now;
        item.audienceAtSend = room.session.audienceCount();
        return true;
    }

    private synchronized void finish(Item item, DanmakuResult result) {
        RoomState room = item.room;
        if (!room.pending.contains(item) && item.started == null) return;
        store.result(room.streamer, item.feature, result, clock.get());
        if (item.started != null) room.recent.addLast(item.started);
        if ((result.sent() || result.uncertain()) && item.timer != null && item.sendingSession != null) {
            item.sendingSession.sent(item.timer, item.part == 0, item.audienceAtSend);
        }
        item.started = null;
        item.part++;
        boolean pending = room.pending.contains(item);
        if (item.part == item.parts.size()) room.pending.remove(item);
        else if (pending && !result.sent()) drop(item, "前一段未确认发出，停止发送");
        accountResult(item.account, result);
    }

    /** 平台也可能在管理员试发时告知账号失效或暂停。 */
    public synchronized void accountResult(AccountStatus account, DanmakuResult result) {
        DanmakuFailure failure = result.failure();
        if (failure == DanmakuFailure.ACCOUNT_UNAVAILABLE || failure == DanmakuFailure.NO_ACCOUNT) {
            for (RoomState other : rooms.values()) {
                if (other.thanks != null && other.thanks.uses(account)) other.thanks.discardPending();
                for (Item waiting : List.copyOf(other.pending)) {
                    if (waiting.account.choice() == account.choice() && Objects.equals(waiting.account.uid(), account.uid())) {
                        drop(waiting, failure.description());
                    }
                }
            }
        }
        if (!result.sent()) purgeAfterFailure();
    }

    private void purgeAfterFailure() {
        AccountStatus current = sender.automaticAccountStatus();
        for (RoomState room : rooms.values()) {
            if (room.thanks != null) room.thanks.checkAccount(current);
        }
        purge(current, clock.get());
    }

    private static List<String> split(String text, int limit) {
        List<String> parts = new ArrayList<>(3);
        int start = 0;
        int remaining = text.codePointCount(0, text.length());
        while (remaining > limit && parts.size() < 2) {
            int end = text.offsetByCodePoints(start, limit);
            parts.add(text.substring(start, end));
            start = end;
            remaining -= limit;
        }
        String last = remaining > limit
                ? text.substring(start, text.offsetByCodePoints(start, limit - 1)) + "…"
                : text.substring(start);
        parts.add(last);
        return parts;
    }
}
