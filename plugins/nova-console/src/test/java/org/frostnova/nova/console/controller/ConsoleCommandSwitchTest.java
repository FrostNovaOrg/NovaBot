package org.frostnova.nova.console.controller;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.bilibili.command.BilibiliAtListCommand;
import org.frostnova.nova.bilibili.command.BilibiliStreamerChoice;
import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.core.command.CommandDispatcher;
import org.frostnova.nova.core.command.CommandFollowUp;
import org.frostnova.nova.core.command.CommandSettingsService;
import org.frostnova.nova.core.command.NovaCommand;
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
import org.frostnova.nova.core.service.NovaStateStore;
import org.frostnova.nova.core.service.RevenueVisibilityService;
import org.frostnova.nova.core.service.SessionMemberNames;
import org.frostnova.nova.core.timeline.TimelineWriter;
import org.frostnova.nova.report.command.BilibiliRankingCommand;
import org.frostnova.nova.report.command.BilibiliRoomDataCommand;
import org.frostnova.nova.report.painter.BilibiliDataQueryPainter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.lang.reflect.Constructor;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 控制台上的命令开关与群里的「禁用命令／启用命令」是<b>同一扇门的两面</b>：
 * 一面在浏览器里点，一面在群里喊，改的都是同一个会话里同一条命令的同一种用法。
 * 两面要是各记各的账，就会出现「控制台明明关了、群里照样回话」，
 * 以及反过来看不出「群里已经关了」这两类谁也查不出来的怪事。
 * <p>
 * 合并后一条命令底下分几种用法（本场／累计、开播名单／动态名单），因此这一面要按<b>用法</b>记账，
 * 而不是按命令正名：只认正名的话，关掉的只是本场那一半，带「总」的照通。
 * <p>
 * 这里跑的是真命令与真分发器，只把出网与存储换成替身：要量的是「点下去之后群里那句话还回不回」，
 * 替身答不了这一问。命令按构造参数的类型现配，不写死构造参数表——
 * 命令要接的东西会跟着改形状，而这条要量的事与它接的是几样东西无关。
 */
@DisplayName("控制台命令开关与群内禁用读写同一套键")
class ConsoleCommandSwitchTest {
    private static final String PLATFORM = "qq-onebot";

    private static final long GROUP = 30003L;

    private static final long SENDER = 2000000002L;

    /** 名单里一个人就够看出「名单出来了」与「名单没出来」 */
    private static final long MEMBER = 2000000001L;

    private static final String UNKNOWN_NAME = "（昵称未知）";

    private final Map<Class<?>, Object> parts = new LinkedHashMap<>();

    private final List<String> replies = new ArrayList<>();

    private CommandSettingsService settings;

    private RuntimeStateController controller;

    private LiveDataService liveDataService;

    private List<NovaCommand> commands = new ArrayList<>();

    @BeforeEach
    void setUp() {
        NovaStateStore store = new NovaStateStore(new NovaCoreProperties());
        settings = new CommandSettingsService(store);

        liveDataService = mock(LiveDataService.class);
        when(liveDataService.supportsTotalData()).thenReturn(true);

        AbstractDataSource dataSource = mock(AbstractDataSource.class);
        when(dataSource.getAllUsers()).thenReturn(List.of(streamer()));
        when(dataSource.getUsers("bilibili")).thenReturn(List.of(streamer()));

        AtSubscriptionService subscriptions = mock(AtSubscriptionService.class);
        when(subscriptions.list(PLATFORM, GROUP, 10001L, "live")).thenReturn(List.of(MEMBER));
        when(subscriptions.list(PLATFORM, GROUP, 10001L, "dynamic")).thenReturn(List.of(MEMBER));

        NovaMessageSender sender = mock(NovaMessageSender.class);
        doAnswer(invocation -> replies.add(((Message) invocation.getArgument(0)).getContent()))
                .when(sender).send(any());

        RevenueVisibilityService revenueVisibility = new RevenueVisibilityService(store);

        parts.put(AbstractDataSource.class, dataSource);
        parts.put(AtSubscriptionService.class, subscriptions);
        parts.put(CommandSettingsService.class, settings);
        parts.put(CommandDispatcher.class, dispatcher(sender));
        parts.put(LiveDataService.class, liveDataService);
        parts.put(NovaMessageSender.class, sender);
        parts.put(NovaStateStore.class, store);
        parts.put(RevenueVisibilityService.class, revenueVisibility);
        parts.put(SessionMemberNames.class, mock(SessionMemberNames.class));
        parts.put(BilibiliStreamerChoice.class, mock(BilibiliStreamerChoice.class));
        parts.put(BilibiliDataQueryPainter.class, mock(BilibiliDataQueryPainter.class));
        parts.put(TimelineWriter.class, TimelineWriter.NONE);
        parts.put(NovaCoreProperties.class, new NovaCoreProperties());
        parts.put(NovaBilibiliProperties.class, new NovaBilibiliProperties());

        commands = List.of(
                build(BilibiliAtListCommand.class),
                build(BilibiliRoomDataCommand.class),
                build(BilibiliRankingCommand.class));

        controller = build(RuntimeStateController.class);
    }

