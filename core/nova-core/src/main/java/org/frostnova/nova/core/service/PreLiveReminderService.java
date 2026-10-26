package org.frostnova.nova.core.service;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.frostnova.nova.core.assistant.AssistantProperties;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.handler.PreLiveReminderPushHandler;
import org.frostnova.nova.core.model.Message;
import org.frostnova.nova.core.model.MessagePlaceholders;
import org.frostnova.nova.core.model.PushTarget;
import org.frostnova.nova.core.model.PushUser;
import org.frostnova.nova.core.command.CommandContext;
import org.frostnova.nova.core.sender.NovaMessageSender;
import org.frostnova.nova.core.sender.PushGate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.time.*;
import java.util.*;

/** 根据封存场次的惯常时间提醒主播本人，错过十分钟后不补发。 */
@Slf4j
@Service
public class PreLiveReminderService {
    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final long HOUR = Duration.ofHours(1).toNanos(), DAY = Duration.ofDays(1).toNanos();
    private final AbstractDataSource source;
    private final LiveSessionArchive archive;
    private final LiveDataService live;
    private final AudienceLedgerService ledger;
    private final SessionRecapService recap;
    private final StreamerProfileStore profiles;
    private final AssistantProperties assistant;
    private final NovaMessageSender sender;
    private final PushGate gate;
    private final PreLiveReminderStore store;
    private final Clock clock;
    private ThreadPoolTaskScheduler scheduler;

    @Autowired
    public PreLiveReminderService(AbstractDataSource source, LiveSessionArchive archive, LiveDataService live,
            AudienceLedgerService ledger, SessionRecapService recap, StreamerProfileStore profiles,
            AssistantProperties assistant, NovaMessageSender sender, PushGate gate, NovaCoreProperties properties) {
        this(source, archive, live, ledger, recap, profiles, assistant, sender, gate,
                new PreLiveReminderStore(Path.of(properties.getLive().getLiveDataPath()).toAbsolutePath()
                        .resolveSibling("pre-live-reminder-deliveries.json")), Clock.systemUTC());
    }

    public PreLiveReminderService(AbstractDataSource source, LiveSessionArchive archive, LiveDataService live,
            AudienceLedgerService ledger, SessionRecapService recap, StreamerProfileStore profiles,
            AssistantProperties assistant, NovaMessageSender sender, PushGate gate,
            PreLiveReminderStore store, Clock clock) {
        this.source = source; this.archive = archive; this.live = live; this.ledger = ledger; this.recap = recap;
        this.profiles = profiles; this.assistant = assistant; this.sender = sender; this.gate = gate;
        this.store = store; this.clock = clock;
    }

