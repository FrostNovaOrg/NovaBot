package org.frostnova.nova.adapter.qqbot;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.health.PushActivityRecorder;
import org.frostnova.nova.core.model.Message;
import org.frostnova.nova.core.model.MessagePlaceholders;
import org.frostnova.nova.core.model.Sender;
import org.frostnova.nova.core.sender.AtAllPermissionResolver;
import org.frostnova.nova.core.sender.NovaMessageSender;
import org.frostnova.nova.core.service.AtAllQuotaService;
import org.frostnova.nova.core.service.NovaSenderService;
import org.frostnova.nova.core.timeline.TimelineEvent;
import org.frostnova.nova.core.timeline.TimelineEventType;
import org.frostnova.nova.core.timeline.TimelineStore;
import org.frostnova.nova.core.util.HttpUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.support.GenericApplicationContext;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class QqBotDeliveryTest {
    private static final String AT_ALL_TAG = "<qqbot-at-everyone />";
    private static final String PLATFORM = "qq-official";
    private static final String APP_ID = "12345678";
    private static final String OPENID = "abcd-fixture-group-wxyz";
    private static final String TOKEN = "fixture-bot-access-token";
    private static final String SECRET = "fixture-bot-app-secret";
    private static final String PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+XzWQAAAAASUVORK5CYII=";
    private static final String JPEG = "/9j/4A==";
    private static final String IMAGE = "{image_base64=" + PNG + "}";
    private static final String FILE_INFO = "opaque+/=file-info";

    private static final int MIB = 1024 * 1024;
    private static final String REPORT = "周报{image_url=https://images.example.invalid/@everyone/cover.png}";

    @TempDir
    Path dataDirectory;

    @Test
    void sendsTextPushToTheRegisteredGroup() {
        withSender(rig -> {
            JSONObject result = rig.send("开播了");
            assertEquals(JSONObject.of("code", 0, "id", "message-1"), result);
            assertEquals(List.of("messages"), rig.endpoints());
            assertEquals(JSONObject.of("msg_type", 0, "content", "开播了"), rig.body(0));
            assertEquals(QqBotDirectory.ProactiveStatus.ENABLED, rig.group().proactive().status());
            assertEquals(QqBotDirectory.SwitchSource.SEND_SUCCESS, rig.group().proactive().source());
        });
    }

    @Test
    void liveNoticeMentionsBothSubscribedMembers() {
        withSender(rig -> {
            String first = rig.memberMention("fixture-first");
            String second = rig.memberMention("fixture-second");
            var notice = Message.create(PLATFORM, PushTargetType.GROUP, rig.number, "主播开播了 " + first + second).get(0);
            assertEquals(0, sendNow(rig, notice).getIntValue("code"));
            assertEquals(List.of("messages"), rig.endpoints());
            assertEquals(2, rig.body(0).getIntValue("msg_type"));
            assertEquals("", rig.body(0).getString("content"));
            assertEquals("主播开播了 <qqbot-at-user id=\"fixture-first\" /><qqbot-at-user id=\"fixture-second\" />",
                    rig.body(0).getJSONObject("markdown").getString("content"));
        });
    }

    @Test
    void sendsMemberAndEveryoneMentionsBeforeTheCover() {
        withSender(rig -> {
            assertEquals(0, rig.send(IMAGE + rig.memberMention("fixture-member") + "{at=all}开播了").getIntValue("code"));
            assertEquals(List.of("messages", "files", "messages"), rig.endpoints());
            assertEquals(2, rig.body(0).getIntValue("msg_type"));
            assertEquals("<qqbot-at-user id=\"fixture-member\" />" + AT_ALL_TAG + "开播了",
                    rig.body(0).getJSONObject("markdown").getString("content"));
            assertEquals(JSONObject.of("msg_type", 7, "content", " ", "media", media()), rig.body(2));
        });
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void omitsUnknownMembersWithoutLoggingTheirNumbers(CapturedOutput output) {
        withSender(rig -> {
            assertEquals(0, rig.send("开播了 **提醒**{at=123456}").getIntValue("code"));
            assertEquals(List.of("messages"), rig.endpoints());
            assertEquals(JSONObject.of("msg_type", 0, "content", "开播了 **提醒**"), rig.body(0));
            rig.send("{at=123456}");
            assertEquals(List.of("messages", "messages"), rig.endpoints());
            assertEquals(JSONObject.of("msg_type", 0, "content", ""), rig.body(1));
            assertTrue(output.getAll().contains("省略"));
            assertFalse(output.getAll().lines().anyMatch(line -> line.contains("省略") && line.contains("123456")));
        });
    }

    @Test
    void dynamicTextKeepsLiteralFormattingAndCannotAddMentions() {
        withSender(rig -> {
            String member = rig.memberMention("fixture-member");
            String external = "**加粗** [链接](x) \\ `code` _ ~ | <x> <qqbot-at-user id=\"x\" />\n"
                    + "#标题\n-一\n+二\n12. 三\n行中 # - + 12.\n";
            assertEquals(0, rig.send(MessagePlaceholders.escapeExternalText(external) + member + "#").getIntValue("code"));
            assertEquals(2, rig.body(0).getIntValue("msg_type"));
            assertEquals("\\*\\*加粗\\*\\* \\[链接\\](x) \\\\ \\`code\\` \\_ \\~ \\| \\<x\\> ＜qqbot-at-user id=\"x\" /\\>\n"
                            + "\\#标题\n\\-一\n\\+二\n12\\. 三\n行中 # - + 12.\n<qqbot-at-user id=\"fixture-member\" />#",
                    rig.body(0).getJSONObject("markdown").getString("content"));
        });
    }

    @ParameterizedTest
    @CsvSource({"304036, 200", "40034127, 400", "40034127, 429"})
    @ExtendWith(OutputCaptureExtension.class)
    void missingMarkdownPermissionStillDeliversTheOriginalPlainNotice(int code, int status, CapturedOutput output) {
        withSender(rig -> {
            String member = rig.memberMention("fixture-member");
            rig.exchange = path -> rig.requests.size() == 1
                    ? new QqBotHttpTransport.Response(status, json("code", code).body()) : json("id", "plain");
            assertEquals(JSONObject.of("code", 0, "id", "plain"),
                    rig.send("<qq" + member + "{at=all}bot-at-everyone />\n**开播了**"));
            assertEquals(List.of("messages", "messages"), rig.endpoints());
            assertEquals(2, rig.body(0).getIntValue("msg_type"));
            assertEquals(JSONObject.of("msg_type", 0, "content", "＜qqbot-at-everyone />\n**开播了**"), rig.body(1));
            assertTrue(output.getAll().contains("平台没给这只机器人发 markdown 的权限，这条改为不 @ 人"));
            assertFalse(output.getAll().contains("fixture-member"));
        });
    }

    @Test
    void permissionErrorOnPlainRetryStopsAfterOneRetry() {
        withSender(rig -> {
            rig.exchange = path -> json("code", 304036);
            var result = rig.send("开播了" + rig.memberMention("fixture-member"));
            assertEquals(-1, result.getIntValue("code"));
            assertEquals(List.of("messages", "messages"), rig.endpoints());
            assertEquals(JSONObject.of("msg_type", 0, "content", "开播了"), rig.body(1));
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {40034008, 40034009, 40034011, 40034124})
    void rejectedMarkdownExplainsTheFormatError(int code) {
        withSender(rig -> {
            rig.exchange = path -> json("code", code);
            var result = rig.send("动态" + rig.memberMention("fixture-member"));
            assertEquals(-1, result.getIntValue("code"));
            assertTrue(result.getString("message").contains("消息格式被平台拒绝（错误码 " + code + "）"));
            assertEquals(List.of("messages"), rig.endpoints());
        });
    }

    @Test
    void everyoneQuotaRejectionKeepsTheMemberMention() {
        withSender(rig -> {
            rig.exchange = path -> rig.requests.size() == 1 ? json("code", 304019) : json("id", "members");
            assertEquals(0, rig.send("<qq{at=all}bot-at-everyone />" + rig.memberMention("fixture-member")).getIntValue("code"));
            assertEquals(List.of("messages", "messages"), rig.endpoints());
            assertEquals(2, rig.body(1).getIntValue("msg_type"));
            assertEquals("＜qqbot-at-everyone /\\><qqbot-at-user id=\"fixture-member\" />",
                    rig.body(1).getJSONObject("markdown").getString("content"));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"admin", "owner"})
    void secondLiveNoticeKeepsOnlyTheText(String role) {
        withSender(role, new QqBotImageDownloader(mock(HttpClient.class)), rig -> {
            assertEquals(0, rig.liveNotice().getIntValue("code"));
            assertEquals(0, rig.liveNotice().getIntValue("code"));
            assertEquals(List.of("今日的 @全体成员 额度已用完"), rig.skippedReasons());
            assertEquals(JSONObject.of("msg_type", 0, "content", AT_ALL_TAG + "开播了"), rig.body(0));
            assertEquals(JSONObject.of("msg_type", 0, "content", "开播了"), rig.body(1));
            assertEquals(1, rig.quota.used(PLATFORM, rig.number));
        });
    }

    @Test
    void adminCanMentionAgainAtMidnightInChina() {
        withSender("admin", new QqBotImageDownloader(mock(HttpClient.class)), rig -> {
            var now = new AtomicReference<>(Instant.parse("2026-11-24T15:59:59Z"));
            try (var dates = mockStatic(LocalDate.class, CALLS_REAL_METHODS)) {
                dates.when(() -> LocalDate.now(any(ZoneId.class)))
                        .thenAnswer(call -> LocalDate.ofInstant(now.get(), call.getArgument(0)));
                assertEquals(0, rig.liveNotice().getIntValue("code"));
                assertEquals(0, rig.liveNotice().getIntValue("code"));
                now.set(now.get().plusSeconds(1));
                assertEquals(0, rig.liveNotice().getIntValue("code"));
                assertEquals(List.of(AT_ALL_TAG + "开播了", "开播了", AT_ALL_TAG + "开播了"),
                        rig.messageRequests().stream().map(request -> request.body().getString("content")).toList());
            }
        });
    }

    @Test
    void memberRoleExplainsHowToEnableMentions() {
        withSender("member", new QqBotImageDownloader(mock(HttpClient.class)), rig -> {
            assertEquals(0, rig.liveNotice().getIntValue("code"));
            assertEquals(List.of("机器人不是这个群的管理员（QQ 官方机器人要先被设为群管理员）"), rig.skippedReasons());
            assertEquals("开播了", rig.body(0).getString("content"));
            assertEquals(0, rig.quota.usedByBot(PLATFORM));
        });
    }

    @Test
    void promotedBotMentionsEveryoneOnTheNextLiveNoticeAfterTenMinutes() {
        withSender("member", new QqBotImageDownloader(mock(HttpClient.class)), rig -> {
            rig.role = "admin";
            rig.time.advance(599_999);
            assertEquals(0, rig.liveNotice().getIntValue("code"));
            assertEquals("开播了", rig.body(0).getString("content"));
            assertEquals(1, rig.roleQueries.get());
            rig.time.advance(1);
            assertEquals(0, rig.liveNotice().getIntValue("code"));
            assertEquals(AT_ALL_TAG + "开播了", rig.body(1).getString("content"));
            assertEquals(2, rig.roleQueries.get());
            assertEquals(1, rig.quota.used(PLATFORM, rig.number));
        });
    }

    @Test
    void demotedBotSendsTheNoticeWithoutMentioningOrUsingQuota() {
        withSender("admin", new QqBotImageDownloader(mock(HttpClient.class)), rig -> {
            rig.role = "member";
            rig.time.advance(Duration.ofMinutes(10).toMillis());
            assertEquals(0, rig.liveNotice().getIntValue("code"));
            assertEquals("开播了", rig.body(0).getString("content"));
            assertEquals(List.of("机器人不是这个群的管理员（QQ 官方机器人要先被设为群管理员）"), rig.skippedReasons());
            assertEquals(0, rig.quota.used(PLATFORM, rig.number));
            assertEquals(2, rig.roleQueries.get());
        });
    }

    @ParameterizedTest
    @CsvSource({"admin,50001,true", "member,40012010,false", ",50001,true"})
    void failedRoleRefreshStillDeliversWithTheLastKnownPermission(String role, int code, boolean mentioned) {
        withSender(role, new QqBotImageDownloader(mock(HttpClient.class)), rig -> {
            rig.roleFailure = json("code", code);
            rig.time.advance(Duration.ofMinutes(10).toMillis());
            assertEquals(0, rig.liveNotice().getIntValue("code"));
            assertEquals((mentioned ? AT_ALL_TAG : "") + "开播了", rig.body(0).getString("content"));
            assertEquals(mentioned ? 1 : 0, rig.quota.used(PLATFORM, rig.number));
            assertEquals(2, rig.roleQueries.get());
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {40034142, 304019})
    @ExtendWith(OutputCaptureExtension.class)
    void rejectedMentionStillDeliversTheNoticeAndExplainsLaterSkips(int code, CapturedOutput output) {
        withSender(rig -> {
            rig.exchange = path -> rig.requests.size() == 1 ? json("code", code) : json("id", "notice");
            assertEquals(0, rig.liveNotice().getIntValue("code"));
            assertEquals(0, rig.liveNotice().getIntValue("code"));
            assertEquals(List.of(AT_ALL_TAG + "开播了", "开播了", "开播了"), rig.messageRequests().stream()
                    .map(request -> request.body().getString("content")).toList());
            assertEquals(List.of("本群今天已经 @全体 过一次"), rig.skippedReasons());
            assertTrue(output.getAll().contains("本群今天已经 @全体 过一次，这条改为不 @"));
        });
    }

    @Test
    void sendsMentionAsTextBeforeTheCover() {
        withSender(rig -> {
            assertEquals(0, rig.send("{at=all}开播了" + IMAGE).getIntValue("code"));
            assertEquals(List.of("messages", "files", "messages"), rig.endpoints());
            var posts = rig.messageRequests();
            assertEquals(List.of(0, 7), posts.stream().map(request -> request.body().getIntValue("msg_type")).toList());
            assertEquals(AT_ALL_TAG + "开播了", posts.get(0).body().getString("content"));
            assertEquals(" ", posts.get(1).body().getString("content"));
            assertEquals(media(), posts.get(1).body().getJSONObject("media"));
        });
    }

    @Test
    void removingARejectedMentionCannotJoinTextIntoAnotherMention() {
        withSender(rig -> {
            rig.exchange = path -> rig.requests.size() == 1 ? json("code", 304019) : json("id", "notice");
            assertEquals(0, rig.send("<qq{at=all}bot-at-everyone />").getIntValue("code"));
            assertEquals("＜qqbot-at-everyone />", rig.body(1).getString("content"));
        });
    }

    @Test
    void deniedMentionWithOnlyACoverStillSendsTheCover() {
        withSender(rig -> {
            rig.exchange = path -> rig.requests.size() == 1
                    ? new QqBotHttpTransport.Response(429, json("code", 304019).body())
                    : path.equals("files") ? json("file_info", FILE_INFO) : json("id", "cover");
            assertEquals("cover", rig.send("{at=all}" + IMAGE).getString("id"));
            assertEquals(List.of("messages", "files", "messages"), rig.endpoints());
            assertEquals(" ", rig.body(2).getString("content"));
            assertEquals(media(), rig.body(2).getJSONObject("media"));
        });
    }

    @Test
    void quotaErrorOnPlainRetryStopsAfterOneRetry() {
        withSender(rig -> {
            rig.exchange = path -> json("code", 40034142);
            assertEquals(-1, rig.send("{at=all}开播了").getIntValue("code"));
            assertEquals(List.of("messages", "messages"), rig.endpoints());
            assertEquals("开播了", rig.body(1).getString("content"));
        });
    }

    @Test
    void splitLongTextKeepsTheMentionInOnePiece() {
        withSender(rig -> {
            rig.exchange = path -> rig.requests.size() == 1 ? json("code", 40054007) : json("id", "part");
            assertEquals(0, rig.send("{at=all}开播了").getIntValue("code"));
            assertEquals(AT_ALL_TAG, rig.body(1).getString("content"));
            assertEquals("开播了", rig.body(2).getString("content"));
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @ExtendWith(OutputCaptureExtension.class)
    void longLiveNoticeStillDeliversEveryLineAfterMemberMentionsAreRejected(boolean everyone, CapturedOutput logs) {
        withSender(rig -> {
            String member = rig.memberMention("fixture-subscriber");
            String firstLine = "**开播了**" + "甲".repeat(1242) + "\n";
            String secondLine = "乙".repeat(1250);
            rig.exchange = path -> {
                var body = rig.body(rig.requests.size() - 1);
                var payload = body.containsKey("markdown") ? body.getJSONObject("markdown") : body;
                return payload.getString("content").length() > 2000
                        ? json("code", 40054007) : json("id", "part-" + ++rig.sent);
            };

            var result = rig.send((everyone ? "{at=all}" : "") + member + firstLine + secondLine);

            assertEquals(0, result.getIntValue("code"), result.getString("message"));
            assertEquals("part-1", result.getString("id"));
            assertEquals(List.of("messages", "messages", "messages"), rig.endpoints());
            assertEquals(2, rig.body(0).getIntValue("msg_type"));
            assertTrue(rig.body(0).getJSONObject("markdown").getString("content")
                    .contains("<qqbot-at-user id=\"fixture-subscriber\" />"));
            assertEquals(JSONObject.of("msg_type", 0, "content", (everyone ? AT_ALL_TAG : "") + firstLine), rig.body(1));
            assertEquals(JSONObject.of("msg_type", 0, "content", secondLine), rig.body(2));
            assertTrue(logs.getAll().contains("这条太长，改为不 @ 人分段发"));
            assertFalse(logs.getAll().contains("fixture-subscriber"));
            assertFalse(logs.getAll().contains(member.substring(4, member.length() - 1)));
        });
    }

    @Test
    void aRejectedMentionIsRemovedFromTheRemainingTextParts() {
        withSender(rig -> {
            rig.exchange = path -> switch (rig.requests.size()) {
                case 1 -> json("code", 40054007);
                case 2 -> json("code", 304019);
                default -> json("id", "part");
            };
            assertEquals(0, rig.send("{at=all}开播了\n{at=all}直播间").getIntValue("code"));
            assertEquals(List.of("开播了\n", "直播间"), rig.messageRequests().stream().skip(2)
                    .map(request -> request.body().getString("content")).toList());
        });
    }

    @Test
    void failedCoverUploadDoesNotResendTextOrMention() {
        withSender(rig -> {
            rig.exchange = path -> path.equals("files") ? json("code", 850019) : json("id", "notice");
            var result = rig.send("{at=all}开播了" + IMAGE);
            assertEquals("notice", result.getString("id"));
            assertTrue(result.getBooleanValue(Sender.LocalDelivery.IMAGE_DEGRADED));
            assertEquals(List.of("messages", "files"), rig.endpoints());
        });
    }

    @Test
    void uploadsTheReportImageBeforeSendingItWithNonemptyContent() {
        withSender(rig -> {
            assertEquals(0, rig.send(IMAGE).getIntValue("code"));
            assertEquals(List.of("files", "messages"), rig.endpoints());
            assertEquals(JSONObject.of("file_type", 1, "file_name", "image.png", "file_data", PNG,
                    "srv_send_msg", false), rig.body(0));
            assertEquals(JSONObject.of("msg_type", 7, "content", " ", "media", media()), rig.body(1));
        });
    }

    @Test
    void sendsTheLiveTitleAndCoverInOneMessage() {
        withSender(rig -> {
            assertEquals(0, rig.send("标题" + IMAGE).getIntValue("code"));
            assertEquals(List.of("files", "messages"), rig.endpoints());
            assertEquals(JSONObject.of("msg_type", 7, "content", "标题", "media", media()), rig.body(1));
        });
    }

    @Test
    void repliesTwiceToTheOriginalMessageWithoutUsingProactiveSending() {
        withSender(rig -> {
            assertEquals(0, rig.reply("第一条", "m1").getIntValue("code"));
            assertEquals(0, rig.reply("第二条", "m1").getIntValue("code"));
            assertEquals(List.of("messages", "messages"), rig.endpoints());
            assertEquals(JSONObject.of("msg_type", 0, "content", "第一条", "msg_id", "m1", "msg_seq", 1),
                    rig.body(0));
            assertEquals(JSONObject.of("msg_type", 0, "content", "第二条", "msg_id", "m1", "msg_seq", 2),
                    rig.body(1));
            assertEquals(QqBotDirectory.ProactiveStatus.UNKNOWN, rig.group().proactive().status());
        });
    }

    @Test
    void liveNoticeWaitsForEarlierGroupTrafficToLeaveTheMinuteWindow() {
        withSender(rig -> {
            for (int i = 0; i < 20; i++) {
                assertEquals(0, rig.reply("礼物播报", "gift-" + i).getIntValue("code"));
            }
            long other = rig.directory.groupSeen("fixture-other-group", Instant.now());
            rig.time.advance(15_000);
            assertEquals(0, rig.deliver(Map.of("type", 1, "num", other, "content", "另一群开播")).getIntValue("code"));
            assertEquals(0, rig.send("开播通知").getIntValue("code"));
            assertEquals(15_000, rig.requests.get(20).millis());
            assertEquals(60_000, rig.requests.get(21).millis());
        });
    }

    @Test
    void welcomeAndRegularMessagesShareTheSameMinuteAllowance() {
        withSender(rig -> {
            for (int i = 0; i < 20; i++) assertEquals(0, rig.send("开播").getIntValue("code"));
            var welcomeAt = new CompletableFuture<Long>();
            rig.exchange = path -> {
                welcomeAt.complete(rig.time.nowMillis());
                return json("id", "welcome");
            };
            rig.listener.onEvent("GROUP_ADD_ROBOT", JSONObject.of("group_openid", OPENID,
                    "timestamp", rig.now.toString()), "GROUP_ADD_ROBOT:x", 1L);
            assertEquals(60_000L, assertDoesNotThrow(() -> welcomeAt.get(10, TimeUnit.SECONDS)));
            assertEquals("GROUP_ADD_ROBOT:x", rig.body(20).getString("event_id"));
        });
    }

    @Test
    void aClockTickDuringTheQuotaCheckDoesNotDropTheWaitingMessage() {
        var millis = new AtomicLong();
        var running = new AtomicBoolean();
        var window = new QqBotSendWindow(Clock.systemUTC(),
                () -> running.get() ? millis.getAndIncrement() : millis.get(), millis::addAndGet);
        for (int i = 0; i < 20; i++) window.acquire(APP_ID, 1);
        running.set(true);
        assertDoesNotThrow(() -> window.acquire(APP_ID, 1));
        assertTrue(millis.get() >= 60_000);
    }

    @Test
    void givesUpWhenConcurrentTrafficTakesTheNextMinuteOfSlots() {
        withSender(rig -> {
            for (int i = 0; i < 20; i++) assertEquals(0, rig.send("礼物").getIntValue("code"));
            rig.time.schedule(() -> {
                for (int i = 0; i < 20; i++) assertEquals(0, rig.send("群通知").getIntValue("code"));
            }, 60_000);
            var result = rig.send("开播通知");
            assertEquals(-1, result.getIntValue("code"));
            assertTrue(result.getString("message").contains("一分钟内发了太多条"));
            assertEquals(60_000, rig.time.nowMillis());
            assertEquals(40, rig.requests.size());
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void retriesAfterFiveSecondsForEitherRateLimitResponse(boolean httpLimit) {
        withSender(rig -> {
            rig.exchange = path -> rig.requests.size() > 1 ? json("id", "retried")
                    : httpLimit ? new QqBotHttpTransport.Response(429, "") : json("code", 40034100);
            assertEquals(0, rig.reply("提醒", "source").getIntValue("code"));
            assertEquals(List.of(0L, 5000L), rig.requests.stream().map(Request::millis).toList());
            assertEquals(rig.body(0), rig.body(1));
        });
    }

    @Test
    void proactiveFallbackSharesTheRateRetryWithTheOriginalAttempt() {
        withSender(rig -> {
            rig.exchange = path -> switch (rig.requests.size()) {
                case 1, 3 -> json("code", 40034100);
                case 2 -> json("code", 40034128);
                default -> json("id", "unexpected");
            };
            var result = rig.reply("答复", "question");
            assertEquals(-1, result.getIntValue("code"));
            assertTrue(result.getString("message").contains("限流"));
            assertEquals(List.of(0L, 5000L, 5000L), rig.requests.stream().map(Request::millis).toList());
            assertFalse(rig.body(2).containsKey("msg_id"));
        });
    }

    @Test
    void rateLimitedUploadRetriesTheFileBeforePostingTheMessage() {
        withSender(rig -> {
            rig.exchange = path -> {
                if (rig.requests.size() == 1) return new QqBotHttpTransport.Response(429, "");
                return path.equals("files") ? json("file_info", FILE_INFO) : json("id", "cover");
            };
            assertEquals(0, rig.send("开播封面" + IMAGE).getIntValue("code"));
            assertEquals(List.of("files", "files", "messages"), rig.endpoints());
            assertEquals(List.of(0L, 5000L, 5000L), rig.requests.stream().map(Request::millis).toList());
        });
    }

    @Test
    void reportsPersistentRateLimitAfterOneRetry() {
        withSender(rig -> {
            rig.exchange = path -> json("code", 40034100);
            var result = rig.send("开播通知");
            assertEquals(-1, result.getIntValue("code"));
            assertTrue(result.getString("message").contains("等 5 秒重发仍未发出"));
            assertEquals(List.of(0L, 5000L), rig.requests.stream().map(Request::millis).toList());
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void liveNoticeWithCoverStopsAfterTheRateLimitRetry(boolean httpLimit) {
        withSender(rig -> {
            var limited = httpLimit ? new QqBotHttpTransport.Response(429, "") : json("code", 40034100);
            rig.exchange = path -> path.equals("files") ? json("file_info", FILE_INFO) : limited;
            var message = Message.create(PLATFORM, PushTargetType.GROUP, rig.number, "开播通知" + IMAGE).get(0);

            var result = sendNow(rig, message);

            assertEquals(-1, result.getIntValue("code"));
            assertFalse(result.getBooleanValue(Sender.LocalDelivery.DELIVERY_UNKNOWN));
            assertTrue(message.getFailureReason().contains("等 5 秒重发仍未发出"));
            var posts = rig.messageRequests();
            assertEquals(List.of(0L, 5000L), posts.stream().map(Request::millis).toList());
            assertEquals(media(), posts.get(0).body().getJSONObject("media"));
            assertEquals(posts.get(0).body(), posts.get(1).body());
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 4})
    @ExtendWith(OutputCaptureExtension.class)
    void longReplyKeepsEveryLineAndUsesOneSequencePerDeliveredPart(int parts, CapturedOutput logs) {
        withSender(rig -> {
            String original = "甲乙\r\n丙丁\n戊己\n庚辛";
            List<JSONObject> delivered = new ArrayList<>();
            rig.exchange = path -> {
                var body = rig.body(rig.requests.size() - 1);
                if (body.getString("content").length() > (parts == 2 ? 7 : 4)) {
                    return json("code", 40054007);
                }
                delivered.add(body);
                return json("id", "part-" + delivered.size());
            };
            assertEquals(0, rig.reply(original, "lines").getIntValue("code"));
            assertEquals(parts, delivered.size());
            assertEquals(original, String.join("", delivered.stream().map(b -> b.getString("content")).toList()));
            for (int i = 0; i < parts; i++) {
                assertEquals(i + 1, delivered.get(i).getIntValue("msg_seq"));
                assertEquals("lines", delivered.get(i).getString("msg_id"));
                if (i + 1 < parts) assertTrue(delivered.get(i).getString("content").endsWith("\n"));
            }
            assertTrue(logs.getAll().contains("已拆成 " + parts + " 段重发"));
        });
    }

    @Test
    void partialTextDeliveryFailsWithoutSendingTheFirstPartOrImageAgain() {
        withSender(rig -> {
            rig.exchange = path -> path.equals("files") ? json("file_info", FILE_INFO) : switch (++rig.sent) {
                case 1 -> json("code", 40054007);
                case 2 -> json("id", "first");
                default -> json("code", 40054002);
            };
            var message = Message.create(PLATFORM, PushTargetType.GROUP, rig.number, "第一段\n第二段" + IMAGE).get(0);
            var result = sendNow(rig, message);
            assertEquals(-1, result.getIntValue("code"));
            assertEquals("first", result.getString("id"));
            assertTrue(result.getString("message").contains("禁言"));
            assertEquals(List.of("files", "messages", "messages", "messages"), rig.endpoints());
            assertEquals(media(), rig.body(2).getJSONObject("media"));
            assertFalse(rig.body(3).containsKey("media"));
        });
    }

    @Test
    void singleLineSplitsKeepSupplementaryCharactersAndStopAtFourParts() {
        withSender(rig -> {
            String original = "🌟".repeat(16);
            List<String> accepted = new ArrayList<>();
            rig.exchange = path -> {
                String part = rig.body(rig.requests.size() - 1).getString("content");
                assertEquals("🌟".repeat(part.codePointCount(0, part.length())), part);
                if (part.length() > 4) return json("code", 40054007);
                accepted.add(part);
                return json("id", "part-" + accepted.size());
            };
            var result = rig.send(original);
            assertEquals(-1, result.getIntValue("code"));
            assertTrue(result.getString("message").contains("太长"));
            assertEquals(2, accepted.size());
            assertEquals(6, rig.requests.size());
        });
    }

    @Test
    void sixthReplyUsesProactiveSending() {
        withSender(rig -> {
            for (int i = 0; i < 6; i++) {
                assertEquals(0, rig.reply("答复", "question").getIntValue("code"));
            }
            assertEquals(5, rig.body(4).getIntValue("msg_seq"));
            assertFalse(rig.body(5).containsKey("msg_id"));
            assertFalse(rig.body(5).containsKey("msg_seq"));
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {40034128, 304103, 40034005, 40034024})
    void remainingAnswersAreSentProactivelyWhenTheReplyIsRejected(int code) {
        withSender(rig -> {
            rig.exchange = path -> rig.requests.size() == 1 ? json("code", code) : json("id", "answer");
            assertEquals(0, rig.reply("答复", "question").getIntValue("code"));
            assertEquals(0, rig.reply("下一条", "question").getIntValue("code"));
            assertEquals(3, rig.requests.size());
            assertEquals("question", rig.body(0).getString("msg_id"));
            for (int i = 1; i < 3; i++) {
                assertFalse(rig.body(i).containsKey("msg_id"));
                assertFalse(rig.body(i).containsKey("msg_seq"));
            }
        });
    }

    @Test
    void rejectedProactiveFallbackExplainsTheSwitchAndWhyItWasNeeded() {
        withSender(rig -> {
            rig.exchange = path -> json("code", rig.requests.size() == 1 ? 40034128 : 40034105);
            var result = rig.reply("答复", "question");
            assertEquals(-1, result.getIntValue("code"));
            assertTrue(result.getString("message").contains("回复次数用完"));
            assertTrue(result.getString("message").contains("群成员里点机器人 → 右上角设置 → 打开"));
            assertEquals(2, rig.requests.size());
            assertEquals(QqBotDirectory.ProactiveStatus.DISABLED, rig.group().proactive().status());
        });
    }

    @Test
    void replyWithImageStopsWhenTheProactiveAttemptIsRejected() {
        withSender(rig -> {
            rig.exchange = path -> {
                if (path.equals("files")) return json("file_info", FILE_INFO);
                return json("code", rig.body(rig.requests.size() - 1).containsKey("msg_id") ? 40034128 : 40034105);
            };
            var message = Message.create(PLATFORM, PushTargetType.GROUP, rig.number, "答复" + IMAGE).get(0);
            message.setReplyTo("question");

            var result = sendNow(rig, message);

            assertEquals(-1, result.getIntValue("code"));
            assertFalse(result.getBooleanValue(Sender.LocalDelivery.DELIVERY_UNKNOWN));
            assertTrue(message.getFailureReason().contains("回复次数用完"));
            assertTrue(message.getFailureReason().contains("手机 QQ 进群 → 群成员里点机器人 → 右上角设置 → 打开"));
            var posts = rig.messageRequests();
            assertEquals(List.of(true, false), posts.stream().map(r -> r.body().containsKey("msg_id")).toList());
            assertEquals("question", posts.get(0).body().getString("msg_id"));
            assertFalse(posts.get(1).body().containsKey("msg_seq"));
            assertEquals(media(), posts.get(1).body().getJSONObject("media"));
        });
    }

    @Test
    void delayedFirstReplyUsesTheOriginalMessageTimestamp() {
        withSender(rig -> {
            Instant original = rig.now.minus(Duration.ofMinutes(20));
            rig.listener.onEvent("GROUP_AT_MESSAGE_CREATE", JSONObject.of("id", "question", "group_openid", OPENID,
                    "content", "问一下", "timestamp", original.toString(),
                    "author", JSONObject.of("member_openid", "fixture-member")), null, 1L);
            rig.time.advance(Duration.ofMinutes(36).toMillis());
            assertEquals(0, rig.reply("答复", "question").getIntValue("code"));
            assertFalse(rig.body(0).containsKey("msg_id"));
            assertFalse(rig.body(0).containsKey("msg_seq"));
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 429})
    void duplicateResponseDoesNotTriggerAnotherAttempt(int status) {
        withSender(rig -> {
            rig.exchange = path -> path.equals("files") ? json("file_info", FILE_INFO)
                    : new QqBotHttpTransport.Response(status, json("code", 40054005).body());
            var message = Message.create(PLATFORM, PushTargetType.GROUP, rig.number, "答复" + IMAGE).get(0);
            message.setReplyTo("question");
            var result = sendNow(rig, message);
            assertTrue(result.getBooleanValue(Sender.LocalDelivery.DELIVERY_UNKNOWN));
            assertTrue(result.getString("message").contains("QQ 判为重复消息"));
            assertEquals(List.of("files", "messages"), rig.endpoints());
        });
    }

    @Test
    void externalTextCannotMentionTheGroupOrItsMembers() {
        withSender(rig -> {
            String text = "<qqbot-at-everyone /> <QQbot-AT-Everyone /> <@abc> @everyone @EvErYoNe <@everyone> {at=all}";
            assertEquals(0, rig.send(MessagePlaceholders.escapeExternalText(text)).getIntValue("code"));
            assertEquals("＜qqbot-at-everyone /> ＜QQbot-AT-Everyone /> ＜@abc> ＠everyone ＠EvErYoNe ＜＠everyone> ｛at=all｝",
                    rig.body(0).getString("content"));
        });
    }

    @Test
    void escapesEachPartOfAMessageSplitByTheCore() {
        withSender(rig -> {
            var messages = Message.create(PLATFORM, PushTargetType.GROUP, rig.number,
                    "<QQbot-at-everyone />{next}<@abc> @Everyone");
            assertEquals(2, messages.size());
            for (Message message : messages) {
                assertEquals(0, rig.send(message.getContent()).getIntValue("code"));
            }
            assertEquals(List.of("messages", "messages"), rig.endpoints());
            assertEquals("＜QQbot-at-everyone />", rig.body(0).getString("content"));
            assertEquals("＜@abc> ＠Everyone", rig.body(1).getString("content"));
        });
    }

    @Test
    void removingPlaceholdersCannotCreateAnActiveMentionInAMediaMessage() {
        withSender(rig -> {
            JSONObject result = rig.send("<qq{at=123}bot-at-everyone /> @{face=1}Everyone" + IMAGE
                    + "{image_base64=" + JPEG + "}");
            assertEquals("message-1", result.getString("id"));
            assertEquals(List.of("files", "files", "messages", "messages"), rig.endpoints());
            assertEquals("image.jpg", rig.body(1).getString("file_name"));
            assertEquals("＜qqbot-at-everyone /> ＠Everyone", rig.body(2).getString("content"));
            assertEquals(" ", rig.body(3).getString("content"));
        });
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void explainsHowTheGroupOwnerCanEnableProactiveSending(CapturedOutput output) {
        withSender(rig -> {
            rig.exchange = path -> json("err_code", 40034105, "message", "official denial " + OPENID,
                    "trace_id", "trace-fixture");
            JSONObject result = rig.send("开播了");
            assertEquals(-1, result.getIntValue("code"));
            String message = result.getString("message");
            assertTrue(message.contains("群「测试群」的群主还没打开"));
            assertTrue(message.contains("群成员里点机器人 → 右上角设置 → 打开"));
            assertEquals(QqBotDirectory.ProactiveStatus.DISABLED, rig.group().proactive().status());
            assertEquals(QqBotDirectory.SwitchSource.SEND_ERROR, rig.group().proactive().source());
            assertEquals(message, rig.group().lastFailure().reason());
            var file = new QqBotDirectoryFile(dataDirectory.resolve("qqbot"));
            assertTrue(assertDoesNotThrow(() -> Files.readString(dataDirectory.resolve("qqbot/directory.json")))
                    .contains("发送报错"));
            assertEquals(QqBotDirectory.SwitchSource.SEND_ERROR,
                    assertDoesNotThrow(file::load).get(APP_ID).groups().get(rig.number).proactive().source());

            String logs = output.getAll();
            assertTrue(logs.contains("40034105") && logs.contains("official denial")
                    && logs.contains("trace-fixture") && logs.contains("abcd…wxyz"));
            assertFalse(logs.contains(OPENID));
        });
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void readTimeoutAfterSendingMarksDeliveryUnknownAndMakesOnlyOneSendRequest(CapturedOutput output) {
        withSender(rig -> {
            rig.exchange = path -> {
                if (path.equals("files")) {
                    return json("file_info", FILE_INFO);
                }
                throw new HttpTimeoutException("fixture read timeout");
            };
            JSONObject result = rig.send("开播了" + IMAGE);
            assertEquals(-1, result.getIntValue("code"));
            assertTrue(result.getBooleanValue(Sender.LocalDelivery.DELIVERY_UNKNOWN));
            assertTrue(result.getString("message").contains("没等到 QQ 的回包"));
            assertEquals(List.of("files", "messages"), rig.endpoints());
            assertTrue(output.getAll().contains("送达不明"));
            assertFalse(output.getAll().contains("发消息失败"));
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void connectionFailureIsAnOrdinaryFailure(boolean timeout) {
        withSender(rig -> {
            rig.exchange = path -> {
                if (timeout) {
                    throw new HttpConnectTimeoutException("fixture connect timeout");
                }
                throw new ConnectException("fixture refused connection");
            };
            JSONObject result = rig.send("开播了");
            assertEquals(-1, result.getIntValue("code"));
            assertFalse(result.getBooleanValue(Sender.LocalDelivery.DELIVERY_UNKNOWN));
            assertEquals(List.of("messages"), rig.endpoints());
        });
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void failedUploadMakesNoSendRequestAndRetriedTextIsEscaped(CapturedOutput output) {
        withSender(rig -> {
            rig.exchange = path -> path.equals("files")
                    ? json("code", 850019, "message", "echo " + PNG + " " + OPENID,
                           "trace_id", TOKEN + " " + SECRET)
                    : json("id", "text-only");
            String content = "<qq" + IMAGE + "bot-at-everyone /> @everyone";
            JSONObject failed = rig.send(content);
            assertEquals(-1, failed.getIntValue("code"));
            assertTrue(failed.getString("message").contains("图片格式不受支持"));
            assertEquals(List.of("files"), rig.endpoints());
            assertEquals(0, rig.send(MessagePlaceholders.stripImages(content)).getIntValue("code"));
            assertEquals(List.of("files", "messages"), rig.endpoints());
            assertEquals("＜qqbot-at-everyone /> ＠everyone", rig.body(1).getString("content"));

            String logs = output.getAll();
            assertTrue(logs.contains("850019") && logs.contains("abcd…wxyz"));
            for (String privateValue : List.of(PNG, OPENID, TOKEN, SECRET)) {
                assertFalse(logs.contains(privateValue));
            }
        });
    }

    @Test
    void rejectedCoverStillDeliversTheLiveNoticeAsText() {
        withSender(rig -> {
            rig.exchange = path -> {
                if (path.equals("files")) return json("file_info", FILE_INFO);
                return rig.body(rig.requests.size() - 1).containsKey("media")
                        ? json("code", 850019) : json("id", "text-only");
            };
            var message = Message.create(PLATFORM, PushTargetType.GROUP, rig.number, "开播通知" + IMAGE).get(0);

            sendNow(rig, message);

            assertEquals("text-only", message.getId());
            assertEquals(List.of("files", "messages", "messages"), rig.endpoints());
            assertEquals(media(), rig.body(1).getJSONObject("media"));
            assertEquals(JSONObject.of("msg_type", 0, "content", "开播通知"), rig.body(2));
        });
    }

    @Test
    void rejectsFriendsUnknownGroupsAndRemovedGroupsBeforeMakingARequest() {
        withSender(rig -> {
            assertTrue(rig.deliver(Map.of("type", 0, "num", rig.number, "content", "开播了"))
                    .getString("message").contains("暂不支持私聊推送"));
            assertTrue(rig.deliver(Map.of("type", 1, "num", 1L, "content", "开播了"))
                    .getString("message").contains("这个群还没对上号"));
            rig.directory.groupRemoved(OPENID, Instant.now());
            assertTrue(rig.send("开播了").getString("message").contains("已被移出"));
            assertTrue(rig.endpoints().isEmpty());
        });
    }

    @Test
    void updatesTheExistingSenderAndKeepsItWhenCredentialsAreCleared() {
        withSender(rig -> {
            rig.credentials.setIntervalMillis(3700);
            assertEquals(3700, rig.sender.getDelay());
            rig.credentials.clear();
            assertFalse(rig.credentials.status().configured());
            assertSame(rig.sender, rig.registry.getSender(PLATFORM).orElseThrow());
            assertTrue(rig.send("开播了").getString("message").contains("尚未配置"));
            assertTrue(rig.endpoints().isEmpty());

            var stored = new QqBotSettings(dataDirectory).snapshot();
            assertFalse(stored.configured());
            assertEquals("", stored.appId());
            assertEquals(3700, stored.intervalMillis());
            rig.credentials.save(APP_ID, SECRET);
            assertSame(rig.sender, rig.registry.getSender(PLATFORM).orElseThrow());
            assertEquals(0, rig.send("开播了").getIntValue("code"));
        });
    }

    @Test
    void downloadsNetworkImagesLocallyAndPreservesOpaqueFileInfo() {
        byte[] bytes = Base64.getDecoder().decode(PNG);
        AtomicBoolean cancelled = new AtomicBoolean();
        withSender(fakeDownloader(bytes, false, cancelled), rig -> {
            String opaque = "opaque- " + TOKEN + " /=+";
            rig.exchange = path -> path.equals("files") ? json("file_info", opaque) : json("id", "message-1");
            assertEquals(0, rig.send("标题{image_url=https://images.example.invalid/@everyone/cover.png}")
                    .getIntValue("code"));
            assertEquals(List.of("files", "messages"), rig.endpoints());
            assertEquals(PNG, rig.body(0).getString("file_data"));
            assertEquals(opaque, rig.body(1).getJSONObject("media").getString("file_info"));
            assertFalse(cancelled.get());
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {4, 5})
    void keepsDirectUploadThroughFiveMiB(int sizeMiB) {
        byte[] image = reportImage(sizeMiB * MIB);
        withSender(fakeDownloader(image, true, new AtomicBoolean()), rig -> {
            assertEquals(0, rig.send(REPORT).getIntValue("code"));
            assertEquals(List.of("files", "messages"), rig.endpoints());
            assertArrayEquals(image, Base64.getDecoder().decode(rig.body(0).getString("file_data")));
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deliversWeeklyReportAfterEveryPartIsConfirmed(boolean retrySecondPart) {
        byte[] image = reportImage(12 * MIB);
        withSender(fakeDownloader(image, false, new AtomicBoolean()), rig -> {
            int[] sizes = {5 * MIB, 4 * MIB, 3 * MIB};
            var secondAttempts = new AtomicInteger();
            rig.exchange = path -> switch (path) {
                case "upload_prepare" -> prepare(300, 2, sizes);
                case "put-1" -> {
                    if (retrySecondPart && secondAttempts.incrementAndGet() == 1) throw new IOException();
                    yield json();
                }
                case "files" -> json("file_info", FILE_INFO);
                case "messages" -> json("id", "report");
                case "put-0", "put-2" -> new QqBotHttpTransport.Response(200, "");
                default -> json();
            };
            var message = Message.create(PLATFORM, PushTargetType.GROUP, rig.number, REPORT).get(0);
            assertEquals(JSONObject.of("code", 0, "id", "report"), sendNow(rig, message));
            var expected = new ArrayList<>(List.of("upload_prepare", "put-0", "upload_part_finish", "put-1",
                    "upload_part_finish", "put-2", "upload_part_finish", "files", "messages"));
            if (retrySecondPart) expected.add(4, "put-1");
            assertEquals(expected, rig.endpoints());
            assertEquals(JSONObject.of("file_type", 1, "file_name", "image.png",
                    "file_size", Integer.toString(image.length), "md5", digest("MD5", image, 0, image.length),
                    "sha1", digest("SHA-1", image, 0, image.length), "md5_10m", digest("MD5", image, 0, 10002432)),
                    rig.body(0));
            int offset = 0;
            for (int part = 0; part < sizes.length; part++) {
                String md5 = digest("MD5", image, offset, sizes[part]);
                int put = expected.indexOf("put-" + part);
                int lastPut = expected.lastIndexOf("put-" + part);
                for (int attempt = put; attempt <= lastPut; attempt++) {
                    assertEquals(JSONObject.of("length", sizes[part], "md5", md5, "timeout", 60000L), rig.body(attempt));
                }
                if (lastPut != put) assertEquals(2000, rig.requests.get(lastPut).millis() - rig.requests.get(put).millis());
                assertEquals(JSONObject.of("upload_id", "report-upload", "part_index", part,
                        "block_size", Integer.toString(sizes[part]), "md5", md5), rig.body(lastPut + 1));
                offset += sizes[part];
            }
            assertEquals(JSONObject.of("file_type", 1, "upload_id", "report-upload", "file_name", "image.png",
                    "srv_send_msg", false), rig.body(expected.indexOf("files")));
            assertEquals(JSONObject.of("msg_type", 7, "content", "周报", "media", media()), rig.body(expected.indexOf("messages")));
        });
    }

    @ParameterizedTest
    @CsvSource({"300,5", "3,2"})
    void stopsBeforeMergeWhenSecondPartKeepsFailing(int timeout, int attempts) {
        byte[] image = reportImage(12 * MIB);
        withSender(fakeDownloader(image, true, new AtomicBoolean()), rig -> {
            rig.exchange = path -> switch (path) {
                case "upload_prepare" -> prepare(timeout, 2, 5 * MIB, 4 * MIB, 3 * MIB);
                case "put-1" -> new QqBotHttpTransport.Response(503, "{\"code\":987654}");
                default -> json();
            };
            JSONObject result = rig.send(REPORT);
            var expected = new ArrayList<>(List.of("upload_prepare", "put-0", "upload_part_finish"));
            for (int i = 0; i < attempts; i++) expected.add("put-1");
            assertEquals(expected, rig.endpoints());
            assertEquals(-1, result.getIntValue("code"));
            assertEquals("图片分片上传失败（错误码 987654）", result.getString("message"));
            assertFalse(result.getBooleanValue(Sender.LocalDelivery.SKIP_IMAGE_FALLBACK));
            if (timeout == 3) assertEquals(1000, rig.body(expected.size() - 1).getIntValue("timeout"));
        });
    }

    @Test
    void preservesOpaqueUploadIdThroughFinishAndMerge() {
        byte[] image = reportImage(8 * MIB);
        withSender(fakeDownloader(image, true, new AtomicBoolean()), rig -> {
            String id = "opaque/" + TOKEN + "+=";
            JSONObject prepared = JSON.parseObject(prepare(300, 1, image.length).body());
            prepared.put("upload_id", id);
            rig.exchange = path -> switch (path) {
                case "upload_prepare" -> new QqBotHttpTransport.Response(200, prepared.toJSONString());
                case "files" -> json("file_info", FILE_INFO);
                case "messages" -> json("id", "report");
                default -> json();
            };
            assertEquals(0, rig.send(REPORT).getIntValue("code"));
            assertEquals(id, rig.body(2).getString("upload_id"));
            assertEquals(id, rig.body(3).getString("upload_id"));
        });
    }

    @Test
    void usesDefaultRetryDelayWhenPrepareOmitsConfig() {
        byte[] image = reportImage(8 * MIB);
        withSender(fakeDownloader(image, true, new AtomicBoolean()), rig -> {
            var prepared = JSON.parseObject(prepare(300, 1, image.length).body());
            prepared.remove("upload_config");
            var attempts = new AtomicInteger();
            rig.exchange = path -> switch (path) {
                case "upload_prepare" -> new QqBotHttpTransport.Response(200, prepared.toJSONString());
                case "put-0" -> attempts.incrementAndGet() == 1 ? new QqBotHttpTransport.Response(502, "") : json();
                case "files" -> json("file_info", FILE_INFO);
                case "messages" -> json("id", "report");
                default -> json();
            };
            assertEquals(0, rig.send(REPORT).getIntValue("code"));
            assertEquals(List.of("upload_prepare", "put-0", "put-0", "upload_part_finish", "files", "messages"),
                    rig.endpoints());
            assertEquals(1000, rig.requests.get(2).millis() - rig.requests.get(1).millis());
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {5 * MIB + 1, 8 * MIB, 20 * MIB})
    void preparesEmbeddedImagesUsingTheAvailablePrefix(int length) {
        byte[] image = reportImage(length);
        String content = "{image_base64=" + Base64.getEncoder().encodeToString(image) + "}";
        withSender(rig -> {
            rig.exchange = path -> switch (path) {
                case "upload_prepare" -> prepare(300, 1, length);
                case "files" -> json("file_info", FILE_INFO);
                case "messages" -> json("id", "report");
                default -> json();
            };
            assertEquals(JSONObject.of("code", 0, "id", "report"), rig.send(content));
            assertEquals(List.of("upload_prepare", "put-0", "upload_part_finish", "files", "messages"), rig.endpoints());
            JSONObject request = rig.body(0);
            assertEquals(String.valueOf(length), request.get("file_size"));
            assertEquals(digest("MD5", image, 0, Math.min(10002432, length)), request.get("md5_10m"));
            if (length < 10002432) assertEquals(request.get("md5"), request.get("md5_10m"));
        });
    }

    @ParameterizedTest
    @CsvSource({"upload_prepare,850019,图片格式不受支持", "upload_part_finish,789012,图片分片上传失败（错误码 789012）",
            "files,40093002,今天发文件的额度用完了"})
    void propagatesMultipartServiceErrors(String endpoint, int code, String message) {
        byte[] image = reportImage(8 * MIB);
        withSender(fakeDownloader(image, true, new AtomicBoolean()), rig -> {
            rig.exchange = path -> {
                if (path.equals(endpoint)) return json("code", code);
                if (path.equals("upload_prepare")) return prepare(300, 1, image.length);
                return json();
            };
            JSONObject result = rig.send(REPORT);
            assertEquals(-1, result.getIntValue("code"));
            assertEquals(message, result.getString("message"));
            assertEquals(endpoint, rig.endpoints().get(rig.endpoints().size() - 1));
            assertFalse(result.getBooleanValue(Sender.LocalDelivery.SKIP_IMAGE_FALLBACK));
        });
    }

    @Test
    void rejectsIncompletePartListBeforeUploading() {
        byte[] image = reportImage(8 * MIB);
        withSender(fakeDownloader(image, true, new AtomicBoolean()), rig -> {
            rig.exchange = path -> prepare(300, 1, 3 * MIB, 4 * MIB);
            JSONObject result = rig.send(REPORT);
            assertEquals(-1, result.getIntValue("code"));
            assertTrue(result.getString("message").contains("分片信息"));
            assertEquals(List.of("upload_prepare"), rig.endpoints());
        });
    }

    @Test
    void retriesOnlyTextAfterReportMergeExhaustsQuota() {
        byte[] image = reportImage(8 * MIB);
        withSender(fakeDownloader(image, true, new AtomicBoolean()), rig -> {
            rig.exchange = path -> switch (path) {
                case "upload_prepare" -> prepare(300, 1, image.length);
                case "files" -> json("code", 40093002);
                case "messages" -> json("id", "text-only");
                default -> json();
            };
            var message = Message.create(PLATFORM, PushTargetType.GROUP, rig.number, REPORT).get(0);
            sendNow(rig, message);
            assertEquals("text-only", message.getId());
            assertEquals(List.of("upload_prepare", "put-0", "upload_part_finish", "files", "messages"), rig.endpoints());
            assertEquals(JSONObject.of("msg_type", 0, "content", "周报"), rig.body(4));
        });
    }

    @Test
    void rejectsOversizedEmbeddedReportBeforeUpload() {
        String content = "{image_base64=" + Base64.getEncoder().encodeToString(reportImage(25 * MIB)) + "}";
        withSender(rig -> {
            assertEquals(JSONObject.of("code", -1, "message", "图片超过 20 MB，没有发出"), rig.send(content));
            assertTrue(rig.endpoints().isEmpty());
        });
    }

    private static QqBotHttpTransport.Response prepare(int timeout, int delay, int... sizes) {
        List<JSONObject> parts = new ArrayList<>();
        for (int i = sizes.length - 1; i >= 0; i--) {
            parts.add(JSONObject.of("index", i, "block_size", Integer.toString(sizes[i]),
                    "presigned_url", "https://media.example.invalid/put-" + i + "?signature=fixture"));
        }
        return json("upload_id", "report-upload", "block_size", Integer.toString(sizes[0]), "parts", parts,
                "upload_config", JSONObject.of("concurrency", 3, "retry_timeout", timeout, "retry_delay", delay));
    }

    private static byte[] reportImage(int length) {
        byte[] image = new byte[length];
        new Random(1234).nextBytes(image);
        byte[] png = Base64.getDecoder().decode(PNG);
        System.arraycopy(png, 0, image, 0, png.length);
        return image;
    }

    private static String digest(String algorithm, byte[] bytes, int offset, int length) {
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            digest.update(ByteBuffer.wrap(bytes, offset, length));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void rejectsOversizedNetworkImagesBeforeUpload(boolean declaresSize) {
        byte[] bytes = new byte[QqBotImageDownloader.MAX_BYTES + 1];
        byte[] png = Base64.getDecoder().decode(PNG);
        System.arraycopy(png, 0, bytes, 0, png.length);
        AtomicBoolean cancelled = new AtomicBoolean();
        withSender(fakeDownloader(bytes, declaresSize, cancelled), rig -> {
            JSONObject result = rig.send("{image_url=https://images.example.invalid/@everyone/cover.png}");
            assertEquals(-1, result.getIntValue("code"));
            assertEquals("图片超过 20 MB，没有发出", result.getString("message"));
            assertTrue(rig.endpoints().isEmpty());
            assertTrue(cancelled.get());
        });
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            40054010 | 链接被平台拦了（example.invalid）
            40054002 | 在这个群被禁言了
            40034101 | 机器人不在这个群
            40054003 | 机器人不在这个群
            40034006 | 内容被平台判为违规
            40054016 | 机器人当前不在线
            50055001 | QQ 侧发送出错
            22006    | 消息格式被平台拒绝（错误码 22006）
            304061   | 消息格式被平台拒绝（错误码 304061）
            340069   | 消息格式被平台拒绝（错误码 340069）
            304080   | 消息格式被平台拒绝（错误码 304080）
            850018   | 被禁言，图片没有传上去
            850019   | 图片格式不受支持
            850031   | 图片太大
            40093002 | 今天发文件的额度用完了
            40093001 | 图片上传失败
            850027   | 图片上传失败
            987654   | QQ 返回错误 987654：远端原因
            """)
    void explainsPlatformErrorsInPlainLanguage(int code, String expected) {
        withSender(rig -> {
            rig.exchange = path -> json("code", code, "message", "远端原因");
            JSONObject result = rig.send("看看（https://example.invalid/live?detail=1）");
            assertEquals(-1, result.getIntValue("code"));
            assertTrue(result.getString("message").contains(expected));
        });
    }

    @Test
    void confirmsTheFirstMessageWhenAnAdditionalImageIsRejected() {
        withSender(rig -> {
            rig.exchange = path -> path.equals("files") ? json("file_info", FILE_INFO)
                    : ++rig.sent == 1 ? json("id", "first") : json("code", 50055001);
            JSONObject result = rig.send("标题" + IMAGE + "{image_base64=" + JPEG + "}");
            assertEquals(0, result.getInteger("code"));
            assertEquals("first", result.getString("id"));
            assertEquals(List.of("files", "files", "messages", "messages"), rig.endpoints());
            assertTrue(rig.group().lastFailure().reason().contains("QQ 侧发送出错"));
        });
    }

    @Test
    @DisplayName("第二张图被拒时，核心和日志能看出图片有缺，已发的正文只发一次")
    @ExtendWith(OutputCaptureExtension.class)
    void recordsMissingImageWithoutResendingDeliveredText(CapturedOutput output) {
        withSender(rig -> {
            rig.exchange = path -> path.equals("files") ? json("file_info", FILE_INFO)
                    : ++rig.sent == 1 ? json("id", "first") : json("code", 50055001);
            NovaCoreProperties properties = new NovaCoreProperties();
            properties.getLive().setLiveDataPath(dataDirectory.resolve("live.json").toString());
            TimelineStore timeline = new TimelineStore(properties);
            PushActivityRecorder activity = new PushActivityRecorder(timeline);
            NovaMessageSender sender = new NovaMessageSender(mock(HttpUtil.class), rig.registry, activity,
                    null, timeline, null, null, null);
            var message = Message.create(PLATFORM, PushTargetType.GROUP, rig.number,
                    "标题" + IMAGE + "{image_base64=" + JPEG + "}").get(0);
            AtomicInteger degraded = new AtomicInteger();
            message.addOnImageDegradedCallback(degraded::incrementAndGet);

            JSONObject result = sender.sendNow(message);

            assertEquals(List.of("files", "files", "messages", "messages"), rig.endpoints());
            assertEquals("标题", rig.body(2).getString("content"));
            assertEquals(" ", rig.body(3).getString("content"));
            assertEquals(0, result.getInteger("code"));
            assertEquals("first", message.getId());
            assertEquals(1, activity.getSuccessCount());
            assertEquals(0, activity.getFailureCount());
            var filter = new TimelineStore.Filter(null, false, null, null, null, null, null, 10);
            var events = timeline.query(filter, null).events();
            assertEquals(1, events.size());
            TimelineEvent event = events.get(0);
            assertEquals(TimelineEventType.PUSH_SENT, event.type());
            assertTrue(event.text().contains("图片未送达"),
                    () -> "回包 " + result + "，日志页显示「" + event.text() + "」");
            assertEquals(TimelineEvent.Level.WARN, event.level());
            assertTrue(event.detail().getOrDefault("reason", "").contains("QQ 侧发送出错"));
            assertTrue(result.getBooleanValue(Sender.LocalDelivery.IMAGE_DEGRADED));
            assertEquals(1, degraded.get());
            assertTrue(output.getAll().lines().anyMatch(line -> line.contains("图片未送达")
                    && line.contains("QQ 侧发送出错")));
        });
    }

    @Test
    void doesNotResendWhenTheResponseHasNoMessageId() {
        withSender(rig -> {
            rig.exchange = path -> json("timestamp", "2026-11-24T15:20:00Z");
            assertTrue(rig.send("开播了").getBooleanValue(Sender.LocalDelivery.DELIVERY_UNKNOWN));
            assertEquals(List.of("messages"), rig.endpoints());
        });
    }

    @Test
    void aLocalWriteFailureAfterSendingDoesNotTurnDeliveryIntoFailure() {
        withSender(rig -> {
            var path = dataDirectory.resolve("qqbot/directory.json");
            var backup = dataDirectory.resolve("qqbot/directory-backup.json");
            assertDoesNotThrow(() -> Files.move(path, backup));
            assertDoesNotThrow(() -> Files.createDirectory(path));
            try {
                assertEquals(JSONObject.of("code", 0, "id", "message-1"), rig.send("开播了"));
                assertEquals(List.of("messages"), rig.endpoints());
            } finally {
                assertDoesNotThrow(() -> Files.delete(path));
                assertDoesNotThrow(() -> Files.move(backup, path));
            }
        });
    }

    private JSONObject sendNow(Rig rig, Message message) {
        var properties = new NovaCoreProperties();
        properties.getLive().setLiveDataPath(dataDirectory.resolve("live.json").toString());
        var timeline = new TimelineStore(properties);
        var sender = new NovaMessageSender(mock(HttpUtil.class), rig.registry, new PushActivityRecorder(timeline),
                null, timeline, null, null, null);
        try {
            return sender.sendNow(message);
        } finally {
            sender.shutdown();
        }
    }

    private void withSender(Consumer<Rig> test) {
        withSender(new QqBotImageDownloader(mock(HttpClient.class)), test);
    }

    private void withSender(QqBotImageDownloader downloader, Consumer<Rig> test) {
        withSender(null, downloader, test);
    }

    private void withSender(String role, QqBotImageDownloader downloader, Consumer<Rig> test) {
        Rig rig = new Rig();
        rig.role = role;
        new ApplicationContextRunner().withUserConfiguration(QqBotPluginAutoConfiguration.class)
                .withInitializer(context -> ((GenericApplicationContext) context).registerBean(
                        "qqBotDirectory", QqBotDirectory.class, () -> new QqBotDirectory(
                                context.getBean(QqBotSettings.class), context.getBean(QqBotApiClient.class),
                                rig.clock, Executors.newSingleThreadExecutor(), QqBotDirectory::sha256)))
                .withBean("qqBotSettings", QqBotSettings.class, () -> new QqBotSettings(dataDirectory))
                .withBean(NovaCoreProperties.class)
                .withBean(AtAllQuotaService.class)
                .withBean(NovaSenderService.class, () -> new NovaSenderService(new NovaCoreProperties()))
                .withBean(QqBotHttpTransport.class, () -> rig, definition -> definition.setPrimary(true))
                .withBean(QqBotGatewayScheduler.class, QqBotGatewayTest.ManualScheduler::new,
                        definition -> definition.setPrimary(true))
                .withBean("qqBotSendWindow", QqBotSendWindow.class, () -> new QqBotSendWindow(
                        rig.clock, rig.time::nowMillis, rig.time::advance))
                .withBean(QqBotImageDownloader.class, () -> downloader, definition -> definition.setPrimary(true))
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    rig.listener = context.getBean(QqBotInboundEvents.class);
                    rig.registry = context.getBean(NovaSenderService.class);
                    assertTrue(rig.registry.getSender(PLATFORM).isEmpty());
                    rig.credentials = context.getBean(QqBotCredentials.class);
                    rig.credentials.save(APP_ID, SECRET);
                    rig.sender = rig.registry.getSender(PLATFORM).orElseThrow(
                            () -> new AssertionError("保存凭据后 QQ 官方机器人应当能投递群消息"));
                    assertEquals("QQ 官方机器人", rig.registry.displayName(PLATFORM));
                    assertEquals(2000, rig.sender.getDelay());
                    rig.directory = context.getBean(QqBotDirectory.class);
                    rig.number = rig.directory.groupJoined(OPENID, Instant.now());
                    rig.directory.renameGroup(rig.number, "测试群");
                    await().atMost(Duration.ofSeconds(10)).until(() -> {
                        var group = rig.group();
                        return group.info() != null && group.botState() != null;
                    });
                    rig.resolvers = context.getBeanProvider(AtAllPermissionResolver.class);
                    rig.quota = context.getBean(AtAllQuotaService.class);
                    test.accept(rig);
                });
    }

    private static QqBotImageDownloader fakeDownloader(byte[] bytes, boolean declaresSize, AtomicBoolean cancelled) {
        HttpClient client = mock(HttpClient.class);
        when(client.<byte[]>sendAsync(any(HttpRequest.class), any())).thenAnswer(call -> {
            HttpRequest request = call.getArgument(0);
            assertEquals("GET", request.method());
            assertEquals(URI.create("https://images.example.invalid/@everyone/cover.png"), request.uri());
            assertEquals(Duration.ofSeconds(15), request.timeout().orElseThrow());
            assertTrue(request.headers().map().isEmpty());
            HttpResponse.BodyHandler<byte[]> handler = call.getArgument(1);
            HttpResponse.ResponseInfo info = mock(HttpResponse.ResponseInfo.class);
            Map<String, List<String>> headers = declaresSize
                    ? Map.of("Content-Length", List.of(Integer.toString(bytes.length))) : Map.of();
            when(info.headers()).thenReturn(HttpHeaders.of(headers, (name, value) -> true));
            var subscriber = handler.apply(info);
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long n) {}

                @Override
                public void cancel() {
                    cancelled.set(true);
                }
            });
            if (!cancelled.get()) {
                int firstChunk = bytes.length / 2;
                subscriber.onNext(List.of(ByteBuffer.wrap(bytes, 0, firstChunk)));
                subscriber.onNext(List.of(ByteBuffer.wrap(bytes, firstChunk, bytes.length - firstChunk)));
            }
            if (!cancelled.get()) {
                subscriber.onComplete();
            }
            HttpResponse<byte[]> response = mock();
            when(response.statusCode()).thenReturn(200);
            return subscriber.getBody().thenApply(body -> {
                when(response.body()).thenReturn(body);
                return response;
            }).toCompletableFuture();
        });
        return new QqBotImageDownloader(client);
    }

    private static JSONObject media() {
        return JSONObject.of("file_info", FILE_INFO);
    }

    private static QqBotHttpTransport.Response json(Object... pairs) {
        JSONObject response = new JSONObject();
        for (int i = 0; i < pairs.length; i += 2) {
            response.put((String) pairs[i], pairs[i + 1]);
        }
        return new QqBotHttpTransport.Response(200, response.toJSONString());
    }

    @FunctionalInterface
    private interface Exchange {
        QqBotHttpTransport.Response respond(String endpoint) throws IOException;
    }

    private record Request(String endpoint, JSONObject body, long millis) {}

    private static final class Rig implements QqBotHttpTransport {
        private final List<Request> requests = new ArrayList<>();
        private final QqBotGatewayTest.ManualScheduler time = new QqBotGatewayTest.ManualScheduler();
        private final Instant now = Instant.now();
        private final Clock clock = Clock.offset(time.clock(), Duration.between(Instant.EPOCH, now));
        private QqBotInboundEvents listener;
        private Exchange exchange;
        private QqBotCredentials credentials;
        private NovaSenderService registry;
        private Sender sender;
        private QqBotDirectory directory;
        private long number;
        private int sent;
        private volatile String role;
        private volatile QqBotHttpTransport.Response roleFailure;
        private final AtomicInteger roleQueries = new AtomicInteger();
        private AtAllQuotaService quota;
        private ObjectProvider<AtAllPermissionResolver> resolvers;
        private final List<TimelineEvent> events = new ArrayList<>();

        JSONObject liveNotice() {
            var core = new NovaMessageSender(mock(HttpUtil.class), registry, new PushActivityRecorder(events::add),
                    null, events::add, quota, resolvers, null);
            try {
                return core.sendNow(Message.create(PLATFORM, PushTargetType.GROUP, number, "{at=all}开播了").get(0));
            } finally {
                core.shutdown();
            }
        }

        List<String> skippedReasons() {
            return events.stream().filter(event -> event.type() == TimelineEventType.AT_ALL_SKIPPED)
                    .map(event -> event.detail().get("reason")).toList();
        }

        Rig() {
            exchange = endpoint -> endpoint.equals("files") ? json("file_info", FILE_INFO)
                    : json("id", "message-" + ++sent, "timestamp", "2026-11-24T15:20:00Z");
        }

        @Override
        public Response send(HttpRequest request) throws IOException {
            assertEquals("PUT", request.method());
            assertEquals("media.example.invalid", request.uri().getHost());
            assertEquals("signature=fixture", request.uri().getQuery());
            assertTrue(request.headers().allValues("Authorization").isEmpty());
            var subscriber = HttpResponse.BodySubscribers.ofByteArray();
            request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
                public void onSubscribe(Flow.Subscription subscription) { subscriber.onSubscribe(subscription); }
                public void onNext(ByteBuffer item) { subscriber.onNext(List.of(item)); }
                public void onError(Throwable error) { subscriber.onError(error); }
                public void onComplete() { subscriber.onComplete(); }
            });
            byte[] bytes = subscriber.getBody().toCompletableFuture().join();
            String endpoint = request.uri().getPath().substring(1);
            requests.add(new Request(endpoint, JSONObject.of("length", bytes.length,
                    "md5", digest("MD5", bytes, 0, bytes.length), "timeout", request.timeout().orElseThrow().toMillis()),
                    time.nowMillis()));
            return exchange.respond(endpoint);
        }

        @Override
        public Response send(String method, URI uri, Map<String, String> headers, String body)
                throws IOException {
            if (uri.getPath().equals("/app/getAppAccessToken")) {
                return json("access_token", TOKEN, "expires_in", 7200);
            }
            if (method.equals("GET")) {
                if (uri.getPath().endsWith("/bot_state")) {
                    roleQueries.incrementAndGet();
                    if (roleFailure != null) return roleFailure;
                    return role == null ? json() : json("member_role", role);
                }
                return json();
            }
            assertEquals("POST", method);
            assertEquals("QQBot " + TOKEN, headers.get("Authorization"));
            String path = uri.getPath();
            assertTrue(path.startsWith("/v2/groups/" + OPENID + "/") || path.startsWith("/v2/groups/fixture-other-group/"));
            String endpoint = path.substring(path.lastIndexOf('/') + 1);
            requests.add(new Request(endpoint, JSON.parseObject(body), time.nowMillis()));
            return exchange.respond(endpoint);
        }

        JSONObject deliver(Map<String, Object> params) {
            return sender.getLocalDelivery().deliver(Map.of(), params);
        }

        String memberMention(String openid) {
            return "{at=" + directory.memberSeen(openid, now) + "}";
        }

        JSONObject send(String content) {
            return deliver(Map.of("type", 1, "num", number, "content", content));
        }

        JSONObject reply(String content, String msgId) {
            return deliver(Map.of("type", 1, "num", number, "content", content, "reply_to", msgId));
        }

        List<String> endpoints() {
            return requests.stream().map(Request::endpoint).toList();
        }

        List<Request> messageRequests() {
            return requests.stream().filter(r -> r.endpoint().equals("messages")).toList();
        }

        JSONObject body(int index) {
            return requests.get(index).body();
        }

        QqBotDirectory.GroupEntry group() {
            return directory.group(number).orElseThrow();
        }
    }
}
