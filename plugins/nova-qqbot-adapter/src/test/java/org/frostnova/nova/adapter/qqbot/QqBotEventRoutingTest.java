package org.frostnova.nova.adapter.qqbot;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.event.remote.NovaRemoteMessageEvent;
import org.frostnova.nova.core.service.NovaSenderService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationListener;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.frostnova.nova.adapter.qqbot.QqBotDirectory.ProactiveStatus.DISABLED;
import static org.frostnova.nova.adapter.qqbot.QqBotDirectory.ProactiveStatus.ENABLED;
import static org.frostnova.nova.adapter.qqbot.QqBotDirectory.SwitchSource.EVENT;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class QqBotEventRoutingTest {
    private static final String GROUP = "fixture-group-openid";
    private static final String OTHER_GROUP = "fixture-other-group-openid";
    private static final String MEMBER = "fixture-member-openid";

    @TempDir
    Path dataDirectory;

    private static final String WELCOME = "大家好，我是这个群的推送机器人。要在群里收到开播通知，请群主在手机 QQ 打开我的"
            + "『机器人主动在群聊内发言』：群成员列表里点我 → 右上角设置 → 打开。管理员还要到控制台连接页给这个群起个名字。";

    @Test
    void joiningWelcomesTheGroupOnceWithTheOuterEventId() {
        var rig = new Rig();
        rig.run(listener -> {
            var joined = rig.group(GROUP).fluentPut("id", "inner-id");
            listener.onEvent("GROUP_ADD_ROBOT", joined, "GROUP_ADD_ROBOT:x", 1L);
            listener.onEvent("GROUP_ADD_ROBOT", joined, "GROUP_ADD_ROBOT:x", 2L);
            listener.onEvent("GROUP_ADD_ROBOT", joined, " ", 3L);
            rig.drainFetches();
            assertEquals(List.of(JSONObject.of("msg_type", 0, "content", WELCOME, "event_id", "GROUP_ADD_ROBOT:x")),
                    rig.posts);
        });
    }

    @ParameterizedTest
    @CsvSource({"40034025,400", "40034026,400", "40034027,400", "40034026,429"})
    @ExtendWith(OutputCaptureExtension.class)
    void rejectedWelcomeIsLoggedOnceWithoutSwitchingToProactive(int code, int status, CapturedOutput output) {
        var rig = new Rig();
        rig.welcomeResponse = JSONObject.of("code", code);
        rig.welcomeStatus = status;
        rig.run(listener -> {
            listener.onEvent("GROUP_ADD_ROBOT", rig.group(GROUP), "GROUP_ADD_ROBOT:x", 1L);
            rig.drainFetches();
            assertEquals(1, rig.posts.size());
            assertTrue(output.getAll().lines().anyMatch(l -> l.contains("INFO") && l.contains(String.valueOf(code))));
            assertFalse(output.getAll().contains(GROUP));
        });
    }

    @Test
    void joinedGroupCanSendTheMenuCommand() {
        var rig = new Rig();
        rig.run(listener -> {
            listener.onEvent("GROUP_ADD_ROBOT", rig.group(GROUP), "joined", 1L);
            long groupNumber = rig.directory.groupNumber(GROUP).orElseThrow();
            assertEquals(rig.now, rig.directory.group(groupNumber).orElseThrow().joinedAt());
            listener.onEvent("GROUP_AT_MESSAGE_CREATE", rig.message("menu", " 菜单 "), "dispatch", 2L);

            assertEquals(1, rig.events.size());
            var message = rig.events.get(0);
            assertEquals("qq-official", message.getPlatform());
            assertEquals("group", message.getMessageType());
            assertEquals(groupNumber, message.getNum());
            assertEquals(rig.directory.memberNumber(MEMBER).orElseThrow(), message.getSenderUid());
            assertEquals("菜单", message.getText());
            assertEquals("admin", message.getSenderRole());
            assertTrue(message.isMentionsBot());
            assertEquals("menu", message.getMessageId());
        });
    }

    @Test
    void replayedMessageDoesNotRunTheCommandTwice() {
        var rig = new Rig();
        rig.run(listener -> {
            var menu = rig.message("same-message", "菜单");
            listener.onEvent("GROUP_AT_MESSAGE_CREATE", menu, "before-reconnect", 1L);
            listener.onEvent("GROUP_AT_MESSAGE_CREATE", menu, "after-resume", 2L);
            assertEquals(List.of("same-message"), rig.events.stream().map(NovaRemoteMessageEvent::getMessageId).toList());
            verify(rig.directory).memberSeen("12345678", MEMBER, rig.now);
        });
    }

    @Test
    void replayedJoinDoesNotRegisterOrRequestTheGroupNameAgain() {
        var rig = new Rig();
        rig.run(listener -> {
            var joined = rig.group(GROUP);
            listener.onEvent("GROUP_ADD_ROBOT", joined, "same-join", 1L);
            rig.drainFetches();
            listener.onEvent("GROUP_ADD_ROBOT", joined, "same-join", 2L);
            rig.drainFetches();
            verify(rig.directory).groupJoined(GROUP, rig.now);
            assertEquals(1, rig.nameRequests.get());
        });
    }

    @Test
    void firstMentionMakesAGroupJoinedWhileOfflineSelectable() {
        var rig = new Rig();
        rig.run(listener -> {
            assertTrue(rig.directory.groupNumber(GROUP).isEmpty());
            listener.onEvent("GROUP_AT_MESSAGE_CREATE", rig.message("first", "菜单"), null, 1L);
            rig.drainFetches();
            var groups = rig.directory.snapshots(false).get(0).groups();
            assertEquals(1, groups.size());
            assertEquals("开播提醒群", groups.get(0).name());
            assertEquals(1, rig.events.size());
            assertEquals(groups.get(0).num(), rig.events.get(0).getNum());
        });
    }

    @Test
    void friendAndDirectMessageEventsLeaveTheDirectoryEmpty() {
        var rig = new Rig();
        rig.run(listener -> {
            for (String name : List.of("FRIEND_ADD", "FRIEND_DEL", "C2C_MESSAGE_CREATE", "C2C_MSG_REJECT",
                    "C2C_MSG_RECEIVE")) {
                listener.onEvent(name, rig.message(name, "菜单"), name, 1L);
            }
            assertTrue(rig.directory.snapshots(false).get(0).groups().isEmpty());
            assertTrue(rig.directory.memberNumber(MEMBER).isEmpty());
            assertTrue(rig.events.isEmpty());
        });
    }

    @Test
    void botAuthorsAreIgnored() {
        var rig = new Rig();
        rig.run(listener -> {
            var message = rig.message("bot-message", "菜单");
            message.getJSONObject("author").put("bot", true);
            listener.onEvent("GROUP_AT_MESSAGE_CREATE", message, null, 1L);
            assertTrue(rig.events.isEmpty());
            assertTrue(rig.directory.groupNumber(GROUP).isEmpty());
        });
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void staleMessagesAreLoggedWithoutOpenIds(CapturedOutput output) {
        var rig = new Rig();
        rig.run(listener -> {
            var old = rig.message("old-message", "菜单");
            old.put("timestamp", rig.now.minus(Duration.ofMinutes(40)).toString());
            listener.onEvent("GROUP_AT_MESSAGE_CREATE", old, null, 1L);
            assertTrue(rig.events.isEmpty());
            assertTrue(rig.directory.groupNumber(GROUP).isEmpty());
        });
        assertTrue(output.getAll().lines().anyMatch(line -> line.contains("INFO") && line.contains("30 分钟")));
        assertFalse(output.getAll().contains(GROUP));
        assertFalse(output.getAll().contains(MEMBER));
    }

    @Test
    void blankQuoteTextReachesTheCore() {
        var rig = new Rig();
        rig.run(listener -> {
            var quote = rig.message("quote", " ");
            quote.put("message_type", 103);
            listener.onEvent("GROUP_AT_MESSAGE_CREATE", quote, null, 1L);
            assertEquals(List.of(""), rig.events.stream().map(NovaRemoteMessageEvent::getText).toList());
        });
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void throwingListenerDoesNotStopFollowingEventsOrLeakOpenIds(CapturedOutput output) {
        var rig = new Rig();
        rig.afterPublish = message -> {
            if (message.getMessageId().equals("failed")) {
                throw new IllegalStateException(GROUP + " " + MEMBER);
            }
        };
        rig.run(listener -> {
            var failed = rig.message("failed", "菜单");
            listener.onEvent("GROUP_AT_MESSAGE_CREATE", failed, null, 1L);
            listener.onEvent("GROUP_AT_MESSAGE_CREATE", failed, null, 2L);
            listener.onEvent("GROUP_AT_MESSAGE_CREATE", rig.message("next", "菜单"), null, 3L);
            assertEquals(List.of("failed", "next"), rig.events.stream().map(NovaRemoteMessageEvent::getMessageId).toList());
        });
        assertEquals(1, output.getAll().lines()
                .filter(line -> line.contains("WARN") && line.contains("GROUP_AT_MESSAGE_CREATE")).count());
        assertFalse(output.getAll().contains(GROUP));
        assertFalse(output.getAll().contains(MEMBER));
    }

    @Test
    void removalMakesTheGroupUnavailable() {
        var rig = new Rig();
        rig.run(listener -> {
            listener.onEvent("GROUP_ADD_ROBOT", rig.group(GROUP), "joined", 1L);
            listener.onEvent("GROUP_DEL_ROBOT", rig.group(GROUP), "removed", 2L);
            long groupNumber = rig.directory.groupNumber(GROUP).orElseThrow();
            assertTrue(rig.directory.group(groupNumber).orElseThrow().removed());
            assertTrue(rig.directory.snapshots(false).get(0).groups().isEmpty());
            assertTrue(rig.events.isEmpty());
        });
    }

    @Test
    void switchEventsRecordTheirSourceAndDeduplicateWithoutOuterIds() {
        var rig = new Rig();
        rig.run(listener -> {
            listener.onEvent("GROUP_MSG_RECEIVE", rig.group(GROUP), null, 1L);
            long groupNumber = rig.directory.groupNumber(GROUP).orElseThrow();
            assertEquals(new QqBotDirectory.Proactive(ENABLED, EVENT, rig.now),
                    rig.directory.group(groupNumber).orElseThrow().proactive());
            listener.onEvent("GROUP_MSG_REJECT", rig.group(GROUP), null, 2L);
            listener.onEvent("GROUP_MSG_RECEIVE", rig.group(GROUP), "", 3L);
            assertEquals(new QqBotDirectory.Proactive(DISABLED, EVENT, rig.now),
                    rig.directory.group(groupNumber).orElseThrow().proactive());

            listener.onEvent("GROUP_MSG_RECEIVE", rig.group(OTHER_GROUP), null, 4L);
            assertTrue(rig.directory.groupNumber(OTHER_GROUP).isPresent());
            listener.onEvent("GROUP_MSG_RECEIVE", JSONObject.of("group_openid", GROUP,
                    "timestamp", rig.now.plusSeconds(1).getEpochSecond()), null, 5L);
            assertEquals(new QqBotDirectory.Proactive(ENABLED, EVENT, rig.now.plusSeconds(1)),
                    rig.directory.group(groupNumber).orElseThrow().proactive());
            assertTrue(rig.events.isEmpty());
        });
    }

    @Test
    void replayHistoryKeepsAtMostFiveThousandEvents() {
        var rig = new Rig();
        rig.run(listener -> {
            var joined = rig.group(GROUP);
            for (long sequence = 1; sequence <= 5001; sequence++) {
                listener.onEvent("GROUP_ADD_ROBOT", joined, "join-" + sequence, sequence);
            }
            listener.onEvent("GROUP_ADD_ROBOT", joined, "join-2", 5002L);
            verify(rig.directory, times(5001)).groupJoined(GROUP, rig.now);
            listener.onEvent("GROUP_ADD_ROBOT", joined, "join-1", 5003L);
            verify(rig.directory, times(5002)).groupJoined(GROUP, rig.now);
        });
    }

    @Test
    void replayHistoryExpiresAnHourAfterTheFirstDelivery() {
        var rig = new Rig();
        rig.run(listener -> {
            var joined = rig.group(GROUP);
            listener.onEvent("GROUP_ADD_ROBOT", joined, "same-join", 1L);
            rig.scheduler.advance(Duration.ofHours(1).toMillis() - 1);
            listener.onEvent("GROUP_ADD_ROBOT", joined, "same-join", 2L);
            verify(rig.directory).groupJoined(GROUP, rig.now);
            rig.scheduler.advance(1);
            listener.onEvent("GROUP_ADD_ROBOT", joined, "same-join", 3L);
            verify(rig.directory, times(2)).groupJoined(GROUP, rig.now);
        });
    }

    private final class Rig {
        final Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        final QqBotSettings settings = new QqBotSettings(dataDirectory);
        final QqBotGatewayTest.ManualScheduler scheduler = new QqBotGatewayTest.ManualScheduler();
        final Queue<Runnable> fetches = new ArrayDeque<>();
        final List<JSONObject> posts = new ArrayList<>();
        int welcomeStatus = 200;
        JSONObject welcomeResponse = JSONObject.of("id", "welcome");
        final AtomicInteger nameRequests = new AtomicInteger();
        final List<NovaRemoteMessageEvent> events = new ArrayList<>();
        final QqBotDirectory directory;
        final QqBotHttpTransport transport;
        final QqBotWelcome welcome;
        Consumer<NovaRemoteMessageEvent> afterPublish = event -> { };

        Rig() {
            settings.save("12345678", "fixture-secret");
            transport = (method, uri, headers, body) -> {
                String path = uri.getPath();
                JSONObject response;
                if (path.equals("/app/getAppAccessToken")) {
                    response = JSONObject.of("access_token", "fixture-token", "expires_in", 7200);
                } else if (path.endsWith("/messages")) {
                    assertEquals("/v2/groups/" + GROUP + "/messages", path);
                    posts.add(JSONObject.parseObject(body));
                    return new QqBotHttpTransport.Response(welcomeStatus, welcomeResponse.toJSONString());
                } else if (path.endsWith("/info")) {
                    nameRequests.incrementAndGet();
                    response = JSONObject.of("group_name", "开播提醒群");
                } else {
                    assertTrue(path.endsWith("/bot_state"));
                    response = JSONObject.of("member_role", "member");
                }
                return new QqBotHttpTransport.Response(200, response.toJSONString());
            };
            var clock = Clock.fixed(now, ZoneOffset.UTC);
            var api = new QqBotApiClient(transport, new QqBotTokenService(settings, transport, clock));
            welcome = new QqBotWelcome(api, new QqBotSendWindow(clock, scheduler::nowMillis, scheduler::advance), fetches::add);
            directory = spy(new QqBotDirectory(settings, api, clock, fetches::add, QqBotDirectory::sha256));
        }

        void run(EventSequence sequence) {
            ApplicationListener<NovaRemoteMessageEvent> recorder = new ApplicationListener<>() {
                @Override
                public void onApplicationEvent(NovaRemoteMessageEvent event) {
                    events.add(event);
                    afterPublish.accept(event);
                }
            };
            new ApplicationContextRunner().withUserConfiguration(QqBotPluginAutoConfiguration.class)
                    .withBean("qqBotSettings", QqBotSettings.class, () -> settings)
                    .withBean(NovaSenderService.class, () -> new NovaSenderService(new NovaCoreProperties()))
                    .withBean(QqBotHttpTransport.class, () -> transport, definition -> definition.setPrimary(true))
                    .withBean(QqBotGatewayScheduler.class, () -> scheduler, definition -> definition.setPrimary(true))
                    .withBean("qqBotWelcome", QqBotWelcome.class, () -> welcome)
                    .withBean("qqBotDirectory", QqBotDirectory.class, () -> directory)
                    .withInitializer(context -> context.addApplicationListener(recorder))
                    .run(context -> {
                        assertNull(context.getStartupFailure());
                        sequence.send(context.getBean(QqBotEventListener.class));
                    });
        }

        JSONObject group(String openid) {
            return JSONObject.of("group_openid", openid, "op_member_openid", MEMBER, "timestamp", now.getEpochSecond());
        }

        JSONObject message(String id, String content) {
            return JSONObject.of("id", id, "content", content, "group_openid", GROUP,
                    "timestamp", DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(now.atOffset(ZoneOffset.ofHours(8))),
                    "author", JSONObject.of("member_openid", MEMBER, "member_role", "admin", "bot", false));
        }

        void drainFetches() {
            while (!fetches.isEmpty()) {
                fetches.remove().run();
            }
        }
    }

    @FunctionalInterface
    private interface EventSequence {
        void send(QqBotEventListener listener) throws Exception;
    }
}
