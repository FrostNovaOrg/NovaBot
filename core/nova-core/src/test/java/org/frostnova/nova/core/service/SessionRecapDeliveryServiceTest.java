package org.frostnova.nova.core.service;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.analytics.SessionRecap;
import org.frostnova.nova.core.analytics.SessionRecapRenderer.History;
import org.frostnova.nova.core.assistant.AssistantProperties;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.enums.LiveEndReason;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.handler.SessionRecapPushHandler;
import org.frostnova.nova.core.model.*;
import org.frostnova.nova.core.sender.*;
import org.frostnova.nova.core.service.SessionRecapDeliveryStore.Status;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.*;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SessionRecapDeliveryServiceTest {
    @TempDir Path dir;
    static final long START = Instant.parse("2026-10-01T00:00:00Z").toEpochMilli();
    static final long END = START + 3600000, DUE = END + 1800000;
    final MutableClock clock = new MutableClock();
    static class MutableClock extends Clock {
        Instant now = Instant.ofEpochMilli(START);
        public Instant instant() { return now; }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        void at(long time) { now = Instant.ofEpochMilli(time); }
    }
    AbstractDataSource source = mock(AbstractDataSource.class);
    LiveSessionArchive archive = mock(LiveSessionArchive.class);
    LiveDataService live = mock(LiveDataService.class);
    AudienceLedgerService ledger = mock(AudienceLedgerService.class);
    SessionRecapService recap = mock(SessionRecapService.class);
    StreamerProfileStore profiles = mock(StreamerProfileStore.class);
    RevenueVisibilityService revenue = mock(RevenueVisibilityService.class);
    NovaMessageSender sender = mock(NovaMessageSender.class);
    PushGate gate = mock(PushGate.class);
    AssistantProperties assistant = new AssistantProperties();
    PushUser user;
    PushTarget target;
    SessionRecapDeliveryStore store;
    SessionRecapDeliveryService service;
    List<Message> sent = new ArrayList<>();
    List<LiveSessionArchive.ArchivedSession> sealed = new ArrayList<>();
    @BeforeEach void setup() throws Exception {
        user = new PushUser(); user.setPlatform("test"); user.setUid(910000001L);
        target = new PushTarget(); target.setPlatform("qq-onebot"); target.setType(PushTargetType.FRIEND);
        target.setNum(930000001L); target.setStreamerSelf(true); target.setUser(user);
        var message = new PushMessage(); message.setHandler(SessionRecapPushHandler.class.getName());
        target.setMessages(new ArrayList<>(List.of(message))); user.setTargets(new ArrayList<>(List.of(target)));
        when(source.getAllUsers()).thenReturn(List.of(user));
        when(archive.sealedSessions()).thenAnswer(i -> List.copyOf(sealed));
        when(live.getLiveStatus(anyString(), anyLong())).thenReturn(Optional.of(false));
        when(profiles.read(anyString(), anyLong())).thenReturn(StreamerProfile.empty(StreamerProfile.Status.READY));
        when(revenue.isVisible(target)).thenReturn(true);
        when(gate.blockedBy()).thenReturn(PushGate.Block.QUIET_HOURS);
        when(gate.allowedAt(any(LocalTime.class), eq(target))).thenReturn(true);
        when(recap.calculate(anyString(), anyLong(), anyLong())).thenAnswer(i -> material(i.getArgument(2)));
        doAnswer(i -> { Message m = i.getArgument(0); sent.add(m);
            assertTrue(store.records().stream().anyMatch(r -> r.status() == Status.SENDING));
            m.markDelivered("synthetic-message").forEach(Runnable::run); return null;
        }).when(sender).sendWeeklyReport(any());
        reopen(); service.check();
    }
    void reopen() {
        store = new SessionRecapDeliveryStore(dir.resolve("session-recap-deliveries.json"));
        service = new SessionRecapDeliveryService(source, archive, live, ledger, recap, profiles, revenue,
                assistant, sender, gate, store, clock, mock(SessionRecapGreetingService.class));
    }
    String key() { return SessionRecapDeliveryService.key("test", user.getUid(), target); }
    SessionRecapDeliveryStore.Delivery latest() { return service.latest(key()).orElseThrow(); }
    void seal(long start, long end, long duration, LiveEndReason reason) {
        sealed.add(new LiveSessionArchive.ArchivedSession("test", user.getUid(), "测试主播", start, end, duration, reason));
    }
    void due() throws Exception { seal(START, END, 3600, LiveEndReason.NORMAL); clock.at(DUE); service.check(); }
    SessionRecap.Item item(String key, long start, Map<String,Object> values, List<Long> people) {
        return new SessionRecap.Item(key, new SessionRecap.Streamer("test", user.getUid()), start, values,
                new SessionRecap.Sample(12, START), "strong", people, List.of());
    }
    SessionRecap.Result material(long start) {
        return new SessionRecap.Result(List.of(
                item("duration", start, Map.of("start", start, "end", start + 3600000, "durationMinutes", 60), List.of()),
                item("gift_rank", start, Map.of("gift", 123.4), List.of()),
                item("top_gifters", start, Map.of("gift", List.of(100, 23)), List.of(920000001L, 920000002L))),
                List.of(item("slot_compare", start, Map.of("slotA", "上午", "slotB", "晚上", "sessionsA", 6,
                        "sessionsB", 6, "watchedA", 200, "watchedB", 100), List.of())),
                new SessionRecap.Presentation(start, 12, "complete", null,
                        Map.of(920000001L, "测试观众甲", 920000002L, "测试观众乙"), Set.of()));
    }
    StreamerProfile tierTwo(boolean omit) {
        return new StreamerProfile(2, START, List.of(), omit ? List.of(new StreamerProfile.Exclusion(
                StreamerProfile.Pattern.slot_compare, START)) : List.of(), false, null, StreamerProfile.Status.READY);
    }

    @Test @DisplayName("下播满半小时发送一次，避免主播收不到或重复收到小结")
    void sendsOnceAtDueAndAfterRestart() throws Exception {
        seal(START, END, 3600, LiveEndReason.NORMAL); clock.at(DUE - 1); service.check();
        assertTrue(sent.isEmpty()); assertEquals(Status.READY, latest().status());
        clock.at(DUE); service.check(); service.check(); reopen(); service.check();
        assertEquals(1, sent.size()); assertEquals(Status.SENT, latest().status());
        assertSame(target, sent.get(0).getChannel());
        verify(ledger).updateStreamer("test", user.getUid());
    }
    @Test @DisplayName("停机期间到点仍能补发，避免重启后漏掉小结")
    void catchesUpWithPersistedEnrollment() throws Exception {
        seal(START, END, 3600, LiveEndReason.NORMAL); clock.at(DUE + 300000); reopen(); service.check();
        assertEquals(Status.SENT, latest().status()); assertEquals(1, sent.size());
    }
    @Test @DisplayName("续播撤销封存并更新结束时间，避免发半场或一场两条")
    void resumedSessionUsesFinalEnd() throws Exception {
        var props = new NovaCoreProperties(); props.getLive().setLiveDataPath(dir.resolve("live.json").toString());
        archive = new LiveSessionArchive(props); reopen();
        archive.append(new LiveSession("test", user.getUid(), "测试主播", 1L, START, END, 3600, Map.of(), Map.of()));
        Path detail = Files.createDirectories(dir.resolve("details/test-" + user.getUid() + "-" + START));
        Files.writeString(detail.resolve("detail.json"), "{}");
        clock.at(END + 600000); service.check();
        Files.writeString(detail.resolve("resumed"), ""); clock.at(DUE); service.check(); assertTrue(sent.isEmpty());
        long finalEnd = END + 3600000;
        archive.append(new LiveSession("test", user.getUid(), "测试主播", 1L, START, finalEnd, 7200, Map.of(), Map.of()));
        Files.delete(detail.resolve("resumed")); service.check(); assertEquals(finalEnd + 1800000, latest().dueAt());
        clock.at(finalEnd + 1800000); service.check(); service.check();
        assertEquals(Status.SENT, latest().status()); assertEquals(1, sent.size());
    }
    @Test @DisplayName("短暂下播后续播成完整场仍发送，避免提前把整场判成短场")
    void shortInterruptionCanBecomeFullSession() throws Exception {
        seal(START, START + 300000, 300, LiveEndReason.NORMAL);
        clock.at(START + 400000); service.check();
        sealed.clear(); seal(START, END, 3600, LiveEndReason.NORMAL);
        clock.at(DUE); service.check();
        assertEquals(Status.SENT, latest().status()); assertEquals(1, sent.size());
    }

    @ParameterizedTest @ValueSource(strings = {"short", "unclosed", "paused", "unreadable"})
    @DisplayName("短场、未关闭和暂停或坏档案不发，避免测试开播被打扰或忘记暂停")
    void recordsReasonsForNotSending(String kind) throws Exception {
        if (kind.equals("paused")) when(profiles.read(anyString(), anyLong())).thenReturn(new StreamerProfile(
                1, null, List.of(), List.of(), true, START, StreamerProfile.Status.READY));
        if (kind.equals("unreadable")) when(profiles.read(anyString(), anyLong()))
                .thenReturn(StreamerProfile.empty(StreamerProfile.Status.UNREADABLE));
        seal(START, END, kind.equals("short") ? 599 : 3600,
                kind.equals("unclosed") ? LiveEndReason.UNCLOSED : LiveEndReason.NORMAL);
        clock.at(DUE); service.check();
        assertEquals(Status.NOT_SENT, latest().status()); assertFalse(latest().reason().isBlank()); assertTrue(sent.isEmpty());
        assertTrue(latest().reason().contains(switch(kind) {
            case "short" -> "10 分钟"; case "unclosed" -> "UNCLOSED"; case "paused" -> "暂停"; default -> "档案"; }));
    }
    @ParameterizedTest @ValueSource(strings = {"temporary", "damaged"})
    @DisplayName("档案读错后恢复能继续发，避免一次读错后永久漏发")
    void retriesRecoveredProfile(String kind) throws Exception {
        when(profiles.read(anyString(), anyLong())).thenReturn(StreamerProfile.empty(kind.equals("temporary")
                ? StreamerProfile.Status.TEMPORARILY_UNREADABLE : StreamerProfile.Status.UNREADABLE));
        due(); assertTrue(sent.isEmpty());
        assertEquals(kind.equals("temporary") ? Status.READY : Status.NOT_SENT, latest().status());
        when(profiles.read(anyString(), anyLong())).thenReturn(StreamerProfile.empty(StreamerProfile.Status.READY));
        clock.at(DUE + 300000); service.check(); assertEquals(Status.SENT, latest().status());
    }
    @Test @DisplayName("静音顺延且重启后继续等待，避免半夜吵醒主播")
    void quietDefersUntilAllowed() throws Exception {
        when(gate.allowedAt(any(LocalTime.class), eq(target))).thenReturn(false); due(); assertEquals(Status.QUIET, latest().status());
        reopen(); service.check(); assertTrue(sent.isEmpty());
        when(gate.allowedAt(any(LocalTime.class), eq(target))).thenReturn(true); clock.at(DUE + 300000); service.check();
        assertEquals(1, sent.size()); assertEquals(Status.SENT, latest().status());
    }
    @Test @DisplayName("按注入时钟和通道静音设置顺延，避免服务器时间影响测试或错过静音结束")
    void realQuietGateUsesInjectedTime() throws Exception {
        var props = new NovaCoreProperties(); gate = new PushGate(props);
        var quiet = new PushTarget.QuietHours(); quiet.setMode("custom"); quiet.setStart("09:00"); quiet.setEnd("10:00");
        target.setQuietHours(quiet); reopen(); due();
        assertEquals(Status.QUIET, latest().status()); assertTrue(sent.isEmpty());
        clock.at(START + 7200000); service.check();
        assertEquals(Status.SENT, latest().status()); assertEquals(1, sent.size());
    }
    @Test @DisplayName("全局暂停推送时不入队，避免关闭推送后仍收到小结")
    void disabledGateRecordsReason() throws Exception {
        when(gate.blockedBy()).thenReturn(PushGate.Block.DISABLED); due();
        assertEquals(Status.NOT_SENT, latest().status()); assertTrue(latest().reason().contains("全局"));
        assertTrue(sent.isEmpty());
    }
    @Test @DisplayName("正文不带管理员注，避免将内部解释发给主播")
    void sendsBodyWithoutNotes() throws Exception {
        var result = material(START);
        when(recap.calculate(anyString(), anyLong(), anyLong())).thenReturn(new SessionRecap.Result(
                result.facts(), result.patterns(), new SessionRecap.Presentation(START, 2, "missing", null,
                Map.of(920000001L, "测试观众甲"), Set.of())));
        due(); assertFalse(sent.get(0).getContent().contains("记录还不到"));
        assertFalse(sent.get(0).getContent().contains("观众名单不完整"));
        assertTrue(sent.get(0).getContent().contains("小结"));
    }

    @ParameterizedTest @ValueSource(strings = {"live", "newer", "late"})
    @DisplayName("再次开播、更新场次或超时则作废，避免收到过时小结")
    void expiresPendingSession(String kind) throws Exception {
        when(gate.allowedAt(any(LocalTime.class), eq(target))).thenReturn(false); due();
        if (kind.equals("live")) when(live.getLiveStatus(anyString(), anyLong())).thenReturn(Optional.of(true));
        if (kind.equals("newer")) seal(END + 1, END + 3600000, 3600, LiveEndReason.NORMAL);
        if (kind.equals("late")) clock.at(DUE + 86400001);
        service.check();
        assertEquals(Status.EXPIRED, store.records().get(0).status()); assertTrue(sent.isEmpty());
    }
    @Test @DisplayName("开启订阅前结束的场不补发，避免刚开启就收到旧小结")
    void noBackfillBeforeEnrollment() throws Exception {
        target.getMessages().clear(); service.check();
        var m = new PushMessage(); m.setHandler(SessionRecapPushHandler.class.getName()); target.getMessages().add(m);
        clock.at(DUE); due(); assertEquals(Status.NOT_SENT, latest().status());
        assertTrue(latest().reason().contains("尚未开启")); assertTrue(sent.isEmpty());
    }
    @Test @DisplayName("发送前持久化，遗留发送中改记不确定，避免重启重复发")
    void sendingBecomesUncertainAndCountsAsSpoken() throws Exception {
        when(profiles.read(anyString(), anyLong())).thenReturn(tierTwo(false));
        doAnswer(i -> { sent.add(i.getArgument(0));
            var disk = new SessionRecapDeliveryStore(dir.resolve("session-recap-deliveries.json"));
            assertEquals(Status.UNCERTAIN, disk.records().get(0).status()); return null;
        }).when(sender).sendWeeklyReport(any());
        due(); assertEquals(Status.SENDING, latest().status()); reopen(); service.check();
        assertEquals(Status.UNCERTAIN, latest().status()); assertEquals(1, sent.size());
        nextSession(1); assertFalse(sent.get(1).getContent().contains("中位数"));
    }
    @ParameterizedTest @ValueSource(strings = {"failed", "uncertain", "throw"})
    @DisplayName("失败和结果不明不自动重发，避免主播收到重复私聊")
    void finalFailuresAreNotRetried(String kind) throws Exception {
        doAnswer(i -> { Message m = i.getArgument(0); sent.add(m);
            if (kind.equals("throw")) throw new IllegalStateException("测试观众甲");
            (kind.equals("failed") ? m.markFailed() : m.markUncertain()).forEach(Runnable::run); return null;
        }).when(sender).sendWeeklyReport(any());
        due(); reopen(); service.check();
        assertEquals(kind.equals("failed") ? Status.FAILED : Status.UNCERTAIN, latest().status());
        assertEquals(1, sent.size()); assertFalse(Files.readString(dir.resolve("session-recap-deliveries.json")).contains("测试观众甲"));
    }
    @Test @DisplayName("隐藏金额与拒绝名单作用于正文，避免泄露金额或点名拒绝过的观众")
    void appliesPrivacyToMessage() throws Exception {
        assistant.setExcludedUids(List.of(920000001L)); due();
        assertFalse(sent.get(0).getContent().contains("测试观众甲")); assertTrue(sent.get(0).getContent().contains("测试观众乙"));
        when(revenue.isVisible(target)).thenReturn(false); nextSession(1);
        assertFalse(sent.get(1).getContent().contains("¥")); assertFalse(sent.get(1).getContent().contains("123.4"));
    }
    @Test @DisplayName("别提开播时间则过滤时段规律，避免忽略主播偏好")
    void respectsOmittedPattern() throws Exception {
        when(profiles.read(anyString(), anyLong())).thenReturn(tierTwo(true)); due();
        assertFalse(sent.get(0).getContent().contains("中位数")); assertTrue(latest().history().patterns().isEmpty());
    }
    void nextSession(int day) throws Exception {
        long start = START + day * 86400000L;
        seal(start, start + 3600000, 3600, LiveEndReason.NORMAL); clock.at(start + 5400000); service.check();
    }
    @Test @DisplayName("最近三场说过的规律不重复且记录没有正文或昵称，避免重复念叨与隐私积存")
    void deduplicatesAndStoresOnlyPatterns() throws Exception {
        when(profiles.read(anyString(), anyLong())).thenReturn(tierTwo(false)); due();
        assertTrue(sent.get(0).getContent().contains("中位数"));
        for (int day = 1; day <= 3; day++) { nextSession(day); assertFalse(sent.get(day).getContent().contains("中位数")); }
        nextSession(4); assertTrue(sent.get(4).getContent().contains("中位数"));
        String disk = Files.readString(dir.resolve("session-recap-deliveries.json"));
        assertFalse(disk.contains("测试观众")); assertFalse(disk.contains("【")); assertFalse(disk.contains("中位数"));
        assertTrue(disk.contains("slot_compare")); assertFalse(sent.get(0).getContent().contains("样本还少"));
    }
    @Test @DisplayName("取消本人标记或订阅会阻止待发，避免发给已更换的接收人")
    void changedSubscriptionStopsPending() throws Exception {
        when(gate.allowedAt(any(LocalTime.class), eq(target))).thenReturn(false); due(); target.setStreamerSelf(false);
        when(gate.allowedAt(any(LocalTime.class), eq(target))).thenReturn(true); service.check(); assertEquals(Status.NOT_SENT, latest().status());
        assertTrue(sent.isEmpty());
    }
    @Test @DisplayName("默认不开启且群通道不投递，避免小结误发到群")
    void handlerDefaultsAndRuntimeRestriction() throws Exception {
        assertFalse(new SessionRecapPushHandler().defaultEnabled());
        assertEquals("下播小结（私聊）", new SessionRecapPushHandler().displayName());
        target.setType(PushTargetType.GROUP); due(); assertTrue(store.records().isEmpty()); assertTrue(sent.isEmpty());
    }
    @Test @DisplayName("过闸前又进入静音则保留待发，避免竞态导致漏发")
    void enqueueGateCanDefer() throws Exception {
        doReturn(PushGate.Block.QUIET_HOURS).when(sender).sendWeeklyReport(any()); due();
        assertEquals(Status.QUIET, latest().status());
        doAnswer(i -> { Message m = i.getArgument(0); m.markDelivered("synthetic-message").forEach(Runnable::run); return null; })
                .when(sender).sendWeeklyReport(any()); service.check(); assertEquals(Status.SENT, latest().status());
    }
    String consoleStatus() throws Exception {
        return service.status("test", user.getUid(), target);
    }
    JSONObject trial(boolean paused, boolean quiet) throws Exception {
        return JSONObject.from(service.trial("test", user.getUid(), target, paused, quiet));
    }
    @Test @DisplayName("没有记录时说明下一场何时发，避免管理员误以为订阅失效")
    void consoleExplainsFirstSession() throws Exception {
        assertEquals("还没有场次：下一场下播 30 分钟后发", consoleStatus());
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.EnumSource(Status.class)
    @DisplayName("逐种状态展示场次与原因，避免管理员看不出是否送达或为什么没发")
    void consoleExplainsEveryDelivery(Status status) throws Exception {
        store.enroll(key(), START, DUE, clock.instant());
        store.update(latest(), status, "合成原因", clock.instant());
        String suffix = switch (status) {
            case READY -> "等发，约 09:30 发";
            case QUIET -> "静音中，静音结束后发";
            case SENDING -> "正在发";
            case SENT -> "已发";
            case FAILED -> "没发出去（合成原因），不会自动重发";
            case UNCERTAIN -> "不确定发出去没有，不会重发";
            case NOT_SENT -> "没发：合成原因";
            case EXPIRED -> "作废：合成原因";
        };
        assertEquals("10/01 上午场：" + suffix, consoleStatus());
    }
    @ParameterizedTest @ValueSource(strings = {"UNREADABLE", "TEMPORARILY_UNREADABLE"})
    @DisplayName("坏档案和暂时读错都显示停发并拒绝试发，避免忽略别提或不知道要恢复")
    void consoleStopsOnUnreadableProfile(String status) throws Exception {
        seal(START, END, 3600, LiveEndReason.NORMAL);
        when(profiles.read(anyString(), anyLong())).thenReturn(StreamerProfile.empty(StreamerProfile.Status.valueOf(status)));
        assertEquals("档案读不出，已停发", consoleStatus());
        var result = trial(true, true);
        assertFalse(result.getBooleanValue("success"));
        assertTrue(result.getString("message").contains("主播页核对恢复"));
        verify(sender, never()).sendNow(any());
    }
    @Test @DisplayName("试发复用真发正文且记录和去重不变，避免之后漏发或重复讲过的规律")
    void trialMatchesDeliveryWithoutChangingHistory() throws Exception {
        when(profiles.read(anyString(), anyLong())).thenReturn(tierTwo(false));
        assistant.setExcludedUids(List.of(920000001L));
        seal(START, END, 3600, LiveEndReason.NORMAL); clock.at(END); service.check();
        var before = store.records(); String disk = Files.readString(dir.resolve("session-recap-deliveries.json"));
        var trials = new ArrayList<Message>();
        when(sender.sendNow(any())).thenAnswer(i -> { trials.add(i.getArgument(0)); return JSONObject.of("code", 0); });
        assertTrue(trial(false, false).getBooleanValue("success"));
        assertEquals(1, trials.size()); assertSame(target, trials.get(0).getChannel());
        assertTrue(trials.get(0).getContent().contains("中位数"));
        assertFalse(trials.get(0).getContent().contains("测试观众甲"));
        assertEquals(before, store.records()); assertEquals(disk, Files.readString(dir.resolve("session-recap-deliveries.json")));
        clock.at(DUE); service.check();
        assertEquals(sent.get(0).getContent(), trials.get(0).getContent());
        assertEquals(Status.SENT, latest().status());
        before = store.records(); assertTrue(trial(false, false).getBooleanValue("success"));
        assertEquals(before, store.records());
        nextSession(1); assertFalse(sent.get(1).getContent().contains("中位数"));
    }
    @Test @DisplayName("试发遵守隐藏金额、档位和别提，避免泄露金额或违背主播偏好")
    void trialUsesSavedPrivacy() throws Exception {
        seal(START, END, 3600, LiveEndReason.NORMAL);
        when(profiles.read(anyString(), anyLong())).thenReturn(tierTwo(true));
        when(revenue.isVisible(target)).thenReturn(false);
        var outgoing = org.mockito.ArgumentCaptor.forClass(Message.class);
        when(sender.sendNow(outgoing.capture())).thenReturn(JSONObject.of("code", 0));
        assertTrue(trial(false, false).getBooleanValue("success"));
        assertFalse(outgoing.getValue().getContent().contains("中位数"));
        assertFalse(outgoing.getValue().getContent().contains("¥"));
        when(profiles.read(anyString(), anyLong())).thenReturn(StreamerProfile.empty(StreamerProfile.Status.READY));
        assertTrue(trial(false, false).getBooleanValue("success"));
        assertFalse(outgoing.getValue().getContent().contains("中位数"));
    }
    @Test @DisplayName("暂停和静音分别确认后才试发，避免误点后打扰暂停中的主播或半夜吵醒她")
    void trialRequiresEachConfirmation() throws Exception {
        seal(START, END, 3600, LiveEndReason.NORMAL);
        when(profiles.read(anyString(), anyLong())).thenReturn(new StreamerProfile(
                1, null, List.of(), List.of(), true, START, StreamerProfile.Status.READY));
        when(gate.inQuietHoursFor(target)).thenReturn(true);
        assertEquals("要先确认", trial(false, false).getString("message"));
        assertEquals("paused", trial(false, false).getString("confirmation"));
        assertEquals("quiet", trial(true, false).getString("confirmation"));
        verify(sender, never()).sendNow(any());
        when(sender.sendNow(any())).thenReturn(JSONObject.of("code", 0));
        assertTrue(trial(true, true).getBooleanValue("success"));
        verify(sender, times(1)).sendNow(any());
    }
    @Test @DisplayName("静音中的未暂停档案也需确认，避免半夜直接调用接口吵醒主播")
    void quietAloneRequiresConfirmation() throws Exception {
        seal(START, END, 3600, LiveEndReason.NORMAL); when(gate.inQuietHoursFor(target)).thenReturn(true);
        assertEquals("要先确认", trial(false, false).getString("message"));
        verify(sender, never()).sendNow(any());
    }
    @ParameterizedTest @ValueSource(strings = {"group", "unmarked", "unknown", "other-streamer"})
    @DisplayName("仅已保存的本人好友通道可试发，避免私密小结误发群或其他人")
    void trialRejectsOtherDestinations(String kind) throws Exception {
        seal(START, END, 3600, LiveEndReason.NORMAL);
        if (kind.equals("group")) target.setType(PushTargetType.GROUP);
        if (kind.equals("unmarked")) target.setStreamerSelf(false);
        if (kind.equals("unknown")) user.setTargets(List.of());
        if (kind.equals("other-streamer")) user.setUid(user.getUid() + 1);
        var identity = new PushTarget(); identity.setPlatform(target.getPlatform()); identity.setType(target.getType());
        identity.setNum(target.getNum()); identity.setStreamerSelf(true);
        // 请求里即使伪造本人标记，也必须读取已保存的那一份。
        var result = JSONObject.from(service.trial("test", 910000001L, identity, true, true));
        assertFalse(result.getBooleanValue("success")); verify(sender, never()).sendNow(any());
    }
    @Test @DisplayName("跳过短场和非正常结束取最近可发场次，没有时明说，避免试发不完整小结")
    void trialSelectsLatestEligibleSealedSession() throws Exception {
        assertEquals("还没有能试发的场次", trial(false, false).getString("message"));
        seal(START - 86400000, END - 86400000, 3600, LiveEndReason.NORMAL);
        seal(START, END, 600, LiveEndReason.NORMAL);
        sealed.add(new LiveSessionArchive.ArchivedSession("test", user.getUid() + 1, "另一位测试主播",
                START + 5, END + 5, 3600, LiveEndReason.NORMAL));
        seal(START + 1, END + 1, 599, LiveEndReason.NORMAL);
        seal(START + 2, END + 2, 3600, LiveEndReason.UNCLOSED);
        seal(START + 3, END + 3, 3600, LiveEndReason.CUT_OFF);
        seal(START + 4, END + 4, 3600, LiveEndReason.ROOM_LOCK);
        when(sender.sendNow(any())).thenReturn(JSONObject.of("code", 0));
        assertTrue(trial(false, false).getBooleanValue("success"));
        verify(recap).calculate("test", user.getUid(), START);
    }

    @ParameterizedTest @ValueSource(strings = {"generation", "rejected", "missing", "exception"})
    @DisplayName("试发生成或发送失败如实提示且记录不变，避免误报送达或打乱真发")
    void failedTrialLeavesRecordsUnchanged(String kind) throws Exception {
        seal(START, END, 3600, LiveEndReason.NORMAL);
        var before = store.records(); String disk = Files.readString(dir.resolve("session-recap-deliveries.json"));
        if (kind.equals("generation")) when(recap.calculate(anyString(), anyLong(), anyLong())).thenThrow(new java.io.IOException("合成错误"));
        if (kind.equals("rejected")) when(sender.sendNow(any())).thenReturn(JSONObject.of("code", 1));
        if (kind.equals("exception")) when(sender.sendNow(any())).thenThrow(new IllegalStateException("合成错误"));
        var result = trial(false, false);
        assertFalse(result.getBooleanValue("success")); assertEquals("试发失败，请稍后重试", result.getString("message"));
        assertEquals(before, store.records()); assertEquals(disk, Files.readString(dir.resolve("session-recap-deliveries.json")));
    }

}
