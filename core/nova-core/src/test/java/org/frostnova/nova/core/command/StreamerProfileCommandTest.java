package org.frostnova.nova.core.command;

import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.event.remote.NovaRemoteMessageEvent;
import org.frostnova.nova.core.model.Message;
import org.frostnova.nova.core.model.PushTarget;
import org.frostnova.nova.core.model.PushUser;
import org.frostnova.nova.core.sender.NovaMessageSender;
import org.frostnova.nova.core.service.NovaStateStore;
import org.frostnova.nova.core.service.StreamerProfile;
import org.frostnova.nova.core.service.StreamerProfileStore;
import org.frostnova.nova.core.service.StreamerSelfResolver;
import org.frostnova.nova.core.timeline.TimelineWriter;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StreamerProfileCommandTest {
    private static final long UID = 990000000101L, SELF = 990000001001L, OTHER = 990000001002L, GROUP = 990000002001L;
    @TempDir Path dir;
    private AnnotationConfigApplicationContext beans;
    private StreamerProfileStore profiles;
    private StreamerSelfResolver resolver;
    private PushTarget self;
    private PushUser user;
    private CommandDispatcher dispatcher;
    private final List<String> replies = new ArrayList<>();
    private final List<String> records = new ArrayList<>();
    private final TestClock clock = new TestClock();
    private int legacyCalls;

    @BeforeEach void setUp() {
        var properties = new NovaCoreProperties();
        properties.getLive().setLiveDataPath(dir.resolve("live").toString());
        self = target(SELF, PushTargetType.FRIEND); self.setStreamerSelf(true);
        user = new PushUser(); user.setPlatform("bilibili"); user.setUid(UID);
        user.setTargets(List.of(self, target(OTHER, PushTargetType.FRIEND), target(GROUP, PushTargetType.GROUP)));
        var source = mock(AbstractDataSource.class);
        when(source.getAllUsers()).thenReturn(List.of(user));
        var sender = mock(NovaMessageSender.class);
        doAnswer(call -> { replies.add(((Message) call.getArgument(0)).getContent()); return null; }).when(sender).send(any());
        beans = new AnnotationConfigApplicationContext();
        beans.registerBean(NovaCoreProperties.class, () -> properties);
        beans.registerBean(AbstractDataSource.class, () -> source);
        beans.register(StreamerProfileStore.class, StreamerSelfResolver.class, StreamerProfileCommands.class);
        beans.registerBean("menu", NovaCommand.class, () -> legacy("菜单"));
        beans.registerBean("legacy", NovaCommand.class, () -> legacy("恢复"));
        beans.refresh();
        profiles = beans.getBean(StreamerProfileStore.class);
        resolver = beans.getBean(StreamerSelfResolver.class);
        @SuppressWarnings("unchecked") ObjectProvider<CommandFollowUp> followUps = mock(ObjectProvider.class);
        when(followUps.iterator()).thenAnswer(call -> List.<CommandFollowUp>of().iterator());
        TimelineWriter timeline = event -> records.add(event.toString());
        dispatcher = new CommandDispatcher(beans.getBeanProvider(NovaCommand.class), followUps,
                new CommandSettingsService(new NovaStateStore(properties)), source, sender, properties, timeline, clock);
    }
    @AfterEach void close() { beans.close(); }
    private NovaCommand legacy(String name) {
        return new NovaCommand() {
            public String name() { return name; }
            public String description() { return "测试命令"; }
            public boolean groupOnly() { return false; }
            public CommandReply execute(CommandContext context) { legacyCalls++; return CommandReply.of("现有命令"); }
        };
    }
    private PushTarget target(long num, PushTargetType type) {
        var target = new PushTarget(); target.setPlatform("qq-onebot"); target.setType(type); target.setNum(num); return target;
    }
    private String say(String text) { return send("private", SELF, SELF, text); }
    private String send(String type, long num, long sender, String text) {
        clock.now = clock.now.plusSeconds(4);
        int before = replies.size();
        dispatcher.onRemoteMessage(new NovaRemoteMessageEvent("qq-onebot", type, num, sender, text, null, true));
        return replies.size() == before ? "" : replies.get(replies.size() - 1);
    }
    private StreamerProfile profile() { return profiles.read("bilibili", UID); }

    @Test @DisplayName("主播改成第二档应收到确认，避免设置被当成闲聊")
    void ownerChangesTierThroughDispatcher() {
        assertEquals("记下了：第 2 档", say("改成 2"));
        assertEquals(2, profile().tier()); assertNotNull(profile().tierSetAt());
    }
    @Test @DisplayName("别人的私聊、群聊和号码不一致的消息不能修改主播设置")
    void otherConversationsKeepExistingRouting() {
        assertEquals("现有命令", send("private", OTHER, OTHER, "改成 2"));
        assertEquals("现有命令", send("group", GROUP, SELF, "改成 2"));
        assertEquals("现有命令", send("private", SELF, OTHER, "改成 2"));
        assertEquals(1, profile().tier()); assertNull(profile().tierSetAt());
        assertEquals("", send("private", 990000001003L, 990000001003L, "改成 2"));
    }
    @Test @DisplayName("取消本人标记后，原号码立即失去改设置的资格")
    void revocationTakesEffectWithoutRestart() {
        say("改成 2"); self.setStreamerSelf(false);
        assertEquals("现有命令", say("改成 1")); assertEquals(2, profile().tier());
        assertTrue(resolver.resolve(new CommandContext("qq-onebot", PushTargetType.FRIEND, SELF, SELF, "2", List.of(), "2")).isEmpty());
    }
    @Test @DisplayName("停用通道或主播后不能再改设置，其他平台的同号码也不能认人")
    void disabledAndDifferentPlatformAreRejected() {
        self.setEnabled(false); say("改成 2"); assertEquals(1, profile().tier());
        self.setEnabled(true); user.setEnabled(false); say("改成 2"); assertEquals(1, profile().tier());
        user.setEnabled(true);
        assertTrue(resolver.resolve(new CommandContext("another", PushTargetType.FRIEND, SELF, SELF, "", List.of(), "2")).isEmpty());
    }
    @Test @DisplayName("第三档不开放，说明当前档位且不改设置时间")
    void thirdTierKeepsCurrentSetting() {
        say("改成 2"); var before = profile();
        assertEquals("第 3 档还没开放，先按第 2 档发", say("改成 3")); assertEquals(before, profile());
    }
    @ParameterizedTest(name = "{0} 对应 {1}")
    @CsvSource({"开播时间,slot_compare", "时段,slot_compare", "几点播,slot_compare", "新人,newcomer_speak_return",
            "新观众,newcomer_speak_return", "开口,newcomer_speak_return", "老观众,regulars_absent", "常客,regulars_absent",
            "老朋友,regulars_absent", "PK,pk_share"})
    @DisplayName("别提的每个别名都保存正确类型，避免下次仍提到")
    void omitAliases(String alias, String pattern) {
        assertTrue(say("别提 " + alias).startsWith("记下了"));
        assertEquals(pattern, profile().exclusions().get(0).type().name()); assertTrue(profile().exclusions().get(0).setAt() > 0);
    }
    @Test @DisplayName("未知的别提只给可用说法，不添一条无效设置")
    void unknownOmissionDoesNotChangeProfile() {
        var before = profile(); String response = say("别提礼物");
        for (var name : List.of("开播时间", "新人", "老观众", "PK")) assertTrue(response.contains(name));
        assertEquals(before, profile());
    }
    @Test @DisplayName("空格、标点和全角不妨碍识别，在意的内容仍保留原话")
    void flexiblePhrasesKeepOriginalConcern() {
        assertEquals("记下了：第 2 档", say("　「改 成：２！」　"));
        assertEquals("记下了：以后不提 PK", say("别提，ＰＫ！"));
        assertEquals("记下了", say("（最近 在意）ＦＯＯ，bar 的 问题！"));
        assertEquals("ＦＯＯ，bar 的 问题！", profile().concerns().get(0).text());
    }
    @Test @DisplayName("在意原话最多三十个字，不保存整段私聊或把原话记到日志")
    void concernIsBoundedAndPrivate() throws Exception {
        String text = "😀".repeat(35) + "消息末尾";
        say("最近在意 " + text);
        assertEquals("😀".repeat(30), profile().concerns().get(0).text());
        String disk = Files.readString(profiles.path("bilibili", UID));
        assertFalse(disk.contains("最近在意")); assertFalse(disk.contains("消息末尾"));
        assertTrue(records.stream().noneMatch(row -> row.contains("😀")));
    }
    @Test @DisplayName("先别发了跨重启保留，恢复会记录新的设置时间")
    void pauseSurvivesRestartAndResume() {
        assertEquals("好的，先不发了；回「恢复」就接着发", say("先别发了"));
        var restarted = new StreamerProfileStore(profiles.path("bilibili", UID).getParent());
        assertTrue(restarted.read("bilibili", UID).paused()); assertNotNull(profile().pauseSetAt());
        assertEquals("好的", say("恢复")); assertFalse(profile().paused()); assertEquals(0, legacyCalls);
    }
    @Test @DisplayName("你记得我什么列全设置及各自时间，原话不能变成消息指令")
    void recallIncludesEverythingAndTimes() {
        say("改成 2"); say("最近在意 回访"); say("最近在意 {image=sample}");
        say("别提时段"); say("别提新人"); say("别提老朋友"); say("别提PK"); say("先别发了");
        String reply = say("你记得我什么");
        for (String expected : List.of("第 2 档", "回访", "开播时间", "新人开口", "老观众", "PK", "是否暂停：是")) assertTrue(reply.contains(expected), reply);
        long dated = reply.lines().filter(line -> line.matches(".*\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}.*")).count();
        assertEquals(8, dated); assertFalse(reply.contains("{image=")); assertTrue(reply.contains("｛image=sample｝"));
    }
    @Test @DisplayName("忘掉一条和全部都删除对应内容，保留档位和暂停及其时间")
    void forgetKeepsTierAndPause() {
        say("改成 2"); say("先别发了"); say("最近在意 回访"); say("最近在意 礼物"); say("别提时段"); say("别提新人");
        var before = profile();
        assertEquals("删了", say("忘掉 回访")); assertEquals("删了", say("忘掉 几点播"));
        assertEquals(List.of("礼物"), profile().concerns().stream().map(StreamerProfile.Concern::text).toList());
        assertEquals(1, profile().exclusions().size());
        String missing = say("忘掉 不存在"); assertTrue(missing.contains("没找到")); assertTrue(missing.contains("礼物")); assertTrue(missing.contains("新人开口"));
        assertEquals("删了", say("忘掉全部")); assertTrue(profile().concerns().isEmpty()); assertTrue(profile().exclusions().isEmpty());
        assertEquals(before.tier(), profile().tier()); assertEquals(before.tierSetAt(), profile().tierSetAt());
        assertTrue(profile().paused()); assertEquals(before.pauseSetAt(), profile().pauseSetAt());
    }
    @Test @DisplayName("本人私聊的重名命令优先，别的私聊和群聊仍走旧命令")
    void scopedCommandsWinOnlyForTheirOwner() {
        assertEquals("好的", say("恢复")); assertEquals(0, legacyCalls);
        assertEquals("现有命令", send("private", OTHER, OTHER, "恢复"));
        assertEquals("现有命令", send("group", GROUP, SELF, "恢复")); assertEquals(2, legacyCalls);
    }
    @Test @DisplayName("坏件后说恢复应清掉读错状态，避免主播永久收不到小结")
    void resumeClearsCorruptionAcrossRestart() throws Exception {
        say("先别发了");
        Files.writeString(profiles.path("bilibili", UID), "broken");
        assertEquals(StreamerProfile.Status.UNREADABLE, profile().status());
        say("最近在意 回访"); say("改成 2");
        assertEquals("好的", say("恢复"));
        var restored = new StreamerProfileStore(profiles.path("bilibili", UID).getParent()).read("bilibili", UID);
        assertEquals(StreamerProfile.Status.READY, restored.status());
        assertEquals(1, restored.tier()); assertNull(restored.tierSetAt());
        assertFalse(restored.paused()); assertNotNull(restored.pauseSetAt());
        assertTrue(restored.concerns().isEmpty()); assertTrue(restored.exclusions().isEmpty());
    }
    @Test @DisplayName("连续发送档案说法也要逐句生效，避免暂停在冷却中被丢掉")
    void profilePhrasesBypassCooldown() {
        assertEquals("现有命令", say("菜单"));
        for (String phrase : List.of("改成 2", "先别发了", "你记得我什么", "恢复")) {
            int before = replies.size();
            dispatcher.onRemoteMessage(new NovaRemoteMessageEvent("qq-onebot", "private", SELF, SELF, phrase, null, true));
            assertEquals(before + 1, replies.size(), phrase);
            if (phrase.equals("先别发了")) assertTrue(profile().paused());
        }
        assertEquals(2, profile().tier()); assertFalse(profile().paused());
    }
    @ParameterizedTest @ValueSource(strings = {"先别发了~", "先别发了～", "先别发了😊"})
    @DisplayName("末尾波浪号和表情不应让暂停与恢复被当成闲聊")
    void trailingSymbolsDoNotHideSettings(String phrase) {
        assertEquals("好的，先不发了；回「恢复」就接着发", say(phrase));
        assertTrue(profile().paused()); assertEquals("好的", say("恢复😊")); assertFalse(profile().paused());
    }
    @Test @DisplayName("本人菜单把档案说法合成一行，避免重复十二条相同说明")
    void profileMenuIsOneLineOnlyForOwner() {
        @SuppressWarnings("unchecked") ObjectProvider<CommandDispatcher> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(dispatcher);
        var settings = new CommandSettingsService(new NovaStateStore(beans.getBean(NovaCoreProperties.class)));
        var menu = new org.frostnova.nova.core.command.builtin.MenuCommand(provider, settings);
        var owner = new CommandContext("qq-onebot", PushTargetType.FRIEND, SELF, SELF, "菜单", List.of(), "菜单");
        String text = menu.execute(owner).content();
        assertEquals(1, text.lines().filter(line -> line.startsWith("档案设置：")).count(), text);
        assertFalse(text.contains("设置或查看自己的主播档案"));
        for (String phrase : List.of("改成", "别提", "最近在意", "先别发了", "恢复", "你记得我什么", "忘掉"))
            assertTrue(text.lines().filter(line -> line.startsWith("档案设置：")).findFirst().orElse("").contains(phrase));
        var other = new CommandContext("qq-onebot", PushTargetType.FRIEND, OTHER, OTHER, "菜单", List.of(), "菜单");
        assertFalse(menu.execute(other).content().contains("档案设置："));
    }
    private static class TestClock extends Clock {
        private Instant now = Instant.parse("2026-10-20T00:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
}
