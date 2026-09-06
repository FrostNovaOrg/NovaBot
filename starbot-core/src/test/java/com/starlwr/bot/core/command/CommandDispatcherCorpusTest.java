package com.starlwr.bot.core.command;

import com.starlwr.bot.core.command.builtin.MenuCommand;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.datasource.AbstractDataSource;
import com.starlwr.bot.core.enums.PushTargetType;
import com.starlwr.bot.core.event.remote.StarBotRemoteMessageEvent;
import com.starlwr.bot.core.model.Message;
import com.starlwr.bot.core.model.PushTarget;
import com.starlwr.bot.core.model.PushUser;
import com.starlwr.bot.core.sender.StarBotMessageSender;
import com.starlwr.bot.core.service.StarBotStateStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 命令触发模型语料回放
 * <p>
 * 「什么算一条命令」这件事只有一种检验方式是靠得住的：把真实会发生的那些消息一条条喂进去，
 * 看机器人是说话还是闭嘴、说的是哪一句。分散在各个用例里的断言做不到这一点——
 * 每加一种触发形态就要新写一个方法，而<b>漏写的那一种与「测过且通过」在报告里长得一样</b>。
 * 因此这里把语料摆成一张表：一行一条消息序列，写明每条消息期望的结果，
 * 加一种形态就是加一行。
 * <p>
 * 结果只分五种，都是使用者真能看见的现象，不涉及任何内部状态：
 * 执行、回菜单、回「已关闭」、回「没权限」、一个字都不说。
 */
@DisplayName("命令触发模型语料")
class CommandDispatcherCorpusTest {
    private static final String PLATFORM = "qq-onebot";

    /**
     * 已配置推送的群
     */
    private static final long GROUP = 30003L;

    /**
     * 机器人在里面、但没配过推送的群
     */
    private static final long STRANGE_GROUP = 99999L;

    /**
     * 已配置推送的好友
     */
    private static final long FRIEND = 20001L;

    /**
     * 一条输入消息
     * @param group 是否群聊
     * @param num 会话号
     * @param text 命令正文（群聊时为去掉 @ 段后的内容）
     * @param mentionsBot 是否 @ 了机器人
     * @param role 发送者在群里的角色
     */
    private record Msg(boolean group, long num, String text, boolean mentionsBot, String role) {
        static Msg at(String text) {
            return new Msg(true, GROUP, text, true, "member");
        }

        static Msg plain(String text) {
            return new Msg(true, GROUP, text, false, "member");
        }

        static Msg atIn(long num, String text) {
            return new Msg(true, num, text, true, "member");
        }

        static Msg atAs(String text, String role) {
            return new Msg(true, GROUP, text, true, role);
        }

        static Msg dm(String text) {
            return new Msg(false, FRIEND, text, false, null);
        }
    }

    /**
     * 使用者看得见的结果
     */
    private enum Outcome {
        /**
         * 命令执行了
         */
        EXECUTED,
        /**
         * 回了菜单，且顶行是使用方法
         */
        MENU,
        /**
         * 回了「已关闭某命令」
         */
        DISABLED,
        /**
         * 回了「仅管理员可用」
         */
        DENIED,
        /**
         * 一个字都没说
         */
        SILENT
    }

    /**
     * 一条语料
     * @param name 这一行在说哪种情形
     * @param disabled 回放前先在该会话关掉的命令，无则为空
     * @param inputs 依次喂进去的消息
     * @param expected 每条消息期望的结果，与 inputs 一一对应
     */
    private record Corpus(String name, String disabled, List<Msg> inputs, List<Outcome> expected) {
        @Override
        public String toString() {
            return name;
        }
    }

    private static Corpus one(String name, Msg input, Outcome expected) {
        return new Corpus(name, null, List.of(input), List.of(expected));
    }

    /**
     * 一个没有任何认领方的追问 provider：本表量的是「什么算一条命令」，与追问应答无关
     */
    @SuppressWarnings("unchecked")
    static ObjectProvider<CommandFollowUp> noFollowUps() {
        ObjectProvider<CommandFollowUp> provider = mock(ObjectProvider.class);
        when(provider.iterator()).thenAnswer(invocation -> List.<CommandFollowUp>of().iterator());
        return provider;
    }

