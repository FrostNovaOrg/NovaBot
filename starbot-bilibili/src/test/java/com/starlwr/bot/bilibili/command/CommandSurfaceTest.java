package com.starlwr.bot.bilibili.command;

import com.starlwr.bot.core.command.CommandDispatcher;
import com.starlwr.bot.core.command.CommandSettingsService;
import com.starlwr.bot.core.command.StarBotCommand;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.datasource.AbstractDataSource;
import com.starlwr.bot.core.enums.PushTargetType;
import com.starlwr.bot.core.event.remote.StarBotRemoteMessageEvent;
import com.starlwr.bot.core.model.Message;
import com.starlwr.bot.core.model.PushTarget;
import com.starlwr.bot.core.model.PushUser;
import com.starlwr.bot.core.sender.StarBotMessageSender;
import com.starlwr.bot.core.service.LiveDataService;
import com.starlwr.bot.core.service.StarBotStateStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 命令面普查
 * <p>
 * 「一共有哪几条命令、各自在哪种会话里能用、菜单列几条」这三问，只有在<b>装齐了核心与本插件</b>的
 * 前提下才有答案：核心自带三条，其余随插件进来。因此这把尺放在本模块——它是同时看得见两边的地方。
 * <p>
 * 命令清单<b>当场从类路径上算</b>，不手抄：手抄的名单会给后来新增的命令签一张免检票——
 * 「不在名单里」与「查过了」在报告里长得一样。取的是 Spring 真正认的那条判据：
 * 类上有没有 {@code @Component}（{@code @StarBotComponent} 是它的元注解）。
 * <p>
 * 命令实例是拿真类构造出来的，依赖换成替身；分发器、菜单、命令开关都用真件。
 * 于是「发一句话过去，机器人说了什么」这一问，答的与线上是同一套代码。
 */
@DisplayName("命令面")
class CommandSurfaceTest {
    private static final String PLATFORM = "qq-onebot";

    private static final long GROUP = 30003L;

    private static final long FRIEND = 20001L;

    /**
     * 全部已注册命令，及其可用的会话范围（true 为仅限群聊）
     * <p>
     * 这张表两个方向都对：多一条、少一条、某条的范围改了，都会红。新增命令必须来这里添一行，
     * 顺带回答「私聊能不能用」——不写就没法过，而不是默默跟着默认值走。
     */
    private static final Map<String, Boolean> GROUP_ONLY = Map.ofEntries(
            // 命令管理
            Map.entry("菜单", false),
            Map.entry("启用命令", true),
            Map.entry("禁用命令", true),
            // 提醒订阅：@ 谁只在群里才有意义
            Map.entry("开播@我", true),
            Map.entry("取消开播@我", true),
            Map.entry("开播@名单", true),
            Map.entry("动态@我", true),
            Map.entry("取消动态@我", true),
            Map.entry("动态@名单", true),
            // 数据查询：已配推送的好友会话里照样能查
            Map.entry("直播报告", false),
            Map.entry("直播间数据", false),
            Map.entry("直播间总数据", false),
            Map.entry("数据排行榜", false),
            Map.entry("总数据排行榜", false));

    /**
     * 已停用的账号绑定族
     * <p>
     * 它们不再是命令，因此收到时走的是「认不出」那条路：回菜单。
     */
    private static final List<String> RETIRED = List.of("绑定", "确认绑定", "解绑", "我的数据", "我的总数据");

    /**
     * 依赖累计存储的两条
     */
    private static final List<String> TOTAL_ONLY = List.of("直播间总数据", "总数据排行榜");

    private static final String TOTAL_OFF_REPLY = "本机没开累计数据，只能查本场。发「直播间数据」看本场。";

    private Registry registry;

    @BeforeEach
    void setUp() {
        registry = new Registry();
    }

    @Test
    @DisplayName("已注册的命令与清单逐条对得上，多一条少一条都算")
    void registryMatchesTheList() {
        assertEquals(GROUP_ONLY.keySet().stream().sorted().toList(),
                registry.commands.stream().map(StarBotCommand::name).sorted().toList());
    }

    @Test
    @DisplayName("每条命令的会话范围逐条对表")
    void sessionScopeMatchesTheList() {
        Map<String, Boolean> actual = new TreeMap<>();
        for (StarBotCommand command : registry.commands) {
            actual.put(command.name(), command.groupOnly());
        }

        assertEquals(new TreeMap<>(GROUP_ONLY), actual);
    }

