package org.frostnova.nova.core.service;

import org.frostnova.nova.core.assistant.AssistantProperties;
import org.frostnova.nova.core.analytics.SessionRecap;
import org.frostnova.nova.core.command.*;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.enums.LiveEndReason;
import org.frostnova.nova.core.event.remote.NovaRemoteMessageEvent;
import org.frostnova.nova.core.handler.SessionRecapPushHandler;
import org.frostnova.nova.core.model.*;
import org.frostnova.nova.core.sender.*;
import org.frostnova.nova.core.util.DurableFiles;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SessionRecapGreetingTest {
    @TempDir Path dir;
    private final MutableClock clock = new MutableClock();
    private final AbstractDataSource source = mock(AbstractDataSource.class);
    private final LiveDataService live = mock(LiveDataService.class);
    private final LiveSessionArchive archive = mock(LiveSessionArchive.class);
    private final NovaMessageSender sender = mock(NovaMessageSender.class);
    private final SessionRecapService recap = mock(SessionRecapService.class);
    private final List<Message> sent = new ArrayList<>();
    private final List<String> replies = new ArrayList<>();
    private final List<String> events = new ArrayList<>();
    private StreamerProfileStore profiles;
    private PushUser user;
    private PushTarget target;
    private PushGate gate;
    private SessionRecapDeliveryService delivery;
    private SessionRecapGreetingService greetings;
    private AnnotationConfigApplicationContext beans;
    private CommandDispatcher dispatcher;
    private NovaCoreProperties properties;

    @BeforeEach void setup() throws Exception {
        profiles = new StreamerProfileStore(dir.resolve("profiles"));
        user = new PushUser(); user.setPlatform("test"); user.setUid(990000000101L);
        target = new PushTarget(); target.setPlatform("qq-onebot"); target.setType(PushTargetType.FRIEND);
        target.setNum(990000001001L); target.setStreamerSelf(true); target.setUser(user);
        var subscription = new PushMessage(); subscription.setHandler(SessionRecapPushHandler.class.getName());
        subscription.setEnabled(false);
        target.setMessages(new ArrayList<>(List.of(subscription))); user.setTargets(List.of(target));
        when(source.getAllUsers()).thenReturn(List.of(user));
        when(live.getLiveStatus("test", user.getUid())).thenReturn(Optional.of(false));
        when(archive.sealedSessions()).thenReturn(List.of());
        properties = new NovaCoreProperties();
        properties.getLive().setLiveDataPath(dir.resolve("live.json").toString());
        properties.getPush().setQuietStart(""); properties.getPush().setQuietEnd("");
        gate = new PushGate(properties);
        doAnswer(call -> {
            Message message = call.getArgument(0); sent.add(message);
            message.markDelivered("synthetic-greeting").forEach(Runnable::run); return null;
        }).when(sender).sendWeeklyReport(any());
        doAnswer(call -> { replies.add(((Message) call.getArgument(0)).getContent()); return null; }).when(sender).send(any());
        reopen();
    }

    private void reopen() {
        if (beans != null) beans.close();
        greetings = new SessionRecapGreetingService(new SessionRecapGreetingStore(dir.resolve("session-recap-greetings.json")),
                profiles, new StreamerSelfResolver(source), live, sender, gate, clock);
        delivery = new SessionRecapDeliveryService(source, archive, live, mock(AudienceLedgerService.class),
                recap, profiles, mock(RevenueVisibilityService.class),
                new AssistantProperties(), sender, gate,
                new SessionRecapDeliveryStore(dir.resolve("session-recap-deliveries.json")), clock, greetings);
        beans = new AnnotationConfigApplicationContext();
        beans.registerBean(StreamerProfileStore.class, () -> profiles);
        beans.registerBean(StreamerSelfResolver.class, () -> new StreamerSelfResolver(source));
        beans.registerBean(SessionRecapGreetingService.class, () -> greetings);
        beans.register(StreamerProfileCommands.class);
        beans.registerBean("menu", NovaCommand.class, () -> new NovaCommand() {
            public String name() { return "菜单"; }
            public String description() { return "测试菜单"; }
            public boolean groupOnly() { return false; }
            public CommandReply execute(CommandContext context) { return CommandReply.of("原有处理"); }
        });
        beans.refresh();
        org.springframework.test.util.ReflectionTestUtils.setField(beans.getBean(StreamerProfileCommands.class), "clock", clock);
        dispatcher = new CommandDispatcher(beans.getBeanProvider(NovaCommand.class), beans.getBeanProvider(CommandFollowUp.class),
                new CommandSettingsService(new NovaStateStore(properties)), source, sender, properties,
                event -> events.add(event.toString()), clock);
    }

    @AfterEach void close() { if (beans != null) beans.close(); }

    private String say(String text) { return say("qq-onebot", "private", target.getNum(), target.getNum(), text); }
    private String say(String platform, String type, long number, long author, String text) {
        int before = replies.size();
        dispatcher.onRemoteMessage(new NovaRemoteMessageEvent(platform, type, number, author, text, null, true));
        return replies.size() == before ? "" : replies.get(replies.size() - 1);
    }

    private StreamerProfile profile() { return profiles.read("test", user.getUid()); }
    private void greet() throws Exception { enable(); delivery.check(); assertEquals(1, sent.size()); }

    private void enable() { target.getMessages().get(0).setEnabled(true); }

    @Test @DisplayName("招呼说明不回就按第二档发，避免主播不知道不回复会怎样")
    void greetsOnFirstEligibleRound() throws Exception {
        delivery.check(); assertTrue(sent.isEmpty());
        enable(); delivery.check(); delivery.check();
        assertEquals(1, sent.size());
        String text = sent.get(0).getContent();
        for (String expected : List.of("你好，我是 Nova", "1　只要小结",
                "2　小结 + 从你过去直播里看到的规律（不回就按这个发）", "最近最在意什么", "先别发了"))
            assertTrue(text.contains(expected), text);
        assertFalse(text.contains("3　")); assertSame(target, sent.get(0).getChannel());
    }

    @Test @DisplayName("回招呼选择第一档后重启仍保留档位和时间，避免默认第二档覆盖回答")
    void tierOneReplySurvivesRestartWithoutAnotherGreeting() throws Exception {
        greet();
        assertEquals("记下了：第 1 档", say("1"));
        assertEquals(1, profile().tier()); assertEquals(clock.millis(), profile().tierSetAt());
        reopen(); delivery.check();
        assertEquals(1, profile().tier()); assertEquals(clock.millis(), profile().tierSetAt());
        assertEquals(1, sent.size());
    }

    @Test @DisplayName("发过招呼重启不重发，避免每次启动都打扰主播")
    void restartDoesNotRepeatGreeting() throws Exception {
        enable(); delivery.check(); reopen(); delivery.check();
        assertEquals(1, sent.size());
    }

    @Test @DisplayName("正在直播时等下播才打招呼，避免打断直播")
    void waitsUntilOffline() throws Exception {
        enable(); when(live.getLiveStatus("test", user.getUid())).thenReturn(Optional.of(true));
        delivery.check(); assertTrue(sent.isEmpty());
        when(live.getLiveStatus("test", user.getUid())).thenReturn(Optional.of(false));
        delivery.check(); assertEquals(1, sent.size());
    }

    @Test @DisplayName("在播状态读不到也打招呼，避免个别主播永远收不到选档提示")
    void unknownLiveStatusStillGreets() throws Exception {
        when(live.getLiveStatus("test", user.getUid())).thenReturn(Optional.empty());
        greet();
    }

    @Test @DisplayName("按通道静音结束时间打招呼，避免半夜吵醒主播")
    void waitsForQuietHoursToEnd() throws Exception {
        var quiet = new PushTarget.QuietHours(); quiet.setMode("custom"); quiet.setStart("08:00"); quiet.setEnd("09:00");
        target.setQuietHours(quiet); enable(); delivery.check(); assertTrue(sent.isEmpty());
        clock.now = clock.now.plusSeconds(3600); delivery.check(); assertEquals(1, sent.size());
    }

    @Test @DisplayName("换本人号码后只给新号码一次招呼，避免新人不知道选档或旧号反复收到")
    void greetsNewNumberOnce() throws Exception {
        enable(); delivery.check(); target.setNum(990000001002L); delivery.check(); delivery.check();
        assertEquals(List.of(990000001001L, 990000001002L), sent.stream().map(Message::getNum).toList());
        target.setNum(990000001001L); reopen(); delivery.check(); assertEquals(2, sent.size());
    }

    @ParameterizedTest @ValueSource(ints = {1, 2})
    @DisplayName("自己设过任一档位就不再问，避免选过还被打招呼")
    void explicitTierSkipsGreeting(int tier) throws Exception {
        profiles.update("test", user.getUid(), p -> new StreamerProfile(tier, clock.millis(), p.concerns(),
                p.exclusions(), false, null, p.preLiveLeadMinutes(), p.preLiveDailyTime(), p.preLiveDisabled(), p.status()));
        enable(); delivery.check(); assertTrue(sent.isEmpty());
    }

    @Test @DisplayName("暂停时不招呼，恢复后再问，避免违背先别发了")
    void pauseDefersGreetingUntilResume() throws Exception {
        profiles.update("test", user.getUid(), p -> new StreamerProfile(1, null, p.concerns(),
                p.exclusions(), true, clock.millis(), p.preLiveLeadMinutes(), p.preLiveDailyTime(), p.preLiveDisabled(), p.status()));
        enable(); delivery.check(); assertTrue(sent.isEmpty());
        profiles.resume("test", user.getUid(), clock.millis()); delivery.check(); assertEquals(1, sent.size());
    }

    @ParameterizedTest @ValueSource(strings = {"damaged", "temporary"})
    @DisplayName("档案读不出时等恢复再招呼，避免带着坏档案打扰或永久漏问")
    void unreadableProfileDefersUntilRecovery(String kind) throws Exception {
        if (kind.equals("damaged")) {
            profiles.update("test", user.getUid(), p -> new StreamerProfile(1, null, p.concerns(), p.exclusions(), true, clock.millis(), p.preLiveLeadMinutes(), p.preLiveDailyTime(), p.preLiveDisabled(), p.status()));
            Files.writeString(profiles.path("test", user.getUid()), "broken");
        } else {
            profiles = spy(profiles);
            doReturn(StreamerProfile.empty(StreamerProfile.Status.TEMPORARILY_UNREADABLE)).when(profiles).read("test", user.getUid());
            reopen();
        }
        enable(); delivery.check(); assertTrue(sent.isEmpty());
        if (kind.equals("damaged")) profiles.recover("test", user.getUid());
        else doCallRealMethod().when(profiles).read("test", user.getUid());
        delivery.check(); assertEquals(1, sent.size());
    }

    @Test @DisplayName("升级前订阅已开也会问档位，避免已有主播错过选档")
    void existingSubscriptionAlsoReceivesGreeting() throws Exception {
        enable();
        new SessionRecapDeliveryStore(dir.resolve("session-recap-deliveries.json"))
                .observe(Set.of(SessionRecapDeliveryService.key("test", user.getUid(), target)), clock.now.minus(Duration.ofDays(5)));
        reopen(); delivery.check(); assertEquals(1, sent.size());
    }

    @Test @DisplayName("同轮招呼排在小结前，避免先收小结才知道能选档")
    void greetingPrecedesRecapInSameRound() throws Exception {
        enable();
        var quiet = new PushTarget.QuietHours(); quiet.setMode("custom"); quiet.setStart("08:00"); quiet.setEnd("09:00");
        target.setQuietHours(quiet); delivery.check();
        long start = clock.millis();
        when(archive.sealedSessions()).thenReturn(List.of(new LiveSessionArchive.ArchivedSession("test", user.getUid(),
                "测试主播", start, start + 3600000, 3600, LiveEndReason.NORMAL)));
        when(recap.calculate("test", user.getUid(), start)).thenReturn(new SessionRecap.Result(List.of(new SessionRecap.Item(
                "duration", new SessionRecap.Streamer("test", user.getUid()), start,
                Map.of("start", start, "end", start + 3600000, "durationMinutes", 60),
                new SessionRecap.Sample(1, start), "strong", List.of(), List.of())), List.of(), null));
        clock.now = clock.now.plusSeconds(5400); delivery.check();
        assertEquals(2, sent.size()); assertTrue(sent.get(0).getContent().contains("你好，我是 Nova"));
        assertFalse(sent.get(1).getContent().contains("你好，我是 Nova"));
    }

    @Test @DisplayName("窗口内数字加在意一起保存并确认，避免选档或关注点丢失")
    void savesTierAndConcernThroughRegisteredFollowUp() throws Exception {
        greet();
        assertEquals(1, beans.getBeansOfType(CommandFollowUp.class).size());
        assertEquals("记下了：第 2 档；最近在意：多留住新人", say("2，多留住新人"));
        assertEquals(2, profile().tier()); assertEquals(clock.millis(), profile().tierSetAt());
        assertEquals(List.of(new StreamerProfile.Concern("多留住新人", clock.millis())), profile().concerns());
        assertTrue(events.isEmpty());
        reopen(); assertEquals("原有处理", say("1")); assertEquals(2, profile().tier());
    }

    @Test @DisplayName("招呼后重启仍接住数字回复，避免重启把她的回答丢掉")
    void restartKeepsPendingWindow() throws Exception {
        greet(); clock.now = clock.now.plus(Duration.ofHours(23)); reopen();
        assertEquals("记下了：第 2 档", say("2"));
        assertEquals(2, new StreamerProfileStore(dir.resolve("profiles")).read("test", user.getUid()).tier());
    }

    @ParameterizedTest @ValueSource(strings = {"１", "　１！　", " 1, ", "２", "2，", "2～", "2~", "2😊", "2❤️", "2\uFE0E", "2\uFE0F"})
    @DisplayName("数字带全角标点波浪号或表情也能选档，避免常见回法不认回答")
    void acceptsFullWidthAndPunctuation(String answer) throws Exception {
        greet(); int tier = answer.contains("2") || answer.contains("２") ? 2 : 1;
        assertEquals("记下了：第 " + tier + " 档", say(answer)); assertEquals(tier, profile().tier());
        assertTrue(profile().concerns().isEmpty());
    }

    @ParameterizedTest @ValueSource(strings = {"2～多留住新人", "2、多留住新人", "2 多留住新人", "2，，多留住新人"})
    @DisplayName("数字后可用波浪号标点或空格接在意，避免常见回法丢失档位和关注点")
    void decoratedChoiceKeepsConcern(String answer) throws Exception {
        greet(); say(answer);
        assertEquals(2, profile().tier());
        assertEquals(List.of(new StreamerProfile.Concern("多留住新人", clock.millis())), profile().concerns());
    }

    @ParameterizedTest @ValueSource(strings = {"谢谢", "12", "21", "２１", "2多留住新人", "1 2", "1.5", "1，2", "１．５", "1 ❤️ 2"})
    @DisplayName("闲聊和多个数字不认领且保留窗口，避免误改档并把数字记成在意")
    void unrelatedTextDoesNotConsumeWindow(String text) throws Exception {
        greet(); assertEquals("原有处理", say(text)); assertNull(profile().tierSetAt());
        assertEquals("记下了：第 2 档", say("2"));
    }

    @ParameterizedTest @ValueSource(longs = {86399999, 86400000, 86400001})
    @DisplayName("窗口严格按落盘的招呼时刻截止，避免隔天随口数字改档")
    void windowUsesPersistedGreetingTime(long elapsedMillis) throws Exception {
        greet(); clock.now = clock.now.plusMillis(elapsedMillis); reopen();
        assertEquals(elapsedMillis < 86400000 ? "记下了：第 1 档" : "原有处理", say("1"));
        assertEquals(elapsedMillis < 86400000, profile().tierSetAt() != null);
        assertEquals("记下了：第 2 档", say("改成 2")); assertEquals(2, profile().tier());
    }

    @ParameterizedTest @ValueSource(strings = {"other", "group", "sender", "platform", "revoked", "disabled", "renumbered"})
    @DisplayName("别人的私聊群聊及失去本人资格的消息不认领，避免他人修改主播档位")
    void otherConversationsCannotClaim(String kind) throws Exception {
        greet();
        long number = target.getNum(), author = number;
        String platform = "qq-onebot", type = "private";
        if (kind.equals("other")) number = author = 990000001002L;
        if (kind.equals("group")) type = "group";
        if (kind.equals("sender")) author = 990000001002L;
        if (kind.equals("platform")) platform = "another";
        if (kind.equals("revoked")) target.setStreamerSelf(false);
        if (kind.equals("disabled")) target.setEnabled(false);
        if (kind.equals("renumbered")) target.setNum(990000001002L);
        assertNull(greetings.claim(new CommandContext(platform, type.equals("group") ? PushTargetType.GROUP : PushTargetType.FRIEND,
                number, author, "1", List.of(), "1")));
        assertNull(profile().tierSetAt());
        target.setStreamerSelf(true); target.setEnabled(true); target.setNum(990000001001L);
        assertEquals("记下了：第 2 档", say("2"));
    }

    @Test @DisplayName("问第三档后仍可选第一档，避免一次询问就失去选档机会")
    void thirdTierKeepsWindow() throws Exception {
        greet(); var before = profile();
        assertEquals("第 3 档还没开放，先按第 2 档发", say("3")); assertEquals(before, profile());
        reopen(); assertEquals("记下了：第 1 档", say("1")); assertEquals(1, profile().tier());
        assertEquals(clock.millis(), profile().tierSetAt());
    }

    @Test @DisplayName("窗口里主动改档后不再认领数字，避免随口数字覆盖刚改好的档位")
    void explicitChangeEndsNumericClaim() throws Exception {
        greet(); clock.now = clock.now.plusSeconds(1);
        assertTrue(say("改成 1").contains("第 1 档"));
        var before = profile(); reopen(); say("2");
        assertEquals(before, profile());
    }

    @Test @DisplayName("数字后的在意最多三十个字且保留原话，避免截坏表情或保存整段私聊")
    void boundsConcernAndEscapesReply() throws Exception {
        greet(); String concern = "😀".repeat(35) + "消息末尾";
        assertEquals("记下了：第 2 档；最近在意：" + "😀".repeat(30), say("2，" + concern));
        assertEquals("😀".repeat(30), profile().concerns().get(0).text());
        assertFalse(Files.readString(profiles.path("test", user.getUid())).contains("消息末尾"));
        String history = Files.readString(dir.resolve("session-recap-greetings.json"));
        assertFalse(history.contains("😀")); assertFalse(history.contains("消息末尾")); assertTrue(events.isEmpty());
    }

    @Test @DisplayName("在意保留全角和句末标点并转义回复，避免改写她的话或执行消息占位符")
    void concernKeepsOriginalTextAndCannotInjectMessage() throws Exception {
        greet(); String text = "ＦＯＯ，{image=sample}！";
        String response = say("２，" + text);
        assertEquals(text, profile().concerns().get(0).text());
        assertTrue(response.contains("ＦＯＯ")); assertFalse(response.contains("{image="));
    }

    @Test @DisplayName("发送器入队时遇静音仍能下轮再发，避免检查后的静音变化吞掉招呼")
    void queueGateRejectionRemainsRetryable() throws Exception {
        enable(); doReturn(PushGate.Block.QUIET_HOURS).when(sender).sendWeeklyReport(any());
        delivery.check(); assertTrue(sent.isEmpty()); reopen();
        doAnswer(call -> {
            Message message = call.getArgument(0); sent.add(message);
            message.markDelivered("synthetic-greeting").forEach(Runnable::run); return null;
        }).when(sender).sendWeeklyReport(any());
        delivery.check(); assertEquals(1, sent.size()); assertEquals("记下了：第 2 档", say("2"));
    }

    @ParameterizedTest @ValueSource(strings = {"interrupted", "uncertain", "exception"})
    @DisplayName("发送中断或结果不明跨重启不重发，避免主播重复收到招呼")
    void interruptedSendingDoesNotRepeat(String result) throws Exception {
        enable(); doAnswer(call -> {
            Message message = call.getArgument(0); sent.add(message);
            if (result.equals("uncertain")) message.markUncertain().forEach(Runnable::run);
            if (result.equals("exception")) throw new IllegalStateException("synthetic");
            return null;
        }).when(sender).sendWeeklyReport(any());
        delivery.check(); reopen(); delivery.check(); assertEquals(1, sent.size());
        assertEquals("记下了：第 2 档", say("2"));
    }

    @Test @DisplayName("发送失败只按间隔试三次且跨重启保留，避免删好友后一直重发和刷日志")
    void confirmedFailureHasPersistentLimitAndBackoff() throws Exception {
        enable(); doAnswer(call -> {
            sent.add(call.getArgument(0));
            ((Message) call.getArgument(0)).markFailed().forEach(Runnable::run); return null;
        }).when(sender).sendWeeklyReport(any());
        delivery.check(); assertEquals(1, sent.size()); reopen();
        clock.now = clock.now.plus(Duration.ofMinutes(30)).minusMillis(1);
        delivery.check(); assertEquals(1, sent.size());
        clock.now = clock.now.plusMillis(1); delivery.check(); assertEquals(2, sent.size()); reopen();
        clock.now = clock.now.plus(Duration.ofHours(2)).minusMillis(1);
        delivery.check(); assertEquals(2, sent.size());
        clock.now = clock.now.plusMillis(1); delivery.check(); assertEquals(3, sent.size());
        assertNull(greetings.claim(new CommandContext("qq-onebot", PushTargetType.FRIEND,
                target.getNum(), target.getNum(), "2", List.of(), "2")));
        reopen(); clock.now = clock.now.plus(Duration.ofDays(7)); delivery.check();
        assertEquals(3, sent.size());
        target.setNum(990000001002L); delivery.check(); assertEquals(4, sent.size());
    }

    @ParameterizedTest @ValueSource(strings = {"quiet", "disabled"})
    @DisplayName("静音或关闭推送拦截不消耗失败机会，避免恢复推送后招呼再也不发")
    void repeatedGateRejectionsDoNotCountAsFailures(String block) throws Exception {
        enable();
        doReturn(block.equals("quiet") ? PushGate.Block.QUIET_HOURS : PushGate.Block.DISABLED)
                .when(sender).sendWeeklyReport(any());
        for (int i = 0; i < 5; i++) { delivery.check(); clock.now = clock.now.plus(Duration.ofMinutes(5)); reopen(); }
        doAnswer(call -> {
            sent.add(call.getArgument(0));
            ((Message) call.getArgument(0)).markFailed().forEach(Runnable::run); return null;
        }).when(sender).sendWeeklyReport(any());
        delivery.check(); assertEquals(1, sent.size());
        clock.now = clock.now.plus(Duration.ofMinutes(30)); delivery.check(); assertEquals(2, sent.size());
        clock.now = clock.now.plus(Duration.ofHours(2)); delivery.check(); assertEquals(3, sent.size());
        reopen(); clock.now = clock.now.plus(Duration.ofDays(1)); delivery.check(); assertEquals(3, sent.size());
    }

    @ParameterizedTest @ValueSource(strings = {"quiet", "disabled"})
    @DisplayName("重试时静音或关闭推送不清零已有失败次数，避免开关变化后又无限重发")
    void gateRejectionPreservesEarlierFailures(String block) throws Exception {
        enable();
        org.mockito.stubbing.Answer<Object> fail = call -> {
            sent.add(call.getArgument(0));
            ((Message) call.getArgument(0)).markFailed().forEach(Runnable::run); return null;
        };
        doAnswer(fail).when(sender).sendWeeklyReport(any());
        delivery.check(); assertEquals(1, sent.size());
        clock.now = clock.now.plus(Duration.ofMinutes(30));
        doReturn(block.equals("quiet") ? PushGate.Block.QUIET_HOURS : PushGate.Block.DISABLED)
                .when(sender).sendWeeklyReport(any());
        for (int i = 0; i < 5; i++) { delivery.check(); reopen(); }
        doAnswer(fail).when(sender).sendWeeklyReport(any());
        delivery.check(); assertEquals(2, sent.size());
        clock.now = clock.now.plus(Duration.ofHours(2)).minusMillis(1);
        delivery.check(); assertEquals(2, sent.size());
        clock.now = clock.now.plusMillis(1); delivery.check(); assertEquals(3, sent.size());
        reopen(); clock.now = clock.now.plus(Duration.ofDays(1)); delivery.check(); assertEquals(3, sent.size());
    }

    @Test @DisplayName("窗口以发送成功的落盘时刻开始，避免排队时间吃掉回复期限")
    void queuedTimeDoesNotShortenWindow() throws Exception {
        enable(); doAnswer(call -> { sent.add(call.getArgument(0)); return null; }).when(sender).sendWeeklyReport(any());
        delivery.check(); clock.now = clock.now.plus(Duration.ofHours(2));
        sent.get(0).markDelivered("synthetic-greeting").forEach(Runnable::run);
        clock.now = clock.now.plus(Duration.ofHours(23)); reopen();
        assertEquals("记下了：第 2 档", say("2"));
    }

    @Test @DisplayName("招呼意图保存失败就不发，恢复后可重试，避免重启重复打招呼")
    void failedIntentSavePreventsSendingAndCanRecover() throws Exception {
        Path path = dir.resolve("session-recap-greetings.json"); enable();
        try (var files = mockStatic(DurableFiles.class, CALLS_REAL_METHODS)) {
            files.when(() -> DurableFiles.replace(eq(path), anyString(), eq(DurableFiles.OWNER_ONLY)))
                    .thenThrow(new java.io.IOException("synthetic"));
            assertDoesNotThrow(() -> delivery.check()); assertTrue(sent.isEmpty());
        }
        delivery.check(); assertEquals(1, sent.size()); reopen(); delivery.check(); assertEquals(1, sent.size());
    }

    @Test @DisplayName("招呼记录写不进仍登记并到点发送小结，避免一个招呼故障拖住整轮小结")
    void greetingSaveFailureDoesNotBlockRecap() throws Exception {
        enable();
        long start = clock.millis();
        when(archive.sealedSessions()).thenReturn(List.of(new LiveSessionArchive.ArchivedSession("test", user.getUid(),
                "测试主播", start, start + 3600000, 3600, LiveEndReason.NORMAL)));
        when(recap.calculate("test", user.getUid(), start)).thenReturn(new SessionRecap.Result(List.of(new SessionRecap.Item(
                "duration", new SessionRecap.Streamer("test", user.getUid()), start,
                Map.of("start", start, "end", start + 3600000, "durationMinutes", 60),
                new SessionRecap.Sample(1, start), "strong", List.of(), List.of())), List.of(), null));
        Path path = dir.resolve("session-recap-greetings.json");
        try (var files = mockStatic(DurableFiles.class, CALLS_REAL_METHODS)) {
            files.when(() -> DurableFiles.replace(eq(path), anyString(), eq(DurableFiles.OWNER_ONLY)))
                    .thenThrow(new java.io.IOException("synthetic"));
            assertDoesNotThrow(() -> delivery.check());
            assertEquals(1, new SessionRecapDeliveryStore(dir.resolve("session-recap-deliveries.json")).records().size());
            clock.now = clock.now.plus(Duration.ofMinutes(90));
            assertDoesNotThrow(() -> delivery.check());
            assertEquals(1, sent.size()); assertFalse(sent.get(0).getContent().contains("你好，我是 Nova"));
        }
    }

    @Test @DisplayName("一位主播的招呼运行异常不挡下一位，避免局部故障让其他主播也收不到招呼")
    void runtimeFailureDoesNotBlockOtherGreetings() throws Exception {
        enable();
        var other = new PushUser(); other.setPlatform("test"); other.setUid(990000000102L);
        var otherTarget = new PushTarget(); otherTarget.setPlatform("qq-onebot"); otherTarget.setType(PushTargetType.FRIEND);
        otherTarget.setNum(990000001002L); otherTarget.setStreamerSelf(true); otherTarget.setUser(other);
        otherTarget.setMessages(target.getMessages()); other.setTargets(List.of(otherTarget));
        when(source.getAllUsers()).thenReturn(List.of(user, other));
        when(live.getLiveStatus("test", user.getUid())).thenThrow(new IllegalStateException("synthetic"));
        when(live.getLiveStatus("test", other.getUid())).thenReturn(Optional.of(false));
        assertDoesNotThrow(() -> delivery.check());
        assertEquals(List.of(otherTarget.getNum()), sent.stream().map(Message::getNum).toList());
    }

    @ParameterizedTest @ValueSource(strings = {"SENT", "SENDING", "UNCERTAIN"})
    @DisplayName("旧格式招呼记录仍可读且不重发，避免升级后重复问档或丢掉回答窗口")
    void readsHistoryWithoutRetryFields(String status) throws Exception {
        greet(); Path path = dir.resolve("session-recap-greetings.json");
        var root = com.alibaba.fastjson2.JSON.parseObject(Files.readString(path));
        var entry = root.getJSONArray("records").getJSONObject(0);
        entry.remove("failures"); entry.remove("retryAt"); entry.put("status", status);
        Files.writeString(path, root.toJSONString());
        reopen(); delivery.check(); assertEquals(1, sent.size());
        assertTrue(say("2").contains("第 2 档")); assertEquals(2, profile().tier());
    }

    @Test @DisplayName("认领状态存不成不能改档或假报成功，避免重启反复消费一个回答")
    void failedClaimSaveDoesNotChangeTier() throws Exception {
        greet(); Path path = dir.resolve("session-recap-greetings.json");
        try (var files = mockStatic(DurableFiles.class, CALLS_REAL_METHODS)) {
            files.when(() -> DurableFiles.replace(eq(path), anyString(), eq(DurableFiles.OWNER_ONLY)))
                    .thenThrow(new java.io.IOException("synthetic"));
            assertTrue(say("2").contains("没能保存")); assertNull(profile().tierSetAt());
        }
        assertEquals("记下了：第 2 档", say("2"));
    }

    @Test @DisplayName("招呼记录损坏时停止问档和数字认领，避免丢历史后重发或误改档")
    void damagedGreetingHistoryFailsClosed() throws Exception {
        greet(); Files.writeString(dir.resolve("session-recap-greetings.json"), "broken");
        reopen(); delivery.check(); assertEquals(1, sent.size());
        assertEquals("原有处理", say("2")); assertNull(profile().tierSetAt());
    }

    @Test @DisplayName("招呼记录仅属主可读写，避免其他系统用户看到主播本人号码")
    void greetingHistoryIsOwnerOnly() throws Exception {
        Assumptions.assumeTrue(Files.getFileAttributeView(dir, java.nio.file.attribute.PosixFileAttributeView.class) != null,
                "文件系统不支持 POSIX 权限");
        greet(); Path path = dir.resolve("session-recap-greetings.json");
        assertEquals(DurableFiles.OWNER_ONLY, Files.getPosixFilePermissions(path));
        Files.setPosixFilePermissions(path, java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"));
        reopen(); assertEquals(DurableFiles.OWNER_ONLY, Files.getPosixFilePermissions(path));
    }

    @Test @DisplayName("全局推送关闭期间先不招呼，打开后再发，避免绕过关闭设置")
    void globalPauseDefersGreeting() throws Exception {
        enable(); properties.getPush().setEnabled(false); delivery.check(); assertTrue(sent.isEmpty());
        properties.getPush().setEnabled(true); delivery.check(); assertEquals(1, sent.size());
    }

    @Test @DisplayName("取消订阅再开启不重复招呼，避免反复开关被反复问档")
    void togglingSubscriptionDoesNotRepeatGreeting() throws Exception {
        greet(); target.getMessages().get(0).setEnabled(false); delivery.check(); enable(); reopen(); delivery.check();
        assertEquals(1, sent.size());
    }

    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-01T00:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
}
