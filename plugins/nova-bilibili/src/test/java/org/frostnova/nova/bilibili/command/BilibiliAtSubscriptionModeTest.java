package org.frostnova.nova.bilibili.command;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.command.CommandContext;
import org.frostnova.nova.core.command.CommandSettingsService;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.model.PushMessage;
import org.frostnova.nova.core.model.PushTarget;
import org.frostnova.nova.core.model.PushUser;
import org.frostnova.nova.core.sender.AtMode;
import org.frostnova.nova.core.service.AtSubscriptionService;
import org.frostnova.nova.core.service.NovaStateStore;
import org.frostnova.nova.core.service.SessionMemberNames;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("订阅按点名主播的推送方式判断")
class BilibiliAtSubscriptionModeTest {
    private static final String PLATFORM = "qq-onebot";
    private static final long GROUP = 38025617409263L;
    private static final long SENDER = 58025617409263L;
    private static final long ALL_UID = 19604318752096L;
    private static final long SUBSCRIBER_UID = 19604318752097L;

    @Test
    @DisplayName("混合推送群：给 @全体主播订阅不再假报成功，按名单的主播照常订上")
    void subscriptionUsesTheNamedStreamersMode() {
        PushUser all = streamer(ALL_UID, "主播甲", AtMode.ALL);
        PushUser byList = streamer(SUBSCRIBER_UID, "主播乙", AtMode.SUBSCRIBERS);
        AbstractDataSource dataSource = mock(AbstractDataSource.class);
        when(dataSource.getUsers("bilibili")).thenReturn(List.of(all, byList));
        AtSubscriptionService subscriptions = new AtSubscriptionService(new NovaStateStore(new NovaCoreProperties()));
        BilibiliStreamerChoice choice = mock(BilibiliStreamerChoice.class);
        CommandSettingsService settings = mock(CommandSettingsService.class);

        for (BilibiliAtSubscribeCommand command : List.of(
                new BilibiliLiveAtMeCommand(dataSource, choice, subscriptions, settings),
                new BilibiliDynamicAtMeCommand(dataSource, choice, subscriptions, settings))) {
            String type = command.noticeKind().type();
            CommandContext allContext = context(command.name(), "主播甲");
            assertTrue(command.availableIn(allContext), "还有主播按名单提醒，群里的订阅命令应保留");
            assertEquals("本群，主播甲的" + command.typeName()
                            + "通知会 @全体成员，单独订阅也不会被单独 @，不用单独订阅",
                    command.execute(allContext).content(), "应点出主播并说明订阅不起作用");
            assertEquals(List.of(), subscriptions.list(PLATFORM, GROUP, ALL_UID, type), "无效订阅不能加入名单");

            assertEquals("好的，主播乙" + command.typeName() + "时会 @ 你。再发「" + command.name() + "」可取消",
                    command.execute(context(command.name(), "主播乙")).content());
            assertEquals(List.of(SENDER), subscriptions.list(PLATFORM, GROUP, SUBSCRIBER_UID, type));

            // 已有订阅的取消与名单查询仍可使用，不受点名主播的新检查影响。
            subscriptions.subscribe(PLATFORM, GROUP, ALL_UID, type, SENDER);
            BilibiliAtListCommand list = new BilibiliAtListCommand(dataSource, choice, subscriptions,
                    new StaticListableBeanFactory().getBeanProvider(SessionMemberNames.class), settings);
            assertTrue(list.execute(context("@名单", command.noticeKind().noticeName(), "主播甲"))
                    .content().contains("主播甲 的" + command.typeName() + "提醒共 1 人订阅"));
            assertTrue(command.execute(context(command.name(), "主播甲")).content().startsWith("已取消"));
            assertFalse(subscriptions.contains(PLATFORM, GROUP, ALL_UID, type, SENDER));
            subscriptions.subscribe(PLATFORM, GROUP, ALL_UID, type, SENDER);
            assertTrue(command.execute(context(command.cancelSpelling(), "主播甲")).content().startsWith("已取消"));

            // 全体失败时会用名单的模式仍允许订阅。
            all.getTargets().get(0).getMessages().stream()
                    .filter(message -> command.isKind(message, command.noticeKind()))
                    .forEach(message -> message.setParamsJsonObject(
                            new JSONObject().fluentPut(AtMode.PARAM_KEY, AtMode.ALL_OR_SUBSCRIBERS.key())));
            assertTrue(command.execute(allContext).content().startsWith("好的，主播甲"));
            assertEquals(List.of(SENDER), subscriptions.list(PLATFORM, GROUP, ALL_UID, type));
        }
    }

    @Test
    @DisplayName("订了一位没往本群推这类通知的主播，回复得说清现在收不到，不再说会 @ 你")
    void subscriptionWhenNothingIsPushedHere() {
        // 主播甲推到本群，但这两类通知的那几条推送都关着，群里一条也收不到；照旧回「会 @ 你」就是假话
        PushUser silent = streamer(ALL_UID, "主播甲", AtMode.SUBSCRIBERS);
        silent.getTargets().get(0).getMessages().forEach(message -> message.setEnabled(false));
        AbstractDataSource dataSource = mock(AbstractDataSource.class);
        when(dataSource.getUsers("bilibili")).thenReturn(List.of(silent));
        AtSubscriptionService subscriptions = new AtSubscriptionService(new NovaStateStore(new NovaCoreProperties()));
        BilibiliStreamerChoice choice = mock(BilibiliStreamerChoice.class);
        CommandSettingsService settings = mock(CommandSettingsService.class);

        for (BilibiliAtSubscribeCommand command : List.of(
                new BilibiliLiveAtMeCommand(dataSource, choice, subscriptions, settings),
                new BilibiliDynamicAtMeCommand(dataSource, choice, subscriptions, settings))) {
            String type = command.noticeKind().type();
            String reply = command.execute(context(command.name(), "主播甲")).content();
            assertTrue(reply.contains("没推到本群"),
                    "回复得点明这类通知现在没推到本群，否则群友以为订上就能收到；实际回复是「" + reply + "」");
            assertEquals(List.of(SENDER), subscriptions.list(PLATFORM, GROUP, ALL_UID, type),
                    "照样订上，等管理员开了推送就用得上");
        }
    }

    private static CommandContext context(String command, String... args) {
        return new CommandContext(PLATFORM, PushTargetType.GROUP, GROUP, SENDER, command, List.of(args), command);
    }

    private static PushUser streamer(long uid, String name, AtMode mode) {
        PushTarget target = new PushTarget();
        target.setPlatform(PLATFORM);
        target.setType(PushTargetType.GROUP);
        target.setNum(GROUP);
        target.setMessages(new ArrayList<>());
        for (BilibiliAtNoticeKind kind : BilibiliAtNoticeKind.values()) {
            PushMessage message = new PushMessage();
            message.setHandler(kind.handlerName());
            message.setParamsJsonObject(new JSONObject().fluentPut(AtMode.PARAM_KEY, mode.key()));
            target.getMessages().add(message);
        }
        PushUser user = new PushUser();
        user.setPlatform("bilibili");
        user.setUid(uid);
        user.setUname(name);
        user.setTargets(List.of(target));
        return user;
    }
}