    @Test
    @DisplayName("账号绑定族五条不再是命令：群里 @ 与私聊都回菜单")
    void retiredNamesFallThroughToMenu() {
        for (String name : RETIRED) {
            assertTrue(registry.feed(true, name).startsWith("用法："), "群里 @ 发「" + name + "」");
            assertTrue(registry.feed(false, name).startsWith("用法："), "私聊发「" + name + "」");
        }
    }

    @Test
    @DisplayName("菜单里没有绑定族，也没有「账号绑定」这一类")
    void menuOmitsRetiredNames() {
        String menu = registry.feed(true, "菜单");

        assertFalse(menu.contains("【账号绑定】"), menu);
        for (String name : RETIRED) {
            assertFalse(menu.contains("\n" + name + " "), name + " 仍在菜单里：" + menu);
        }
    }

    @Test
    @DisplayName("菜单条数：群聊与私聊各列同样十四条")
    void menuListsFourteenInBothKinds() {
        assertEquals(GROUP_ONLY.size(), entryCount(registry.feed(true, "菜单")));
        assertEquals(GROUP_ONLY.size(), entryCount(registry.feed(false, "菜单")));
    }

    @Test
    @DisplayName("累计没开：菜单藏掉两条「总」字命令")
    void menuHidesTotalCommandsWhenUnsupported() {
        registry.supportsTotalData(false);

        String menu = registry.feed(true, "菜单");

        for (String name : TOTAL_ONLY) {
            assertFalse(menu.contains("\n" + name + " "), name + " 不该出现：" + menu);
        }
        assertEquals(GROUP_ONLY.size() - TOTAL_ONLY.size(), entryCount(menu));
    }

    @Test
    @DisplayName("累计开着：两条照列，菜单回到十四条")
    void menuKeepsTotalCommandsWhenSupported() {
        registry.supportsTotalData(true);

        String menu = registry.feed(true, "菜单");

        for (String name : TOTAL_ONLY) {
            assertTrue(menu.contains("\n" + name + " "), name + " 该出现：" + menu);
        }
        assertEquals(GROUP_ONLY.size(), entryCount(menu));
    }