    @Test
    @DisplayName("控制台关了「@名单」，群里那两种问法都不再回名单")
    void consoleOffAtListStopsBothSpellings() {
        // 关之前先看一眼：名单是真出来了的，否则「没回名单」与「本来就没配人」分不开
        assertTrue(feed("@名单").contains(UNKNOWN_NAME), feed("@名单"));

        JSONObject off = controller.toggleCommand(toggle("@名单", true));
        assertTrue(off.getBooleanValue("success"), off.toJSONString());

        for (String spelling : List.of("@名单", "@名单 开播", "@名单 动态")) {
            String reply = feed(spelling);
            assertTrue(reply.contains("已关闭"), spelling + " 没被控制台那一下挡住：" + reply);
            assertFalse(reply.contains(UNKNOWN_NAME), spelling + " 名单还是出来了：" + reply);
        }
    }

    @Test
    @DisplayName("控制台关得掉累计那一半，群里带「总」的问法跟着停，本场那一半没被连累")
    void consoleCanTurnOffTheCumulativeHalf() {
        // 「直播间总数据」与「直播间数据 总」说的是同一种用法，两种问法都得关得掉
        for (String spelling : List.of("直播间总数据", "直播间数据 总")) {
            JSONObject off = controller.toggleCommand(toggle(spelling, true));
            assertTrue(off.getBooleanValue("success"), spelling + " 关不掉累计那一半：" + off.toJSONString());
        }
        for (String spelling : List.of("总数据排行榜", "数据排行榜 总")) {
            JSONObject off = controller.toggleCommand(toggle(spelling, true));
            assertTrue(off.getBooleanValue("success"), spelling + " 关不掉累计那一半：" + off.toJSONString());
        }

        for (String spelling : List.of("直播间数据 总", "直播间总数据", "数据排行榜 弹幕 总", "总数据排行榜 弹幕")) {
            String reply = feed(spelling);
            assertTrue(reply.contains("已关闭"), spelling + " 还在回话：" + reply);
        }

        for (String spelling : List.of("直播间数据", "数据排行榜 弹幕")) {
            String reply = feed(spelling);
            assertFalse(reply.contains("已关闭"), spelling + " 本场那一半被连累了：" + reply);
        }
    }

    @Test
    @DisplayName("本机没开累计数据时，累计那一半在页面上置灰")
    void cumulativeHalfGreysOutWithoutTotalData() {
        when(liveDataService.supportsTotalData()).thenReturn(false);

        JSONObject page = pageCommand("直播间数据");
        JSONArray usages = page.getJSONArray("usages");
        assertTrue(usages != null && !usages.isEmpty(),
                "一条命令底下的几种用法都没报给页面，置灰也就无从谈起：" + page.toJSONString());

        // 页面置灰的依据是「这一格在这台机器上开没开」，不是写死的「能用」
        assertTrue(availableOf(usages, "直播间数据"), "本场那一半不该被一起置灰：" + usages.toJSONString());
        assertFalse(availableOf(usages, "直播间总数据"), "累计那一半该置灰：" + usages.toJSONString());
    }

    @Test
    @DisplayName("接口先关「@名单」再开，两格都开回来，群里「@名单」照样应答")
    void apiReopensEveryCellWhenOneNameCoversSeveral() {
        JSONObject off = controller.toggleCommand(toggle("@名单", true));
        assertTrue(off.getBooleanValue("success"), off.toJSONString());
        assertTrue(settings.isDisabled(PLATFORM, GROUP, "开播@名单"), "先把开播那一格关上");
        assertTrue(settings.isDisabled(PLATFORM, GROUP, "动态@名单"), "先把动态那一格关上");

        JSONObject on = controller.toggleCommand(toggle("@名单", false));
        assertTrue(on.getBooleanValue("success"), on.toJSONString());
        String message = on.getString("message");
        assertFalse(settings.isDisabled(PLATFORM, GROUP, "开播@名单"),
                "开播那一格该开回来，回话却是：" + message);
        assertFalse(settings.isDisabled(PLATFORM, GROUP, "动态@名单"),
                "动态那一格该开回来，回话却是：" + message);
        assertTrue(message.contains("开播@名单") && message.contains("动态@名单"),
                "回话该写明开了哪几格：" + message);

        String reply = feed("@名单");
        assertFalse(reply.contains("已关闭"), "群里「@名单」该应答，实际：" + reply);
        assertTrue(reply.contains(UNKNOWN_NAME), "群里「@名单」该把名单答出来：" + reply);
    }

    // ── 夹具 ────────────────────────────────────────────────────────────

    private JSONObject toggle(String command, boolean disabled) {
        return new JSONObject()
                .fluentPut("platform", PLATFORM)
                .fluentPut("num", GROUP)
                .fluentPut("command", command)
                .fluentPut("disabled", disabled);
    }

