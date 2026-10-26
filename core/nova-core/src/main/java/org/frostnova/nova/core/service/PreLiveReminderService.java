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
        if (subscriptions.isEmpty() || gate.blockedBy() == PushGate.Block.DISABLED) return;
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
        var recent = history.stream().sorted(Comparator.comparingLong(LiveSessionArchive.ArchivedSession::startTime)
                .reversed()).limit(10).toList();
        var dueTimes = new ArrayList<ZonedDateTime>();
        if (profile.preLiveDailyTime() != null) {
            // 昨日的定时也可能仍在跨午夜的十分钟有效期内。
            for (int offset = -1; offset <= 0; offset++)
                dueTimes.add(now.toLocalDate().plusDays(offset).atTime(LocalTime.parse(profile.preLiveDailyTime())).atZone(ZONE));
        } else {
            var usual = usualStart(recent);
            if (usual.isEmpty()) return;
            int lead = profile.preLiveLeadMinutes() == null ? 60 : profile.preLiveLeadMinutes();
            for (int offset = -1; offset <= 1; offset++) {
                var start = now.toLocalDate().plusDays(offset).atTime(usual.get()).atZone(ZONE);
                var weekAgo = start.minusWeeks(1).toInstant();
                if (history.stream().anyMatch(session -> session.startTime() >= weekAgo.toEpochMilli()
                        && session.startTime() <= weekAgo.plus(Duration.ofHours(1)).toEpochMilli()))
                    dueTimes.add(start.minusMinutes(lead));
            }
        }
        for (var due : dueTimes) {
            if (now.isBefore(due) || now.isAfter(due.plusMinutes(10))) continue;
            String key = WeeklyReportDeliveryService.key(who.platform(), who.uid(), sub.target());
            if (store.contains(key, now.toLocalDate()) || store.contains(key, due.toLocalDate())) return;
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
        sender.sendRequested(message);
    }

    private void deliver(PendingReminder reminder) { deliver(reminder, false); }

    private void deliver(PendingReminder reminder, boolean requested) {
        var sendTime = clock.instant().atZone(ZONE);
        if (!requested && (sendTime.isBefore(reminder.due()) || sendTime.isAfter(reminder.due().plusMinutes(10)))) return;
        if (gate.blockedBy() == PushGate.Block.DISABLED) return;
        LocalDate date = sendTime.toLocalDate(), dueDate = reminder.due().toLocalDate();
        String key = reminder.key();
        var target = reminder.target();
        Message message = Message.create(target.getPlatform(), target.getType(), target.getNum(),
                MessagePlaceholders.escapeExternalText(reminder.body())).get(0);
        message.setChannel(target); message.setReply(requested);
        if (!(requested ? store.reserveRequested(key, date) : store.reserve(key, date, dueDate))) return;
        message.addOnSuccessCallback(() -> store.result(key, date, dueDate, PreLiveReminderStore.Status.SENT));
        message.addOnFailureCallback(() -> store.result(key, date, dueDate, PreLiveReminderStore.Status.FAILED));
        message.addOnUncertainCallback(() -> store.result(key, date, dueDate, PreLiveReminderStore.Status.UNCERTAIN));
        try {
            var block = requested ? sender.sendRequested(message)
                    : reminder.ignoreQuiet() ? sender.sendScheduled(message, sendTime.toLocalTime(), true)
                    : sender.sendScheduled(message, sendTime.toLocalTime());
            if (block != null) store.result(key, date, dueDate, PreLiveReminderStore.Status.NOT_SENT);
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
