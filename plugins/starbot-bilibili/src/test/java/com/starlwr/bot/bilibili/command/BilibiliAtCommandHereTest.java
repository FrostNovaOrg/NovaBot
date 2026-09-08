package com.starlwr.bot.bilibili.command;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.command.CommandContext;
import com.starlwr.bot.core.datasource.AbstractDataSource;
import com.starlwr.bot.core.enums.PushTargetType;
import com.starlwr.bot.core.model.PushMessage;
import com.starlwr.bot.core.model.PushTarget;
import com.starlwr.bot.core.model.PushUser;
import com.starlwr.bot.core.sender.AtMode;
import com.starlwr.bot.core.service.AtSubscriptionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 「@我」类命令两句「在哪儿」须跟会话走：群里仍说「本群」，私聊不许再说「本群」。
 */
@DisplayName("私聊里不说本群")
class BilibiliAtCommandHereTest {
    private static final String PLATFORM = "qq-onebot";

    private static final long GROUP = 30003L;

    private static final long FRIEND = 20001L;

    private static final long SENDER = 2000000002L;

    private static final String GROUP_NOTE = "本群开播通知会先 @全体成员，@ 不成时才按这份名单 @ 人";

    private static final String GROUP_REPLY = "本群开播通知会 @全体成员，不用单独订阅";

    private static final String PRIVATE_NOTE = "这里开播通知会先 @全体成员，@ 不成时才按这份名单 @ 人";

    private static final String PRIVATE_REPLY = "这里开播通知会 @全体成员，不用单独订阅";

    private static final String EMPTY_ACT = "好的，测试主播开播时会 @ 你";

    @Test
    @DisplayName("群仍说本群；私聊不含本群；空模式与改前一致；两处已接 everyoneNotice")
    void groupKeepsPlaceWordPrivateOmitsItEmptyUnchangedAndWired() throws Exception {
        Fixture fixture = new Fixture();
        List<String> red = new ArrayList<>();

        try {
            fixture.setMode(PushTargetType.GROUP, AtMode.ALL_OR_SUBSCRIBERS);
            assertEquals(GROUP_NOTE, fixture.command.menuNote(fixture.group()));
            fixture.setMode(PushTargetType.GROUP, AtMode.ALL);
            assertEquals(GROUP_REPLY, fixture.command.execute(fixture.group()).content());
        } catch (Throwable t) {
            red.add("① " + t.getMessage());
        }

        try {
            fixture.setMode(PushTargetType.FRIEND, AtMode.ALL_OR_SUBSCRIBERS);
            String note = fixture.command.menuNote(fixture.priv());
            assertFalse(note.contains("本群"), note);
            assertEquals(PRIVATE_NOTE, note);
            fixture.setMode(PushTargetType.FRIEND, AtMode.ALL);
            String reply = fixture.command.execute(fixture.priv()).content();
            assertFalse(reply.contains("本群"), reply);
            assertEquals(PRIVATE_REPLY, reply);
        } catch (Throwable t) {
            red.add("② " + t.getMessage());
        }

        try {
            fixture.clearModes();
            assertEquals("", fixture.command.menuNote(fixture.group()));
            assertEquals("", fixture.command.menuNote(fixture.priv()));
            assertEquals(EMPTY_ACT, fixture.command.execute(fixture.group()).content());
            assertEquals(EMPTY_ACT, fixture.command.execute(fixture.priv()).content());
        } catch (Throwable t) {
            red.add("③ " + t.getMessage());
        }

        try {
            String source = Files.readString(sourceFile(), StandardCharsets.UTF_8);
            assertTrue(source.contains("everyoneNotice(CommandContext context, boolean fallback)"),
                    "应抽出按语境取文案的 everyoneNotice");
            int wired = source.split("everyoneNotice\\(context", -1).length - 1;
            assertEquals(2, wired, "menuNote 与 execute 两处都应接 everyoneNotice(context, …)，实际 " + wired);
        } catch (Throwable t) {
            red.add("④ " + t.getMessage());
        }

        if (!red.isEmpty()) {
            fail(red.size() + " 问红：" + String.join("；", red));
        }
    }

    private static Path sourceFile() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve(
                    "plugins/starbot-bilibili/src/main/java/com/starlwr/bot/bilibili/command/BilibiliAtCommand.java");
            if (Files.exists(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("找不到 BilibiliAtCommand.java");
    }

    private static class Fixture {
        private final PushUser streamer = streamer();

        private final BilibiliLiveAtMeCommand command;

        Fixture() {
            AbstractDataSource dataSource = mock(AbstractDataSource.class);
            when(dataSource.getUsers("bilibili")).thenReturn(List.of(streamer));
            AtSubscriptionService subscriptions = mock(AtSubscriptionService.class);
            when(subscriptions.subscribe(anyString(), any(), any(), anyString(), any()))
                    .thenReturn(AtSubscriptionService.Result.OK);
            command = new BilibiliLiveAtMeCommand(dataSource, mock(BilibiliStreamerChoice.class),
                    subscriptions);
        }

        CommandContext group() {
            return context(PushTargetType.GROUP, GROUP);
        }

        CommandContext priv() {
            return context(PushTargetType.FRIEND, FRIEND);
        }

        void setMode(PushTargetType type, AtMode mode) {
            for (PushTarget target : streamer.getTargets()) {
                if (target.getType() != type) {
                    continue;
                }
                target.getMessages().clear();
                PushMessage message = new PushMessage();
                message.setHandler(BilibiliAtNoticeKind.LIVE.handlerName());
                message.setParamsJsonObject(new JSONObject().fluentPut(AtMode.PARAM_KEY, mode.key()));
                target.getMessages().add(message);
            }
        }

        void clearModes() {
            for (PushTarget target : streamer.getTargets()) {
                target.getMessages().clear();
            }
        }

        private static CommandContext context(PushTargetType type, long num) {
            return new CommandContext(PLATFORM, type, num, SENDER, "开播@我", List.of(), "开播@我");
        }

        private static PushUser streamer() {
            PushUser user = new PushUser();
            user.setUid(10001L);
            user.setUname("测试主播");
            user.setPlatform("bilibili");
            user.setTargets(List.of(target(PushTargetType.GROUP, GROUP), target(PushTargetType.FRIEND, FRIEND)));
            return user;
        }

        private static PushTarget target(PushTargetType type, long num) {
            PushTarget target = new PushTarget();
            target.setPlatform(PLATFORM);
            target.setType(type);
            target.setNum(num);
            target.setMessages(new ArrayList<>());
            return target;
        }
    }
}
