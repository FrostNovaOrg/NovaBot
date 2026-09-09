package org.frostnova.nova.console.controller;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.command.CommandContext;
import org.frostnova.nova.core.command.CommandDispatcher;
import org.frostnova.nova.core.command.CommandReply;
import org.frostnova.nova.core.command.CommandSettingsService;
import org.frostnova.nova.core.command.NovaCommand;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.model.PushTarget;
import org.frostnova.nova.core.model.PushUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 通道页要用的那两支：会话的菜单口径（读）与成批开关命令（写）
 *
 * <h2>读那一半为什么由服务端答</h2>
 * 控制台要把「本群设置」里的几行置灰、把摘要从 14 改成 12，靠的是「这个会话的菜单里
 * 不列哪几条」。这条规则在命令那一侧只有一份实现（{@code availableIn}）——本群这类通知
 * 全配成 @全体成员 时藏掉三条订阅命令、累计存储没配时藏掉两条「总」字命令，都在那里判。
 * 抄一份到界面上之后，改了那一份的那天控制台仍按旧规矩画，<b>而两边的代码看起来都对</b>。
 *
 * <h2>写那一半为什么要成批</h2>
 * 「组开关」与「一键恢复」按下去是一个动作。让界面自己循环调单条的话，中途失败会留下
 * 一半开一半关的局面，而屏幕上只有最后那一条的报错——使用者不知道刚才究竟改成了什么样。
 * 因此这里<b>先全查再全改</b>：名单里只要有一条禁不得，整批都不动。
 */
@DisplayName("通道页的菜单口径与成批开关")
class ChannelSettingsSurfaceTest {
    private static final String PLATFORM = "qq";

    private static final long GROUP_NUM = 12345L;

    private static final long FRIEND_NUM = 67890L;

    /** 本会话里不列进菜单的那一条，理由由它自己给 */
    private static final String HIDDEN = "开播@我";

    /** 关不得的那一条：关掉之后群里就再没有把它开回来的入口了 */
    private static final String LOCKED = "菜单";

    /**
     * 不限群聊的六条：与菜单命令测试那一份同字。
     * 控制台测不能引用核心测试类，因此在这里各留一份。
     */
    private static final List<String> PRIVATE_OK = List.of(
            "菜单", "直播报告", "数据排行榜", "总数据排行榜", "直播间数据", "直播间总数据");

    /** 仅限群聊的八条：私聊菜单不该出现 */
    private static final List<String> GROUP_ONLY_NAMES = List.of(
            "开播@我", "取消开播@我", "开播@名单",
            "动态@我", "取消动态@我", "动态@名单",
            "启用命令", "禁用命令");

    private final Map<Class<?>, Object> dependencies = new LinkedHashMap<>();

    private RuntimeStateController controller;

    private CommandSettingsService settings;

    @BeforeEach
    void setUp() throws Exception {
        controller = newController();
        settings = dependency(CommandSettingsService.class);
        assertNotNull(settings, "控制器没接命令开关服务，写那一半无从量起");

        CommandDispatcher dispatcher = dependency(CommandDispatcher.class);
        assertNotNull(dispatcher, "控制器没接命令分发器，读那一半无从量起");
        when(dispatcher.all()).thenReturn(commands());

        AbstractDataSource dataSource = dependency(AbstractDataSource.class);
        assertNotNull(dataSource, "控制器没接数据源，会话清单无从量起");
        when(dataSource.getAllUsers()).thenReturn(List.of(streamer()));
        when(dataSource.getIncompleteEntries()).thenReturn(List.of());

        when(settings.all()).thenReturn(List.of());
        when(settings.disable(anyString(), any(), anyString())).thenReturn(true);
        when(settings.enable(anyString(), any(), anyString())).thenReturn(true);
    }

    // ── 读：菜单口径 ────────────────────────────────────────────────────

