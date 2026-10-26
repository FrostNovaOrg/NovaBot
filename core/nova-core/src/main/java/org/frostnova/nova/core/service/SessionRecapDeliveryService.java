package org.frostnova.nova.core.service;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.frostnova.nova.core.analytics.SessionRecap;
import org.frostnova.nova.core.analytics.SessionRecapRenderer;
import org.frostnova.nova.core.assistant.AssistantProperties;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.enums.LiveEndReason;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.event.datasource.base.NovaDataSourceChangeEvent;
import org.frostnova.nova.core.handler.SessionRecapPushHandler;
import org.frostnova.nova.core.model.Message;
import org.frostnova.nova.core.model.MessagePlaceholders;
import org.frostnova.nova.core.model.PushTarget;
import org.frostnova.nova.core.sender.NovaMessageSender;
import org.frostnova.nova.core.sender.PushGate;
import org.frostnova.nova.core.service.SessionRecapDeliveryStore.Delivery;
import org.frostnova.nova.core.service.SessionRecapDeliveryStore.Status;
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

/** 封存场次下播半小时后发送，所有待发判断使用当前通道与档案。 */
@Slf4j
@Service
public class SessionRecapDeliveryService {
    private static final String UNREADABLE = "主播档案读不出，等待恢复";
    private final AbstractDataSource source;
    private final LiveSessionArchive archive;
    private final LiveDataService live;
    private final AudienceLedgerService ledger;
    private final SessionRecapService recap;
    private final StreamerProfileStore profiles;
    private final RevenueVisibilityService revenue;
    private final AssistantProperties assistant;
    private final NovaMessageSender sender;
    private final PushGate gate;
    private final SessionRecapDeliveryStore store;
    private final Clock clock;
    private ThreadPoolTaskScheduler scheduler;
    private boolean started;