    @Order(100) @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        if (scheduler != null) return;
        scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1); scheduler.setThreadNamePrefix("pre-live-reminder-"); scheduler.initialize();
        scheduler.scheduleWithFixedDelay(this::safelyCheck, clock.instant(), Duration.ofMinutes(5));
    }

    @PreDestroy public synchronized void stop() { if (scheduler != null) scheduler.shutdown(); }

    private void safelyCheck() {
        try { check(); } catch (IOException | RuntimeException failure) { log.error("开播前提醒调度失败", failure); }
    }

    private record Streamer(String platform, long uid) {}
    private record Subscription(Streamer streamer, PushTarget target) {}
    private record PendingReminder(String key, PushTarget target, ZonedDateTime due, String body, boolean ignoreQuiet) {}

    public synchronized void check() throws IOException {
        var now = clock.instant().atZone(ZONE);
        var subscriptions = new ArrayList<Subscription>();
        for (var user : source.getAllUsers()) {
            if (Boolean.FALSE.equals(user.getEnabled())) continue;
            for (var target : user.getTargets()) {
                if (Boolean.FALSE.equals(target.getEnabled()) || target.getType() != PushTargetType.FRIEND
                        || !Boolean.TRUE.equals(target.getStreamerSelf())) continue;
                if (target.getMessages().stream().anyMatch(m -> !Boolean.FALSE.equals(m.getEnabled())
                        && PreLiveReminderPushHandler.class.getName().equals(m.handlerClassName())))
                    subscriptions.add(new Subscription(new Streamer(user.getPlatform(), user.getUid()), target));
            }
        }
        if (subscriptions.isEmpty() || !gate.isReady() || gate.blockedBy() == PushGate.Block.DISABLED) return;
        // 每轮只读取一次封存归档，按平台和主播分组，重复订阅共用计算素材。
        var sessions = new HashMap<Streamer, List<LiveSessionArchive.ArchivedSession>>();
        for (var session : archive.sealedSessions()) {
            if (session.durationSeconds() < 600 || session.startTime() > now.toInstant().toEpochMilli()) continue;
            sessions.computeIfAbsent(new Streamer(session.platform(), session.uid()), k -> new ArrayList<>()).add(session);
        }
        var material = new HashMap<Streamer, SessionRecapService.PreLiveAudience>();
        var pending = new ArrayList<PendingReminder>();
        ledger.withRecoveredLedger(() -> {
            for (var subscription : subscriptions) {
                try { prepare(subscription, sessions.getOrDefault(subscription.streamer(), List.of()), now, material, pending); }
                catch (IOException | RuntimeException failure) { log.warn("本轮开播前提醒未能生成", failure); }
            }
        });
        // 入队可能同步执行被挤出消息的回调；发送和记录均在释放账本锁之后进行。
        for (var reminder : pending) {
            try { deliver(reminder); }
            catch (RuntimeException failure) { log.warn("本轮开播前提醒未能发送", failure); }
        }
    }

    private void prepare(Subscription sub, List<LiveSessionArchive.ArchivedSession> history, ZonedDateTime now,
                         Map<Streamer, SessionRecapService.PreLiveAudience> material,
                         List<PendingReminder> pending) throws IOException {
        var who = sub.streamer();
        var profile = profiles.read(who.platform(), who.uid());
        if (profile.status() != StreamerProfile.Status.READY || profile.paused() || profile.preLiveDisabled()
                || gate.blockedBy() == PushGate.Block.DISABLED) return;
        var recent = recent(history);
        var dueTimes = dueTimes(profile, history, now);
        for (var due : dueTimes) {
            if (now.isBefore(due) || now.isAfter(due.plusMinutes(10))) continue;
            String key = WeeklyReportDeliveryService.key(who.platform(), who.uid(), sub.target());
            if (store.contains(key, due.toLocalDate())
                    || (profile.preLiveDailyTime() == null && store.contains(key, now.toLocalDate()))) return;
            if ((profile.preLiveDailyTime() == null && !gate.allowedAt(now.toLocalTime(), sub.target()))
                    || live.getLiveStatus(who.platform(), who.uid()).orElse(false)) return;
            var audience = material.get(who);
            if (audience == null) {
                audience = audience(who, recent);
                material.put(who, audience);
            }
            String body = render(audience, profile, who.uid(), new HashSet<>(assistant.getExcludedUids()));
            if (body.isBlank()) return;
            pending.add(new PendingReminder(key, sub.target(), due, body, profile.preLiveDailyTime() != null));
        }
    }

    private static List<LiveSessionArchive.ArchivedSession> recent(List<LiveSessionArchive.ArchivedSession> history) {
        return history.stream().sorted(Comparator.comparingLong(LiveSessionArchive.ArchivedSession::startTime)
                .reversed()).limit(10).toList();
    }

    /** 调度与控制台共用到点计算；包含跨午夜宽限期以及今天、明天的候选时刻。 */
    private static List<ZonedDateTime> dueTimes(StreamerProfile profile,
            List<LiveSessionArchive.ArchivedSession> history, ZonedDateTime now) {
        var times = new ArrayList<ZonedDateTime>();
        if (profile.preLiveDailyTime() != null) {
            for (int offset = -1; offset <= 1; offset++)
                times.add(now.toLocalDate().plusDays(offset).atTime(LocalTime.parse(profile.preLiveDailyTime())).atZone(ZONE));
        } else {
            var usual = usualStart(recent(history));
            if (usual.isEmpty()) return times;
            int lead = profile.preLiveLeadMinutes() == null ? 60 : profile.preLiveLeadMinutes();
            for (int offset = -1; offset <= 2; offset++) {
                var start = now.toLocalDate().plusDays(offset).atTime(usual.get()).atZone(ZONE);
                var weekAgo = start.minusWeeks(1).toInstant();
                if (history.stream().anyMatch(session -> session.startTime() >= weekAgo.toEpochMilli()
                        && session.startTime() <= weekAgo.plus(Duration.ofHours(1)).toEpochMilli()))
                    times.add(start.minusMinutes(lead));
            }
        }
        return times.stream().sorted().toList();
    }

    private List<LiveSessionArchive.ArchivedSession> history(String platform, long uid) throws IOException {
        return archive.sealedSessions().stream().filter(s -> s.platform().equals(platform) && s.uid() == uid
                && s.durationSeconds() >= 600 && s.startTime() <= clock.millis()).toList();
    }

    private PushTarget target(String platform, long uid, PushTarget identity) {
        return source.getAllUsers().stream()
                .filter(user -> platform.equals(user.getPlatform()) && Objects.equals(uid, user.getUid()))
                .map(user -> org.frostnova.nova.core.model.ChannelSettings.target(user, identity.getPlatform(),
                        identity.getType(), identity.getNum())).filter(Objects::nonNull).findFirst().orElse(null);
    }

    private boolean subscribed(PushTarget target) {
        return target != null && target.getMessages().stream().anyMatch(m -> !Boolean.FALSE.equals(m.getEnabled())
                && PreLiveReminderPushHandler.class.getName().equals(m.handlerClassName()));
    }

    public record DeliveryStatus(String status, boolean profileUnreadable, boolean historyUnreadable) {}

    public synchronized DeliveryStatus status(String platform, long uid, PushTarget identity) {
        var target = target(platform, uid, identity);
        var profile = profiles.read(platform, uid);
        var history = store.history(WeeklyReportDeliveryService.key(platform, uid, identity));
        var now = clock.instant().atZone(ZONE);
        boolean unreadable = profile.status() != StreamerProfile.Status.READY;
        boolean stopped = !subscribed(target) || profile.preLiveDisabled() || profile.paused()
                || gate.blockedBy() == PushGate.Block.DISABLED || unreadable;
        var parts = new ArrayList<String>();
        parts.add(subscribed(target) ? "开播前提醒（私聊）已勾选" : "开播前提醒（私聊）没勾选，不会发");
        parts.add("主播的设置：" + (unreadable ? "档案读不出，暂时无法确认" :
                org.frostnova.nova.core.command.StreamerProfileCommands.describePreLiveReminder(profile)));
        if (unreadable || history.unreadable()) parts.add("下一次：暂无");
        else if (profile.preLiveDisabled()) parts.add("下一次：暂无");
        else {
            try {
                var sessions = history(platform, uid);
                if (profile.preLiveDailyTime() == null && usualStart(recent(sessions)).isEmpty())
                    parts.add("惯常开播时间还没算出来，暂时不会按提前量发");
                else {
                    var next = dueTimes(profile, sessions, now).stream()
                            .filter(due -> !now.isAfter(due.plusMinutes(10)))
                            .filter(due -> !due.toLocalDate().isAfter(now.toLocalDate().plusDays(1)))
                            .filter(due -> !history.records().containsKey(due.toLocalDate()))
                            .filter(due -> profile.preLiveDailyTime() != null || !history.records().containsKey(
                                    due.isAfter(now) ? due.toLocalDate() : now.toLocalDate()))
                            .findFirst();
                    parts.add(next.map(due -> relativeDate(due.toLocalDate(), now.toLocalDate()) + " "
                            + due.toLocalTime().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")))
                            .map(time -> "下一次：" + (stopped ? "暂不会发（条件满足时为" + time + "）" : time))
                            .orElse("下一次：今明两天没有符合条件的提醒"));
                }
            } catch (IOException | RuntimeException failure) { parts.add("场次归档读不出，下一次暂时无法计算"); }
        }
        if (target == null || target.getType() != PushTargetType.FRIEND || !Boolean.TRUE.equals(target.getStreamerSelf()))
            parts.add("没有可用的主播本人好友通道，不会发");
        else if (Boolean.FALSE.equals(target.getEnabled())) parts.add("通道已停用，不会发");
        if (source.getAllUsers().stream().anyMatch(user -> platform.equals(user.getPlatform())
                && Objects.equals(uid, user.getUid()) && Boolean.FALSE.equals(user.getEnabled())))
            parts.add("主播已停用，不会发");
        if (profile.preLiveDisabled()) parts.add("主播本人说过不要开播前提醒，不会发");
        if (profile.paused()) parts.add("已暂停（主播本人说过「先别发了」），不会发");
        if (gate.blockedBy() == PushGate.Block.DISABLED) parts.add("全局推送开关已关闭，不会发");
        if (unreadable) parts.add("档案读不出，已停发");
        if (history.unreadable()) parts.add("发送记录读不出，已停发");
        if (target != null && profile.preLiveDailyTime() == null && gate.inQuietHoursFor(target))
            parts.add("现在是静音时段，按提前量的提醒会被挡住");
        if (!gate.isReady()) parts.add("机器人刚启动，暂时不会发");
        if (live.getLiveStatus(platform, uid).orElse(false)) parts.add("主播正在直播，暂时不会发");
        if (history.unreadable()) parts.add("上一次：发送记录读不出");
        else parts.add(history.records().entrySet().stream().max(Map.Entry.comparingByKey())
                .map(last -> "上一次：" + last.getKey() + " " + switch (last.getValue()) {
                    case SENDING -> "正在发";
                    case SENT -> "已发";
                    case FAILED -> "发送失败，不会自动重发";
                    case UNCERTAIN -> "发送结果不明，不会自动重发";
                    case NOT_SENT -> "没发出去";
                }).orElse("还没发过"));
        return new DeliveryStatus(String.join("；", parts), unreadable, history.unreadable());
    }

    private static String relativeDate(LocalDate date, LocalDate today) {
        if (date.equals(today)) return "今天";
        if (date.equals(today.plusDays(1))) return "明天";
        if (date.equals(today.minusDays(1))) return "昨天";
        return date.toString();
    }

    public record TrialResult(boolean success, String message, String confirmation) {}

    /** 控制台试发只生成并发送正文，不登记记录，也不占定时名额。 */
    public synchronized TrialResult trial(String platform, long uid, PushTarget identity,
            boolean confirmPaused, boolean confirmQuiet, boolean confirmDisabled) {
        var target = target(platform, uid, identity);
        if (target == null || target.getType() != PushTargetType.FRIEND || !Boolean.TRUE.equals(target.getStreamerSelf()))
            return new TrialResult(false, "只能试发到这位主播标了「主播本人」的好友通道", "");
        var profile = profiles.read(platform, uid);
        if (profile.status() != StreamerProfile.Status.READY)
            return new TrialResult(false, "档案读不出，已停发，请先去主播页核对恢复", "");
        if (profile.preLiveDisabled()) return new TrialResult(false, "主播本人说过不要开播前提醒，试发也不发", "");
        if (profile.paused() && !confirmPaused) return new TrialResult(false, "要先确认", "paused");
        if (gate.inQuietHoursFor(target) && !confirmQuiet) return new TrialResult(false, "要先确认", "quiet");
        if (gate.blockedBy() == PushGate.Block.DISABLED && !confirmDisabled)
            return new TrialResult(false, "要先确认", "disabled");
        try {
            var recent = recent(history(platform, uid));
            var body = new String[1];
            ledger.withRecoveredLedger(() -> body[0] = render(audience(new Streamer(platform, uid), recent), profile, uid,
                    new HashSet<>(assistant.getExcludedUids())));
            if (body[0].isBlank()) return new TrialResult(false,
                    "这次没有要特别留意的：最近没有缺席的常客，上一场也没有第一次说话的新观众。", "");
            Message message = Message.create(target.getPlatform(), target.getType(), target.getNum(),
                    MessagePlaceholders.escapeExternalText(body[0])).get(0);
            message.setChannel(target);
            var response = sender.sendNow(message);
            boolean success = response != null && Integer.valueOf(0).equals(response.getInteger("code"));
            return new TrialResult(success, success ? "已试发开播前提醒，请确认主播是否收到。不记进发送记录，也不算当天那一条，当天定时的照常发"
                    : "试发失败，请稍后重试", "");
        } catch (IOException | RuntimeException failure) { return new TrialResult(false, "提醒生成或试发失败，请稍后重试", ""); }
    }

    private SessionRecapService.PreLiveAudience audience(Streamer who, List<LiveSessionArchive.ArchivedSession> recent) throws IOException {
        ledger.updateStreamer(who.platform(), who.uid());
        if (recent.isEmpty()) return new SessionRecapService.PreLiveAudience(List.of(), List.of(), Map.of());
        return recap.preLiveAudience(who.platform(), who.uid(), recent.get(0).startTime());
    }

    /** 本人当面索取，不受暂停、在播或静音影响，每次索取都可发送。 */
    public synchronized void request(PushUser user, CommandContext context) throws IOException {
        if (gate.blockedBy() == PushGate.Block.DISABLED) return;
        var target = user.getTargets().stream().filter(t -> Boolean.TRUE.equals(t.getStreamerSelf())
                && t.getType() == context.getType() && Objects.equals(t.getNum(), context.getNum())
                && Objects.equals(t.getPlatform(), context.getPlatform()) && !Boolean.FALSE.equals(t.getEnabled()))
                .findFirst().orElseThrow();
        if (!gate.isReady()) {
            requestedReply(target, "机器人刚启动，请过一会儿再说一次");
            return;
        }
        if (target.getMessages().stream().noneMatch(m -> !Boolean.FALSE.equals(m.getEnabled())
                && PreLiveReminderPushHandler.class.getName().equals(m.handlerClassName()))) {
            requestedReply(target, "开播前提醒还没开，要请管理员在控制台给你这条私聊勾上「开播前提醒（私聊）」");
            return;
        }
        var who = new Streamer(user.getPlatform(), user.getUid());
        var profile = profiles.read(who.platform(), who.uid());
        if (profile.status() != StreamerProfile.Status.READY) {
            requestedReply(target, "档案暂时读不出，请稍后再试");
            return;
        }
        var recent = archive.sealedSessions().stream().filter(session -> session.platform().equals(who.platform())
                && session.uid() == who.uid() && session.durationSeconds() >= 600 && session.startTime() <= clock.millis())
                .sorted(Comparator.comparingLong(LiveSessionArchive.ArchivedSession::startTime).reversed()).limit(10).toList();
        var body = new String[1];
        ledger.withRecoveredLedger(() -> body[0] = render(audience(who, recent), profile, who.uid(),
                new HashSet<>(assistant.getExcludedUids())));
        if (body[0].isBlank()) {
            requestedReply(target, "这次没有要特别留意的：最近没有缺席的常客，上一场也没有第一次说话的新观众。");
            return;
        }
        deliver(new PendingReminder(WeeklyReportDeliveryService.key(who.platform(), who.uid(), target),
                target, clock.instant().atZone(ZONE), body[0], true), true);
    }

    private void requestedReply(PushTarget target, String text) {
        Message message = Message.create(target.getPlatform(), target.getType(), target.getNum(), text).get(0);
        message.setChannel(target); message.setReply(true);
        sender.sendRequestedReply(message);
    }

    private void deliver(PendingReminder reminder) { deliver(reminder, false); }

    private void deliver(PendingReminder reminder, boolean requested) {
        var sendTime = clock.instant().atZone(ZONE);
        if (!requested && (sendTime.isBefore(reminder.due()) || sendTime.isAfter(reminder.due().plusMinutes(10)))) return;
        if (gate.blockedBy() == PushGate.Block.DISABLED) return;
        LocalDate dueDate = reminder.due().toLocalDate();
        LocalDate date = !requested && reminder.ignoreQuiet() ? dueDate : sendTime.toLocalDate();
        String key = reminder.key();
        var target = reminder.target();
        Message message = Message.create(target.getPlatform(), target.getType(), target.getNum(),
                MessagePlaceholders.escapeExternalText(reminder.body())).get(0);
        message.setChannel(target); message.setReply(requested);
        if (!(requested ? store.reserveRequested(key, date) : store.reserve(key, date, dueDate))) {
            if (requested) requestedReply(target, "发送记录暂时读不出，这次没发，请稍后再试");
            return;
        }
        message.addOnSuccessCallback(() -> store.result(key, date, dueDate, PreLiveReminderStore.Status.SENT));
        message.addOnFailureCallback(() -> store.result(key, date, dueDate, PreLiveReminderStore.Status.FAILED));
        message.addOnUncertainCallback(() -> store.result(key, date, dueDate, PreLiveReminderStore.Status.UNCERTAIN));
        try {
            var block = requested ? sender.sendRequested(message)
                    : reminder.ignoreQuiet() ? sender.sendScheduled(message, sendTime.toLocalTime(), true)
                    : sender.sendScheduled(message, sendTime.toLocalTime());
            if (block != null) {
                store.result(key, date, dueDate, PreLiveReminderStore.Status.NOT_SENT);
                if (requested && !gate.isReady()) requestedReply(target, "机器人刚启动，请过一会儿再说一次");
            }
        } catch (RuntimeException failure) { store.result(key, date, dueDate, PreLiveReminderStore.Status.UNCERTAIN); }
    }

    /** 所有候选窗口以某场的开播时刻起始；同场数时取钟面上较早者。 */
    static Optional<LocalTime> usualStart(List<LiveSessionArchive.ArchivedSession> recent) {
        var times = recent.stream().map(s -> Instant.ofEpochMilli(s.startTime()).atZone(ZONE).toLocalTime())
                .sorted().toList();
        LocalTime best = null;
        long most = 4;
        for (var start : times) {
            long count = times.stream().filter(t -> Math.floorMod(t.toNanoOfDay() - start.toNanoOfDay(), DAY) <= HOUR).count();
            if (count > most) { most = count; best = start; }
        }
        return Optional.ofNullable(best);
    }

    static String render(SessionRecapService.PreLiveAudience audience, StreamerProfile profile, long host, Set<Long> excluded) {
        var omitted = profile.exclusions().stream().map(StreamerProfile.Exclusion::type).toList();
        var lines = new ArrayList<String>();
        if (!omitted.contains(StreamerProfile.Pattern.regulars_absent))
            addNames(lines, "最近没来的常客：", audience.absentRegulars(), audience.nicknames(), host, excluded);
        if (!omitted.contains(StreamerProfile.Pattern.newcomer_speak_return))
            addNames(lines, "上一场第一次说话的新观众：", audience.newSpeakers(), audience.nicknames(), host, excluded);
        return lines.isEmpty() ? "" : "【开播前提醒】\n" + String.join("\n", lines);
    }

    private static void addNames(List<String> lines, String label, List<Long> people, Map<Long, String> nicknames,
                                 long host, Set<Long> excluded) {
        var allowed = people.stream().filter(uid -> uid > 0 && uid != host && !excluded.contains(uid)).distinct().toList();
        var names = allowed.stream().map(nicknames::get).filter(Objects::nonNull)
                .filter(n -> !n.isBlank() && n.codePoints().noneMatch(Character::isISOControl)).limit(3).toList();
        if (!names.isEmpty()) lines.add(label + String.join("、", names)
                + (names.size() < allowed.size() ? " 等 " + allowed.size() + " 位" : ""));
    }
}
