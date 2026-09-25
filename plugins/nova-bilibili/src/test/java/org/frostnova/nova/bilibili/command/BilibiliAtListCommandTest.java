package org.frostnova.nova.bilibili.command;

import org.frostnova.nova.core.command.CommandContext;
import org.frostnova.nova.core.command.CommandReply;
import org.frostnova.nova.core.command.CommandSettingsService;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.model.PushTarget;
import org.frostnova.nova.core.model.PushUser;
import org.frostnova.nova.core.service.AtSubscriptionService;
import org.frostnova.nova.core.service.SessionMemberNames;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mockingDetails;

/**
 * 「{@code @名单}」回名单时要问几次名字
 * <p>
 * 名单里的人名是发回群里给人看的，得逐个换成群昵称。换名字这一步走的是取名字的服务，
 * 而它是<b>要出远门的</b>：群里几十个人就出几十趟远门，一次回话能把群接口打穿。
 * 因此名单应当<b>一次把这批人问全</b>——一次回话每个群问一趟，而不是一人一趟。
 *
 * <h2>取名字那一支不顶用时，名单照出</h2>
 * 这条命令在没装推送平台适配器的机器上也得能跑：名字取不到就写占位，
 * <b>不能因为一个名字服务就把整张名单变成一句不回</b>。
 */
@DisplayName("@名单 回名单时取名字只问一次")
class BilibiliAtListCommandTest {
    private static final String PLATFORM = "qq-onebot";

    private static final long GROUP = 30003L;

    private static final long SENDER = 2000000002L;

    private static final long STREAMER = 10001L;

    /** 每类各这么多人：一到取名字的上限，正好把「逐人问」的次数撑到最刺眼 */
    private static final int PER_KIND = 30;

    @Test
    @DisplayName("两类各 30 人的一次回话，只向取名字的服务问一次，且一次问全")
    void oneReplyAsksMemberNamesOnceWithEveryone() {
        AbstractDataSource dataSource = dataSource();
        AtSubscriptionService subscriptions = subscriptions();
        SessionMemberNames memberNames = mock(SessionMemberNames.class);
        doReturn(true).when(memberNames).supports(anyString());

        CommandReply reply = command(dataSource, subscriptions, memberNames)
                .execute(context("@名单"));

        long asked = askedTimes(memberNames);
        assertEquals(1, asked,
                "一次回话该向取名字的服务问一次，实际问了 " + asked + " 次");
        Collection<Long> batch = askedUids(memberNames);
        assertEquals(PER_KIND * 2, batch.size(),
                "那一次该把两类的人一起问全，实际问到 " + batch.size() + " 人");
        assertTrue(reply.content().contains("开播"), reply.content());
        assertTrue(reply.content().contains("动态"), reply.content());
    }

    @Test
    @DisplayName("取名字那一支问了就抛时，名单照出，名字退成「（昵称未知）」")
    void listStillPrintsWhenNameServiceThrows() {
        AbstractDataSource dataSource = dataSource();
        AtSubscriptionService subscriptions = subscriptions();
        SessionMemberNames broken = mock(SessionMemberNames.class, invocation -> {
            if ("supports".equals(invocation.getMethod().getName())) {
                return true;
            }
            return fail("取名字这一支抛了，名单却没照出");
        });

        CommandReply reply = command(dataSource, subscriptions, broken)
                .execute(context("@名单"));

        assertTrue(reply.content().contains("（昵称未知）"), reply.content());
        assertFalse(reply.content().contains("" + STREAMER), "名字取不到也不是把账号写出去的理由：" + reply.content());
    }

    @Test
    @DisplayName("认领平台那一步出错时，名单照出，名字写「（昵称未知）」")
    void listStillPrintsWhenClaimingThePlatformThrows() {
        AbstractDataSource dataSource = dataSource();
        AtSubscriptionService subscriptions = subscriptions();
        SessionMemberNames broken = mock(SessionMemberNames.class, invocation -> {
            throw new IllegalStateException("取昵称这一步出错");
        });

        CommandReply reply = command(dataSource, subscriptions, broken)
                .execute(context("@名单"));

        assertTrue(reply.content().contains("（昵称未知）"), reply.content());
        assertFalse(reply.content().contains("" + STREAMER), "名字取不到也不是把账号写出去的理由：" + reply.content());
    }

    // ── 夹具 ────────────────────────────────────────────────────────────

    /**
     * 取名字的服务被问了几次
     * <p>
     * 问的是它被<b>动了几次手</b>，不问方法名：名字是一批取还是逐个取，
     * 正是这条要量的事，钉死方法名的话换一种取法这条就编不过了。
     * 「负责哪个平台」那一下不算数。
     */
    private static long askedTimes(SessionMemberNames memberNames) {
        return mockingDetails(memberNames).getInvocations().stream()
                .filter(invocation -> !"supports".equals(invocation.getMethod().getName()))
                .count();
    }