    @Test
    @DisplayName("累计没开时还是有人发了：回一句人话，并记一行日志")
    void repliesAndLogsWhenTotalUnsupported() {
        registry.supportsTotalData(false);

        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(BilibiliScopedDataCommand.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            for (String name : TOTAL_ONLY) {
                assertEquals(TOTAL_OFF_REPLY, registry.feed(true, name), name);
            }
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        List<String> lines = appender.list.stream()
                .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                .filter(line -> line.contains("累计"))
                .toList();
        assertEquals(TOTAL_ONLY.size(), lines.size(), "每被请求一次记一行: " + appender.list);
    }

    @Test
    @DisplayName("累计开着时不该再回「没开」")
    void doesNotRefuseWhenTotalSupported() {
        registry.supportsTotalData(true);

        for (String name : TOTAL_ONLY) {
            assertFalse(registry.feed(true, name).contains("没开累计数据"), name);
        }
    }

    /**
     * 菜单里的命令条数：每条命令占一行，行内以「 — 」分隔命令与说明
     */
    private int entryCount(String menu) {
        return (int) Arrays.stream(menu.split("\n")).filter(line -> line.contains(" — ")).count();
    }

    /**
     * 装齐核心与本插件后的一整套命令
     * <p>
     * 命令清单从类路径上现算，实例用真构造方法造、依赖给替身；同类型的依赖<b>共用一份替身</b>，
     * 否则「把累计存储关掉」只会关掉其中一条命令看到的那一份。
     */
    private static class Registry {
        private final List<String> replies = new ArrayList<>();

        private final Map<Class<?>, Object> dependencies = new LinkedHashMap<>();

        private final CommandSettingsService settings =
                new CommandSettingsService(new StarBotStateStore(new StarBotCoreProperties()));

        private final AtomicReference<CommandDispatcher> current = new AtomicReference<>();

        private final List<StarBotCommand> commands = new ArrayList<>();

        private final AbstractDataSource dataSource = mock(AbstractDataSource.class);

        private final LiveDataService liveDataService = mock(LiveDataService.class);

        private final StarBotMessageSender sender = mock(StarBotMessageSender.class);

        private final ObjectProvider<StarBotCommand> provider = provider();

        Registry() {
            // 默认按「累计存储配好了」起：那是命令齐全的那一档，各用例要试没配的情形自己关掉
            when(liveDataService.supportsTotalData()).thenReturn(true);

            PushUser streamer = configuredUser();
            when(dataSource.getAllUsers()).thenReturn(List.of(streamer));
            when(dataSource.getUsers("bilibili")).thenReturn(List.of(streamer));
            doAnswer(invocation -> replies.add(((Message) invocation.getArgument(0)).getContent()))
                    .when(sender).send(any());

            @SuppressWarnings("unchecked")
            ObjectProvider<CommandDispatcher> self = mock(ObjectProvider.class);
            when(self.getIfAvailable()).thenAnswer(invocation -> current.get());

            dependencies.put(AbstractDataSource.class, dataSource);
            dependencies.put(LiveDataService.class, liveDataService);
            dependencies.put(CommandSettingsService.class, settings);
            dependencies.put(ObjectProvider.class, self);

            for (Class<?> type : scan()) {
                commands.add(instantiate(type));
            }
        }

        void supportsTotalData(boolean supported) {
            when(liveDataService.supportsTotalData()).thenReturn(supported);
        }

        /**
         * 喂一条消息，返回机器人说的话，一个字没说时为空串
         * <p>
         * 每次现造一个分发器：命令冷却按会话记在分发器里，复用一个的话，第二条起全被冷却挡住，
         * 而「被冷却挡住」与「本就不应答」在回复里长得一样。
         */
        String feed(boolean group, String text) {
            replies.clear();
            CommandDispatcher dispatcher = new CommandDispatcher(provider, settings, dataSource, sender,
                    new StarBotCoreProperties());
            current.set(dispatcher);

            dispatcher.onRemoteMessage(new StarBotRemoteMessageEvent(PLATFORM, group ? "group" : "private",
                    group ? GROUP : FRIEND, 2000000002L, text, group ? "member" : null, group));

            return String.join("\n", replies);
        }

        /**
         * 类路径上全部被注册为 Bean 的命令实现
         */
        private List<Class<?>> scan() {
            ClassPathScanningCandidateComponentProvider scanner =
                    new ClassPathScanningCandidateComponentProvider(true);
            List<Class<?>> types = new ArrayList<>();
            for (BeanDefinition definition : scanner.findCandidateComponents("com.starlwr.bot")) {
                Class<?> type = ClassUtils.resolveClassName(definition.getBeanClassName(), null);
                if (StarBotCommand.class.isAssignableFrom(type)) {
                    types.add(type);
                }
            }
            return types;
        }

        /**
         * 用真构造方法造一个命令，参数取共用替身
         */
        private StarBotCommand instantiate(Class<?> type) {
            Constructor<?> chosen = null;
            for (Constructor<?> candidate : type.getDeclaredConstructors()) {
                if (candidate.isAnnotationPresent(Autowired.class)) {
                    chosen = candidate;
                    break;
                }
                if (chosen == null || candidate.getParameterCount() > chosen.getParameterCount()) {
                    chosen = candidate;
                }
            }

            Object[] args = Arrays.stream(chosen.getParameterTypes())
                    .map(parameter -> dependencies.computeIfAbsent(parameter, Registry::stub))
                    .toArray();
            try {
                chosen.setAccessible(true);
                return (StarBotCommand) chosen.newInstance(args);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("造不出命令 " + type.getName(), e);
            }
        }

        private static Object stub(Class<?> type) {
            return mock(type);
        }

        private ObjectProvider<StarBotCommand> provider() {
            @SuppressWarnings("unchecked")
            ObjectProvider<StarBotCommand> mocked = mock(ObjectProvider.class);
            when(mocked.iterator()).thenAnswer(invocation -> new ArrayList<>(commands).iterator());
            when(mocked.orderedStream()).thenAnswer(invocation -> new ArrayList<>(commands).stream());
            return mocked;
        }

        /**
         * 一个把推送同时配到测试群与测试好友的主播
         */
        private PushUser configuredUser() {
            PushUser user = new PushUser();
            user.setUid(10001L);
            user.setUname("测试主播");
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
}
