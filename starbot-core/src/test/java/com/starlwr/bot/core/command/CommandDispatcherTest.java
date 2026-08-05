package com.starlwr.bot.core.command;

import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.datasource.AbstractDataSource;
import com.starlwr.bot.core.enums.PushTargetType;
import com.starlwr.bot.core.event.remote.StarBotRemoteMessageEvent;
import com.starlwr.bot.core.model.Message;
import com.starlwr.bot.core.model.PushTarget;
import com.starlwr.bot.core.model.PushUser;
import com.starlwr.bot.core.sender.StarBotMessageSender;
import com.starlwr.bot.core.service.StarBotStateStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 命令分发器测试
 * <p>
 * 重点覆盖三条横切约束：只在已配置推送的会话响应、未知命令沉默、命令开关生效。
 * 这些约束一旦失守，机器人就会在无关群里说话——这是这类产品最招人反感的行为。
 */
@DisplayName("命令分发器")
class CommandDispatcherTest {
    private static final String PLATFORM = "qq-onebot";

    private static final Long GROUP = 30003L;

    private StarBotMessageSender sender;

    private CommandSettingsService settings;

    private RecordingCommand command;

    private CommandDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        AbstractDataSource dataSource = mock(AbstractDataSource.class);
        when(dataSource.getAllUsers()).thenReturn(List.of(configuredUser()));

        sender = mock(StarBotMessageSender.class);
        settings = new CommandSettingsService(new StarBotStateStore(new StarBotCoreProperties()));
        command = new RecordingCommand();