    @Autowired
    public SessionRecapDeliveryService(AbstractDataSource source, LiveSessionArchive archive, LiveDataService live,
            AudienceLedgerService ledger, SessionRecapService recap, StreamerProfileStore profiles,
            RevenueVisibilityService revenue, AssistantProperties assistant, NovaMessageSender sender,
            PushGate gate, NovaCoreProperties properties) {
        this(source, archive, live, ledger, recap, profiles, revenue, assistant, sender, gate,
                new SessionRecapDeliveryStore(Path.of(properties.getLive().getLiveDataPath()).toAbsolutePath()
                        .resolveSibling("session-recap-deliveries.json")), Clock.systemUTC());
    }
    public SessionRecapDeliveryService(AbstractDataSource source, LiveSessionArchive archive, LiveDataService live,
            AudienceLedgerService ledger, SessionRecapService recap, StreamerProfileStore profiles,
            RevenueVisibilityService revenue, AssistantProperties assistant, NovaMessageSender sender,
            PushGate gate, SessionRecapDeliveryStore store, Clock clock) {
        this.source = source; this.archive = archive; this.live = live; this.ledger = ledger; this.recap = recap;
        this.profiles = profiles; this.revenue = revenue; this.assistant = assistant; this.sender = sender;
        this.gate = gate; this.store = store; this.clock = clock;
    }
    @Order(100) @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        if (scheduler != null) return;
        scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1); scheduler.setThreadNamePrefix("session-recap-"); scheduler.initialize();
        scheduler.scheduleWithFixedDelay(this::safelyCheck, clock.instant(), Duration.ofMinutes(5));
    }
    @PreDestroy public synchronized void stop() { if (scheduler != null) scheduler.shutdown(); }
    @EventListener(NovaDataSourceChangeEvent.class)
    public synchronized void subscriptionsChanged() { if (started) safelyCheck(); }
    private void safelyCheck() {
        try { check(); } catch (IOException | RuntimeException failure) { log.error("下播小结调度失败", failure); }
    }
    private record Subscription(String platform, long uid, PushTarget target) {}
    public static String key(String platform, long uid, PushTarget target) {
        return WeeklyReportDeliveryService.key(platform, uid, target);
    }
    private Map<String, Subscription> active() {
        var result = new LinkedHashMap<String, Subscription>();
        for (var user : source.getAllUsers()) {
            if (Boolean.FALSE.equals(user.getEnabled())) continue;
            for (var target : user.getTargets()) {
                if (Boolean.FALSE.equals(target.getEnabled()) || target.getType() != PushTargetType.FRIEND
                        || !Boolean.TRUE.equals(target.getStreamerSelf())) continue;
                if (target.getMessages().stream().anyMatch(m -> !Boolean.FALSE.equals(m.getEnabled())
                        && SessionRecapPushHandler.class.getName().equals(m.handlerClassName())))
                    result.put(key(user.getPlatform(), user.getUid(), target),
                            new Subscription(user.getPlatform(), user.getUid(), target));
            }
        }
        return result;
    }
    public synchronized void check() throws IOException {
        started = true;
        if (!store.ensureSaved()) return;
        Instant now = clock.instant();
        var targets = active();
        if (!store.observe(targets.keySet(), now)) return;
        var sealed = archive.sealedSessions();
        long cutoff = now.minus(Duration.ofDays(90)).toEpochMilli();
        for (var entry : targets.entrySet()) {
            var sub = entry.getValue();
            for (var session : sealed) {
                if (!session.platform().equals(sub.platform()) || session.uid() != sub.uid()) continue;
                long due = session.endTime() + Duration.ofMinutes(30).toMillis();
                if (due < cutoff) continue;
                if (!store.enroll(entry.getKey(), session.startTime(), due, now)) return;
            }
        }
        for (var record : store.records()) {
            if (!store.ensureSaved()) return;
            if (record.status() != Status.READY && record.status() != Status.QUIET
                    && !(record.status() == Status.NOT_SENT && UNREADABLE.equals(record.reason()))) continue;
            var sub = targets.get(record.subscription());
            if (sub == null) { store.update(record, Status.NOT_SENT, "订阅或主播本人通道已停用或移除", now); continue; }
            var session = sealed.stream().filter(s -> s.platform().equals(sub.platform()) && s.uid() == sub.uid()
                    && s.startTime() == record.startTime()).findFirst();
            // 续播会撤销封存；同一开播时刻再次封存时重建到点时刻。
            if (session.isEmpty() || record.dueAt() != session.get().endTime() + Duration.ofMinutes(30).toMillis()) continue;
            var s = session.get();
            if (s.endTime() < store.subscribedAt(record.subscription())) {
                store.update(record, Status.NOT_SENT, "这一场结束时尚未开启订阅，不补发", now); continue;
            }
            boolean newer = sealed.stream().anyMatch(n -> n.platform().equals(sub.platform()) && n.uid() == sub.uid()
                    && n.startTime() > record.startTime());
            if (live.getLiveStatus(sub.platform(), sub.uid()).orElse(false) || newer
                    || now.isAfter(Instant.ofEpochMilli(record.dueAt()).plus(Duration.ofHours(24)))) {
                store.update(record, Status.EXPIRED, newer ? "已有更新的封存场次" :
                        live.getLiveStatus(sub.platform(), sub.uid()).orElse(false) ? "主播已经再次开播" : "超过到点时刻 24 小时", now);
                continue;
            }
            if (now.toEpochMilli() < record.dueAt()) continue;
            if (s.durationSeconds() < 600 || s.endReason() == LiveEndReason.UNCLOSED) {
                store.update(record, Status.NOT_SENT, s.durationSeconds() < 600 ? "直播时长不到 10 分钟" : "场次未正常关闭（UNCLOSED）", now); continue;
            }
            var profile = profiles.read(sub.platform(), sub.uid());
            if (profile.status() == StreamerProfile.Status.TEMPORARILY_UNREADABLE) continue;
            if (profile.status() == StreamerProfile.Status.UNREADABLE) {
                store.update(record, Status.NOT_SENT, UNREADABLE, now); continue;
            }
            if (profile.paused()) { store.update(record, Status.NOT_SENT, "主播已暂停小结", now); continue; }
            if (gate.blockedBy() == PushGate.Block.DISABLED) {
                store.update(record, Status.NOT_SENT, "全局推送开关已关闭", now); continue;
            }
            if (!gate.allowedAt(now.atZone(WeeklyReportDeliveryService.ZONE).toLocalTime(), sub.target())) { store.update(record, Status.QUIET, "静音中，结束后发送", now); continue; }
            deliver(record, sub, profile, now);
        }
    }
    private void deliver(Delivery record, Subscription sub, StreamerProfile profile, Instant now) {
        SessionRecapRenderer.Draft draft;
        Message message;
        try {
            draft = draft(sub, profile, record.startTime());
            message = message(sub.target(), draft);
        } catch (IOException | RuntimeException failure) {
            store.update(record, Status.FAILED, "小结生成失败", now); return;
        }
        // 只落规律与数值，不把正文、昵称或异常内容写入发送记录。
        var history = new SessionRecapRenderer.History(record.startTime(), draft.history().patterns());
        if (!store.update(record, Status.SENDING, "", now, history)) return;
        Delivery sending = store.records().stream().filter(r -> r.subscription().equals(record.subscription())
                && r.startTime() == record.startTime()).findFirst().orElseThrow();
        message.addOnSuccessCallback(() -> result(sending, Status.SENT, ""));
        message.addOnFailureCallback(() -> result(sending, Status.FAILED, "发送失败"));
        message.addOnUncertainCallback(() -> result(sending, Status.UNCERTAIN, "发送结果不明"));
        try {
            var blocked = sender.sendWeeklyReport(message);
            if (blocked != null) result(sending, blocked == PushGate.Block.QUIET_HOURS ? Status.QUIET : Status.NOT_SENT,
                    blocked.getDescription());
        } catch (RuntimeException failure) { result(sending, Status.UNCERTAIN, "发送结果不明"); }
    }
    private SessionRecapRenderer.Draft draft(Subscription sub, StreamerProfile profile, long startTime) throws IOException {
        ledger.updateStreamer(sub.platform(), sub.uid());
        var material = recap.calculate(sub.platform(), sub.uid(), startTime);
        var omitted = profile.exclusions().stream().map(e -> e.type().name()).toList();
        var filtered = new SessionRecap.Result(material.facts(), material.patterns().stream()
                .filter(p -> !omitted.contains(p.key())).toList(), material.presentation());
        String streamerPrefix = sub.platform() + ":" + sub.uid() + ":";
        var history = store.records().stream().filter(r -> r.subscription().startsWith(streamerPrefix)
                && (r.status() == Status.SENT || r.status() == Status.UNCERTAIN)).map(Delivery::history).toList();
        var draft = SessionRecapRenderer.render(filtered, new SessionRecapRenderer.Options(profile.tier(),
                revenue.isVisible(sub.target()), SessionRecapRenderer.Nicknames.ORIGINAL,
                new HashSet<>(assistant.getExcludedUids())), history);
        if (draft.message().isBlank()) throw new IllegalStateException("没有小结正文");
        return draft;
    }
    private Message message(PushTarget target, SessionRecapRenderer.Draft draft) {
        Message message = Message.create(target.getPlatform(), target.getType(), target.getNum(),
                MessagePlaceholders.escapeExternalText(draft.message())).get(0);
        message.setChannel(target);
        return message;
    }

    public synchronized String status(String platform, long uid, PushTarget target) {
        if (profiles.read(platform, uid).status() != StreamerProfile.Status.READY) return "档案读不出，已停发";
        if (!store.warning().isEmpty()) return store.warning();
        var last = latest(key(platform, uid, target));
        if (last.isEmpty()) return "还没有场次：下一场下播 30 分钟后发";
        var record = last.get();
        return SessionRecapRenderer.sessionName(record.startTime()) + "：" + switch (record.status()) {
            case READY -> "等发，约 " + java.time.format.DateTimeFormatter.ofPattern("HH:mm")
                    .withZone(WeeklyReportDeliveryService.ZONE).format(Instant.ofEpochMilli(record.dueAt())) + " 发";
            case QUIET -> "静音中，静音结束后发";
            case SENDING -> "正在发";
            case SENT -> "已发";
            case FAILED -> "没发出去（" + record.reason() + "），不会自动重发";
            case UNCERTAIN -> "不确定发出去没有，不会重发";
            case NOT_SENT -> "没发：" + record.reason();
            case EXPIRED -> "作废：" + record.reason();
        };
    }

    public record TrialResult(boolean success, String message, String confirmation) {}

    /** 只读发送历史；主动试发不登记状态，也不把试发过的规律算作此前说过。 */
    public synchronized TrialResult trial(String platform, long uid, PushTarget identity,
                                          boolean confirmPaused, boolean confirmQuiet) {
        var target = source.getAllUsers().stream()
                .filter(user -> platform.equals(user.getPlatform()) && Objects.equals(uid, user.getUid()))
                .map(user -> org.frostnova.nova.core.model.ChannelSettings.target(user, identity.getPlatform(),
                        identity.getType(), identity.getNum())).filter(Objects::nonNull).findFirst().orElse(null);
        if (target == null || target.getType() != PushTargetType.FRIEND || !Boolean.TRUE.equals(target.getStreamerSelf()))
            return new TrialResult(false, "只能试发到这位主播标了「主播本人」的好友通道", "");
        var profile = profiles.read(platform, uid);
        if (profile.status() != StreamerProfile.Status.READY)
            return new TrialResult(false, "档案读不出，已停发，请先去主播页核对恢复", "");
        try {
            var session = archive.sealedSessions().stream()
                    .filter(s -> s.platform().equals(platform) && s.uid() == uid && s.durationSeconds() >= 600
                            && s.endReason() == LiveEndReason.NORMAL)
                    .max(Comparator.comparingLong(LiveSessionArchive.ArchivedSession::startTime));
            if (session.isEmpty()) return new TrialResult(false, "还没有能试发的场次", "");
            if (profile.paused() && !confirmPaused) return new TrialResult(false, "要先确认", "paused");
            if (gate.inQuietHoursFor(target) && !confirmQuiet) return new TrialResult(false, "要先确认", "quiet");
            var draft = draft(new Subscription(platform, uid, target), profile, session.get().startTime());
            var response = sender.sendNow(message(target, draft));
            boolean success = response != null && Integer.valueOf(0).equals(response.getInteger("code"));
            return new TrialResult(success, success ? "最近一场的小结已发送，请到这条通道确认是否收到" : "试发失败，请稍后重试", "");
        } catch (IOException | RuntimeException failure) {
            return new TrialResult(false, "试发失败，请稍后重试", "");
        }
    }
    private synchronized void result(Delivery record, Status status, String reason) {
        store.update(record, status, reason, clock.instant());
    }
    /** 读取这条订阅最近场次的状态和原因。 */
    public Optional<Delivery> latest(String subscription) {
        return store.records().stream().filter(r -> r.subscription().equals(subscription))
                .max(Comparator.comparingLong(Delivery::startTime));
    }
    public String warning() { return store.warning(); }
}