    /**
     * 那一次问到的都是谁
     */
    @SuppressWarnings("unchecked")
    private static Collection<Long> askedUids(SessionMemberNames memberNames) {
        return mockingDetails(memberNames).getInvocations().stream()
                .filter(invocation -> !"supports".equals(invocation.getMethod().getName()))
                .map(invocation -> {
                    Object[] arguments = invocation.getArguments();
                    Object last = arguments[arguments.length - 1];
                    if (last instanceof Collection<?> uids) {
                        return (Collection<Long>) uids;
                    }
                    return List.<Long>of((Long) last);
                })
                .flatMap(Collection::stream)
                .collect(Collectors.toList());
    }

    private AbstractDataSource dataSource() {
        AbstractDataSource dataSource = mock(AbstractDataSource.class);
        when(dataSource.getUsers("bilibili")).thenReturn(List.of(streamer()));
        return dataSource;
    }

    private AtSubscriptionService subscriptions() {
        AtSubscriptionService subscriptions = mock(AtSubscriptionService.class);
        when(subscriptions.list(PLATFORM, GROUP, STREAMER, "live")).thenReturn(uids(0));
        when(subscriptions.list(PLATFORM, GROUP, STREAMER, "dynamic")).thenReturn(uids(PER_KIND));
        return subscriptions;
    }

    private static List<Long> uids(int from) {
        List<Long> uids = new ArrayList<>();
        for (int i = 0; i < PER_KIND; i++) {
            uids.add((long) (from + i + 1));
        }
        return uids;
    }

    private static PushUser streamer() {
        PushTarget target = new PushTarget();
        target.setPlatform(PLATFORM);
        target.setType(PushTargetType.GROUP);
        target.setNum(GROUP);
        target.setMessages(new ArrayList<>());

        PushUser user = new PushUser();
        user.setUid(STREAMER);
        user.setUname("测试主播");
        user.setPlatform("bilibili");
        user.setTargets(List.of(target));
        return user;
    }

    private static CommandContext context(String typed, String... args) {
        return new CommandContext(PLATFORM, PushTargetType.GROUP, GROUP, SENDER,
                typed, Arrays.asList(args), typed);
    }

    /**
     * 按构造参数的类型现配依赖
     * <p>
     * 这条命令要接的东西会跟着「取名字那一支按平台挑实现」改形状，而这条要量的事
     * 与它接的是单个实现还是一把实现无关。钉死构造参数表的话，改形状那天这条就编不过了，
     * 量的也就不是它想量的那件事。
     */
    private BilibiliAtListCommand command(AbstractDataSource dataSource, AtSubscriptionService subscriptions,
                                          SessionMemberNames memberNames) {
        Map<Class<?>, Object> parts = new LinkedHashMap<>();
        parts.put(AbstractDataSource.class, dataSource);
        parts.put(BilibiliStreamerChoice.class, mock(BilibiliStreamerChoice.class));
        parts.put(AtSubscriptionService.class, subscriptions);
        parts.put(SessionMemberNames.class, memberNames);
        parts.put(CommandSettingsService.class, mock(CommandSettingsService.class));

        for (Constructor<?> constructor : BilibiliAtListCommand.class.getDeclaredConstructors()) {
            Object[] arguments = Arrays.stream(constructor.getParameterTypes())
                    .map(parameter -> ObjectProvider.class.isAssignableFrom(parameter)
                            ? providersOf(memberNames)
                            : parts.get(parameter))
                    .toArray();
            constructor.setAccessible(true);
            try {
                return (BilibiliAtListCommand) constructor.newInstance(arguments);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("「@名单」这条命令造不出来", e);
            }
        }
        throw new IllegalStateException("「@名单」这条命令没有构造器");
    }

    /**
     * 「一把实现」形状的替身：只装这一个，按平台认领
     */
    private static ObjectProvider<?> providersOf(SessionMemberNames memberNames) {
        List<SessionMemberNames> holders = memberNames == null ? List.of() : List.of(memberNames);
        @SuppressWarnings("unchecked")
        ObjectProvider<SessionMemberNames> provider = mock(ObjectProvider.class);
        when(provider.iterator()).thenAnswer(invocation -> holders.iterator());
        when(provider.stream()).thenAnswer(invocation -> holders.stream());
        when(provider.orderedStream()).thenAnswer(invocation -> holders.stream());
        when(provider.getIfAvailable()).thenAnswer(invocation ->
                holders.isEmpty() ? null : holders.get(0));
        return provider;
    }
}