        dispatcher = new CommandDispatcher(providerOf(command), settings, dataSource, sender, new StarBotCoreProperties());
    }

    @Test
    @DisplayName("已配置推送的群内应执行命令并回复")
    void executesInConfiguredGroup() {
        dispatcher.onRemoteMessage(event(GROUP, "测试命令"));

        assertEquals(1, command.executions);
        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(sender).send(captor.capture());
        assertEquals("已执行", captor.getValue().getContent());
    }

    @Test
    @DisplayName("未配置推送的群应完全沉默")
    void staysSilentInUnconfiguredGroup() {
        dispatcher.onRemoteMessage(event(99999L, "测试命令"));

        assertEquals(0, command.executions);
        verify(sender, never()).send(any());
    }

    @Test
    @DisplayName("未知命令不应有任何回复")
    void ignoresUnknownCommand() {
        dispatcher.onRemoteMessage(event(GROUP, "今天天气不错"));

        verify(sender, never()).send(any());
    }

    @Test
    @DisplayName("命令参数应按空白切分后传入")
    void parsesArguments() {
        dispatcher.onRemoteMessage(event(GROUP, "测试命令  参数一   参数二"));

        assertEquals(List.of("参数一", "参数二"), command.lastArgs);
    }

    @Test
    @DisplayName("被禁用的命令不应执行")
    void skipsDisabledCommand() {
        settings.disable(PLATFORM, GROUP, "测试命令");

        dispatcher.onRemoteMessage(event(GROUP, "测试命令"));

        assertEquals(0, command.executions);
    }

    @Test
    @DisplayName("冷却期内的连续命令只执行一次")
    void appliesCooldown() {
        dispatcher.onRemoteMessage(event(GROUP, "测试命令"));
        dispatcher.onRemoteMessage(event(GROUP, "测试命令"));

        assertEquals(1, command.executions);
    }

    @Test
    @DisplayName("配置了前缀时，不带前缀的消息应被忽略")
    void respectsConfiguredPrefix() {
        StarBotCoreProperties properties = new StarBotCoreProperties();
        properties.getCommand().setPrefix("/");
        AbstractDataSource dataSource = mock(AbstractDataSource.class);
        when(dataSource.getAllUsers()).thenReturn(List.of(configuredUser()));
        CommandDispatcher prefixed = new CommandDispatcher(providerOf(command), settings, dataSource, sender, properties);

        prefixed.onRemoteMessage(event(GROUP, "测试命令"));
        assertEquals(0, command.executions, "缺少前缀时不应执行");

        prefixed.onRemoteMessage(event(GROUP, "/测试命令"));
        assertEquals(1, command.executions, "带前缀时应执行");
    }

    @Test
    @DisplayName("别名同样能触发命令")
    void matchesAlias() {
        dispatcher.onRemoteMessage(event(GROUP, "别名"));

        assertEquals(1, command.executions);
    }

    @Test
    @DisplayName("命令抛出异常时不应把异常细节回给群里")
    void swallowsCommandException() {
        command.explode = true;

        dispatcher.onRemoteMessage(event(GROUP, "测试命令"));

        verify(sender, never()).send(any());
    }

    @Test
    @DisplayName("私聊中不应执行仅限群聊的命令")
    void skipsGroupOnlyCommandInPrivateChat() {
        StarBotRemoteMessageEvent privateMessage =
                new StarBotRemoteMessageEvent(PLATFORM, "private", GROUP, 1L, "测试命令");

        dispatcher.onRemoteMessage(privateMessage);

        assertEquals(0, command.executions);
    }

    @Test
    @DisplayName("全部命令应可枚举，供菜单使用")
    void listsAllCommands() {
        assertTrue(dispatcher.all().stream().anyMatch(c -> "测试命令".equals(c.name())));
    }

    // ============ 管理员权限 ============
    // 在加权限之前，群里任何人都能发「禁用命令」把功能对全群关掉——这是个真漏洞，
    // 因此下面几条用例守的是「谁能动全群的开关」

    @Test
    @DisplayName("普通成员执行管理命令应被拒绝并收到说明")
    void rejectsAdminCommandFromMember() {
        command.adminOnly = true;

        dispatcher.onRemoteMessage(roleEvent(GROUP, "测试命令", "member"));

        assertEquals(0, command.executions);
        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(sender).send(captor.capture());
        assertTrue(captor.getValue().getContent().contains("仅群主"), captor.getValue().getContent());
    }

    @Test
    @DisplayName("群主可执行管理命令")
    void allowsAdminCommandFromOwner() {
        command.adminOnly = true;

        dispatcher.onRemoteMessage(roleEvent(GROUP, "测试命令", "owner"));

        assertEquals(1, command.executions);
    }

    @Test
    @DisplayName("群管理员可执行管理命令")
    void allowsAdminCommandFromGroupAdmin() {
        // 不复用上一条用例的分发器：同会话有 3 秒冷却，而换个群号又会撞上
        // 「只在已配置推送的会话响应」，两条约束叠在一起只能靠各自新建分发器绕开
        command.adminOnly = true;

        dispatcher.onRemoteMessage(roleEvent(GROUP, "测试命令", "admin"));

        assertEquals(1, command.executions);
    }

    @Test
    @DisplayName("角色缺失时应按普通成员处理，不能默认放行")
    void treatsMissingRoleAsMember() {
        command.adminOnly = true;

        dispatcher.onRemoteMessage(event(GROUP, "测试命令"));

        assertEquals(0, command.executions);
    }

    @Test
    @DisplayName("超级管理员名单里的账号不依赖群角色即可执行")
    void allowsConfiguredSuperAdmin() {
        StarBotCoreProperties properties = new StarBotCoreProperties();
        properties.getCommand().getAdmins().add(1L);

        AbstractDataSource dataSource = mock(AbstractDataSource.class);
        when(dataSource.getAllUsers()).thenReturn(List.of(configuredUser()));
        CommandDispatcher withAdmins = new CommandDispatcher(providerOf(command), settings, dataSource, sender, properties);
        command.adminOnly = true;

        // 发送者 uid 为 1，角色是普通成员，但在超管名单里
        withAdmins.onRemoteMessage(roleEvent(GROUP, "测试命令", "member"));

        assertEquals(1, command.executions);
    }

    @Test
    @DisplayName("普通命令不受权限限制，且能读到自己的管理员身份")
    void normalCommandUnaffected() {
        dispatcher.onRemoteMessage(roleEvent(GROUP, "测试命令", "owner"));

        assertEquals(1, command.executions);
        assertEquals(Boolean.TRUE, command.lastSeenAdmin);
    }

    private StarBotRemoteMessageEvent roleEvent(Long group, String text, String role) {
        return new StarBotRemoteMessageEvent(PLATFORM, "group", group, 1L, text, role);
    }

    private StarBotRemoteMessageEvent event(Long group, String text) {
        return new StarBotRemoteMessageEvent(PLATFORM, "group", group, 1L, text);
    }

    /**
     * 构造一个把推送发到测试群的推送用户
     */
    private PushUser configuredUser() {
        PushTarget target = new PushTarget();
        target.setPlatform(PLATFORM);
        target.setType(PushTargetType.GROUP);
        target.setNum(GROUP);
        target.setMessages(new ArrayList<>());

        PushUser user = new PushUser();
        user.setUid(10001L);
        user.setUname("主播甲");
        user.setPlatform("bilibili");
        user.setTargets(List.of(target));
        return user;
    }

    /**
     * 把单个命令包装成 ObjectProvider
     */
    private ObjectProvider<StarBotCommand> providerOf(StarBotCommand command) {
        @SuppressWarnings("unchecked")
        ObjectProvider<StarBotCommand> provider = mock(ObjectProvider.class);
        when(provider.iterator()).thenAnswer(invocation -> List.of(command).iterator());
        when(provider.orderedStream()).thenAnswer(invocation -> Stream.of(command));
        return provider;
    }

    /**
     * 记录调用情况的测试命令
     */
    private static class RecordingCommand implements StarBotCommand {
        private int executions;
        private List<String> lastArgs;
        private boolean explode;
        private boolean adminOnly;
        private Boolean lastSeenAdmin;

        @Override
        public boolean requiresAdmin() {
            return adminOnly;
        }

        @Override
        public String name() {
            return "测试命令";
        }

        @Override
        public List<String> aliases() {
            return List.of("别名");
        }

        @Override
        public String description() {
            return "供测试使用";
        }

        @Override
        public CommandReply execute(CommandContext context) {
            if (explode) {
                throw new IllegalStateException("测试异常");
            }
            executions++;
            lastArgs = context.getArgs();
            lastSeenAdmin = context.isAdmin();
            return CommandReply.of("已执行");
        }
    }
}