    private static Stream<Corpus> corpus() {
        return Stream.of(
                one("群里 @ 机器人加命令名 —— 执行", Msg.at("测试命令"), Outcome.EXECUTED),
                one("群里 @ 机器人加命令名与参数 —— 执行", Msg.at("测试命令 参数一 参数二"), Outcome.EXECUTED),
                one("群里 @ 机器人，命令名前后有多余空白 —— 执行", Msg.at("  测试命令  "), Outcome.EXECUTED),
                one("群里 @ 机器人加别名 —— 执行", Msg.at("别名"), Outcome.EXECUTED),
                one("群里没 @ 机器人，正文恰好是命令名 —— 沉默", Msg.plain("测试命令"), Outcome.SILENT),
                one("群里没 @ 机器人，随口聊天 —— 沉默", Msg.plain("今天天气不错"), Outcome.SILENT),
                one("群里 @ 机器人但不是命令 —— 回菜单", Msg.at("今天天气不错"), Outcome.MENU),
                one("群里 @ 机器人且正文为空 —— 回菜单", Msg.at(""), Outcome.MENU),
                one("群里 @ 机器人且正文只有空白 —— 回菜单", Msg.at("   "), Outcome.MENU),
                new Corpus("群里 @ 机器人，命令被本群关掉 —— 回已关闭", "测试命令",
                        List.of(Msg.at("测试命令")), List.of(Outcome.DISABLED)),
                one("没配过推送的群里 @ 机器人加命令名 —— 沉默", Msg.atIn(STRANGE_GROUP, "测试命令"), Outcome.SILENT),
                one("没配过推送的群里 @ 机器人说别的 —— 沉默", Msg.atIn(STRANGE_GROUP, "随便说说"), Outcome.SILENT),
                one("私聊直接发命令名 —— 执行", Msg.dm("私聊命令"), Outcome.EXECUTED),
                one("私聊发的不是命令 —— 回菜单", Msg.dm("在吗"), Outcome.MENU),
                one("私聊发仅限群聊的命令 —— 回菜单", Msg.dm("测试命令"), Outcome.MENU),
                new Corpus("私聊发仅限群聊的命令回菜单后连发闲话 —— 第二条沉默", null,
                        List.of(Msg.dm("测试命令"), Msg.dm("在吗")),
                        List.of(Outcome.MENU, Outcome.SILENT)),
                // 「菜单」是私聊里唯一一定要能直呼的命令：它顶行那句写的就是私聊怎么用。
                // 它若也仅限群聊，私聊里发对了名字反而沉默，而发错了倒能收到菜单
                one("私聊直呼「菜单」—— 回菜单", Msg.dm("菜单"), Outcome.MENU),
                one("私聊直呼菜单的别名 —— 回菜单", Msg.dm("帮助"), Outcome.MENU),
                one("群里 @ 机器人发「菜单」—— 回菜单", Msg.at("菜单"), Outcome.MENU),
                one("普通成员 @ 机器人发管理命令 —— 回没权限", Msg.at("管理命令"), Outcome.DENIED),
                one("群主 @ 机器人发管理命令 —— 执行", Msg.atAs("管理命令", "owner"), Outcome.EXECUTED),
                new Corpus("冷却期内连发两条命令 —— 第二条沉默", null,
                        List.of(Msg.at("测试命令"), Msg.at("测试命令")),
                        List.of(Outcome.EXECUTED, Outcome.SILENT)),
                new Corpus("冷却期内先执行命令再 @ 一句闲话 —— 菜单也被冷却挡住", null,
                        List.of(Msg.at("测试命令"), Msg.at("在吗")),
                        List.of(Outcome.EXECUTED, Outcome.SILENT)),
                new Corpus("冷却期内连发两条闲话 —— 菜单只回一次", null,
                        List.of(Msg.at("在吗"), Msg.at("还在吗")),
                        List.of(Outcome.MENU, Outcome.SILENT)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("corpus")
    void replays(Corpus corpus) {
        Fixture fixture = new Fixture();
        if (corpus.disabled() != null) {
            for (Msg input : corpus.inputs()) {
                fixture.settings.disable(PLATFORM, input.num(), corpus.disabled());
            }
        }

        List<Outcome> actual = new ArrayList<>();
        for (Msg input : corpus.inputs()) {
            actual.add(fixture.feed(input));
        }

        assertEquals(corpus.expected(), actual, corpus.name());
    }

    /**
     * 一次回放用的整套零件
     * <p>
     * 每条语料新建一份：命令冷却按会话记在分发器里，共用一份的话，前一条语料的冷却会落到下一条头上。
     */
    private static class Fixture {
        private final List<String> replies = new ArrayList<>();

        private final CommandSettingsService settings =
                new CommandSettingsService(new StarBotStateStore(new StarBotCoreProperties()));

        private final RecordingCommand groupCommand = new RecordingCommand("测试命令", List.of("别名"), true, false);

        private final RecordingCommand privateCommand = new RecordingCommand("私聊命令", List.of(), false, false);

        private final RecordingCommand adminCommand = new RecordingCommand("管理命令", List.of(), true, true);

        private final CommandDispatcher dispatcher;

        Fixture() {
            AbstractDataSource dataSource = mock(AbstractDataSource.class);
            when(dataSource.getAllUsers()).thenReturn(List.of(configuredUser()));

            StarBotMessageSender sender = mock(StarBotMessageSender.class);
            doAnswer(invocation -> replies.add(((Message) invocation.getArgument(0)).getContent()))
                    .when(sender).send(any());

            List<StarBotCommand> commands = new ArrayList<>();
            @SuppressWarnings("unchecked")
            ObjectProvider<StarBotCommand> provider = mock(ObjectProvider.class);
            when(provider.iterator()).thenAnswer(invocation -> new ArrayList<>(commands).iterator());
            when(provider.orderedStream()).thenAnswer(invocation -> new ArrayList<>(commands).stream());

            dispatcher = new CommandDispatcher(provider, noFollowUps(), settings, dataSource, sender, new StarBotCoreProperties());

            @SuppressWarnings("unchecked")
            ObjectProvider<CommandDispatcher> self = mock(ObjectProvider.class);
            when(self.getIfAvailable()).thenReturn(dispatcher);

            commands.add(new MenuCommand(self, settings));
            commands.add(groupCommand);
            commands.add(privateCommand);
            commands.add(adminCommand);
        }

        /**
         * 喂一条消息，返回使用者看得见的结果
         */
        Outcome feed(Msg input) {
            replies.clear();
            int before = groupCommand.executions + privateCommand.executions + adminCommand.executions;

            dispatcher.onRemoteMessage(new StarBotRemoteMessageEvent(PLATFORM,
                    input.group() ? "group" : "private", input.num(), 1L, input.text(),
                    input.role(), input.mentionsBot()));

            int executed = groupCommand.executions + privateCommand.executions + adminCommand.executions - before;
            String said = String.join("\n", replies);

            if (executed > 0) {
                return Outcome.EXECUTED;
            }
            if (said.isEmpty()) {
                return Outcome.SILENT;
            }
            if (said.startsWith("用法：")) {
                return Outcome.MENU;
            }
            if (said.contains("已关闭「")) {
                return Outcome.DISABLED;
            }
            if (said.contains("仅群主")) {
                return Outcome.DENIED;
            }
            throw new AssertionError("说了一句没见过的话: " + said);
        }

        /**
         * 一个把推送发到测试群与测试好友的推送用户
         */
        private PushUser configuredUser() {
            PushUser user = new PushUser();
            user.setUid(10001L);
            user.setUname("主播甲");
            user.setPlatform("bilibili");
            user.setTargets(List.of(target(PushTargetType.GROUP, GROUP), target(PushTargetType.FRIEND, FRIEND)));
            return user;
        }

        private PushTarget target(PushTargetType type, long num) {
            PushTarget target = new PushTarget();
            target.setPlatform(PLATFORM);
            target.setType(type);
            target.setNum(num);
            target.setMessages(new ArrayList<>());
            return target;
        }
    }

    /**
     * 只记录被调用过的测试命令
     */
    private static class RecordingCommand implements StarBotCommand {
        private final String name;
        private final List<String> aliases;
        private final boolean groupOnly;
        private final boolean adminOnly;
        private int executions;

        RecordingCommand(String name, List<String> aliases, boolean groupOnly, boolean adminOnly) {
            this.name = name;
            this.aliases = aliases;
            this.groupOnly = groupOnly;
            this.adminOnly = adminOnly;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public List<String> aliases() {
            return aliases;
        }

        @Override
        public String description() {
            return "供测试使用";
        }

        @Override
        public boolean groupOnly() {
            return groupOnly;
        }

        @Override
        public boolean requiresAdmin() {
            return adminOnly;
        }

        @Override
        public CommandReply execute(CommandContext context) {
            executions++;
            return CommandReply.of("已执行 " + String.join(" ", context.getArgs()));
        }
    }
}
