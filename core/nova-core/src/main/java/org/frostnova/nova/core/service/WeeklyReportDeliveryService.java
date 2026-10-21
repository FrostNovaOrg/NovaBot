package org.frostnova.nova.core.service;

import jakarta.annotation.PreDestroy;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.frostnova.nova.core.analytics.WeeklyReport;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.event.datasource.base.NovaDataSourceChangeEvent;
import org.frostnova.nova.core.handler.WeeklyReportPushHandler;
import org.frostnova.nova.core.model.Message;
import org.frostnova.nova.core.model.MessagePlaceholders;
import org.frostnova.nova.core.model.PushTarget;
import org.frostnova.nova.core.sender.NovaMessageSender;
import org.frostnova.nova.core.sender.PushGate;
import org.frostnova.nova.core.service.WeeklyReportDeliveryStore.Delivery;
import org.frostnova.nova.core.service.WeeklyReportDeliveryStore.Status;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

/** 固定东八区周一十点投递；静音待发每五分钟复查。 */
@Slf4j
@Service
public class WeeklyReportDeliveryService {
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final AbstractDataSource source;
    private final WeeklyReportContentService content;
    private final RevenueVisibilityService revenue;
    private final NovaMessageSender sender;
    private final PushGate gate;
    private final WeeklyReportDeliveryStore store;
    private final Clock clock;
    private ThreadPoolTaskScheduler scheduler;
    private boolean started;
    private boolean startupPending;

    @Autowired
    public WeeklyReportDeliveryService(AbstractDataSource source, WeeklyReportContentService content,
            RevenueVisibilityService revenue, NovaMessageSender sender, PushGate gate, NovaCoreProperties properties) {
        this(source, content, revenue, sender, gate,
                new WeeklyReportDeliveryStore(Path.of(properties.getLive().getLiveDataPath())
                        .toAbsolutePath().resolveSibling("weekly-report-deliveries.json")), Clock.systemUTC());
    }
    public WeeklyReportDeliveryService(AbstractDataSource source, WeeklyReportContentService content,
            RevenueVisibilityService revenue, NovaMessageSender sender, PushGate gate,
            WeeklyReportDeliveryStore store, Clock clock) {
        this.source = source;
        this.content = content;
        this.revenue = revenue;
        this.sender = sender;
        this.gate = gate;
        this.store = store;
        this.clock = clock;
    }

