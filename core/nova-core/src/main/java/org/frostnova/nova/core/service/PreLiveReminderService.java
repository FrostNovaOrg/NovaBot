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
        ledger.withRecoveredLedger(() -> {
            for (var subscription : subscriptions) {
                try { check(subscription, sessions.getOrDefault(subscription.streamer(), List.of()), now, material); }
                catch (IOException | RuntimeException failure) { log.warn("本轮开播前提醒未能生成", failure); }
            }
        });
    }

    private void check(Subscription sub, List<LiveSessionArchive.ArchivedSession> history, ZonedDateTime now,
                       Map<Streamer, SessionRecapService.PreLiveAudience> material) throws IOException {
        var recent = history.stream().sorted(Comparator.comparingLong(LiveSessionArchive.ArchivedSession::startTime)
                .reversed()).limit(10).toList();
        var usual = usualStart(recent);
        if (usual.isEmpty()) return;
        // 午夜后的开播可能在前一天提醒，按开播日期定位上周对应窗口。
        for (int offset = 0; offset <= 1; offset++) {
            var start = now.toLocalDate().plusDays(offset).atTime(usual.get()).atZone(ZONE);
            var due = start.minusHours(1);
            if (now.isBefore(due) || now.isAfter(due.plusMinutes(10))) continue;
            var weekAgo = start.minusWeeks(1).toInstant();
            if (history.stream().noneMatch(s -> s.startTime() >= weekAgo.toEpochMilli()
                    && s.startTime() <= weekAgo.plus(Duration.ofHours(1)).toEpochMilli())) continue;
            var who = sub.streamer();
            String key = WeeklyReportDeliveryService.key(who.platform(), who.uid(), sub.target());
            LocalDate dueDate = due.toLocalDate();
            if (store.contains(key, now.toLocalDate()) || store.contains(key, dueDate)) return;
            var profile = profiles.read(who.platform(), who.uid());
            if (profile.status() != StreamerProfile.Status.READY || profile.paused()
                    || !gate.allowedAt(now.toLocalTime(), sub.target())
                    || live.getLiveStatus(who.platform(), who.uid()).orElse(false)) return;
            var audience = material.get(who);
            if (audience == null) {
                ledger.updateStreamer(who.platform(), who.uid());
                audience = recap.preLiveAudience(who.platform(), who.uid(), recent.get(0).startTime());
                material.put(who, audience);
            }
            String body = render(audience, profile, who.uid(), new HashSet<>(assistant.getExcludedUids()));
            if (body.isBlank()) return;
            var sendTime = clock.instant().atZone(ZONE);
            if (sendTime.isBefore(due) || sendTime.isAfter(due.plusMinutes(10))) return;
            LocalDate date = sendTime.toLocalDate();
            Message message = Message.create(sub.target().getPlatform(), sub.target().getType(), sub.target().getNum(),
                    MessagePlaceholders.escapeExternalText(body)).get(0);
            message.setChannel(sub.target());
            if (!store.reserve(key, date, dueDate)) return;
            message.addOnSuccessCallback(() -> store.result(key, date, dueDate, PreLiveReminderStore.Status.SENT));
            message.addOnFailureCallback(() -> store.result(key, date, dueDate, PreLiveReminderStore.Status.FAILED));
            message.addOnUncertainCallback(() -> store.result(key, date, dueDate, PreLiveReminderStore.Status.UNCERTAIN));
            try {
                if (sender.sendScheduled(message, sendTime.toLocalTime()) != null)
                    store.result(key, date, dueDate, PreLiveReminderStore.Status.NOT_SENT);
            } catch (RuntimeException failure) { store.result(key, date, dueDate, PreLiveReminderStore.Status.UNCERTAIN); }
        }
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
