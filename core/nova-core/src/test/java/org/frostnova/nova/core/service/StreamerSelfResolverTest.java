package org.frostnova.nova.core.service;

import org.frostnova.nova.core.command.CommandContext;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.model.PushTarget;
import org.frostnova.nova.core.model.PushUser;
import org.junit.jupiter.api.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StreamerSelfResolverTest {
    private static final long SELF = 990000001001L;
    private AbstractDataSource source;
    private StreamerSelfResolver resolver;
    private PushUser user;
    private PushTarget target;

    @BeforeEach void setUp() {
        target = new PushTarget(); target.setPlatform("qq-onebot"); target.setNum(SELF);
        target.setType(PushTargetType.FRIEND); target.setStreamerSelf(true);
        user = new PushUser(); user.setPlatform("bilibili"); user.setUid(990000000101L); user.setTargets(List.of(target));
        source = mock(AbstractDataSource.class); when(source.getAllUsers()).thenReturn(List.of(user));
        resolver = new StreamerSelfResolver(source);
        assertEquals(user, resolver.resolve(context(PushTargetType.FRIEND, SELF)).orElseThrow());
    }
    private CommandContext context(PushTargetType type, long sender) {
        return new CommandContext("qq-onebot", type, SELF, sender, "恢复", List.of(), "恢复");
    }
    @Test @DisplayName("通道停用后立即认不出，避免已关闭的接收号码继续改档案")
    void disabledTargetCannotResolve() {
        target.setEnabled(false); assertTrue(resolver.resolve(context(PushTargetType.FRIEND, SELF)).isEmpty());
    }
    @Test @DisplayName("主播停用后立即认不出，避免向停用主播继续提供私人设置入口")
    void disabledStreamerCannotResolve() {
        user.setEnabled(false); assertTrue(resolver.resolve(context(PushTargetType.FRIEND, SELF)).isEmpty());
    }
    @Test @DisplayName("发件人必须等于会话号码，避免伪造会话身份修改档案")
    void senderMustMatchConversation() {
        assertTrue(resolver.resolve(context(PushTargetType.FRIEND, SELF + 1)).isEmpty());
    }
    @Test @DisplayName("别人的会话即使填本人发件号也不能认人，避免冒用主播号码修改设置")
    void ownerSenderInAnotherConversationCannotResolve() {
        var forged = new CommandContext("qq-onebot", PushTargetType.FRIEND, SELF + 1, SELF,
                "改成", List.of("2"), "改成 2");
        assertTrue(resolver.resolve(forged).isEmpty());
    }
    @Test @DisplayName("会话必须是好友私聊，避免同号群消息冒充本人")
    void onlyFriendConversationCanResolve() {
        assertTrue(resolver.resolve(context(PushTargetType.GROUP, SELF)).isEmpty());
    }
    @Test @DisplayName("本人通道也必须是好友类型，避免错误配置的群通道被认成本人")
    void targetMustAlsoBeFriend() {
        target.setType(PushTargetType.GROUP);
        assertTrue(resolver.resolve(context(PushTargetType.FRIEND, SELF)).isEmpty());
    }
    @Test @DisplayName("多位主播同号时不猜身份，避免把一人的设置记到另一人名下")
    void ambiguousNumberCannotResolve() {
        var other = new PushUser(); other.setPlatform("bilibili"); other.setUid(990000000102L); other.setTargets(List.of(target));
        when(source.getAllUsers()).thenReturn(List.of(user, other));
        assertTrue(resolver.resolve(context(PushTargetType.FRIEND, SELF)).isEmpty());
    }
}
