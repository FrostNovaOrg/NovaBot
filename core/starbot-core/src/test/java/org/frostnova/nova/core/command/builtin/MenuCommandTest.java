package org.frostnova.nova.core.command.builtin;

import org.frostnova.nova.core.command.CommandContext;
import org.frostnova.nova.core.command.CommandDispatcher;
import org.frostnova.nova.core.command.CommandReply;
import org.frostnova.nova.core.command.CommandSettingsService;
import org.frostnova.nova.core.command.NovaCommand;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.service.NovaStateStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 菜单命令测试
 * <p>
 * 命令数量已经上到二十条，菜单要能分组、要能隐藏被禁用的命令，
 * 否则它自己就成了刷屏的那一条。
 */
@DisplayName("菜单命令")
public class MenuCommandTest {
    private static final String PLATFORM = "qq-onebot";

    private static final Long GROUP = 30003L;

    /**
     * 不限群聊的六条：私聊菜单只该列这些
     * <p>
     * 控制台会话卡片的 {@code menuHidden} 与菜单正文共用这一份，改一处漏一处会让
     * 私聊菜单和通道页置灰对不上。
     */
    public static final List<String> PRIVATE_OK = List.of(
            "菜单", "直播报告", "数据排行榜", "总数据排行榜", "直播间数据", "直播间总数据");

    /**
     * 仅限群聊的八条：私聊菜单不该出现
     */
    public static final List<String> GROUP_ONLY_NAMES = List.of(
            "开播@我", "取消开播@我", "开播@名单",
            "动态@我", "取消动态@我", "动态@名单",
            "启用命令", "禁用命令");

    private CommandSettingsService settings;

    private MenuCommand menu;

    @BeforeEach
    void setUp() {
        CommandDispatcher dispatcher = mock(CommandDispatcher.class);
        when(dispatcher.all()).thenReturn(List.of(
                stub("直播间数据", "数据查询", true),
                stub("数据排行榜", "数据查询", true),
                stub("直播间总数据", "数据查询", false),
                stub("开播@我", "提醒订阅", true)));

        @SuppressWarnings("unchecked")
        ObjectProvider<CommandDispatcher> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(dispatcher);

        settings = new CommandSettingsService(new NovaStateStore(new NovaCoreProperties()));
        menu = new MenuCommand(provider, settings);
    }

    @Test
    @DisplayName("顶行应写清怎么用，且群聊与私聊各说各的")
    void leadsWithUsage() {
        // 认不出的消息回的就是这一份，看到它的人多半正是没打对触发方式的那个人
        assertTrue(menu.execute(context(PushTargetType.GROUP)).content().startsWith("用法：@ 我，"),
                menu.execute(context(PushTargetType.GROUP)).content());
        assertTrue(menu.execute(context(PushTargetType.FRIEND)).content().startsWith("用法：直接发命令名"),
                menu.execute(context(PushTargetType.FRIEND)).content());
    }

    @Test
    @DisplayName("同一分类的命令应归在一个标题下，且各分类只出现一次")
    void groupsByCategory() {
        String text = menu.execute(context()).content();

        assertEquals(1, count(text, "【数据查询】"));
        assertEquals(1, count(text, "【提醒订阅】"));
        // 分类标题应排在自己那组命令之前
        assertTrue(text.indexOf("【数据查询】") < text.indexOf("直播间数据"));
        assertTrue(text.indexOf("【提醒订阅】") < text.indexOf("开播@我"));
    }

    @Test
    @DisplayName("被禁用的命令不应出现，其分类若因此空了也不应留下空标题")
    void hidesDisabledCommandsAndEmptyCategories() {
        settings.disable(PLATFORM, GROUP, "开播@我");

        String text = menu.execute(context()).content();

        assertFalse(text.contains("【提醒订阅】"), text);
        assertTrue(text.contains("【数据查询】"));
    }

    @Test
    @DisplayName("本机没开的能力对应的命令不应出现")
    void hidesUnavailableCommands() {
        // 与「被本群禁用」是两回事：那是人为选择，这是整台机器没有这项能力。
        // 列出来的下场一样——照着发一遍，收到一句拒绝
        String text = menu.execute(context()).content();

        assertFalse(text.contains("直播间总数据"), text);
        assertTrue(text.contains("直播间数据"), text);
    }

    @Test
    @DisplayName("私聊菜单只列能在私聊用的六条，群聊十四条照旧")
    void privateMenuOmitsGroupOnlyCommands() {
        MenuCommand full = menuOfFourteen();
        List<String> groupNames = listedNames(full.execute(context(PushTargetType.GROUP)).content());
        String friendText = full.execute(context(PushTargetType.FRIEND)).content();
        List<String> friendNames = listedNames(friendText);

        assertEquals(14, groupNames.size(), "群聊菜单：" + groupNames);
        assertEquals(Set.copyOf(allFourteenNames()), Set.copyOf(groupNames));
        assertEquals(6, friendNames.size(), "私聊菜单：" + friendText);
        assertEquals(Set.copyOf(PRIVATE_OK), Set.copyOf(friendNames));
        for (String name : GROUP_ONLY_NAMES) {
            assertFalse(friendNames.contains(name), name + " 不该出现在私聊菜单：" + friendText);
        }
    }

    private int count(String text, String token) {
        int total = 0;
        for (int i = text.indexOf(token); i >= 0; i = text.indexOf(token, i + token.length())) {
            total++;
        }
        return total;
    }

    private CommandContext context() {
        return context(PushTargetType.GROUP);
    }

    private CommandContext context(PushTargetType type) {
        return new CommandContext(PLATFORM, type, GROUP, 1L, "菜单", List.of(), "菜单");
    }

    private NovaCommand stub(String name, String category, boolean available) {
        return stub(name, category, available, true);
    }

    private NovaCommand stub(String name, String category, boolean available, boolean groupOnly) {
        return new NovaCommand() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return name + "的说明";
            }

            @Override
            public String category() {
                return category;
            }

            @Override
            public boolean available() {
                return available;
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

    private MenuCommand menuOfFourteen() {
        List<NovaCommand> commands = new ArrayList<>();
        for (String name : PRIVATE_OK) {
            commands.add(stub(name, categoryOf(name), true, false));
        }
        for (String name : GROUP_ONLY_NAMES) {
            commands.add(stub(name, categoryOf(name), true, true));
        }
        CommandDispatcher dispatcher = mock(CommandDispatcher.class);
        when(dispatcher.all()).thenReturn(commands);
        @SuppressWarnings("unchecked")
        ObjectProvider<CommandDispatcher> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(dispatcher);
        return new MenuCommand(provider, settings);
    }

    private List<String> allFourteenNames() {
        List<String> names = new ArrayList<>(PRIVATE_OK);
        names.addAll(GROUP_ONLY_NAMES);
        return names;
    }

    private String categoryOf(String name) {
        if (name.endsWith("@我") || name.endsWith("@名单")) {
            return "提醒订阅";
        }
        if ("菜单".equals(name) || name.endsWith("命令")) {
            return "命令管理";
        }
        return "数据查询";
    }

    private List<String> listedNames(String menu) {
        List<String> names = new ArrayList<>();
        for (String line : menu.split("\n")) {
            if (line.contains(" — ")) {
                names.add(line.split(" ")[0]);
            }
        }
        return names;
    }
}
