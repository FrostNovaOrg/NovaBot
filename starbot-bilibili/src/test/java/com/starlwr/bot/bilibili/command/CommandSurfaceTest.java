package com.starlwr.bot.bilibili.command;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.command.CommandDispatcher;
import com.starlwr.bot.core.command.CommandFollowUp;
import com.starlwr.bot.core.command.CommandSettingsService;
import com.starlwr.bot.core.command.StarBotCommand;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.datasource.AbstractDataSource;
import com.starlwr.bot.core.enums.PushTargetType;
import com.starlwr.bot.core.event.remote.StarBotRemoteMessageEvent;
import com.starlwr.bot.core.model.Message;
import com.starlwr.bot.core.model.PushMessage;
import com.starlwr.bot.core.model.PushTarget;
import com.starlwr.bot.core.model.PushUser;
import com.starlwr.bot.core.sender.AtMode;
import com.starlwr.bot.core.sender.StarBotMessageSender;
import com.starlwr.bot.core.service.AtSubscriptionService;
import com.starlwr.bot.core.service.CompositeLiveDataService;
import com.starlwr.bot.core.service.DefaultLiveDataService;
import com.starlwr.bot.core.service.LiveDataService;
import com.starlwr.bot.core.service.StarBotStateStore;
import com.starlwr.bot.core.service.TotalDataStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

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
     * 发命令的那个人
     */
    private static final long SENDER = 2000000002L;

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

    /**
     * 开播那一类的三条订阅命令，与动态那一类的三条
     */
    private static final List<String> LIVE_AT_COMMANDS = List.of("开播@我", "取消开播@我", "开播@名单");

    private static final List<String> DYNAMIC_AT_COMMANDS = List.of("动态@我", "取消动态@我", "动态@名单");

    private static final String AT_ALL_REPLY = "本群开播通知会 @全体成员，不用单独订阅";

    private static final String AT_ALL_NOTE = "本群开播通知会先 @全体成员";

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
    @DisplayName("菜单条数：群聊十四条，私聊只列能在私聊用的六条")
    void menuListsFourteenInGroupAndSixInPrivate() {
        String groupMenu = registry.feed(true, "菜单");
        String friendMenu = registry.feed(false, "菜单");
        List<String> privateOk = GROUP_ONLY.entrySet().stream()
                .filter(entry -> !entry.getValue())
                .map(Map.Entry::getKey)
                .sorted()
                .toList();

        assertEquals(GROUP_ONLY.size(), entryCount(groupMenu));
        assertEquals(6, privateOk.size());
        assertEquals(privateOk.size(), entryCount(friendMenu), friendMenu);
        for (String name : privateOk) {
            assertTrue(friendMenu.contains("\n" + name + " "), name + " 该出现在私聊菜单：" + friendMenu);
        }
        for (Map.Entry<String, Boolean> entry : GROUP_ONLY.entrySet()) {
            if (entry.getValue()) {
                assertFalse(friendMenu.contains("\n" + entry.getKey() + " "),
                        entry.getKey() + " 不该出现在私聊菜单：" + friendMenu);
            }
        }
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
    @DisplayName("⚠️ 累计存储在运行中连上、掉线、连回来，菜单当场跟着变——中间一次没重启")
    void menuFollowsRuntimeStorage() {
        AtomicBoolean up = new AtomicBoolean(true);
        AtomicLong clock = new AtomicLong(1_000_000L);
        TotalDataStorage storage = new TotalDataStorage(
                TotalDataStorage.Settings.UNSET, settings -> redisFactory(up), clock::get);
        // 这一份 Registry 背后是真的判定链：菜单 ← 命令的 available() ← supportsTotalData()
        // ← 累计存储的探活。上面那几条用替身量的是链条的前半截，这一条把后半截接上
        Registry runtime = new Registry(new CompositeLiveDataService(
                new DefaultLiveDataService(new StarBotCoreProperties()), storage));

        assertEquals(GROUP_ONLY.size() - TOTAL_ONLY.size(), entryCount(runtime.feed(true, "菜单")),
                "还没配累计存储，那两条不该列");

        storage.applyHost("127.0.0.1");
        assertEquals(GROUP_ONLY.size(), entryCount(runtime.feed(true, "菜单")),
                "运行中配好了，那两条当场就该出现——此前这里要等一次重启");

        up.set(false);
        clock.addAndGet(TotalDataStorage.PROBE_CACHE_MILLIS + 1);
        assertEquals(GROUP_ONLY.size() - TOTAL_ONLY.size(), entryCount(runtime.feed(true, "菜单")),
                "连不上还照列，点进去查到的会是一片 0");

        up.set(true);
        clock.addAndGet(TotalDataStorage.PROBE_CACHE_MILLIS + 1);
        assertEquals(GROUP_ONLY.size(), entryCount(runtime.feed(true, "菜单")),
                "连回来之后该自己恢复");
    }

    /**
     * 假 Redis 连接工厂：{@code up} 为假时连不上
     * <p>
     * 不装 Redis、不引嵌入式实现——「连不上」这一档在真 Redis 上要量得先把它杀掉。
     */
    private static RedisConnectionFactory redisFactory(AtomicBoolean up) {
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        RedisConnection connection = mock(RedisConnection.class);
        when(factory.getConnection()).thenAnswer(invocation -> {
            if (!up.get()) {
                throw new IllegalStateException("连不上");
            }
            return connection;
        });
        return factory;
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
    @DisplayName("累计没开：「启用命令／禁用命令」都认不到那两条，状态里不会多出一条关不掉的记录")
    void toggleRefusesUnavailableCommands() {
        registry.supportsTotalData(false);

        // 两个方向都要量：它们是同一个基类的两头，只量一头的话，另一头漏改了没有任何现象
        for (String toggle : List.of("启用命令", "禁用命令")) {
            for (String name : TOTAL_ONLY) {
                String said = registry.feed(true, toggle + " " + name, "owner");

                assertTrue(said.contains("没开"), toggle + " " + name + " 该回一句「本机没开」，实际说了：" + said);
                assertFalse(said.contains("已启用") || said.contains("已禁用"),
                        toggle + " " + name + " 竟然真办了：" + said);
                assertFalse(registry.settings.isDisabled(PLATFORM, GROUP, name),
                        name + " 进了命令开关表：菜单里本就没有它，这条记录谁也看不见、也关不回来");
            }
        }
    }

    @Test
    @DisplayName("阴性：累计开着时那两条照常开关")
    void toggleAcceptsAvailableCommands() {
        registry.supportsTotalData(true);

        for (String name : TOTAL_ONLY) {
            assertEquals("已禁用「" + name + "」", registry.feed(true, "禁用命令 " + name, "owner"), name);
            assertTrue(registry.settings.isDisabled(PLATFORM, GROUP, name), name);

            assertEquals("已启用「" + name + "」", registry.feed(true, "启用命令 " + name, "owner"), name);
            assertFalse(registry.settings.isDisabled(PLATFORM, GROUP, name), name);
        }
    }

    @Test
    @DisplayName("名字压根不存在的那一路照旧：说的是「没有这条命令」，不是「没开」")
    void toggleStillSaysNoSuchCommand() {
        String said = registry.feed(true, "禁用命令 并不存在的命令", "owner");

        assertTrue(said.contains("没有名为"), said);
        assertFalse(said.contains("没开"), "两种情形要分得开：一个是打错了名字，一个是能力没配好。" + said);
    }

    @Test
    @DisplayName("累计开着时不该再回「没开」")
    void doesNotRefuseWhenTotalSupported() {
        registry.supportsTotalData(true);

        for (String name : TOTAL_ONLY) {
            assertFalse(registry.feed(true, name).contains("没开累计数据"), name);
        }
    }

    @Test
    @DisplayName("开关与菜单同一问：三种盘面下，菜单列几条，开关就动得了哪几条")
    void toggleAgreesWithMenu() {
        // 两处口径不同源时，现象是「菜单里没有这条命令，它却开关得动」——
        // 开关表里于是躺着一条谁也验证不了的记录。三种盘面各量一遍，
        // 因为两处答案的分歧只在<b>某一条被藏起来</b>的那些盘面上才显形
        assertEquals(GROUP_ONLY.size(), menuNames().size(), "默认盘面该是十四条都列得出");
        assertEquals(menuNames(), toggleFinds(), "默认盘面");

        registry.supportsTotalData(false);
        assertEquals(GROUP_ONLY.size() - TOTAL_ONLY.size(), menuNames().size(), "本机没开累计数据");
        assertEquals(menuNames(), toggleFinds(), "本机没开累计数据");

        registry.supportsTotalData(true);
        registry.atMode(BilibiliAtNoticeKind.LIVE, AtMode.ALL);
        assertEquals(GROUP_ONLY.size() - LIVE_AT_COMMANDS.size(), menuNames().size(), "本群配成 @全体成员");
        assertEquals(menuNames(), toggleFinds(), "本群配成 @全体成员");
    }

    @Test
    @DisplayName("「在哪儿」两句同一处取词：群里都说「本群」，私聊都说「这里」")
    void bothSentencesNameTheSamePlace() {
        // 「说不出主播」与「这条命令被关掉了」出自两个模块，措辞却回答同一个问题。
        // 各写各的字面量时，改了一处的那天，使用者在私聊里会同时听见「这里」和什么都不带
        for (boolean group : List.of(true, false)) {
            long num = group ? GROUP : FRIEND;
            String word = group ? "本群" : "这里";

            String unknown = registry.feed(group, "直播间数据 主播壬");

            registry.settings.disable(PLATFORM, num, "直播间数据");
            String disabled = registry.feed(group, "直播间数据");
            registry.settings.enable(PLATFORM, num, "直播间数据");

            assertTrue(unknown.startsWith(word), "说不出主播那一句：" + unknown);
            assertTrue(disabled.startsWith(word), "命令被关掉那一句：" + disabled);
        }
    }

    /**
     * 菜单列得出的命令名。每条命令占一行，行首就是命令名
     */
    private Set<String> menuNames() {
        return Arrays.stream(registry.feed(true, "菜单").split("\n"))
                .filter(line -> line.contains(" — "))
                .map(line -> line.split(" ")[0])
                .collect(Collectors.toCollection(TreeSet::new));
    }

    /**
     * 「禁用命令」找得到的命令名
     * <p>
     * 找不到的那一路回的是「不用开关它」——本机没开与本会话用不上都归它。
     * 量完随手把状态复原：真被关掉的那几条会从菜单里消失，而这张表下一次还要量菜单。
     */
    private Set<String> toggleFinds() {
        Set<String> found = new TreeSet<>();
        for (String name : GROUP_ONLY.keySet()) {
            if (!registry.feed(true, "禁用命令 " + name, "owner").contains("不用开关它")) {
                found.add(name);
            }
            registry.feed(true, "启用命令 " + name, "owner");
        }
        return found;
    }

    @Test
    @DisplayName("本群开播通知配成 @全体成员：菜单撤下开播那三条，动态三条照列")
    void menuHidesLiveSubscriptionCommandsWhenAtAll() {
        registry.atMode(BilibiliAtNoticeKind.LIVE, AtMode.ALL);

        String menu = registry.feed(true, "菜单");

        for (String name : LIVE_AT_COMMANDS) {
            assertFalse(menu.contains("\n" + name + " "), name + " 不该出现：" + menu);
        }
        // 动态那一类没配成 @全体成员，它的订阅照样有用——只藏该藏的那一类
        for (String name : DYNAMIC_AT_COMMANDS) {
            assertTrue(menu.contains("\n" + name + " "), name + " 该出现：" + menu);
        }
        assertEquals(GROUP_ONLY.size() - LIVE_AT_COMMANDS.size(), entryCount(menu));
    }

    @Test
    @DisplayName("配成 @全体成员 时三条还是发过来了：各回一句为什么")
    void repliesInsteadOfSubscribingWhenAtAll() {
        registry.atMode(BilibiliAtNoticeKind.LIVE, AtMode.ALL);

        for (String name : LIVE_AT_COMMANDS) {
            assertEquals(AT_ALL_REPLY, registry.feed(true, name), name);
        }
    }

    @Test
    @DisplayName("另外两档：三条都照列，十四条一条不少")
    void menuKeepsSubscriptionCommandsInOtherModes() {
        for (AtMode mode : List.of(AtMode.SUBSCRIBERS, AtMode.ALL_OR_SUBSCRIBERS)) {
            registry.atMode(BilibiliAtNoticeKind.LIVE, mode);

            String menu = registry.feed(true, "菜单");
            for (String name : LIVE_AT_COMMANDS) {
                assertTrue(menu.contains("\n" + name + " "), mode.key() + " 下 " + name + " 该出现：" + menu);
            }
            assertEquals(GROUP_ONLY.size(), entryCount(menu), mode.key());
        }
    }

    @Test
    @DisplayName("「@ 不成就 @ 订阅的人」那一档：三行各加一句说明，只 @ 订阅的人时没有")
    void menuNoteOnlyInFallbackMode() {
        registry.atMode(BilibiliAtNoticeKind.LIVE, AtMode.ALL_OR_SUBSCRIBERS);
        assertEquals(LIVE_AT_COMMANDS.size(), noteCount(registry.feed(true, "菜单")));

        registry.atMode(BilibiliAtNoticeKind.LIVE, AtMode.SUBSCRIBERS);
        assertEquals(0, noteCount(registry.feed(true, "菜单")));
    }

    @Test
    @DisplayName("一条这类推送都没配：不算「全都 @ 全体」，菜单照列十四条")
    void emptyChannelHidesNothing() {
        // 空表上「全都是 @全体成员」恒为真，而恒真的判据与真查过了在菜单上长得一样
        assertEquals(GROUP_ONLY.size(), entryCount(registry.feed(true, "菜单")));
        assertTrue(registry.feed(true, "开播@名单").contains("还没有人订阅"), "该照常执行");
    }

    @Test
    @DisplayName("切到 @全体成员 再切回来，订阅名单还在")
    void subscriptionSurvivesModeSwitch() {
        registry.atMode(BilibiliAtNoticeKind.LIVE, AtMode.SUBSCRIBERS);
        assertTrue(registry.feed(true, "开播@我").contains("会 @ 你"), "先订上");

        // 这一档里连「取消」都只回一句，于是名单不可能在这中间被谁清掉
        registry.atMode(BilibiliAtNoticeKind.LIVE, AtMode.ALL);
        assertEquals(AT_ALL_REPLY, registry.feed(true, "取消开播@我"));

        registry.atMode(BilibiliAtNoticeKind.LIVE, AtMode.SUBSCRIBERS);
        assertTrue(registry.feed(true, "开播@名单").contains(String.valueOf(SENDER)),
                "切回来之后名单该还在");
    }

    /**
     * 菜单里带着「@ 不成才按名单 @ 人」那句说明的行数
     */
    private int noteCount(String menu) {
        return (int) Arrays.stream(menu.split("\n")).filter(line -> line.contains(AT_ALL_NOTE)).count();
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

        private final LiveDataService liveDataService;

        /**
         * 累计存储是替身还是真件
         * <p>
         * 真件那一份不许再用 {@link #supportsTotalData(boolean)} 去拨——那等于绕开被测的那条路，
         * 把一条端到端的判据悄悄退化成替身判据。
         */
        private final boolean stubbed;

        private final StarBotMessageSender sender = mock(StarBotMessageSender.class);

        private final ObjectProvider<StarBotCommand> provider = provider();

        /**
         * 唯一那位主播，各用例要改它在本群的 @ 模式，因此留成字段
         */
        private final PushUser streamer = configuredUser();

        Registry() {
            this(null);
        }

        /**
         * @param liveData 直播数据服务，传空则用替身
         */
        Registry(LiveDataService liveData) {
            this.stubbed = liveData == null;
            this.liveDataService = stubbed ? mock(LiveDataService.class) : liveData;
            if (stubbed) {
                // 默认按「累计存储配好了」起：那是命令齐全的那一档，各用例要试没配的情形自己关掉
                when(liveDataService.supportsTotalData()).thenReturn(true);
            }

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
            // 订阅服务用真件：「切换模式之后名单还在不在」这一问，替身答不了
            dependencies.put(AtSubscriptionService.class,
                    new AtSubscriptionService(new StarBotStateStore(new StarBotCoreProperties())));

            for (Class<?> type : scan()) {
                commands.add(instantiate(type));
            }
        }

        void supportsTotalData(boolean supported) {
            if (!stubbed) {
                throw new IllegalStateException("这一份背后是真的累计存储，要改它得去动那一侧");
            }
            when(liveDataService.supportsTotalData()).thenReturn(supported);
        }

        /**
         * 把本群这一类通知的 @ 模式配成指定档
         * <p>
         * 只配群聊那个通道：私聊没有 @全体成员 这回事，配上去只会让别的用例的读数变得难解释。
         * 同一类先清后配，于是连着调两次量到的是后一次，而不是两次的并集。
         */
        void atMode(BilibiliAtNoticeKind kind, AtMode mode) {
            for (PushTarget target : streamer.getTargets()) {
                if (PushTargetType.GROUP != target.getType()) {
                    continue;
                }

                target.getMessages().removeIf(message -> kind.handlerName().equals(message.getHandler()));

                PushMessage message = new PushMessage();
                message.setHandler(kind.handlerName());
                message.setParamsJsonObject(new JSONObject().fluentPut(AtMode.PARAM_KEY, mode.key()));
                target.getMessages().add(message);
            }
        }

        /**
         * 喂一条消息，返回机器人说的话，一个字没说时为空串
         * <p>
         * 每次现造一个分发器：命令冷却按会话记在分发器里，复用一个的话，第二条起全被冷却挡住，
         * 而「被冷却挡住」与「本就不应答」在回复里长得一样。
         */
        String feed(boolean group, String text) {
            return feed(group, text, group ? "member" : null);
        }

        /**
         * 以指定群角色喂一条消息：管理命令要群主或管理员才动得了，
         * 拿普通成员去发只会量到那一句「仅群主…可用」
         */
        String feed(boolean group, String text, String role) {
            replies.clear();
            CommandDispatcher dispatcher = new CommandDispatcher(provider, noFollowUps(), settings, dataSource, sender,
                    new StarBotCoreProperties());
            current.set(dispatcher);

            dispatcher.onRemoteMessage(new StarBotRemoteMessageEvent(PLATFORM, group ? "group" : "private",
                    group ? GROUP : FRIEND, SENDER, text, role, group));

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

        /**
         * 没有任何追问认领方：本类量的是命令表面（有哪几条、私聊能不能用），与追问应答无关
         */
        private ObjectProvider<CommandFollowUp> noFollowUps() {
            @SuppressWarnings("unchecked")
            ObjectProvider<CommandFollowUp> mocked = mock(ObjectProvider.class);
            when(mocked.iterator()).thenAnswer(invocation -> List.<CommandFollowUp>of().iterator());
            return mocked;
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
