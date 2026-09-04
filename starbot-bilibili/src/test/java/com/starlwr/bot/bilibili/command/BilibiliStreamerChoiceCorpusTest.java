package com.starlwr.bot.bilibili.command;

import com.starlwr.bot.core.command.CommandContext;
import com.starlwr.bot.core.command.CommandReply;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.datasource.AbstractDataSource;
import com.starlwr.bot.core.enums.PushTargetType;
import com.starlwr.bot.core.model.PushTarget;
import com.starlwr.bot.core.model.PushUser;
import com.starlwr.bot.core.service.LiveDataService;
import com.starlwr.bot.core.service.LiveSessionArchive;
import com.starlwr.bot.core.service.StarBotStateStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 多主播「问是哪一位」语料回放
 * <p>
 * 「这条命令说的是哪位主播」这件事只有一种检验方式靠得住：把真实会发生的对话一句句喂进去，
 * 看机器人办了谁的事、还是反问了一句、反问的那句里都写了什么。分散在各个用例里的断言做不到——
 * 每加一种情形就要新写一个方法，而<b>漏写的那一种与「测过且通过」在报告里长得一样</b>。
 * 因此这里把情形摆成一张表：一行一段对话，加一种情形就是加一行。
 * <p>
 * 期望写成「这句话里该出现什么」，因为使用者看见的就是这句话本身：序号的顺序、括号里的 uid、
 * 折叠那一行的措辞，改了任何一处都该让这张表红。以 {@code !} 开头的期望表示
 * <b>这一段必须不出现</b>——「不该照着办」这件事没有别的写法，而它恰恰是最要紧的一半：
 * 认错了人，出来的是另一位主播的数据，<b>而那张图看起来完全正常</b>。
 */
@DisplayName("多主播问是哪一位语料")
class BilibiliStreamerChoiceCorpusTest {
    private static final String PLATFORM = "qq-onebot";

    /**
     * 「记住的选择」曾经用过的那个命名空间
     * <p>
     * <b>逐字写死在这里，不从被测那边取</b>：被测里已经没有这个名字了，
     * 从它那儿取等于让被测自己出题，删掉命名空间的同时判据也跟着失去了要找的东西。
     */
    private static final String NAMESPACE = "StreamerChoice";

    /**
     * 只为验这把尺看得见命名空间而写进去的一个
     */
    private static final String MARKER = "CorpusMarker";

    /**
     * 已配置推送的群
     */
    private static final long GROUP = 30003L;

    /**
     * 已配置推送的好友会话
     */
    private static final long FRIEND = 20001L;

    /**
     * 发命令的人
     */
    private static final long ASKER = 40001L;

    /**
     * 主播的展示名，按序号取用；uid 一律 10000 + 序号
     */
    private static final List<String> NAMES =
            List.of("主播甲", "主播乙", "主播丙", "主播丁", "主播戊", "主播己", "主播庚", "主播辛");

    /**
     * 场次归档所在目录。本表不写归档，只是不让它读到别处那一份
     */
    @TempDir
    static Path dataDir;

    /**
     * 一句话与它期望换来的回复
     *
     * @param sender 谁说的
     * @param text 命令参数，空串表示不带参数
     * @param says 回复里该出现的片段；以 {@code !} 开头表示必须不出现
     */
    private record Say(long sender, String text, List<String> says) {
    }

    private static Say say(String text, String... says) {
        return new Say(ASKER, text, List.of(says));
    }

    /**
     * 一行语料
     *
     * @param name 这一行在说哪种情形
     * @param type 会话类型，不写时为群聊
     * @param streamers 本会话配了几位主播，依次取 {@link #NAMES} 的前几个
     * @param living 其中正在直播的，序号从 1 起
     * @param playedToday 其中今天播过的，序号从 1 起
     * @param dialogue 依次发的话
     */
    private record Corpus(String name, PushTargetType type, int streamers, Set<Integer> living,
                          Set<Integer> playedToday, List<Say> dialogue) {
        @Override
        public String toString() {
            return name;
        }
    }

    private static Corpus corpus(String name, int streamers, Set<Integer> living, Set<Integer> playedToday,
                                 Say... dialogue) {
        return corpus(name, PushTargetType.GROUP, streamers, living, playedToday, dialogue);
    }