    @Test
    @DisplayName("阳：会话里不列进菜单的那几条与它们的说明，从状态里读得到")
    void stateCarriesPerSessionMenuVisibility() {
        JSONObject session = sessionOf(PushTargetType.GROUP, GROUP_NUM);

        assertNotNull(session.getJSONArray("menuHidden"),
                "群会话该有一份菜单口径，哪怕一条都不藏: " + session);
        assertFalse(session.getJSONArray("menuHidden").toList(String.class).contains(HIDDEN),
                "群会话不该把仅限群聊的命令藏掉: " + session);
        assertEquals("本群开播通知会先 @全体成员", session.getJSONObject("menuNotes").getString(HIDDEN),
                "菜单里那句会话相关的说明没带过来，控制台只能另拼一句: " + session);
    }

    @Test
    @DisplayName("阳：好友会话藏掉全部仅限群聊的命令，群会话不藏；可见的六条与菜单同一份名单")
    void friendSessionHidesGroupOnlyCommands() {
        JSONObject group = sessionOf(PushTargetType.GROUP, GROUP_NUM);
        JSONObject friend = sessionOf(PushTargetType.FRIEND, FRIEND_NUM);
        List<String> groupHidden = group.getJSONArray("menuHidden").toList(String.class);
        List<String> friendHidden = friend.getJSONArray("menuHidden").toList(String.class);

        for (String name : GROUP_ONLY_NAMES) {
            assertTrue(friendHidden.contains(name), "好友会话该藏「" + name + "」: " + friendHidden);
            assertFalse(groupHidden.contains(name), "群会话不该藏「" + name + "」: " + groupHidden);
        }
        assertEquals(PRIVATE_OK.size(),
                commands().size() - friendHidden.size(),
                "好友会话可见条数该是私聊可用的那些: " + friendHidden);

        Set<String> visible = new LinkedHashSet<>(PRIVATE_OK);
        visible.addAll(GROUP_ONLY_NAMES);
        friendHidden.forEach(visible::remove);
        assertEquals(Set.copyOf(PRIVATE_OK), visible,
                "好友会话可见的名字该与私聊菜单同一份: " + visible);
    }

    @Test
    @DisplayName("阳：命令把 availableIn 覆写成假时，群会话 menuHidden 恰含它，好友会话亦含")
    void groupSessionHidesCommandWhenAvailableInOverridesToFalse() {
        CommandDispatcher dispatcher = dependency(CommandDispatcher.class);
        List<NovaCommand> roster = new ArrayList<>();
        for (String name : PRIVATE_OK) {
            roster.add(simple(name, !LOCKED.equals(name), false));
        }
        for (String name : GROUP_ONLY_NAMES) {
            roster.add(HIDDEN.equals(name) ? hiddenByAvailableIn() : simple(name, true, true));
        }
        when(dispatcher.all()).thenReturn(roster);

        JSONObject group = sessionOf(PushTargetType.GROUP, GROUP_NUM);
        JSONObject friend = sessionOf(PushTargetType.FRIEND, FRIEND_NUM);
        List<String> groupHidden = group.getJSONArray("menuHidden").toList(String.class);
        List<String> friendHidden = friend.getJSONArray("menuHidden").toList(String.class);

        assertEquals(List.of(HIDDEN), groupHidden,
                "群会话该只因覆写藏这一条: " + groupHidden);
        assertTrue(friendHidden.contains(HIDDEN),
                "好友会话也该藏它: " + friendHidden);
        for (String name : GROUP_ONLY_NAMES) {
            assertTrue(friendHidden.contains(name),
                    "覆写不得冲掉好友会话对仅限群聊命令的隐藏: " + friendHidden);
        }
    }

    @Test
    @DisplayName("阴：类型未知的会话答不了菜单口径，那一栏是空的而不是一个空表")
    void strandedSessionAnswersNothingRatherThanEmpty() {
        // 推送配置里已经没有这个会话，只在状态文件里留着：没有会话类型，构不出上下文。
        // 🔴 回一个空表会被读成「一条都不藏」——答不了与都列着在 `|| []` 底下长得一模一样
        when(settings.all()).thenReturn(List.of(
                new CommandSettingsService.Disabled(PLATFORM, 777L, List.of("直播报告"))));

        JSONObject stranded = sessions().stream()
                .filter(item -> item.getLongValue("num") == 777L)
                .findFirst().orElse(null);
        assertNotNull(stranded, "状态文件里残留的会话没有出现在清单里");
        assertNull(stranded.get("menuHidden"),
                "答不了的时候给了一个空表，那与「一条都不藏」在界面上分不出来: " + stranded);
    }

