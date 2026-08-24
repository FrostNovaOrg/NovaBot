package com.starlwr.bot.bilibili.command;

import com.starlwr.bot.bilibili.model.Up;
import com.starlwr.bot.bilibili.util.BilibiliApiUtil;
import com.starlwr.bot.core.command.CommandContext;
import com.starlwr.bot.core.command.CommandReply;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.enums.PushTargetType;
import com.starlwr.bot.core.service.StarBotStateStore;
import com.starlwr.bot.core.service.UserBindingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 绑定命令测试
 * <p>
 * 重点在「查到昵称才让确认、确认过才落库」这条两步流程：
 * 少了任何一步，打错一位数字就会静默绑到别人身上。
 */
@DisplayName("绑定命令")
class BilibiliBindCommandTest {
    private static final String PLATFORM = "qq-onebot";

    private static final Long GROUP = 30003L;

    private static final Long QQ = 2000000002L;

    private static final Long UID = 500000004L;

    private BilibiliApiUtil api;

    private UserBindingService bindings;

    private BilibiliBindCommand bind;

    private BilibiliBindConfirmCommand confirm;

    @BeforeEach
    void setUp() {
        api = mock(BilibiliApiUtil.class);
        when(api.getUpInfoByUid(UID)).thenReturn(new Up(UID, "测试主播", null));

        bindings = new UserBindingService(new StarBotStateStore(new StarBotCoreProperties()));
        bind = new BilibiliBindCommand(api, bindings);
        confirm = new BilibiliBindConfirmCommand(bind);
    }

    @Test
    @DisplayName("不带 uid 时应说明用法")
    void hintsWhenNoArgument() {
        String content = bind.execute(context("绑定")).content();

        System.out.println("[绑定·不带参数] 用户看到的正文：\n" + content);
        assertTrue(content.contains("uid"), content);
        assertNoCopyableNumber(content);
    }

    @Test
    @DisplayName("uid 不是数字时应明确指出")
    void rejectsNonNumericUid() {
        CommandReply reply = bind.execute(context("绑定", "我的主页"));

        System.out.println("[绑定·参数不是数字] 用户看到的正文：\n" + reply.content());
        assertTrue(reply.content().contains("纯数字"), reply.content());
        assertNoCopyableNumber(reply.content());
        assertEquals(Optional.empty(), bindings.get(PLATFORM, "bilibili", QQ));
    }

    @Test
    @DisplayName("查不到账号时不应留下待确认状态")
    void doesNotStagePendingWhenLookupFails() {
        when(api.getUpInfoByUid(anyLong())).thenThrow(new RuntimeException("接口不可用"));

        assertTrue(bind.execute(context("绑定", "999")).content().contains("查不到"));
        assertTrue(confirm.execute(context("确认绑定")).content().contains("没有待确认"));
    }

    @Test
    @DisplayName("第一步只回昵称让人核对，不应直接落库")
    void firstStepOnlyAsksForConfirmation() {
        CommandReply reply = bind.execute(context("绑定", String.valueOf(UID)));

        assertTrue(reply.content().contains("测试主播"));
        assertTrue(reply.content().contains("确认绑定"));
        assertEquals(Optional.empty(), bindings.get(PLATFORM, "bilibili", QQ));
    }

    @Test
    @DisplayName("确认后才写入绑定")
    void confirmCompletesBinding() {
        bind.execute(context("绑定", String.valueOf(UID)));

        assertTrue(confirm.execute(context("确认绑定")).content().contains("已绑定"));
        assertEquals(Optional.of(UID), bindings.get(PLATFORM, "bilibili", QQ));
    }

    @Test
    @DisplayName("重复确认不应再次生效")
    void confirmIsSingleUse() {
        bind.execute(context("绑定", String.valueOf(UID)));
        confirm.execute(context("确认绑定"));

        assertTrue(confirm.execute(context("确认绑定")).content().contains("没有待确认"));
    }

    @Test
    @DisplayName("待确认状态应按发送者隔离，不能确认到别人的 uid 上")
    void pendingIsolatedPerSender() {
        bind.execute(context("绑定", String.valueOf(UID)));

        CommandContext other = new CommandContext(PLATFORM, PushTargetType.GROUP, GROUP, 10000L,
                "确认绑定", java.util.List.of(), "确认绑定");
        assertTrue(confirm.execute(other).content().contains("没有待确认"));
        assertEquals(Optional.empty(), bindings.get(PLATFORM, "bilibili", 10000L));
    }

    /**
     * 用法提示里不许出现<b>能被照抄的号</b>
     * <p>
     * 🔴 原来这两条断言只核 {@code contains("uid")}，示例号是真号还是占位记号<b>它都绿</b>。
     * 示例里那串 9 位数在哔哩哔哩是合法 uid，用户照着抄就绑到别人头上；手册那处更在
     * 「照着做」的编号步骤里，链接可点。
     * <p>
     * 断言钉的是<b>性质</b>而不是那一个字符串：正文里不得有连续 4 位以上的数字。
     * 这样它拦得住「为了过测试把占位记号又写回像号的东西」——把 {@code <你的 uid>}
     * 换回任何一串号，这条都会红。
     */
    private static void assertNoCopyableNumber(String content) {
        assertFalse(java.util.regex.Pattern.compile("\\d{4,}").matcher(content).find(),
                "用法提示里不该出现能被照抄的号，实际正文：" + content);
    }

    private CommandContext context(String command, String... args) {
        return new CommandContext(PLATFORM, PushTargetType.GROUP, GROUP, QQ, command, Arrays.asList(args), command);
    }
}
