package org.frostnova.nova.bilibili.command;

import org.frostnova.nova.core.command.CommandContext;
import org.frostnova.nova.core.command.CommandDispatcher;
import org.frostnova.nova.core.command.CommandFollowUp;
import org.frostnova.nova.core.command.CommandReply;
import org.frostnova.nova.core.command.CommandReplyHint;
import org.frostnova.nova.core.command.CommandSettingsService;
import org.frostnova.nova.core.command.GroupMessageCapability;
import org.frostnova.nova.core.command.NovaCommand;
import org.frostnova.nova.core.command.builtin.MenuCommand;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.event.remote.NovaRemoteMessageEvent;
import org.frostnova.nova.core.model.Message;
import org.frostnova.nova.core.model.PushTarget;
import org.frostnova.nova.core.model.PushUser;
import org.frostnova.nova.core.sender.NovaMessageSender;
import org.frostnova.nova.core.service.AtSubscriptionService;
import org.frostnova.nova.core.service.LiveDataService;
import org.frostnova.nova.core.service.LiveSessionArchive;
import org.frostnova.nova.core.service.NovaStateStore;
import org.frostnova.nova.core.timeline.TimelineWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 「回个序号」语料回放
 * <p>
 * 机器人问「是哪一位」之后，使用者接下来发的那个数字<b>本身不是命令</b>，分发器认不出它。
 * 这一段路只有整条走一遍才量得到：从群里那句话进去，看出来的是那位主播的图、还是一份菜单、
 * 还是一个字都没有。
 * <p>
 * 期望以 {@code !} 开头表示这一段必须不出现——把序号认错人，出来的是另一位主播的数据，
 * <b>而那张图看起来完全正常</b>，这一半只有反向的期望写得出来。
 * <p>
 * 表里另有一半钉的是两种真发生过的别扭：群里问完「是哪一位」，直接回个序号没反应、
 * 非得 @ 了机器人才算数；以及别人随口一个数字被当成回答。没 @ 的那几行因此各有两种读法：
 * 该出声的（本人回序号、回「全部」）出声，不该出声的（闲聊词、多词、别人的序号）
 * 连菜单都不回、也不吃掉冷却与那次追问。
 * <p>
 * 时间由夹具里的时钟说了算：那两分钟的窗口若靠真等，这张表没人会跑第二遍，
 * 而不跑的表与跑过且通过的表在报告里长得一样。<b>命令冷却读的也是这一把钟</b>——
 * 两把钟的那一版里，凡是「等一会儿再说一句」的语料都会被冷却挡在门外，
 * 挡住之后机器人一个字都不说，而那与「这一句本就不该有回应」是同一种读数。
 */
@DisplayName("回序号选主播语料")
class BilibiliStreamerFollowUpCorpusTest {
    private static final String PLATFORM = "qq-onebot";
    private static final String OFFICIAL = "qq-official";

    private static final long GROUP = 30003L;

    private static final long ASKER = 40001L;

    private static final long OTHER = 40002L;

    private static final List<String> NAMES =
            List.of("主播甲", "主播乙", "主播丙", "主播丁", "主播戊", "主播己", "主播庚", "主播辛");

    @TempDir
    static Path dataDir;

    /**
     * 群里的一句话与它期望换来的回复
     *
     * @param sender 谁说的
     * @param after 说这句话之前先过多久
     * @param text 机器人的正文（@ 过的为去掉 @ 段后的内容）
     * @param mentionsBot 是否 @ 了机器人
     * @param says 回复里该出现的片段；以 {@code !} 开头表示必须不出现
     */
    private record Say(long sender, Duration after, String text, boolean mentionsBot, List<String> says) {
    }

    private static Say at(String text, String... says) {
        return new Say(ASKER, Duration.ZERO, text, true, List.of(says));
    }

    private static Say atAfter(Duration after, String text, String... says) {
        return new Say(ASKER, after, text, true, List.of(says));
    }

    private static Say atBy(long sender, String text, String... says) {
        return new Say(sender, Duration.ZERO, text, true, List.of(says));
    }

    private static Say plain(String text, String... says) {
        return new Say(ASKER, Duration.ZERO, text, false, List.of(says));
    }

    private static Say plainAfter(Duration after, String text, String... says) {
        return new Say(ASKER, after, text, false, List.of(says));
    }

    private static Say plainByAfter(long sender, Duration after, String text, String... says) {
        return new Say(sender, after, text, false, List.of(says));
    }

    private record Corpus(String name, List<Say> dialogue) {
        @Override
        public String toString() {
            return name;
        }
    }