    // ── 写：成批开关 ────────────────────────────────────────────────────

    @Test
    @DisplayName("阳：成批启用照数改，并逐条落到命令开关服务上")
    void batchEnableChangesEveryNamedCommand() {
        JSONObject result = controller.toggleCommands(body(List.of("直播报告", "数据排行榜"), false));

        assertTrue(result.getBooleanValue("success"), result.toString());
        assertEquals(2, result.getIntValue("changed"), result.toString());
        verify(settings).enable(PLATFORM, GROUP_NUM, "直播报告");
        verify(settings).enable(PLATFORM, GROUP_NUM, "数据排行榜");
        verify(settings, never()).disable(anyString(), any(), anyString());
    }

    @Test
    @DisplayName("阴：名单里有一条禁不得时整批都不动")
    void batchDisableRejectsWholeBatchWhenOneIsLocked() {
        JSONObject result = controller.toggleCommands(body(List.of("直播报告", LOCKED), true));

        assertFalse(result.getBooleanValue("success"), result.toString());
        assertTrue(result.getString("message").contains("整批未改"), result.toString());
        // 🔴 关键在这一句：排在它前面的那一条也没有被改掉。改了一半再报错，
        // 等于让人从一个自己没选过的状态往回收拾
        verify(settings, never()).disable(anyString(), any(), anyString());
    }

    @Test
    @DisplayName("阴：名单里有一条根本不存在时整批都不动")
    void batchDisableRejectsUnknownCommand() {
        JSONObject result = controller.toggleCommands(body(List.of("直播报告", "并没有这条"), true));

        assertFalse(result.getBooleanValue("success"), result.toString());
        verify(settings, never()).disable(anyString(), any(), anyString());
    }

    @Test
    @DisplayName("阴：少参数、空名单与空命令名都拒，且一条也不改")
    void batchRejectsMalformedRequests() {
        JSONObject noNum = body(List.of("直播报告"), false);
        noNum.remove("num");
        JSONObject noFlag = body(List.of("直播报告"), false);
        noFlag.remove("disabled");
        JSONObject empty = body(List.of(), false);
        JSONObject blank = body(new ArrayList<>(List.of("")), false);

        for (JSONObject request : List.of(noNum, noFlag, empty, blank)) {
            JSONObject result = controller.toggleCommands(request);
            assertFalse(result.getBooleanValue("success"), request + " 该被拒: " + result);
        }
        verify(settings, never()).enable(anyString(), any(), anyString());
        verify(settings, never()).disable(anyString(), any(), anyString());
    }

    @Test
    @DisplayName("一条也没真改到时不落盘，改到了才落盘")
    void savesOnlyWhenSomethingActuallyChanged() {
        org.frostnova.nova.core.service.NovaStateStore store =
                dependency(org.frostnova.nova.core.service.NovaStateStore.class);
        assertNotNull(store, "控制器没接状态存储，落盘那一步无从量起");

        when(settings.enable(anyString(), any(), eq("直播报告"))).thenReturn(false);
        controller.toggleCommands(body(List.of("直播报告"), false));
        verify(store, never()).save();

        when(settings.enable(anyString(), any(), eq("直播报告"))).thenReturn(true);
        controller.toggleCommands(body(List.of("直播报告"), false));
        verify(store, times(1)).save();
    }

    // ── 夹具 ────────────────────────────────────────────────────────────

    private JSONObject body(List<String> names, boolean disabled) {
        JSONObject request = new JSONObject();
        request.put("platform", PLATFORM);
        request.put("num", GROUP_NUM);
        request.put("commands", new JSONArray(names.toArray()));
        request.put("disabled", disabled);
        return request;
    }

