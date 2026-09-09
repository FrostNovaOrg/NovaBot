package org.frostnova.nova.core.command;

import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.event.remote.NovaRemoteMessageEvent;
import org.frostnova.nova.core.model.Message;
import org.frostnova.nova.core.model.PushTarget;
import org.frostnova.nova.core.model.PushUser;
import org.frostnova.nova.core.sender.NovaMessageSender;
import org.frostnova.nova.core.service.NovaStateStore;
import org.frostnova.nova.core.timeline.TimelineWriter;
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
 * 覆盖分发这一步本身：参数怎么切、回复怎么发、权限怎么判、命令炸了怎么办。
 * <b>「什么算一条命令」不在这里</b>——那件事的用例数远多于方法数，
 * 摆在 {@link CommandDispatcherCorpusTest} 的语料表里逐条回放。
 */
@DisplayName("命令分发器")
class CommandDispatcherTest {
    private static final String PLATFORM = "qq-onebot";

    private static final Long GROUP = 30003L;

    private NovaMessageSender sender;

    private CommandSettingsService settings;

    private RecordingCommand command;

    private CommandDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        AbstractDataSource dataSource = mock(AbstractDataSource.class);
        when(dataSource.getAllUsers()).thenReturn(List.of(configuredUser()));

        sender = mock(NovaMessageSender.class);
        settings = new CommandSettingsService(new NovaStateStore(new NovaCoreProperties()));
        command = new RecordingCommand();

        // 这一件问的是「谁应了、谁没应」，不问日志页；命令记事那一头由 TimelineHookTest 量
        dispatcher = new CommandDispatcher(providerOf(command), noFollowUps(), settings, dataSource, sender,
                new NovaCoreProperties(), TimelineWriter.NONE);
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
    @DisplayName("没有注册「菜单」命令时，认不出的消息不应把分发器带崩")
    void survivesMissingMenuCommand() {
        // 认不出的消息要回菜单，而菜单本身也只是一个命令 Bean。
        // 插件把它换掉或改名之后，这条路会找不到东西可回——那时候该是安静地不回，
        // 而不是每条闲聊都在日志里抛一次空指针
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
    @DisplayName("被禁用的命令不应执行，但要说明它被关了")
    void skipsDisabledCommand() {
        settings.disable(PLATFORM, GROUP, "测试命令");

        dispatcher.onRemoteMessage(event(GROUP, "测试命令"));

        assertEquals(0, command.executions);
        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(sender).send(captor.capture());
        assertEquals("本群已关闭「测试命令」命令", captor.getValue().getContent());
    }

    @Test
    @DisplayName("冷却期内的连续命令只执行一次")
    void appliesCooldown() {
        dispatcher.onRemoteMessage(event(GROUP, "测试命令"));
        dispatcher.onRemoteMessage(event(GROUP, "测试命令"));

        assertEquals(1, command.executions);
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
        NovaCoreProperties properties = new NovaCoreProperties();
        properties.getCommand().getAdmins().add(1L);

        AbstractDataSource dataSource = mock(AbstractDataSource.class);
        when(dataSource.getAllUsers()).thenReturn(List.of(configuredUser()));
        CommandDispatcher withAdmins = new CommandDispatcher(providerOf(command), noFollowUps(), settings,
                dataSource, sender, properties, TimelineWriter.NONE);
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

    /**
     * 群聊消息一律带上「@ 了机器人」——不带 @ 的消息根本进不了分发这一步，
     * 那条判定归语料表管
     */
    private NovaRemoteMessageEvent roleEvent(Long group, String text, String role) {
        return new NovaRemoteMessageEvent(PLATFORM, "group", group, 1L, text, role, true);
    }

    private NovaRemoteMessageEvent event(Long group, String text) {
        return roleEvent(group, text, null);
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
    private ObjectProvider<NovaCommand> providerOf(NovaCommand command) {
        @SuppressWarnings("unchecked")
        ObjectProvider<NovaCommand> provider = mock(ObjectProvider.class);
        when(provider.iterator()).thenAnswer(invocation -> List.of(command).iterator());
        when(provider.orderedStream()).thenAnswer(invocation -> Stream.of(command));
        return provider;
    }

    /**
     * 没有任何追问认领方：本类量的是命令本身的路由与权限，与追问应答无关
     */
    private ObjectProvider<CommandFollowUp> noFollowUps() {
        return CommandDispatcherCorpusTest.noFollowUps();
    }

    /**
     * 记录调用情况的测试命令
     */
    private static class RecordingCommand implements NovaCommand {
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