    private JSONObject pageCommand(String name) {
        JSONArray commands = controller.state().getJSONArray("commands");
        for (int i = 0; i < commands.size(); i++) {
            JSONObject item = commands.getJSONObject(i);
            if (name.equals(item.getString("name"))) {
                return item;
            }
        }
        throw new IllegalStateException("页面上没有「" + name + "」这一条：" + commands.toJSONString());
    }

    private static boolean availableOf(JSONArray usages, String key) {
        for (int i = 0; i < usages.size(); i++) {
            JSONObject usage = usages.getJSONObject(i);
            if (key.equals(usage.getString("key"))) {
                return usage.getBooleanValue("available");
            }
        }
        throw new IllegalStateException("用法表里没有「" + key + "」这一格：" + usages.toJSONString());
    }

    /**
     * 喂一句话进群里，返回机器人说的一个字没说时为空串
     * <p>
     * 每次现造一个分发器：命令冷却按会话记在分发器里，复用一个的话第二条起全被冷却挡住，
     * 而「被冷却挡住」与「本就不应答」在回话里长得一样。
     */
    private String feed(String text) {
        replies.clear();
        NovaMessageSender sender = (NovaMessageSender) parts.get(NovaMessageSender.class);
        CommandDispatcher dispatcher = dispatcher(sender);
        parts.put(CommandDispatcher.class, dispatcher);
        dispatcher.onRemoteMessage(new NovaRemoteMessageEvent(PLATFORM, "group", GROUP, SENDER, text, "member", true));
        return String.join("\n", replies);
    }

    private CommandDispatcher dispatcher(NovaMessageSender sender) {
        @SuppressWarnings("unchecked")
        ObjectProvider<NovaCommand> provided = mock(ObjectProvider.class);
        when(provided.iterator()).thenAnswer(invocation -> new ArrayList<>(commands).iterator());
        when(provided.orderedStream()).thenAnswer(invocation -> new ArrayList<>(commands).stream());

        @SuppressWarnings("unchecked")
        ObjectProvider<CommandFollowUp> noFollowUps = mock(ObjectProvider.class);
        when(noFollowUps.iterator()).thenAnswer(invocation -> List.<CommandFollowUp>of().iterator());

        return new CommandDispatcher(provided, noFollowUps, settings,
                (AbstractDataSource) parts.get(AbstractDataSource.class), sender,
                new NovaCoreProperties(), TimelineWriter.NONE);
    }

    private static PushUser streamer() {
        PushTarget target = new PushTarget();
        target.setPlatform(PLATFORM);
        target.setType(PushTargetType.GROUP);
        target.setNum(GROUP);
        target.setMessages(new ArrayList<>());

        PushUser user = new PushUser();
        user.setUid(10001L);
        user.setUname("测试主播");
        user.setPlatform("bilibili");
        user.setTargets(List.of(target));
        return user;
    }

    /**
     * 按构造参数的类型现配依赖
     * <p>
     * 与运行状态接口那把尺同一理由：控制器和命令接的东西会变，这条要量的事不变。
     */
    @SuppressWarnings("unchecked")
    private <T> T build(Class<T> type) {
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            Object[] arguments = Arrays.stream(constructor.getParameters())
                    .map(this::argument)
                    .toArray();
            constructor.setAccessible(true);
            try {
                return (T) constructor.newInstance(arguments);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("造不出 " + type.getSimpleName(), e);
            }
        }
        throw new IllegalStateException(type.getSimpleName() + " 没有构造器");
    }

    private Object argument(Parameter parameter) {
        Class<?> raw = parameter.getType();
        if (ObjectProvider.class.isAssignableFrom(raw)) {
            return providersOf(parts.get(elementOf(parameter)));
        }
        Object found = parts.get(raw);
        if (found == null) {
            throw new IllegalStateException("没配 " + raw.getSimpleName() + " 这一样依赖");
        }
        return found;
    }

    /**
     * 「一把实现」形状的替身：只装给定那一个，谁问都吐它
     */
    private static ObjectProvider<?> providersOf(Object target) {
        List<Object> holders = target == null ? List.of() : List.of(target);
        @SuppressWarnings("unchecked")
        ObjectProvider<Object> provider = mock(ObjectProvider.class);
        when(provider.iterator()).thenAnswer(invocation -> holders.iterator());
        when(provider.stream()).thenAnswer(invocation -> holders.stream());
        when(provider.orderedStream()).thenAnswer(invocation -> holders.stream());
        when(provider.getIfAvailable()).thenAnswer(invocation ->
                holders.isEmpty() ? null : holders.get(0));
        return provider;
    }

    private static Class<?> elementOf(Parameter parameter) {
        Type generic = parameter.getParameterizedType();
        if (generic instanceof ParameterizedType parameterized) {
            Type[] arguments = parameterized.getActualTypeArguments();
            if (arguments.length == 1 && arguments[0] instanceof Class<?> element) {
                return element;
            }
        }
        return Object.class;
    }
}