    private JSONObject sessionOf(PushTargetType type, long num) {
        JSONObject found = null;
        for (JSONObject item : sessions()) {
            if (item.getLongValue("num") == num && type.getStr().equals(item.getString("type"))) {
                found = item;
                break;
            }
        }
        assertNotNull(found, "找不到 " + type.getStr() + " " + num + " 这条会话: " + sessions());
        return found;
    }

    private List<JSONObject> sessions() {
        JSONArray items = controller.state().getJSONArray("sessions");
        List<JSONObject> out = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            out.add(items.getJSONObject(i));
        }
        return out;
    }

    private PushUser streamer() {
        PushUser user = new PushUser();
        user.setUid(3493L);
        user.setUname("柚子");
        user.setTargets(List.of(
                target(PushTargetType.GROUP, GROUP_NUM),
                target(PushTargetType.FRIEND, FRIEND_NUM)));
        return user;
    }

    private PushTarget target(PushTargetType type, long num) {
        PushTarget target = new PushTarget();
        target.setPlatform(PLATFORM);
        target.setType(type);
        target.setNum(num);
        return target;
    }

    /**
     * 命令表：私聊可用的那些，加上仅限群聊的那些；「开播@我」另带一句会话说明。
     * 仅限群聊的那些靠 {@code groupOnly()}，不再硬写 {@code availableIn}。
     */
    private List<NovaCommand> commands() {
        List<NovaCommand> commands = new ArrayList<>();
        for (String name : PRIVATE_OK) {
            commands.add(simple(name, !LOCKED.equals(name), false));
        }
        for (String name : GROUP_ONLY_NAMES) {
            commands.add(HIDDEN.equals(name) ? hiddenSubscribe() : simple(name, true, true));
        }
        return commands;
    }

    /**
     * 本群配成 @全体成员 时「开播@我」自己把 {@code availableIn} 覆写成假。
     * 与 {@link #hiddenSubscribe()} 的差别只在这一覆写：群会话也必须把它藏进 menuHidden。
     */
    private NovaCommand hiddenByAvailableIn() {
        return new NovaCommand() {
            @Override
            public String name() {
                return HIDDEN;
            }

            @Override
            public String description() {
                return "开播时 @ 我";
            }

            @Override
            public boolean groupOnly() {
                return true;
            }

            @Override
            public boolean availableIn(CommandContext context) {
                return false;
            }

            @Override
            public CommandReply execute(CommandContext context) {
                return CommandReply.none();
            }
        };
    }

    private NovaCommand hiddenSubscribe() {
        return new NovaCommand() {
            @Override
            public String name() {
                return HIDDEN;
            }

            @Override
            public String description() {
                return "开播时 @ 我";
            }

            @Override
            public boolean groupOnly() {
                return true;
            }

            @Override
            public String menuNote(CommandContext context) {
                return "本群开播通知会先 @全体成员";
            }

            @Override
            public CommandReply execute(CommandContext context) {
                return CommandReply.none();
            }
        };
    }

    private NovaCommand simple(String name, boolean disableable, boolean groupOnly) {
        return new NovaCommand() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return name + "做什么";
            }

            @Override
            public boolean disableable() {
                return disableable;
            }

            @Override
            public boolean groupOnly() {
                return groupOnly;
            }

            @Override
            public CommandReply execute(CommandContext context) {
                return CommandReply.none();
            }
        };
    }

    private <T> T dependency(Class<T> type) {
        return type.cast(dependencies.get(type));
    }

    /**
     * 依赖按类型现填，不写死构造参数表：这把尺守的是接口面，
     * 不该因为控制器多接一个依赖就编不过
     */
    private RuntimeStateController newController() throws Exception {
        Constructor<?> constructor = RuntimeStateController.class.getDeclaredConstructors()[0];
        Class<?>[] types = constructor.getParameterTypes();
        Object[] args = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            args[i] = dependencies.computeIfAbsent(types[i], type -> mock(type));
        }

        constructor.setAccessible(true);
        return (RuntimeStateController) constructor.newInstance(args);
    }
}
