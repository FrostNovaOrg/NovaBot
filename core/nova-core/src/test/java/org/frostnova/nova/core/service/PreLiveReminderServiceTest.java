package org.frostnova.nova.core.service;

import org.frostnova.nova.core.assistant.AssistantProperties;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.enums.LiveEndReason;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.handler.PreLiveReminderPushHandler;
import org.frostnova.nova.core.model.*;
import org.frostnova.nova.core.sender.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.*;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PreLiveReminderServiceTest {
    @TempDir Path dir;
    static final long HOST = 910000001L;
    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    static final LocalDate TODAY = LocalDate.of(2026, 10, 26);
    static final class MutableClock extends Clock {
        Instant now = TODAY.atTime(19, 0).atZone(ZONE).toInstant();
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
    final MutableClock clock = new MutableClock();
    final AbstractDataSource source = mock(AbstractDataSource.class);
    final LiveSessionArchive archive = mock(LiveSessionArchive.class);
    final LiveDataService live = mock(LiveDataService.class);
    final AudienceLedgerService ledger = mock(AudienceLedgerService.class);
    final SessionRecapService recap = mock(SessionRecapService.class);
    final StreamerProfileStore profiles = mock(StreamerProfileStore.class);
    final NovaMessageSender sender = mock(NovaMessageSender.class);
    final PushGate gate = mock(PushGate.class);
    final AssistantProperties privacy = new AssistantProperties();
    final List<LiveSessionArchive.ArchivedSession> sealed = new ArrayList<>();
    final List<Message> sent = new ArrayList<>();
    PushUser user;
    PushTarget target;
    PreLiveReminderService service;
    SessionRecapService.PreLiveAudience audience = new SessionRecapService.PreLiveAudience(
            List.of(920000001L, 920000002L, 920000003L, 920000004L, 920000005L),
            List.of(920000006L, 920000007L), Map.of(920000001L, "小A", 920000002L, "小B", 920000003L, "小C",
            920000004L, "合成常客丁", 920000005L, "合成常客戊", 920000006L, "小D", 920000007L, "小E"));

    @BeforeEach void setup() throws Exception {
        user = new PushUser(); user.setPlatform("test"); user.setUid(HOST);
        target = new PushTarget(); target.setPlatform("qq-onebot"); target.setNum(930000001L);
        target.setType(PushTargetType.FRIEND); target.setStreamerSelf(true); target.setUser(user);
        var message = new PushMessage(); message.setHandler(PreLiveReminderPushHandler.class.getName());
        target.setMessages(new ArrayList<>(List.of(message))); user.setTargets(new ArrayList<>(List.of(target)));
        when(source.getAllUsers()).thenReturn(List.of(user));
        when(archive.sealedSessions()).thenAnswer(i -> List.copyOf(sealed));
        when(live.getLiveStatus("test", HOST)).thenReturn(Optional.of(false));
        when(profiles.read("test", HOST)).thenReturn(StreamerProfile.empty(StreamerProfile.Status.READY));
        when(gate.allowedAt(any(), eq(target))).thenReturn(true);
        when(gate.isReady()).thenReturn(true);
        when(recap.preLiveAudience(eq("test"), eq(HOST), anyLong())).thenAnswer(i -> audience);
        doAnswer(i -> {
            Message m = i.getArgument(0); sent.add(m);
            assertTrue(Files.readString(file()).contains("SENDING"), "先保存发送意图再发送");
            m.markDelivered("synthetic-reminder").forEach(Runnable::run); return null;
        }).when(sender).sendScheduled(any(), any());
        doAnswer(i -> { ((AudienceLedgerService.LedgerWork) i.getArgument(0)).run(); return null; })
                .when(ledger).withRecoveredLedger(any());
        doAnswer(i -> {
            Message m = i.getArgument(0); sent.add(m);
            m.markDelivered("synthetic-reminder").forEach(Runnable::run); return null;
        }).when(sender).sendScheduled(any(), any(), eq(true));
        doAnswer(i -> {
            Message m = i.getArgument(0); sent.add(m);
            m.markDelivered("synthetic-reminder").forEach(Runnable::run); return null;
        }).when(sender).sendRequested(any());
        regularHistory(LocalTime.of(20, 0)); reopen();
    }
    Path file() { return dir.resolve("pre-live-reminder-deliveries.json"); }
    void reopen() {
        service = new PreLiveReminderService(source, archive, live, ledger, recap, profiles, privacy,
                sender, gate, new PreLiveReminderStore(file()), clock);
    }
    void add(LocalDate day, LocalTime time, long duration) {
        long start = day.atTime(time).atZone(ZONE).toInstant().toEpochMilli();
        sealed.add(new LiveSessionArchive.ArchivedSession("test", HOST, "合成主播", start,
                start + duration * 1000, duration, LiveEndReason.NORMAL));
    }
    void regularHistory(LocalTime start) {
        sealed.clear();
        // 最近十场中六场集中在五十分钟内，包含上周同一天。
        for (int i = 1; i <= 10; i++) {
            var time = i <= 5 || i == 7 ? start.plusMinutes(i == 7 ? 50 : (i - 1) * 10)
                    : start.plusHours(i);
            add(TODAY.minusDays(i), time, 3600);
        }
    }
    void at(LocalDate date, LocalTime time) { clock.now = date.atTime(time).atZone(ZONE).toInstant(); }
    StreamerProfile profile(boolean paused, StreamerProfile.Pattern... omitted) {
        return new StreamerProfile(1, null, List.of(), Arrays.stream(omitted)
                .map(p -> new StreamerProfile.Exclusion(p, 0)).toList(), paused, paused ? 0L : null, null, null, false, StreamerProfile.Status.READY);
    }

    void reminderProfile(Integer lead, String daily, boolean disabled) throws Exception {
        var directory = dir.resolve("profiles"); Files.createDirectories(directory);
        var real = new StreamerProfileStore(directory);
        Files.writeString(real.path("test", HOST), com.alibaba.fastjson2.JSONObject.of(
                "tier", 2, "tierSetAt", null, "concerns", List.of(), "exclusions", List.of(),
                "paused", false, "pauseSetAt", null, "preLiveLeadMinutes", lead,
                "preLiveDailyTime", daily, "preLiveDisabled", disabled).toJSONString(
                        com.alibaba.fastjson2.JSONWriter.Feature.WriteMapNullValue));
        when(profiles.read("test", HOST)).thenReturn(real.read("test", HOST));
    }

    @Test @DisplayName("前30分钟只在新时刻发，避免仍按默认提前一小时")
    void customLeadReplacesDefault() throws Exception {
        reminderProfile(30, null, false);
        service.check(); assertTrue(sent.isEmpty());
        at(TODAY, LocalTime.of(19, 30)); service.check(); assertEquals(1, sent.size());
    }

    @Test @DisplayName("每天定时不受上周缺播、无惯常时间和静音阻拦，避免自己定的时间收不到")
    void dailyTimeIgnoresHistoryScheduleAndQuietHours() throws Exception {
        reminderProfile(null, "19:00", false);
        sealed.clear(); add(TODAY.minusDays(1), LocalTime.of(10, 0), 3600);
        when(gate.allowedAt(any(), eq(target))).thenReturn(false);
        service.check(); assertEquals(1, sent.size());
        verify(sender).sendScheduled(any(), eq(LocalTime.of(19, 0)), eq(true));
    }

    @Test @DisplayName("每天定时正在播也不发，避免直播中打扰")
    void dailyTimeStillBlocksLive() throws Exception {
        reminderProfile(null, "19:00", false);
        when(live.getLiveStatus("test", HOST)).thenReturn(Optional.of(true));
        service.check(); assertTrue(sent.isEmpty());
    }

    @Test @DisplayName("不要了停止定时提醒，避免又收到不要的提醒")
    void disabledReminderStopsSchedule() throws Exception {
        reminderProfile(null, null, true);
        service.check(); assertTrue(sent.isEmpty());
    }

    org.frostnova.nova.core.command.CommandContext requestContext() {
        return new org.frostnova.nova.core.command.CommandContext("qq-onebot", PushTargetType.FRIEND,
                target.getNum(), target.getNum(), "准备开播", List.of(), "准备开播");
    }

    @Test @DisplayName("主动要过后当天定时不再发且重启仍记得，避免同天再打扰")
    void requestedReminderConsumesScheduledDateButCanRepeat() throws Exception {
        reminderProfile(null, "19:00", false);
        at(TODAY, LocalTime.of(18, 0)); service.request(user, requestContext());
        assertEquals(1, sent.size());
        at(TODAY, LocalTime.of(19, 0)); reopen(); service.check(); assertEquals(1, sent.size());
        service.request(user, requestContext()); assertEquals(2, sent.size());
        assertEquals(sent.get(0).getDisplay(), sent.get(1).getDisplay());
    }

    @ParameterizedTest @ValueSource(strings = {"paused", "global", "starting", "late"})
    @DisplayName("每天定时仍受就绪、暂停、全局开关和十分钟有效期约束，避免定时打扰")
    void dailyTimeKeepsOtherGuards(String state) throws Exception {
        reminderProfile(null, "19:00", false);
        switch (state) {
            case "paused" -> {
                var p = profiles.read("test", HOST);
                when(profiles.read("test", HOST)).thenReturn(new StreamerProfile(p.tier(), p.tierSetAt(), p.concerns(),
                        p.exclusions(), true, 0L, null, "19:00", false, p.status()));
            }
            case "global" -> when(gate.blockedBy()).thenReturn(PushGate.Block.DISABLED);
            case "starting" -> when(gate.isReady()).thenReturn(false);
            case "late" -> at(TODAY, LocalTime.of(19, 11));
        }
        service.check(); assertTrue(sent.isEmpty());
    }

    @Test @DisplayName("每天定时发送后一天只发一次，避免五分钟检查重复发")
    void dailyTimeOnlyOnce() throws Exception {
        reminderProfile(null, "19:00", false);
        service.check(); reopen(); service.check(); assertEquals(1, sent.size());
    }

    @Test @DisplayName("深夜定时首次跨午夜检查后连续三天都收到，避免隔天漏提醒")
    void dailyLateReminderOnlyConsumesDueDate() throws Exception {
        reminderProfile(null, "23:58", false);
        for (int day = 0; day < 3; day++) {
            LocalDate dueDate = TODAY.plusDays(day);
            at(dueDate.plusDays(1), LocalTime.of(0, 2));
            reopen(); service.check();
            assertEquals(day + 1, sent.size(), "每天的到期提醒都应发送");
            assertTrue(Files.readString(file()).contains(dueDate.toString()));
            assertFalse(Files.readString(file()).contains(dueDate.plusDays(1).toString()), "不占用发送日的定时名额");
            reopen(); service.check(); assertEquals(day + 1, sent.size());
        }
    }

    @Test @DisplayName("有惯常时间且上周今天播过才提醒，避免主播不知道今天该留意谁")
    void sendsBothSectionsAtDueOnceAcrossRestart() throws Exception {
        at(TODAY, LocalTime.of(18, 59)); service.check(); assertTrue(sent.isEmpty());
        at(TODAY, LocalTime.of(19, 0)); service.check(); service.check(); reopen(); service.check();
        assertEquals(1, sent.size());
        assertEquals("【开播前提醒】\n最近没来的常客：小A、小B、小C 等 5 位\n上一场第一次说话的新观众：小D、小E", sent.get(0).getDisplay());
        assertSame(target, sent.get(0).getChannel());
        verify(gate).allowedAt(LocalTime.of(19, 0), target);
        verify(ledger).updateStreamer("test", HOST);
        var order = inOrder(ledger, recap);
        order.verify(ledger).updateStreamer("test", HOST);
        order.verify(recap).preLiveAudience("test", HOST, sealed.get(0).startTime());
        assertFalse(Files.readString(file()).contains("小A"));
    }

    @Test @DisplayName("没有五场集中在一小时内不提醒，避免没规律还天天打扰")
    void irregularStarts() throws Exception {
        sealed.clear();
        for (int i = 1; i <= 10; i++) add(TODAY.minusDays(i), LocalTime.of(i * 2, 0), 3600);
        service.check(); assertTrue(sent.isEmpty());
    }

    @ParameterizedTest @ValueSource(ints = {4, 5})
    @DisplayName("到点时四场不算惯常、五场才提醒，避免没规律打扰或刚够门槛却漏发")
    void usualStartRequiresFiveSessionsAtDue(int clustered) throws Exception {
        thresholdHistory(clustered);
        at(TODAY, LocalTime.of(19, 0));
        service.check();
        assertEquals(clustered == 5 ? 1 : 0, sent.size());
    }

    @Test @DisplayName("到点也只看最近十场，避免较早的开播习惯触发过时提醒")
    void olderSessionsCannotEstablishUsualStart() throws Exception {
        thresholdHistory(3);
        for (int i = 11; i <= 20; i++) add(TODAY.minusDays(i), LocalTime.of(20, 0), 3600);
        at(TODAY, LocalTime.of(19, 0));
        service.check();
        assertTrue(sent.isEmpty());
    }

    void thresholdHistory(int clustered) {
        sealed.clear();
        // 上周同一天必在候选窗内，其他零散时刻互隔两小时。
        add(TODAY.minusDays(7), LocalTime.of(20, 0), 3600);
        for (int i = 1; i < clustered; i++) add(TODAY.minusDays(i), LocalTime.of(20, i * 10), 3600);
        int scattered = 0;
        for (int i = clustered; i <= 10; i++) {
            if (i != 7) add(TODAY.minusDays(i), LocalTime.of(scattered++ * 2, 0), 3600);
        }
    }

    @Test @DisplayName("只看最近十场满十分钟的本主播场次，避免别人的规律或短播触发提醒")
    void ignoresOldShortAndOtherStreamerSessions() throws Exception {
        sealed.clear();
        for (int i = 1; i <= 10; i++) add(TODAY.minusDays(i), LocalTime.of(i * 2, 0), 600);
        for (int i = 11; i <= 20; i++) add(TODAY.minusDays(i), LocalTime.of(20, 0), 3600);
        for (int i = 1; i <= 10; i++) add(TODAY.minusDays(i), LocalTime.of(20, 0), 599);
        for (int i = 1; i <= 10; i++) {
            long start = TODAY.minusDays(i).atTime(20, 0).atZone(ZONE).toInstant().toEpochMilli();
            sealed.add(new LiveSessionArchive.ArchivedSession("other", HOST, "合成主播", start, start + 3600000, 3600, LiveEndReason.NORMAL));
            sealed.add(new LiveSessionArchive.ArchivedSession("test", HOST + 1, "合成主播", start, start + 3600000, 3600, LiveEndReason.NORMAL));
        }
        service.check(); assertTrue(sent.isEmpty());
    }

    @Test @DisplayName("上周今天只在窗口外开播不提醒，避免休息时段收到提醒")
    void lastWeekOutsideWindow() throws Exception {
        sealed.removeIf(s -> Instant.ofEpochMilli(s.startTime()).atZone(ZONE).toLocalDate().equals(TODAY.minusWeeks(1)));
        add(TODAY.minusWeeks(1), LocalTime.of(21, 1), 3600);
        service.check(); assertTrue(sent.isEmpty());
    }

    @Test @DisplayName("上一场跳过不足十分钟的短播，避免提醒引用错场的新观众")
    void latestQualifyingSession() throws Exception {
        long expected = sealed.get(0).startTime();
        add(TODAY, LocalTime.of(10, 0), 599);
        service.check(); assertEquals(1, sent.size());
        verify(recap).preLiveAudience("test", HOST, expected);
    }

    @Test @DisplayName("归档一时读取失败可在下一轮重试，避免短暂故障漏掉整天提醒")
    void archiveReadRecoversWithinWindow() throws Exception {
        when(archive.sealedSessions()).thenThrow(new java.io.IOException("synthetic read failure"))
                .thenAnswer(i -> List.copyOf(sealed));
        assertThrows(java.io.IOException.class, service::check); assertTrue(sent.isEmpty());
        clock.now = clock.now.plusSeconds(300); service.check(); assertEquals(1, sent.size());
    }

    @Test @DisplayName("上周今天没在窗口里开播不提醒，避免不播的日子也收到")
    void absentLastWeek() throws Exception {
        sealed.removeIf(s -> Instant.ofEpochMilli(s.startTime()).atZone(ZONE).toLocalDate().equals(TODAY.minusWeeks(1)));
        service.check(); assertTrue(sent.isEmpty());
    }

    @Test @DisplayName("午夜后惯常开播在前一天提醒，避免跨午夜日期算错")
    void midnightReminderOnPreviousDate() throws Exception {
        regularHistory(LocalTime.of(0, 20));
        at(TODAY.minusDays(1), LocalTime.of(23, 20)); service.check();
        assertEquals(1, sent.size());
        assertTrue(Files.readString(file()).contains(TODAY.minusDays(1).toString()));
        verify(gate).allowedAt(LocalTime.of(23, 20), target);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    @DisplayName("十分钟有效期跨过午夜仍只提醒一次，避免零点后重复收到")
    void gracePeriodAcrossDateChange(boolean firstSentAfterMidnight) throws Exception {
        regularHistory(LocalTime.of(0, 55));
        if (firstSentAfterMidnight) at(TODAY, LocalTime.MIDNIGHT);
        else at(TODAY.minusDays(1), LocalTime.of(23, 55));
        service.check(); assertEquals(1, sent.size());
        at(TODAY, LocalTime.of(0, 5)); reopen(); service.check(); assertEquals(1, sent.size());
        assertTrue(Files.readString(file()).contains(TODAY.minusDays(1).toString()));
        if (firstSentAfterMidnight) assertTrue(Files.readString(file()).contains(TODAY.toString()));
    }

    @Test @DisplayName("生成正文耗时越过有效期也不发，避免提醒准备太久变成开播后打扰")
    void draftingCannotExtendGracePeriod() throws Exception {
        when(recap.preLiveAudience(eq("test"), eq(HOST), anyLong())).thenAnswer(i -> {
            clock.now = clock.now.plusSeconds(601); return audience;
        });
        service.check(); assertTrue(sent.isEmpty());
    }

    @Test @DisplayName("跨午夜的一小时作为同一组，避免把深夜习惯拆成两天")
    void windowWrapsAtMidnight() throws Exception {
        sealed.clear();
        for (int i = 1; i <= 10; i++) add(TODAY.minusDays(i), i <= 5 || i == 7
                ? LocalTime.of(23, 50).plusMinutes(i == 7 ? 50 : (i - 1) * 10) : LocalTime.of(i, 0), 3600);
        // 上周窗口起于 10/19 23:50，00:40 的场次落在次日。
        add(TODAY.minusWeeks(1).plusDays(1), LocalTime.of(0, 40), 3600);
        at(TODAY, LocalTime.of(22, 50)); service.check(); assertEquals(1, sent.size());
    }

    @Test @DisplayName("多个一小时窗口取覆盖场数最多的一组，避免过早提醒")
    void densestWindowWins() throws Exception {
        sealed.clear();
        for (int i = 1; i <= 10; i++) add(TODAY.minusDays(i), LocalTime.of(19, 0).plusMinutes(i == 1 ? 0 : 50 + i * 5), 3600);
        assertEquals(LocalTime.of(20, 0), PreLiveReminderService.usualStart(sealed).orElseThrow());
    }

    @ParameterizedTest @ValueSource(longs = {0, 300, 600, 601})
    @DisplayName("十分钟内仍可提醒但超过不补，避免开播后才收到开播前提醒")
    void gracePeriod(long seconds) throws Exception {
        clock.now = clock.now.plusSeconds(seconds); service.check();
        assertEquals(seconds <= 600 ? 1 : 0, sent.size());
    }

    @Test @DisplayName("已经在播不提醒，避免播着播着收到开播前提醒")
    void alreadyLive() throws Exception {
        when(live.getLiveStatus("test", HOST)).thenReturn(Optional.of(true));
        service.check(); assertTrue(sent.isEmpty());
        verify(ledger, never()).updateStreamer(anyString(), anyLong());
        verifyNoInteractions(recap);
    }

    @Test @DisplayName("东八区静音时段不发，避免半夜吵醒主播")
    void quietHours() throws Exception {
        when(gate.allowedAt(LocalTime.of(19, 0), target)).thenReturn(false);
        service.check(); assertTrue(sent.isEmpty());
    }

    @Test @DisplayName("全局推送关闭不发，避免暂停推送后仍打扰")
    void globalDisabled() throws Exception {
        when(gate.blockedBy()).thenReturn(PushGate.Block.DISABLED);
        service.check(); assertTrue(sent.isEmpty()); verifyNoInteractions(archive);
    }

    @ParameterizedTest @ValueSource(strings = {"paused", "UNREADABLE", "TEMPORARILY_UNREADABLE"})
    @DisplayName("档案暂停或读不出时不发，避免说过先别发还收到")
    void profileBlocks(String state) throws Exception {
        when(profiles.read("test", HOST)).thenReturn(state.equals("paused") ? profile(true)
                : StreamerProfile.empty(StreamerProfile.Status.valueOf(state)));
        service.check(); assertTrue(sent.isEmpty());
    }

    @Test @DisplayName("档案暂时读不出后五分钟恢复可发，避免一时故障整天漏提醒")
    void temporaryProfileFailureRecovers() throws Exception {
        when(profiles.read("test", HOST)).thenReturn(StreamerProfile.empty(StreamerProfile.Status.TEMPORARILY_UNREADABLE),
                StreamerProfile.empty(StreamerProfile.Status.READY));
        service.check(); assertTrue(sent.isEmpty());
        clock.now = clock.now.plusSeconds(300); service.check(); assertEquals(1, sent.size());
    }

    @ParameterizedTest @ValueSource(strings = {"user", "target", "subscription", "missing", "self", "group"})
    @DisplayName("停用、没订阅、群或非本人通道不发，避免提醒发错人")
    void inactiveChannel(String state) throws Exception {
        switch (state) {
            case "user" -> user.setEnabled(false);
            case "target" -> target.setEnabled(false);
            case "subscription" -> target.getMessages().get(0).setEnabled(false);
            case "missing" -> target.getMessages().clear();
            case "self" -> target.setStreamerSelf(false);
            case "group" -> target.setType(PushTargetType.GROUP);
        }
        service.check(); assertTrue(sent.isEmpty());
    }

    @Test @DisplayName("两段都空不发送，避免收到空提醒")
    void emptyAudience() throws Exception {
        audience = new SessionRecapService.PreLiveAudience(List.of(), List.of(), Map.of());
        service.check(); assertTrue(sent.isEmpty()); assertFalse(Files.exists(file()));
    }

    @ParameterizedTest @ValueSource(strings = {"regulars_absent", "newcomer_speak_return"})
    @DisplayName("别提老观众或新人会省略对应段，避免说了别提还提")
    void exclusions(String pattern) throws Exception {
        when(profiles.read("test", HOST)).thenReturn(profile(false, StreamerProfile.Pattern.valueOf(pattern)));
        service.check(); String body = sent.get(0).getDisplay();
        assertEquals(!pattern.equals("regulars_absent"), body.contains("最近没来的常客"));
        assertEquals(!pattern.equals("newcomer_speak_return"), body.contains("上一场第一次说话的新观众"));
    }

    @Test @DisplayName("两段都被要求别提则不发送，避免用空消息打扰")
    void bothExcluded() throws Exception {
        when(profiles.read("test", HOST)).thenReturn(profile(false, StreamerProfile.Pattern.regulars_absent,
                StreamerProfile.Pattern.newcomer_speak_return));
        service.check(); assertTrue(sent.isEmpty());
    }

    @Test @DisplayName("拒绝名单、主播本人和缺昵称都不点名，避免提到不想被提的人")
    void privacyAndUnknownNames() throws Exception {
        privacy.setExcludedUids(List.of(920000001L));
        audience = new SessionRecapService.PreLiveAudience(List.of(920000001L, HOST, 920000009L),
                List.of(920000006L, 920000007L), Map.of(920000001L, "合成拒绝观众", HOST, "合成主播",
                920000006L, "小D", 920000007L, "小E"));
        service.check(); assertEquals("【开播前提醒】\n上一场第一次说话的新观众：小D、小E", sent.get(0).getDisplay());
    }

    @Test @DisplayName("多条订阅每轮共用一次封存归档，避免逐条重读导致错过提醒")
    void readsArchiveOncePerRound() throws Exception {
        var second = new PushTarget(); second.setPlatform(target.getPlatform()); second.setNum(930000002L);
        second.setType(PushTargetType.FRIEND); second.setStreamerSelf(true); second.setMessages(target.getMessages());
        second.setUser(user); user.getTargets().add(second);
        when(gate.allowedAt(any(), eq(second))).thenReturn(true);
        service.check(); assertEquals(2, sent.size());
        verify(archive).sealedSessions(); verify(ledger).updateStreamer("test", HOST);
    }

    @Test @DisplayName("发送结果不明时重启不重发，避免同一天收到重复提醒")
    void uncertainSendIsNotRetried() throws Exception {
        doThrow(new IllegalStateException("synthetic failure")).when(sender).sendScheduled(any(), any());
        service.check(); reopen(); service.check();
        verify(sender).sendScheduled(any(), any());
        assertTrue(Files.readString(file()).contains("UNCERTAIN"));
    }
}
