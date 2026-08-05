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
                stub("我的数据", "数据查询"),
                stub("数据排行榜", "数据查询"),
                stub("绑定", "账号绑定")));

        @SuppressWarnings("unchecked")
        ObjectProvider<CommandDispatcher> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(dispatcher);

        settings = new CommandSettingsService(new StarBotStateStore(new StarBotCoreProperties()));
        menu = new MenuCommand(provider, settings, new StarBotCoreProperties());
    }

    @Test
    @DisplayName("同一分类的命令应归在一个标题下，且各分类只出现一次")
    void groupsByCategory() {
        String text = menu.execute(context()).content();

        assertEquals(1, count(text, "【数据查询】"));
        assertEquals(1, count(text, "【账号绑定】"));
        // 分类标题应排在自己那组命令之前
        assertTrue(text.indexOf("【数据查询】") < text.indexOf("我的数据"));
        assertTrue(text.indexOf("【账号绑定】") < text.indexOf("绑定"));
    }

    @Test
    @DisplayName("被禁用的命令不应出现，其分类若因此空了也不应留下空标题")
    void hidesDisabledCommandsAndEmptyCategories() {
        settings.disable(PLATFORM, GROUP, "绑定");

        String text = menu.execute(context()).content();

        assertFalse(text.contains("【账号绑定】"), text);
        assertTrue(text.contains("【数据查询】"));
    }

    private int count(String text, String token) {
        int total = 0;
        for (int i = text.indexOf(token); i >= 0; i = text.indexOf(token, i + token.length())) {
            total++;
        }
        return total;
    }

    private CommandContext context() {
        return new CommandContext(PLATFORM, PushTargetType.GROUP, GROUP, 1L, "菜单", List.of(), "菜单");
    }

    private StarBotCommand stub(String name, String category) {
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
            public CommandReply execute(CommandContext context) {
                return CommandReply.none();
            }
        };
    }
}
