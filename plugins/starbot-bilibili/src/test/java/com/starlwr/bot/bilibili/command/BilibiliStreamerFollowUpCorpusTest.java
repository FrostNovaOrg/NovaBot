package com.starlwr.bot.bilibili.command;

import com.starlwr.bot.core.command.CommandContext;
import com.starlwr.bot.core.command.CommandDispatcher;
import com.starlwr.bot.core.command.CommandFollowUp;
import com.starlwr.bot.core.command.CommandReply;
import com.starlwr.bot.core.command.CommandSettingsService;
import com.starlwr.bot.core.command.NovaCommand;
import com.starlwr.bot.core.command.builtin.MenuCommand;
import com.starlwr.bot.core.config.NovaCoreProperties;
import com.starlwr.bot.core.datasource.AbstractDataSource;
import com.starlwr.bot.core.enums.PushTargetType;
import com.starlwr.bot.core.event.remote.NovaRemoteMessageEvent;
import com.starlwr.bot.core.model.Message;
import com.starlwr.bot.core.model.PushTarget;
import com.starlwr.bot.core.model.PushUser;
import com.starlwr.bot.core.sender.NovaMessageSender;
import com.starlwr.bot.core.service.LiveDataService;
import com.starlwr.bot.core.service.LiveSessionArchive;
import com.starlwr.bot.core.service.StarBotStateStore;
import com.starlwr.bot.core.timeline.TimelineWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
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
 * 时间由夹具里的时钟说了算：那两分钟的窗口若靠真等，这张表没人会跑第二遍，
 * 而不跑的表与跑过且通过的表在报告里长得一样。<b>命令冷却读的也是这一把钟</b>——
 * 两把钟的那一版里，凡是「等一会儿再说一句」的语料都会被冷却挡在门外，
 * 挡住之后机器人一个字都不说，而那与「这一句本就不该有回应」是同一种读数。
 */
@DisplayName("回序号选主播语料")
class BilibiliStreamerFollowUpCorpusTest {
    private static final String PLATFORM = "qq-onebot";

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
     * @param text @ 机器人之后的正文
     * @param says 回复里该出现的片段；以 {@code !} 开头表示必须不出现
     */
    private record Say(long sender, Duration after, String text, List<String> says) {
    }

    private static Say at(String text, String... says) {
        return new Say(ASKER, Duration.ZERO, text, List.of(says));
    }

    private static Say atAfter(Duration after, String text, String... says) {
        return new Say(ASKER, after, text, List.of(says));
    }

    private static Say atBy(long sender, String text, String... says) {
        return new Say(sender, Duration.ZERO, text, List.of(says));
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
                        at("直播间数据", "回序号选一位"),
                        at("2", "已出图：主播乙")),

                corpus("回「全部」—— 展开完整清单，序号照旧管用",
                        at("直播间数据", "还有 3 位，回「全部」展开"),
                        at("全部", "8. 主播辛（10008）", "!还有 3 位"),
                        at("8", "已出图：主播辛")),

                corpus("序号超出范围 —— 不当序号看，那次追问也不该被它用掉",
                        at("直播间数据", "回序号选一位"),
                        at("99", "!已出图"),
                        at("2", "已出图：主播乙")),

                corpus("回过序号之后再省掉参数 —— 还是问一遍，不替人记上次那位",
                        at("直播间数据", "回序号选一位"),
                        at("2", "已出图：主播乙"),
                        atAfter(Duration.ofSeconds(5), "直播间数据", "回序号选一位", "!已出图")),

                corpus("同一次追问只认一个答案 —— 选过之后再回一个序号不再照办",
                        at("直播间数据", "回序号选一位"),
                        at("2", "已出图：主播乙"),
                        at("3", "!已出图：主播丙")),

                corpus("没问过就回数字 —— 当成认不出的消息，回菜单",
                        at("2", "用法：", "!已出图")),

                corpus("答的是别人那份追问 —— 不算数，问的那个人自己回才算",
                        at("直播间数据", "回序号选一位"),
                        atBy(OTHER, "2", "!已出图"),
                        at("2", "已出图：主播乙")),

                corpus("差一点满两分钟 —— 还算数",
                        at("直播间数据", "回序号选一位"),
                        atAfter(Duration.ofSeconds(119), "2", "已出图：主播乙")),

                corpus("过了两分钟 —— 作废，不照着办",
                        at("直播间数据", "回序号选一位"),
                        atAfter(Duration.ofSeconds(121), "2", "!已出图", "用法：")),

