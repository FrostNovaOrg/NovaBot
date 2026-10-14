package org.frostnova.nova.core.service;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.analytics.LiveMetricCatalog;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.enums.LiveEndReason;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.handler.WeeklyReportPushHandler;
import org.frostnova.nova.core.model.*;
import org.frostnova.nova.core.sender.NovaMessageSender;
import org.frostnova.nova.core.sender.PushGate;
import org.frostnova.nova.core.service.WeeklyReportDeliveryStore.Status;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.scheduling.support.SimpleTriggerContext;
import org.springframework.context.ApplicationEventPublisher;
import org.frostnova.nova.core.datasource.DataSourceServiceRegistry;
import org.frostnova.nova.core.datasource.DataSourceService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("运营周报订阅投递")
class WeeklyReportDeliveryServiceTest {
    @TempDir Path dir;
    final MutableClock clock = new MutableClock();
    AbstractDataSource source;
    NovaMessageSender sender;
    PushGate gate;
    NovaCoreProperties props;
    WeeklyReportContentService content;
    RevenueVisibilityService revenue;
    WeeklyReportDeliveryStore store;
    WeeklyReportDeliveryService service;
    List<PushUser> users;
    List<Message> sent;
    boolean quiet;

    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-12T02:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void at(String value) { now = ZonedDateTime.parse(value + "+08:00[Asia/Shanghai]").toInstant(); }
    }
    @BeforeEach @SuppressWarnings("unchecked") void setup() {
        props = new NovaCoreProperties();
        props.getLive().setLiveDataPath(dir.resolve("live.json").toString());
        props.getDatasource().setJsonPath(dir.resolve("datasource.json").toString());
        source = mock(AbstractDataSource.class);
        users = new ArrayList<>(List.of(user(1L, 1001L, true)));
        when(source.getAllUsers()).thenAnswer(i -> List.copyOf(users));
        sender = mock(NovaMessageSender.class);
        sent = new ArrayList<>();
        doAnswer(i -> {
            Message message = i.getArgument(0); sent.add(message);
            message.markDelivered("sample-message").forEach(Runnable::run); return null;
        }).when(sender).sendWeeklyReport(any());
        when(sender.sendNow(any())).thenReturn(new JSONObject().fluentPut("code", 0));
        gate = mock(PushGate.class);
        when(gate.blockedBy()).thenAnswer(i -> props.getPush().isEnabled() ? PushGate.Block.QUIET_HOURS : PushGate.Block.DISABLED);
        when(gate.allowedFor(anyString(), any(), anyLong())).thenAnswer(i -> props.getPush().isEnabled() && !quiet);
        ObjectProvider<LiveMetricCatalog> catalogs = mock(ObjectProvider.class);
        LiveMetricCatalog catalog = mock(LiveMetricCatalog.class);
        when(catalog.platform()).thenReturn("sample");
        when(catalog.insights()).thenReturn(new LiveMetricCatalog.Insights(List.of("gift"), "messages", List.of("chat"), List.of("chat"), "fans"));
        when(catalogs.orderedStream()).thenAnswer(i -> Stream.of(catalog));
        ObjectProvider<WeeklyReportRenderer> renderers = mock(ObjectProvider.class);
        when(renderers.orderedStream()).thenAnswer(i -> Stream.empty());
        var archive = new LiveSessionArchive(props);
        Instant from = Instant.parse("2026-10-05T00:00:00Z");
        archive.append(new LiveSession("sample", 1L, "主播示例", 2L, from.toEpochMilli(), from.plusSeconds(600).toEpochMilli(),
                600, Map.of("gift", 1234.5, "messages", 8901d), Map.of(), LiveEndReason.NORMAL,
                List.of(), 0, Map.of("chat", List.of()), 0, Map.of(), Map.of()));
        content = new WeeklyReportContentService(source, new StreamerDirectory(props), archive,
                new StreamerSnapshotArchive(props), catalogs, renderers);
        revenue = new RevenueVisibilityService(mock(NovaStateStore.class));
        reopen();
    }
    void reopen() {
        store = new WeeklyReportDeliveryStore(dir.resolve("weekly-report-deliveries.json"));
        service = new WeeklyReportDeliveryService(source, content, revenue, sender, gate, store, clock);
    }
    static PushUser user(Long uid, Long num, boolean subscribed) {
        var user = new PushUser(); user.setPlatform("sample"); user.setUid(uid); user.setEnabled(true);
        var target = new PushTarget(); target.setPlatform("qq-onebot"); target.setType(PushTargetType.GROUP);
        target.setNum(num); target.setEnabled(true); target.setUser(user);
        var message = new PushMessage(); message.setHandler(WeeklyReportPushHandler.class.getName());
        message.setEnabled(subscribed); message.setTarget(target);
        target.setMessages(new ArrayList<>(List.of(message))); user.setTargets(new ArrayList<>(List.of(target)));
        return user;
    }
    PushTarget target() { return users.get(0).getTargets().get(0); }
    String key() { return WeeklyReportDeliveryService.key("sample", 1L, target()); }
    Status status() { return store.records().get(0).status(); }

    @Test @DisplayName("周一十点仅向启用的订阅各发一次") void mondayTen() {
        users.add(user(2L, 1002L, true));
        users.add(user(3L, 1003L, false));
        var disabled = user(4L, 1004L, true); disabled.getTargets().get(0).setEnabled(false); users.add(disabled);
        assertFalse(new WeeklyReportPushHandler().defaultEnabled());
        assertEquals("运营周报", new WeeklyReportPushHandler().displayName());
        service.check(false); service.check(false);
        assertEquals(List.of(1001L, 1002L), sent.stream().map(Message::getNum).toList());
        assertTrue(store.records().stream().allMatch(r -> r.status() == Status.SENT));
    }
    @Test @DisplayName("八至十二点静音时周报待发，十二点五分发出") void quietDeferral() throws Exception {
        props.getPush().setQuietStart("08:00"); props.getPush().setQuietEnd("12:00"); quiet = true;
        service.check(false);
        assertEquals(Status.QUIET, status()); verify(sender, never()).sendWeeklyReport(any()); verify(sender, never()).send(any());
        assertTrue(Files.readString(dir.resolve("weekly-report-deliveries.json")).contains("QUIET"));
        clock.at("2026-10-12T12:05:00"); quiet = false; service.check(false);
        assertEquals(Status.SENT, status()); assertEquals(1, sent.size());
    }
    @Test @DisplayName("已发出后重启，同一周不再发") void restartDeduplication() {
        service.check(false); reopen(); clock.at("2026-10-12T10:30:00"); service.check(true);
        assertEquals(1, sent.size()); assertEquals(Status.SENT, status());
    }
    @Test @DisplayName("上周停机，周一十四点启动补发一次") void missedWhileStopped() {
        clock.at("2026-10-11T09:00:00"); service.check(false);
        clock.at("2026-10-12T14:00:00"); reopen(); service.check(true); service.check(false);
        assertEquals(1, sent.size()); assertEquals(Status.SENT, status());
    }
    @Test @DisplayName("周三新订阅不补上周周报") void wednesdaySubscription() {
        users.clear(); service.check(false);
        clock.at("2026-10-14T10:00:00"); users.add(user(1L, 1001L, true)); service.check(false);
        reopen(); service.check(true);
        assertEquals(0, sent.size()); assertTrue(store.records().isEmpty());
        clock.at("2026-10-19T10:00:00"); service.check(false); assertEquals(1, sent.size());
    }
    @Test @DisplayName("发送途中停止，重启记为送达不明且不再发") void interruptedSend() {
        doAnswer(i -> { sent.add(i.getArgument(0)); return null; }).when(sender).sendWeeklyReport(any());
        service.check(false); assertEquals(Status.SENDING, status());
        reopen(); clock.at("2026-10-12T10:30:00"); service.check(true);
        assertEquals(Status.UNCERTAIN, status()); assertEquals(1, sent.size());
    }
    @Test @DisplayName("隐藏金额的群收到的内容没有金额") void hiddenRevenue() {
        service.check(false);
        var body = sent.get(0).getContent();
        assertTrue(body.contains("运营周报"));
        for (String token : List.of("流水", "元", "revenue", "1234.5")) assertFalse(body.contains(token), token);
    }
    @Test @DisplayName("试发后周一十点照常发送") void trialIndependent() {
        clock.at("2026-10-12T09:00:00"); quiet = true;
        service.trial("sample", 1L, target()); verify(sender).sendNow(any());
        assertTrue(store.records().isEmpty());
        quiet = false; clock.at("2026-10-12T10:00:00"); service.check(false);
        assertEquals(1, sent.size()); assertEquals(Status.SENT, status());
    }
    @Test @DisplayName("总开关关闭时不发且写明原因") void globalDisabled() {
        props.getPush().setEnabled(false); service.check(false);
        assertEquals(0, sent.size()); assertEquals(Status.NOT_SENT, status());
        assertTrue(service.status(key()).contains("全局推送开关已关闭"));
        props.getPush().setEnabled(true); service.check(false); assertEquals(0, sent.size());
    }
    @Test @DisplayName("静音待发跨到下一期，上一期过期") void expiresNextMonday() {
        quiet = true; service.check(false);
        clock.at("2026-10-19T09:59:00"); service.check(false); assertEquals(Status.QUIET, status());
        clock.at("2026-10-19T10:00:00"); quiet = false; service.check(false);
        assertEquals(Status.EXPIRED, status()); assertEquals(1, sent.size());
        assertEquals(2, store.records().size()); assertEquals(Status.SENT, store.records().get(1).status());
    }
    @Test @DisplayName("失败与送达不明的回调均落盘且不重发") void terminalCallbacks() {
        doAnswer(i -> {
            Message m = i.getArgument(0); m.setFailureReason("示例失败"); m.markFailed().forEach(Runnable::run); return null;
        }).when(sender).sendWeeklyReport(any());
        service.check(false); assertEquals(Status.FAILED, status()); reopen(); service.check(true);
        verify(sender, times(1)).sendWeeklyReport(any());
        clock.at("2026-10-19T10:00:00");
        doAnswer(i -> {
            Message m = i.getArgument(0); m.markUncertain().forEach(Runnable::run); return null;
        }).when(sender).sendWeeklyReport(any());
        service.check(false); assertEquals(Status.UNCERTAIN, store.records().get(1).status());
        reopen(); service.check(true); verify(sender, times(2)).sendWeeklyReport(any());
    }
    @Test @DisplayName("待发期间停用目标或订阅不顺延") void canceledSubscription() {
        quiet = true; service.check(false); target().getMessages().get(0).setEnabled(false); service.check(false);
        assertEquals(Status.NOT_SENT, status()); assertTrue(service.status(key()).contains("订阅已停用"));
        quiet = false; service.check(false); assertEquals(0, sent.size());
    }
    @Test @DisplayName("初次安装在周三启动不当作停机补发") void firstStartup() {
        clock.at("2026-10-14T10:00:00"); service.check(true); service.check(false);
        assertEquals(0, sent.size());
    }
    @Test @DisplayName("东八区到点不随默认时区变化") void explicitTimezone() {
        var original = TimeZone.getDefault();
        try {
            for (String zone : List.of("UTC", "America/Los_Angeles")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));
                clock.at("2026-10-12T10:00:00");
                assertEquals(Instant.parse("2026-10-12T02:00:00Z"), WeeklyReportDeliveryService.latestDue(clock.instant()), zone);
                clock.at("2026-10-12T09:59:59");
                assertEquals(Instant.parse("2026-10-05T02:00:00Z"), WeeklyReportDeliveryService.latestDue(clock.instant()), zone);
            }
        } finally { TimeZone.setDefault(original); }
    }
    @Test @DisplayName("记录满一年清理，只补最近一期") void retentionAndLatestOnly() {
        clock.at("2025-10-06T10:00:00"); service.check(false); assertEquals(1, store.records().size());
        clock.at("2026-10-12T14:00:00"); reopen(); service.check(true);
        assertEquals(1, store.records().size()); assertEquals(2, sent.size());
        assertEquals(Instant.parse("2026-10-12T02:00:00Z").toEpochMilli(), store.records().get(0).dueAt());
    }
    @Test @DisplayName("发送入队前遇静音仍顺延") void gateChangesBeforeEnqueue() {
        doReturn(PushGate.Block.QUIET_HOURS).when(sender).sendWeeklyReport(any());
        service.check(false); assertEquals(Status.QUIET, status());
        reopen(); assertEquals(Status.QUIET, status());
    }
    @Test @DisplayName("启动即检查并安装东八区周一十点与五分钟复查")
    void schedulerInstalled() {
        var original = TimeZone.getDefault();
        try {
            for (String zone : List.of("UTC", "America/Los_Angeles")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));
                clock.at("2026-10-12T10:00:00");
                try (var construction = mockConstruction(ThreadPoolTaskScheduler.class)) {
                    var scheduled = new WeeklyReportDeliveryService(source, content, revenue, sender, gate, store, clock);
                    try {
                        scheduled.start();
                        assertEquals(1, construction.constructed().size(), "必须安装调度器");
                        var scheduler = construction.constructed().get(0);
                        var initial = org.mockito.ArgumentCaptor.forClass(Runnable.class);
                        verify(scheduler).schedule(initial.capture(), eq(clock.instant()));
                        initial.getValue().run();
                        assertEquals(clock.instant().toEpochMilli(), store.lastSeen(), "启动检查");
                        var trigger = org.mockito.ArgumentCaptor.forClass(CronTrigger.class);
                        verify(scheduler).schedule(any(Runnable.class), trigger.capture());
                        var context = new SimpleTriggerContext(Clock.fixed(Instant.parse("2026-10-12T01:59:00Z"), ZoneOffset.UTC));
                        assertEquals(Instant.parse("2026-10-12T02:00:00Z"), trigger.getValue().nextExecution(context), zone);
                        verify(scheduler).scheduleWithFixedDelay(any(Runnable.class), eq(clock.instant().plusSeconds(300)), eq(Duration.ofMinutes(5)));
                    } finally { scheduled.stop(); }
                }
            }
        } finally { TimeZone.setDefault(original); }
    }

    @ParameterizedTest(name = "损坏记录：{0}")
    @ValueSource(strings = {"this is not json", "", "{\"lastSeen\":1}",
            "{\"lastSeen\":1,\"period\":1,\"subscriptions\":[],\"records\":{}}",
            "{\"lastSeen\":1,\"period\":1,\"subscriptions\":[],\"records\":[{\"subscription\":\"sample:1:qq-onebot:GROUP:1001\",\"dueAt\":1,\"status\":\"INVALID\",\"reason\":\"\",\"updatedAt\":1}]}"})
    @DisplayName("坏记录不阻止启动，原文件留底且不补发本期")
    void corruptRecords(String raw) throws Exception {
        Path file = dir.resolve("weekly-report-deliveries.json");
        Files.writeString(file, raw);
        assertDoesNotThrow(this::reopen);
        assertTrue(service.status(key()).contains("周报记录文件损坏"));
        try (var files = Files.list(dir)) {
            var backup = files.filter(p -> p.getFileName().toString().startsWith("weekly-report-deliveries.json.corrupt-")).findFirst().orElseThrow();
            assertEquals(raw, Files.readString(backup));
        }
        clock.at("2026-10-12T14:00:00"); service.check(true);
        assertTrue(store.records().isEmpty()); assertEquals(0, sent.size());
        clock.at("2026-10-19T10:00:00"); service.check(false);
        assertEquals(1, sent.size()); assertTrue(service.status(key()).contains("已发出"));
    }

    void blockWrites() throws Exception {
        Path file = dir.resolve("weekly-report-deliveries.json");
        Files.deleteIfExists(file); Files.createDirectory(file);
        Files.writeString(file.resolve("keep.txt"), "sample");
    }
    void restoreWrites() throws Exception {
        Path file = dir.resolve("weekly-report-deliveries.json");
        Files.delete(file.resolve("keep.txt")); Files.delete(file);
    }
    @Test @DisplayName("记录无法保存时不发，恢复保存后正常发送")
    void writeFailurePausesSending() throws Exception {
        blockWrites();
        assertDoesNotThrow(() -> service.check(false));
        assertEquals(0, sent.size());
        assertTrue(service.status(key()).contains("周报记录无法保存，自动发送已暂停："));
        restoreWrites(); service.check(false);
        assertEquals(1, sent.size()); assertEquals(Status.SENT, status());
        assertTrue(service.status(key()).contains("已发出")); reopen(); assertEquals(Status.SENT, status());
    }
    @Test @DisplayName("发送中状态保存失败时不入队，恢复后只发一次")
    void sendingIntentWriteFailure() throws Exception {
        try (var files = mockStatic(org.frostnova.nova.core.util.DurableFiles.class, CALLS_REAL_METHODS)) {
            var writes = new java.util.concurrent.atomic.AtomicInteger();
            files.when(() -> org.frostnova.nova.core.util.DurableFiles.replace(any(Path.class), anyString(), any()))
                    .thenAnswer(call -> {
                        if (writes.incrementAndGet() >= 2) throw new java.io.IOException("sample disk full");
                        return call.callRealMethod();
                    });
            assertDoesNotThrow(() -> service.check(false));
            verify(sender, never()).sendWeeklyReport(any());
            assertEquals(Status.READY, status());
            assertTrue(service.status(key()).contains("自动发送已暂停"));
        }
        service.check(false); reopen(); service.check(true);
        assertEquals(Status.SENT, status()); assertEquals(1, sent.size());
    }
    @Test @DisplayName("回调保存失败不向消息发送者抛异常，也不重新发送")
    void callbackWriteFailure() throws Exception {
        doAnswer(i -> { sent.add(i.getArgument(0)); return null; }).when(sender).sendWeeklyReport(any());
        service.check(false); blockWrites();
        assertDoesNotThrow(() -> sent.get(0).markDelivered("sample-message").forEach(Runnable::run));
        assertTrue(service.status(key()).contains("自动发送已暂停"));
        restoreWrites(); service.check(false); reopen(); service.check(true);
        assertEquals(Status.SENT, status()); assertEquals(1, sent.size());
    }
    @Test @DisplayName("发送中记录恢复时无法保存也不阻止启动，不再重发")
    void recoveryWriteFailure() throws Exception {
        doAnswer(i -> { sent.add(i.getArgument(0)); return null; }).when(sender).sendWeeklyReport(any());
        service.check(false);
        try (var files = mockStatic(org.frostnova.nova.core.util.DurableFiles.class)) {
            files.when(() -> org.frostnova.nova.core.util.DurableFiles.replace(any(Path.class), anyString(), any()))
                    .thenThrow(new java.io.IOException("sample disk full"));
            assertDoesNotThrow(this::reopen);
            assertEquals(Status.UNCERTAIN, status());
            assertTrue(service.status(key()).contains("自动发送已暂停"));
            assertDoesNotThrow(() -> service.check(true)); assertEquals(1, sent.size());
        }
        service.check(false); reopen(); assertEquals(Status.UNCERTAIN, status());
    }
    @Test @DisplayName("周报保存失败不打断同次配置重载的后续主播与监听器")
    void reloadContinuesAfterWriteFailure() throws Exception {
        var registry = mock(DataSourceServiceRegistry.class);
        when(registry.getDataSourceService("sample")).thenReturn(Optional.of(mock(DataSourceService.class)));
        var listener = new ArrayList<Object>();
        var callback = new java.util.concurrent.atomic.AtomicReference<Runnable>(() -> {});
        ApplicationEventPublisher publisher = event -> { callback.get().run(); listener.add(event); };
        source = new AbstractDataSource(publisher, registry, null) { public void load() {} };
        source.add(List.of(user(1L, 1001L, true), user(2L, 1002L, true)));
        service = new WeeklyReportDeliveryService(source, content, revenue, sender, gate, store, clock);
        quiet = true; service.check(false); blockWrites(); listener.clear();
        callback.set(service::subscriptionsChanged);
        assertDoesNotThrow(() -> source.update(List.of(user(1L, 2001L, true), user(2L, 2002L, true))));
        assertEquals(List.of(2001L, 2002L), source.getAllUsers().stream().map(u -> u.getTargets().get(0).getNum()).toList());
        assertEquals(2, listener.size(), "后续监听器仍收到两位主播的更新");
        assertTrue(service.status(key()).contains("自动发送已暂停"));
    }

}
