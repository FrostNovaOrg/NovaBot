package com.starlwr.bot.core.command.builtin;

import com.starlwr.bot.core.command.CommandContext;
import com.starlwr.bot.core.command.CommandDispatcher;
import com.starlwr.bot.core.command.CommandReply;
import com.starlwr.bot.core.command.CommandSettingsService;
import com.starlwr.bot.core.command.StarBotCommand;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.enums.PushTargetType;
import com.starlwr.bot.core.service.StarBotStateStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

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
class MenuCommandTest {
    private static final String PLATFORM = "qq-onebot";

    private static final Long GROUP = 30003L;

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

        settings = new CommandSettingsService(new StarBotStateStore(new StarBotCoreProperties()));
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

    private StarBotCommand stub(String name, String category, boolean available) {
        return new StarBotCommand() {
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
            public CommandReply execute(CommandContext context) {
                return CommandReply.none();
            }
        };
    }
}