    @Order(100)
    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        if (scheduler != null) return;
        scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("weekly-report-");
        scheduler.initialize();
        scheduler.schedule(() -> safely(() -> check(true)), clock.instant());
        scheduler.schedule(() -> safely(() -> check(false)), new CronTrigger("0 0 10 * * MON", ZONE));
        scheduler.scheduleWithFixedDelay(() -> safely(() -> check(false)),
                clock.instant().plusSeconds(300), Duration.ofMinutes(5));
    }
    private void safely(Runnable work) {
        try { work.run(); }
        catch (RuntimeException e) { log.error("运营周报调度失败", e); }
    }
    @PreDestroy public synchronized void stop() {
        if (scheduler != null) scheduler.shutdown();
    }

    public static Instant latestDue(Instant now) {
        var local = now.atZone(ZONE);
        var due = local.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .atTime(10, 0).atZone(ZONE);
        return (local.isBefore(due) ? due.minusWeeks(1) : due).toInstant();
    }
    public static String key(String platform, Long uid, PushTarget target) {
        return platform + ":" + uid + ":" + target.getPlatform() + ":" + target.getType().name() + ":" + target.getNum();
    }
    private Map<String, PushTarget> active() {
        Map<String, PushTarget> result = new LinkedHashMap<>();
        for (var user : source.getAllUsers()) {
            if (Boolean.FALSE.equals(user.getEnabled())) continue;
            for (var target : user.getTargets()) {
                if (Boolean.FALSE.equals(target.getEnabled())) continue;
                if (target.getMessages().stream().anyMatch(m -> !Boolean.FALSE.equals(m.getEnabled())
                        && WeeklyReportPushHandler.class.getName().equals(m.handlerClassName())))
                    result.put(key(user.getPlatform(), user.getUid(), target), target);
            }
        }
        return result;
    }

    /** startup 只补停机前已经存在的订阅；运行中新增的订阅等下一期。 */
    public synchronized void check(boolean startup) {
        started = true;
        // 保存恢复前保留启动补发规则，不把运行中新订阅的目标算成停机错过。
        startupPending |= startup;
        if (!store.ensureSaved()) return;
        Instant now = clock.instant();
        long due = latestDue(now).toEpochMilli();
        var targets = active();
        if (store.period() < due) {
            List<String> enrolled = new ArrayList<>();
            if (startupPending) {
                if (store.lastSeen() > 0 && store.lastSeen() < due) enrolled.addAll(store.subscriptions());
                else if (now.toEpochMilli() == due) enrolled.addAll(targets.keySet());
            } else if (now.toEpochMilli() == due || (store.lastSeen() > 0 && store.lastSeen() < due)) {
                enrolled.addAll(targets.keySet());
            }
            // 名单和本期标记一起落盘，发送途中停止也不会漏掉名单后半段。
            if (!store.enroll(due, enrolled, now)) return;
        }
        startupPending = false;
        for (var record : store.records()) {
            if (!store.ensureSaved()) return;
            if (record.status() != Status.READY && record.status() != Status.QUIET) continue;
            if (record.dueAt() < due) {
                store.update(record, Status.EXPIRED, "下一期已到，过期未发", now);
                continue;
            }
            var target = targets.get(record.subscription());
            if (gate.blockedBy() == PushGate.Block.DISABLED) {
                store.update(record, Status.NOT_SENT, "全局推送开关已关闭", now);
            } else if (target == null) {
                store.update(record, Status.NOT_SENT, inactiveReason(record.subscription()), now);
            } else if (!gate.allowedFor(target)) {
                if (record.status() != Status.QUIET) store.update(record, Status.QUIET, "静音中", now);
            } else {
                deliver(record, target, now);
            }
        }
        store.observe(new ArrayList<>(targets.keySet()), now);
    }
    @EventListener(NovaDataSourceChangeEvent.class)
    public synchronized void subscriptionsChanged() {
        if (started) safely(() -> check(false));
    }

    private String inactiveReason(String subscription) {
        var parts = subscription.split(":");
        var user = source.getAllUsers().stream().filter(u -> parts[0].equals(u.getPlatform())
                && parts[1].equals(String.valueOf(u.getUid())) && !Boolean.FALSE.equals(u.getEnabled())).findFirst();
        if (user.isEmpty()) return "主播已停用或移除";
        boolean targetExists = user.get().getTargets().stream().anyMatch(t -> key(parts[0], user.get().getUid(), t)
                .equals(subscription) && !Boolean.FALSE.equals(t.getEnabled()));
        return targetExists ? "运营周报订阅已停用" : "推送目标已停用或移除";
    }
    private void deliver(Delivery record, PushTarget target, Instant now) {
        Message message;
        try {
            // 用本期到点的时刻固定统计周，周一六点后顺延也不会跳到下一统计周。
            message = message(record.subscription(), target, Instant.ofEpochMilli(record.dueAt()));
        } catch (RuntimeException e) {
            store.update(record, Status.FAILED, "周报生成失败：" + e.getMessage(), now);
            return;
        }
        if (!store.update(record, Status.SENDING, "", now)) return;
        var sending = store.records().stream().filter(r -> r.subscription().equals(record.subscription())
                && r.dueAt() == record.dueAt()).findFirst().orElseThrow();
        message.addOnSuccessCallback(() -> result(sending, Status.SENT, ""));
        message.addOnFailureCallback(() -> result(sending, Status.FAILED, message.getFailureReason()));
        message.addOnUncertainCallback(() -> result(sending, Status.UNCERTAIN, message.getFailureReason()));
        try {
            var blocked = sender.sendWeeklyReport(message);
            if (blocked != null) result(sending, blocked == PushGate.Block.QUIET_HOURS ? Status.QUIET : Status.NOT_SENT,
                    blocked.getDescription());
        } catch (RuntimeException e) {
            result(sending, Status.UNCERTAIN, "发送结果不明：" + e.getMessage());
        }
    }
    private synchronized void result(Delivery sending, Status status, String reason) {
        store.update(sending, status, reason, clock.instant());
    }
    private Message message(String subscription, PushTarget target, Instant period) {
        var parts = subscription.split(":");
        boolean visible = revenue.isVisible(target);
        var report = content.preview(parts[0], Long.valueOf(parts[1]), period, visible)
                .orElseThrow(() -> new IllegalArgumentException("没有这位主播的记录"));
        String text = MessagePlaceholders.escapeExternalText(report.image() == null ? report.text() : report.summary());
        String body = report.image() == null ? text : "{image_base64="
                + Base64.getEncoder().encodeToString(report.image()) + "}\n" + text;
        Message message = Message.create(target.getPlatform(), target.getType(), target.getNum(), body).get(0);
        message.setChannel(target);
        return message;
    }

    private PushTarget savedChannel(String platform, Long uid, PushTarget identity) {
        return source.getAllUsers().stream().filter(user -> platform.equals(user.getPlatform()) && uid.equals(user.getUid()))
                .map(user -> org.frostnova.nova.core.model.ChannelSettings.target(user, identity.getPlatform(), identity.getType(), identity.getNum()))
                .filter(Objects::nonNull).findFirst().orElse(identity);
    }

    public Optional<WeeklyReport.Preview> preview(String platform, Long uid, PushTarget target) {
        return content.preview(platform, uid, clock.instant(),
                revenue.isVisible(savedChannel(platform, uid, target)));
    }
    public JSONObject trial(String platform, Long uid, PushTarget target) {
        PushTarget channel = savedChannel(platform, uid, target);
        return sender.sendNow(message(key(platform, uid, channel), channel, clock.instant()));
    }
    public String status(String subscription) {
        String warning = store.warning();
        if (!warning.isEmpty()) return warning;
        var last = store.records().stream().filter(r -> r.subscription().equals(subscription))
                .max(Comparator.comparingLong(Delivery::dueAt));
        if (last.isEmpty()) return "还没有发过";
        var r = last.get();
        return switch (r.status()) {
            case READY -> "本期等待发送";
            case QUIET -> "本期在静音时段，静音结束后发送";
            case SENDING -> "本期正在发送";
            case SENT -> "上次：" + DateTimeFormatter.ofPattern("M 月 d 日 HH:mm").withZone(ZONE)
                    .format(Instant.ofEpochMilli(r.updatedAt())) + " 已发出";
            case FAILED -> "上次发送失败";
            case UNCERTAIN -> "上次送达情况不明";
            case NOT_SENT -> "上次未发：" + r.reason();
            case EXPIRED -> "上次过期未发";
        };
    }
}