                // 上一行只说了「作废」，而作废之后<b>还问不问得出来</b>是另一件事：
                // 追问表里若留着一份过期的记录不清，第二次问会被它顶掉，现象是「机器人不理我了」。
                // 这一行要走完 121 秒＋命令冷却两道时间，靠真等没人会跑第二遍——
                // 分发器与追问因此共用同一把推着走的时钟
                corpus("过期之后重新问 —— 清单照样问得出来",
                        at("直播间数据", "回序号选一位"),
                        atAfter(Duration.ofSeconds(121), "2", "!已出图"),
                        atAfter(Duration.ofSeconds(5), "直播间数据", "回序号选一位")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("corpus")
    void replays(Corpus corpus) {
        Fixture fixture = new Fixture();

        for (Say line : corpus.dialogue()) {
            String said = fixture.feed(line.sender(), line.after(), line.text());

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

    @Test
    @DisplayName("重跑时把选中那位接在原参数末尾 —— 榜单与页码都还在")
    void rerunCarriesTheChoiceAsTheLastArgument() {
        // 上面那张表走的都是不带参数的命令，量不到这一条：选择既然不再存在任何地方，
        // 它只能跟着这一次重跑走，而带参数的命令（「数据排行榜 礼物 2」）里，
        // 放错位置就会把榜单名挤走——现象是回一句「请指明要看哪张榜」，与追问丢了长得一样
        NovaCoreProperties properties = new NovaCoreProperties();
        properties.getLive().setLiveDataPath(dataDir.resolve("data.json").toString());

        LiveDataService liveDataService = mock(LiveDataService.class);
        when(liveDataService.getLiveStatus(anyString(), anyLong())).thenReturn(Optional.of(false));
        when(liveDataService.getLiveEndTime(anyString(), anyLong())).thenReturn(Optional.empty());

        BilibiliStreamerChoice choice = new BilibiliStreamerChoice(liveDataService,
                new LiveSessionArchive(properties), new Ticking());

        List<PushUser> candidates = new ArrayList<>();
        for (int index = 1; index <= NAMES.size(); index++) {
            candidates.add(streamer(index));
        }
        choice.ask(new CommandContext(PLATFORM, PushTargetType.GROUP, GROUP, ASKER,
                "数据排行榜", List.of("礼物", "2"), "数据排行榜 礼物 2"), choice.rank(candidates));

        CommandFollowUp.Claimed claimed = choice.claim(new CommandContext(PLATFORM, PushTargetType.GROUP,
                GROUP, ASKER, "3", List.of(), "3"));

        assertEquals("数据排行榜", claimed.command());
        // 「数据排行榜」按「三位以内的纯数字算页码、更长的算 uid」认参数，
        // 因此接在末尾的这一串走的与他自己打 uid 那一次是同一条路（见该命令自己的那把尺）
        assertEquals(List.of("礼物", "2", "10003"), claimed.args());
    }

    /**
     * 一次回放用的整套零件
     * <p>
     * 每行语料新建一份：追问按会话与人记着，共用一份的话，前一行的痕迹会落到下一行头上。
     */
    private static class Fixture {
        private final List<String> replies = new ArrayList<>();

        private final CommandDispatcher dispatcher;

        private final Ticking clock = new Ticking();

        Fixture() {
            List<PushUser> users = new ArrayList<>();
            for (int index = 1; index <= NAMES.size(); index++) {
                users.add(streamer(index));
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

            CommandSettingsService settings = new CommandSettingsService(new StarBotStateStore(properties));
            BilibiliStreamerChoice choice =
                    new BilibiliStreamerChoice(liveDataService, new LiveSessionArchive(properties), clock);

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
        }

        /**
         * 群里 @ 机器人说一句，返回机器人说了什么
         */
        String feed(long sender, Duration after, String text) {
            clock.advance(after);
            replies.clear();
            dispatcher.onRemoteMessage(new NovaRemoteMessageEvent(PLATFORM, "group", GROUP, sender,
                    text, "member", true));
            return String.join("\n", replies);
        }

    }

    private static PushUser streamer(int index) {
        PushUser user = new PushUser();
        user.setUid(10000L + index);
        user.setUname(NAMES.get(index - 1));
        user.setPlatform("bilibili");

        PushTarget target = new PushTarget();
        target.setPlatform(PLATFORM);
        target.setType(PushTargetType.GROUP);
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
            return "直播间数据";
        }

        @Override
        public String description() {
            return "供语料回放使用";
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