    /**
     * 指明会话类型的那几行：私聊与群聊在<b>话术</b>上不是同一份，
     * 而两者的差别只有把同一段对话在两种会话里各喂一遍才量得到
     */
    private static Corpus corpus(String name, PushTargetType type, int streamers, Set<Integer> living,
                                 Set<Integer> playedToday, Say... dialogue) {
        return new Corpus(name, type, streamers, living, playedToday, List.of(dialogue));
    }

    private static Set<Integer> none() {
        return Set.of();
    }

    private static Set<Integer> at(Integer... indexes) {
        return new LinkedHashSet<>(Arrays.asList(indexes));
    }

    private static Stream<Corpus> corpus() {
        return Stream.of(
                corpus("本群只配了一位、不带主播 —— 直接办", 1, none(), none(),
                        say("", "已出图：主播甲")),

                corpus("多位主播、只有一位在播、不带主播 —— 用在播那位，并说清用的是谁", 8, at(1), none(),
                        say("", "本次用的是：主播甲", "已出图：主播甲")),

                corpus("多位在播、不带主播 —— 回序号清单，在播的排前、今天播过的其次", 8, at(3, 5), at(2),
                        say("", "回序号选一位",
                                "1. 主播丙（10003）· 直播中",
                                "2. 主播戊（10005）· 直播中",
                                "3. 主播乙（10002）· 今天播过",
                                "还有 3 位，回「全部」展开",
                                "!已出图")),

                corpus("谁都没在播、今天也没人播过 —— 清单仍列得出来，其余折起", 8, none(), none(),
                        say("", "1. 主播甲（10001）",
                                "5. 主播戊（10005）",
                                "还有 3 位，回「全部」展开",
                                "!已出图")),

                corpus("只剩一位可折 —— 不折，直接列全", 6, none(), none(),
                        say("", "6. 主播己（10006）", "!回「全部」展开")),

                corpus("带昵称片段 —— 一次到位，不必反问", 8, none(), none(),
                        say("主播丙", "已出图：主播丙", "!回序号选一位")),

                corpus("带 uid —— 一次到位", 8, none(), none(),
                        say("10003", "已出图：主播丙")),

                corpus("选过一次，下一次仍旧问 —— 不替人记上次那位", 8, none(), none(),
                        say("主播丙", "已出图：主播丙"),
                        say("", "回序号选一位", "!已出图", "!本次用的是")),

                corpus("多位在播 —— 不猜，一律问", 8, at(1, 3), none(),
                        say("主播丙", "已出图：主播丙"),
                        say("", "回序号选一位", "!已出图")),

                corpus("本群一位主播都没配 —— 不认", 0, none(), none(),
                        say("", "本群没有配置任何哔哩哔哩主播的推送")),

                corpus("点名一位本群没配过的主播 —— 不认，并列出可选", 8, none(), none(),
                        say("主播壬", "本群没有配置「主播壬」的推送", "!已出图")),

                // 下面两行与上面两行是同一段对话换了个会话类型：私聊里没有「本群」这回事，
                // 说了的话，问的人会去群里找一个并不存在的配置。上面两行是它们的阴性对照
                corpus("私聊里一位主播都没配 —— 不认，且不说「本群」", PushTargetType.FRIEND, 0, none(), none(),
                        say("", "没有配置任何哔哩哔哩主播的推送", "!本群")),

                corpus("私聊里点名一位没配过的主播 —— 不认，同样不说「本群」", PushTargetType.FRIEND, 8,
                        none(), none(),
                        say("主播壬", "没有配置「主播壬」的推送", "!本群", "!已出图")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("corpus")
    void replays(Corpus corpus) {
        Fixture fixture = new Fixture(corpus.type(), corpus.streamers(), corpus.living(), corpus.playedToday());

        for (Say line : corpus.dialogue()) {
            String said = fixture.feed(line.sender(), line.text());

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
    @DisplayName("选过之后盘上不留痕 —— 运行状态里没有这个命名空间")
    void leavesNothingOnDisk(@TempDir Path stateDir) {
        StarBotCoreProperties properties = new StarBotCoreProperties();
        properties.getLive().setLiveDataPath(stateDir.resolve("data.json").toString());
        StarBotStateStore store = new StarBotStateStore(properties);

        AbstractDataSource dataSource = mock(AbstractDataSource.class);
        when(dataSource.getUsers("bilibili")).thenReturn(List.of(streamer(1), streamer(3)));

        LiveDataService liveDataService = mock(LiveDataService.class);
        when(liveDataService.getLiveStatus(anyString(), anyLong())).thenReturn(Optional.of(false));
        when(liveDataService.getLiveEndTime(anyString(), anyLong())).thenReturn(Optional.empty());

        // 点名是最明确的一次「选择」：这一路若还往状态里写，写的就是它
        new ProbeCommand(dataSource, new BilibiliStreamerChoice(liveDataService,
                new LiveSessionArchive(properties)))
                .execute(new CommandContext(PLATFORM, PushTargetType.GROUP, GROUP, ASKER,
                        "直播间数据", List.of("主播丙"), "直播间数据 主播丙"));

        // 阴性对照：这把尺看得见落在文件里的命名空间。少了它，「文件里没有」与
        // 「压根没写出文件」「这个读法根本看不见命名空间」在报告里长得一样
        store.write(MARKER, data -> data.put("seen", 1));
        store.save();

        String saved = readString(stateDir.resolve("state.json"));
        assertTrue(saved.contains(MARKER), "这把尺连自己刚写进去的命名空间都看不见：" + saved);
        assertFalse(saved.contains(NAMESPACE), "选择被记到了盘上：" + saved);
    }

    private static String readString(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static PushUser streamer(int index) {
        return streamer(index, PushTargetType.GROUP);
    }

    private static PushUser streamer(int index, PushTargetType type) {
        PushUser user = new PushUser();
        user.setUid(10000L + index);
        user.setUname(NAMES.get(index - 1));
        user.setPlatform("bilibili");

        PushTarget target = new PushTarget();
        target.setPlatform(PLATFORM);
        target.setType(type);
        target.setNum(numOf(type));
        target.setMessages(new ArrayList<>());
        user.setTargets(List.of(target));
        return user;
    }

    /**
     * 会话号：群聊为群号，私聊为对方账号。两者取自互不相干的号段，不许共用一个值
     */
    private static long numOf(PushTargetType type) {
        return PushTargetType.GROUP == type ? GROUP : FRIEND;
    }

    /**
     * 一次回放用的整套零件
     * <p>
     * 每行语料新建一份：主播名单与在播状态逐行不同，共用一份的话，前一行的盘面会落到下一行头上。
     */
    private static class Fixture {
        private final BilibiliStreamerCommand command;

        private final PushTargetType type;

        Fixture(PushTargetType type, int streamers, Set<Integer> living, Set<Integer> playedToday) {
            this.type = type;

            List<PushUser> users = new ArrayList<>();
            for (int index = 1; index <= streamers; index++) {
                users.add(streamer(index, type));
            }

            AbstractDataSource dataSource = mock(AbstractDataSource.class);
            when(dataSource.getUsers("bilibili")).thenReturn(users);

            LiveDataService liveDataService = mock(LiveDataService.class);
            when(liveDataService.getLiveStatus(anyString(), anyLong())).thenAnswer(invocation ->
                    Optional.of(living.contains(indexOf(invocation.getArgument(1)))));
            // 「今天播过」有两处来源，这里喂的是「最近一场的结束时刻落在今天」那一处；
            // 另一处（场次归档）指向一个空目录，因此这张表量的是两者取并集之后的结果
            when(liveDataService.getLiveEndTime(anyString(), anyLong())).thenAnswer(invocation ->
                    playedToday.contains(indexOf(invocation.getArgument(1)))
                            ? Optional.of(todayStartMillis() + 3_600_000L)
                            : Optional.empty());

            StarBotCoreProperties properties = new StarBotCoreProperties();
            properties.getLive().setLiveDataPath(dataDir.resolve("data.json").toString());

            command = new ProbeCommand(dataSource,
                    new BilibiliStreamerChoice(liveDataService, new LiveSessionArchive(properties)));
        }

        /**
         * 发一句话，返回机器人说了什么
         */
        String feed(long sender, String text) {
            List<String> args = text.isEmpty() ? List.of() : List.of(text.trim().split("\\s+"));
            CommandReply reply = command.execute(new CommandContext(PLATFORM, type, numOf(type),
                    sender, command.name(), args, command.name() + " " + text));
            return reply == null || !reply.hasContent() ? "" : reply.content();
        }

        private static int indexOf(Object uid) {
            return (int) (((Long) uid) - 10000L);
        }

        private static long todayStartMillis() {
            ZoneId zone = ZoneId.of("Asia/Shanghai");
            return LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli();
        }
    }

    /**
     * 一条只报「用了哪位主播」的命令
     * <p>
     * 真正的数据查询命令要出图，而出图与「说的是哪位主播」无关；用它来量，量到的是画图那一段。
     * 这里只留解析这一步，回一句「已出图：某某」代替那张图。
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