    private static Corpus corpus(String name, Say... dialogue) {
        return new Corpus(name, List.of(dialogue));
    }

    private static Stream<Corpus> corpus() {
        return Stream.of(
                corpus("问完回一个序号 —— 照着办，用的是选中的那位",
                        at("直播报告", "回序号选一位"),
                        at("2", "已出图：主播乙")),

                corpus("回「全部」—— 展开完整清单，序号照旧管用",
                        at("直播报告", "还有 3 位，回「全部」展开"),
                        at("全部", "8. 主播辛（10008）", "!还有 3 位"),
                        at("8", "已出图：主播辛")),

                corpus("序号超出范围 —— 提示的上限包括折叠的主播，清单还可继续选",
                        at("直播报告", "还有 3 位，回「全部」展开"),
                        at("99", "没有 99 号", "1 到 8", "!已出图"),
                        at("2", "已出图：主播乙")),

                corpus("回过序号之后再省掉参数 —— 还是问一遍，不替人记上次那位",
                        at("直播报告", "回序号选一位"),
                        at("2", "已出图：主播乙"),
                        atAfter(Duration.ofSeconds(5), "直播报告", "回序号选一位", "!已出图"),
                        plain("2", "已出图：主播乙")),

                corpus("换个序号还能接着选 —— 超出范围的数字也不结束清单",
                        at("直播报告", "2 分钟内还能接着选别的"),
                        plain("4", "已出图：主播丁"),
                        plain("9999", "没有 9999 号", "1 到 8", "!已出图"),
                        plainAfter(Duration.ofSeconds(13), "2", "已出图：主播乙")),

                corpus("选完再回「全部」—— 展开时保留已选过的主播",
                        at("直播报告", "还有 3 位，回「全部」展开"),
                        plain("2", "已出图：主播乙"),
                        plain("全部", "8. 主播辛", "!还有 3 位"),
                        plain("2", "已经选过", "!已出图"),
                        plain("8", "已出图：主播辛")),

                corpus("选过主播后发普通聊天 —— 聊天和之后的序号都不接话",
                        at("直播报告", "回序号选一位"),
                        plain("2", "已出图：主播乙"),
                        plain("明天 几点开播", "!已出图", "!用法："),
                        plain("3", "!已出图", "!用法：")),

                corpus("选过主播后发普通单词 —— 回复照旧，随后不能再选主播",
                        at("直播报告", "回序号选一位"),
                        at("2", "已出图：主播乙"),
                        atAfter(Duration.ofSeconds(5), "你好", "用法："),
                        plain("3", "!已出图", "!用法：")),

                corpus("没问过就回数字 —— 当成认不出的消息，回菜单",
                        at("2", "用法：", "!已出图")),

                corpus("答的是别人那份追问 —— 不算数，问的那个人自己回才算",
                        at("直播报告", "回序号选一位"),
                        atBy(OTHER, "2", "!已出图"),
                        at("2", "已出图：主播乙")),

                corpus("差一点满两分钟还能选 —— 选完也不延长有效期",
                        at("直播报告", "回序号选一位"),
                        atAfter(Duration.ofSeconds(119), "2", "已出图：主播乙"),
                        plainAfter(Duration.ofSeconds(1), "3", "!已出图")),

                corpus("过了两分钟 —— 作废，不照着办",
                        at("直播报告", "回序号选一位"),
                        atAfter(Duration.ofSeconds(121), "2", "!已出图", "用法：")),

                // 上一行只说了「作废」，而作废之后<b>还问不问得出来</b>是另一件事：
                // 追问表里若留着一份过期的记录不清，第二次问会被它顶掉，现象是「机器人不理我了」。
                // 这一行要走完 121 秒＋命令冷却两道时间，靠真等没人会跑第二遍——
                // 分发器与追问因此共用同一把推着走的时钟
                corpus("过期之后重新问 —— 清单照样问得出来",
                        at("直播报告", "回序号选一位"),
                        atAfter(Duration.ofSeconds(121), "2", "!已出图"),
                        atAfter(Duration.ofSeconds(5), "直播报告", "回序号选一位")),

                // 以下几行钉「问完之后不 @ 也答得上」：机器人刚在群里问过「是哪一位」，
                // 这句话就是对它说的，不该还要求使用者先 @ 一遍
                corpus("问完没 @ 回一个序号 —— 照常执行，用的是选中的那位",
                        at("直播报告", "回序号选一位"),
                        plain("2", "已出图：主播乙")),

                corpus("问完没 @ 回「全部」—— 展开完整清单",
                        at("直播报告", "回序号选一位"),
                        plain("全部", "8. 主播辛（10008）", "!还有 3 位")),

                corpus("别人没 @ 回同一个序号 —— 不出声，追问还在，问的人自己回才算",
                        at("直播报告", "回序号选一位"),
                        // 隔 5 秒再说下一句：挤在冷却窗里的沉默分不出是「没接话」还是「被冷却挡了」
                        plainByAfter(OTHER, Duration.ofSeconds(5), "2", "!已出图", "!用法："),
                        plainAfter(Duration.ofSeconds(5), "2", "已出图：主播乙")),

                corpus("没 @ 的普通单词 —— 不出声、不回菜单、不吃冷却，紧接着的命令照常问得出来",
                        plain("今天天气不错", "!已出图", "!用法："),
                        at("直播报告", "回序号选一位")),

                corpus("没 @ 的多词消息（首词是数字）—— 不出声、不回菜单，也不吃掉那次追问",
                        at("直播报告", "回序号选一位"),
                        plainAfter(Duration.ofSeconds(5), "2 3", "!已出图", "!用法："),
                        plainAfter(Duration.ofSeconds(5), "2", "已出图：主播乙")),

                corpus("没 @ 回数字但没人问过 —— 不出声、不回菜单",
                        plain("2", "!已出图", "!用法：")),

                // 首次选择时，@ 后的多词消息仍按第一个词认领。
                corpus("已 @ 的首次选择 —— 多词仍按第一个词认领",
                        at("直播报告", "回序号选一位"),
                        at("2 你猜", "已出图：主播乙")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("corpus")
    void replays(Corpus corpus) {
        Fixture fixture = new Fixture();

        for (Say line : corpus.dialogue()) {
            String said = fixture.feed(line.sender(), line.after(), line.text(), line.mentionsBot());

            for (String expected : line.says()) {
                if (expected.startsWith("!")) {
                    assertFalse(said.contains(expected.substring(1)),
                            corpus.name() + "：不该出现「" + expected.substring(1) + "」，实际说了「" + said + "」");
                } else {
                    assertTrue(said.contains(expected),
                            corpus.name() + "：缺「" + expected + "」，实际说了「" + said + "」");
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("六位主播的清单收到 9 时只提示一次，之后仍能选第 3 位")
    void warnsOnceAndKeepsTheStreamerList(boolean mentionsBot) {
        Fixture fixture = new Fixture(PLATFORM, PushTargetType.GROUP, 6);
        String list = fixture.feed(ASKER, Duration.ZERO, "直播报告", true);
        assertTrue(list.contains("6. 主播己"), () -> list);

        String warning = fixture.feed(ASKER, Duration.ZERO, "9", mentionsBot);
        assertTrue(warning.contains("没有 9 号") && warning.contains("1 到 6"),
                () -> "越界时应提示序号范围，实际说了「" + warning + "」");
        assertEquals("", fixture.feed(ASKER, Duration.ZERO, "8", false));
        String fallback = fixture.feed(ASKER, Duration.ofSeconds(4), "7", true);
        assertTrue(fallback.contains("用法："), () -> fallback);
        assertFalse(fallback.contains("清单里没有"), () -> fallback);
        String report = fixture.feed(ASKER, Duration.ZERO, "3", false);
        assertTrue(report.contains("已出图：主播丙"), () -> report);

        fixture.feed(ASKER, Duration.ofSeconds(4), "直播报告", true);
        String newWarning = fixture.feed(ASKER, Duration.ZERO, "9", mentionsBot);
        assertTrue(newWarning.contains("没有 9 号"), () -> newWarning);
    }

    @Test
    @DisplayName("重跑时把选中那位接在原参数末尾 —— 原有参数一字不动")
    void rerunCarriesTheChoiceAsTheLastArgument() {
        // 带参数的命令（「@名单 开播 2」）重跑时要保留原参数的顺序。
        // 放错位置会把「开播」挤出首参，影响用法键的选择。
        NovaCoreProperties properties = new NovaCoreProperties();
        properties.getLive().setLiveDataPath(dataDir.resolve("data.json").toString());

        LiveDataService liveDataService = mock(LiveDataService.class);
        when(liveDataService.getLiveStatus(anyString(), anyLong())).thenReturn(Optional.of(false));
        when(liveDataService.getLiveEndTime(anyString(), anyLong())).thenReturn(Optional.empty());

        BilibiliStreamerChoice choice = new BilibiliStreamerChoice(liveDataService,
                new LiveSessionArchive(properties), new Ticking());

        List<PushUser> candidates = new ArrayList<>();
        for (int index = 1; index <= NAMES.size(); index++) {
            candidates.add(streamer(index, PLATFORM, PushTargetType.GROUP));
        }
        choice.ask(new CommandContext(PLATFORM, PushTargetType.GROUP, GROUP, ASKER,
                "@名单", List.of("开播", "2"), "@名单 开播 2"), choice.rank(candidates));

        CommandFollowUp.Claimed claimed = choice.claim(new CommandContext(PLATFORM, PushTargetType.GROUP,
                GROUP, ASKER, "3", List.of(), "3"));

        assertEquals("@名单", claimed.command());
        assertEquals(List.of("开播", "2", "10003"), claimed.args());
    }

    @Test
    void officialGroupMenuTellsTheAskerToMentionTheBotAndAcceptsTheNumber() {
        var fixture = new Fixture(OFFICIAL, PushTargetType.GROUP);

        String menu = fixture.feed(ASKER, Duration.ZERO, "直播报告", true);
        String selected = fixture.feed(ASKER, Duration.ZERO, "2", true);

        assertTrue(menu.contains("@我 回序号选一位"), () -> menu);
        assertTrue(selected.contains("已出图：主播乙"), () -> selected);
    }

    @Test
    void oneBotGroupMenuStillAcceptsAnUnmentionedNumber() {
        var fixture = new Fixture();

        String menu = fixture.feed(ASKER, Duration.ZERO, "直播报告", true);
        String selected = fixture.feed(ASKER, Duration.ZERO, "2", false);

        assertTrue(menu.contains("位主播，回序号选一位"), () -> menu);
        assertFalse(menu.contains("@我"), () -> menu);
        assertTrue(selected.contains("已出图：主播乙"), () -> selected);
    }

    @Test
    void officialPrivateMenuStillAcceptsAnUnmentionedNumber() {
        var fixture = new Fixture(OFFICIAL, PushTargetType.FRIEND);

        String menu = fixture.feed(ASKER, Duration.ZERO, "直播报告", false);
        String selected = fixture.feed(ASKER, Duration.ZERO, "2", false);

        assertTrue(menu.contains("位主播，回序号选一位"), () -> menu);
        assertFalse(menu.contains("@我"), () -> menu);
        assertTrue(selected.contains("已出图：主播乙"), () -> selected);
    }

    @Test
    void numericPrefixQuestionAfterAChoiceDoesNotSubscribeToAnotherStreamer() {
        var fixture = new Fixture();

        String menu = fixture.feed(ASKER, Duration.ZERO, "开播@我", true);
        assertTrue(menu.contains("回序号选一位"), () -> menu);
        fixture.feed(ASKER, Duration.ZERO, "1", true);
        assertEquals(List.of(ASKER), fixture.subscriptions.list(PLATFORM, GROUP, 10001L, "live"));

        String question = fixture.feed(ASKER, Duration.ofSeconds(5), "2 小时前主播甲开播了吗", true);
        assertEquals(List.of(), fixture.subscriptions.list(PLATFORM, GROUP, 10002L, "live"), () -> question);
        assertTrue(question.contains("用法："), () -> question);

        assertEquals("", fixture.feed(ASKER, Duration.ZERO, "2", false));
        assertEquals(List.of(), fixture.subscriptions.list(PLATFORM, GROUP, 10002L, "live"));
    }

    /**
     * 一次回放用的整套零件
     * <p>
     * 每行语料新建一份：追问按会话与人记着，共用一份的话，前一行的痕迹会落到下一行头上。
     */
    private static class Fixture {
        private final List<String> replies = new ArrayList<>();

        private final CommandDispatcher dispatcher;

        private final AtSubscriptionService subscriptions;

        private final Ticking clock = new Ticking();
        private final String platform;
        private final PushTargetType type;

        Fixture() {
            this(PLATFORM, PushTargetType.GROUP);
        }

        Fixture(String platform, PushTargetType type) {
            this(platform, type, NAMES.size());
        }

        Fixture(String platform, PushTargetType type, int streamerCount) {
            this.platform = platform;
            this.type = type;
            List<PushUser> users = new ArrayList<>();
            for (int index = 1; index <= streamerCount; index++) {
                users.add(streamer(index, platform, type));
            }

            AbstractDataSource dataSource = mock(AbstractDataSource.class);
            when(dataSource.getAllUsers()).thenReturn(users);
            when(dataSource.getUsers("bilibili")).thenReturn(users);

            LiveDataService liveDataService = mock(LiveDataService.class);
            when(liveDataService.getLiveStatus(anyString(), anyLong())).thenReturn(Optional.of(false));
            when(liveDataService.getLiveEndTime(anyString(), anyLong())).thenReturn(Optional.empty());

            NovaMessageSender sender = mock(NovaMessageSender.class);
            doAnswer(invocation -> replies.add(((Message) invocation.getArgument(0)).getContent()))
                    .when(sender).send(any());

            NovaCoreProperties properties = new NovaCoreProperties();
            properties.getLive().setLiveDataPath(dataDir.resolve("data.json").toString());

            NovaStateStore state = new NovaStateStore(properties);
            CommandSettingsService settings = new CommandSettingsService(state);
            subscriptions = new AtSubscriptionService(state);
            BilibiliStreamerChoice choice =
                    new BilibiliStreamerChoice(liveDataService, new LiveSessionArchive(properties), clock);
            var capability = mock(GroupMessageCapability.class);
            when(capability.platform()).thenReturn(OFFICIAL);
            when(capability.receivesUnmentionedGroupMessages()).thenReturn(false);
            choice.setReplyHint(new CommandReplyHint(List.of(capability)));

            List<NovaCommand> commands = new ArrayList<>();
            @SuppressWarnings("unchecked")
            ObjectProvider<NovaCommand> provider = mock(ObjectProvider.class);
            when(provider.iterator()).thenAnswer(invocation -> new ArrayList<>(commands).iterator());
            when(provider.orderedStream()).thenAnswer(invocation -> new ArrayList<>(commands).stream());

            @SuppressWarnings("unchecked")
            ObjectProvider<CommandFollowUp> followUps = mock(ObjectProvider.class);
            when(followUps.iterator()).thenAnswer(invocation -> List.of((CommandFollowUp) choice).iterator());

            // 分发器与追问共用这一把钟：两者各读各的时间时，「过期之后重新问」那一行
            // 会撞在读真钟的那道冷却上，而撞上冷却与「追问表没清干净」在回复里长得一样（都是一个字不说）
            dispatcher = new CommandDispatcher(provider, followUps, settings, dataSource, sender, properties,
                    TimelineWriter.NONE, clock);

            @SuppressWarnings("unchecked")
            ObjectProvider<CommandDispatcher> self = mock(ObjectProvider.class);
            when(self.getIfAvailable()).thenReturn(dispatcher);

            commands.add(new MenuCommand(self, settings));
            commands.add(new ProbeCommand(dataSource, choice));
            commands.add(new BilibiliLiveAtMeCommand(dataSource, choice, subscriptions, settings));
        }

        /**
         * 群里说一句，返回机器人说了什么
         */
        String feed(long sender, Duration after, String text, boolean mentionsBot) {
            clock.advance(after);
            replies.clear();
            dispatcher.onRemoteMessage(new NovaRemoteMessageEvent(platform,
                    type == PushTargetType.GROUP ? "group" : "private", GROUP, sender, text, "member", mentionsBot));
            return String.join("\n", replies);
        }

    }

    private static PushUser streamer(int index, String platform, PushTargetType type) {
        PushUser user = new PushUser();
        user.setUid(10000L + index);
        user.setUname(NAMES.get(index - 1));
        user.setPlatform("bilibili");

        PushTarget target = new PushTarget();
        target.setPlatform(platform);
        target.setType(type);
        target.setNum(GROUP);
        target.setMessages(new ArrayList<>());
        user.setTargets(List.of(target));
        return user;
    }

    /**
     * 一把只在被推的时候才走的时钟
     */
    private static class Ticking extends Clock {
        private Instant now = Instant.parse("2026-09-04T12:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.systemDefault();
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /**
     * 一条只报「用了哪位主播」的命令，理由同 {@link BilibiliStreamerChoiceCorpusTest}：
     * 出图那一段与「说的是哪位主播」无关
     */
    private static class ProbeCommand extends BilibiliStreamerCommand {
        ProbeCommand(AbstractDataSource dataSource, BilibiliStreamerChoice choice) {
            super(dataSource, choice);
        }

        @Override
        public String name() {
            return "直播报告";
        }

        @Override
        public String description() {
            return "供语料回放使用";
        }

        @Override
        public boolean groupOnly() {
            return false;
        }

        @Override
        public CommandReply execute(CommandContext context) {
            Resolved resolved = resolve(context, context.arg(0));
            return resolved.failed()
                    ? resolved.error()
                    : withNotice(resolved, CommandReply.of("已出图：" + nameOf(resolved.streamer())));
        }
    }
}
