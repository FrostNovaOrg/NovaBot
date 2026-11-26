package org.frostnova.nova.adapter.outbox;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.bilibili.event.live.BilibiliLiveOnEvent;
import org.frostnova.nova.bilibili.handler.BilibiliLiveOnPushHandler;
import org.frostnova.nova.bilibili.model.Room;
import org.frostnova.nova.bilibili.model.Up;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.health.PushActivityRecorder;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import org.frostnova.nova.core.model.Message;
import org.frostnova.nova.core.model.PushMessage;
import org.frostnova.nova.core.model.PushTarget;
import org.frostnova.nova.core.model.PushUser;
import org.frostnova.nova.core.model.Sender;
import org.frostnova.nova.core.sender.AtAllPermissionResolver;
import org.frostnova.nova.core.sender.FirstPushTipService;
import org.frostnova.nova.core.sender.NovaMessageSender;
import org.frostnova.nova.core.sender.PushGate;
import org.frostnova.nova.core.service.AtAllQuotaService;
import org.frostnova.nova.core.service.AtSubscriptionService;
import org.frostnova.nova.core.service.LiveDataService;
import org.frostnova.nova.core.service.NovaSenderService;
import org.frostnova.nova.core.timeline.TimelineEvent;
import org.frostnova.nova.core.timeline.TimelineWriter;
import org.frostnova.nova.core.util.HttpUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OutboxDeliveryTest {
    @TempDir Path data;
    final NovaCoreProperties properties = new NovaCoreProperties();
    final NovaSenderService registry = new NovaSenderService(properties);
    final List<TimelineEvent> events = new CopyOnWriteArrayList<>();
    final HttpUtil http = mock(HttpUtil.class);

    ApplicationContextRunner application() {
        properties.getLive().setLiveDataPath(data.resolve("live.json").toString());
        return new ApplicationContextRunner().withUserConfiguration(OutboxPluginAutoConfiguration.class)
                .withBean(NovaCoreProperties.class, () -> properties)
                .withBean(NovaSenderService.class, () -> registry)
                .withBean(TimelineWriter.class, () -> events::add)
                .withBean(NovaMessageSender.class, () -> new NovaMessageSender(http, registry,
                        new PushActivityRecorder(events::add), new PushGate(properties), events::add,
                        mock(AtAllQuotaService.class), new org.springframework.beans.factory.support.DefaultListableBeanFactory()
                            .getBeanProvider(AtAllPermissionResolver.class), mock(FirstPushTipService.class)));
    }

    Message message(String platform, long num, String content) {
        var owner = new PushUser();
        owner.setPlatform("live"); owner.setUid(85L); owner.setUname("主播甲");
        var channel = new PushTarget();
        channel.setPlatform(platform); channel.setType(PushTargetType.GROUP); channel.setNum(num); channel.setUser(owner);
        var message = Message.create(platform, channel.getType(), num, content).get(0);
        message.setChannel(channel);
        message.setPushOrigin(new Message.PushOrigin(Message.PushKind.LIVE_ON, "live", 85L, null));
        return message;
    }

    @Test @DisplayName("开播通知已有主播昵称，外发记录却认不出是哪位主播")
    void storesNameResolvedForLiveNotice() {
        application().run(context -> {
            long id = context.getBean(OutboxConversations.class).create("早班", "", 0, 0).id();
            var sender = context.getBean(NovaMessageSender.class);
            var api = mock(BilibiliApiUtil.class);
            var up = new Up();
            up.setUname("新昵称");
            when(api.getUpInfoByUid(85L)).thenReturn(up);
            when(api.getLiveInfoByRoomId(42L)).thenReturn(new Room());
            var handler = new BilibiliLiveOnPushHandler(api, sender, mock(AtSubscriptionService.class),
                    mock(LiveDataService.class));
            var target = new PushTarget();
            target.setPlatform("outbox");
            target.setType(PushTargetType.GROUP);
            target.setNum(id);
            var user = new PushUser();
            user.setPlatform("bilibili");
            user.setUid(85L);
            target.setUser(user);
            var push = new PushMessage();
            push.setTarget(target);
            push.setParamsJsonObject(JSONObject.of("message", "{uname} 开播"));
            var outbox = context.getBean(OutboxService.class);
            try {
                handler.handle(new BilibiliLiveOnEvent(new LiveStreamerInfo(85L, null, 42L)), push);
                var rows = await().atMost(Duration.ofSeconds(10)).until(
                        () -> outbox.list(OutboxRecord.Status.PENDING, 0, 1, s -> true), list -> !list.isEmpty());
                assertEquals("新昵称 开播", rows.get(0).segments().get(0).value());
                assertEquals("新昵称", rows.get(0).streamer().name());
            } finally { sender.shutdown(); }
        });
    }

    @Test @DisplayName("入箱成功的通知被重复发送，丢了主播、种类或全体提醒，或者正文被写进推送记录")
    void queuedPushIsStoredExactlyOnce() {
        application().run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals("外发", registry.displayName("outbox"));
            var conversations = context.getBean(OutboxConversations.class);
            long num = conversations.create("早班", "", 0, 0).id();
            var sender = context.getBean(NovaMessageSender.class);
            var message = message("outbox", num, "{at=all}仅收件人看得到{image_base64=AQID}");
            var stored = new CountDownLatch(1);
            message.addOnSuccessCallback(stored::countDown);
            try {
                sender.send(message);
                assertTrue(stored.await(10, TimeUnit.SECONDS));
            } finally { sender.shutdown(); }
            var store = context.getBean(OutboxService.class);
            var rows = store.list(OutboxRecord.Status.PENDING, 0, 10, s -> "live".equals(s.platform()) && s.uid() == 85);
            assertEquals(1, rows.size());
            var row = rows.get(0);
            assertEquals(message.getId(), String.valueOf(row.id()));
            assertEquals("主播甲", row.streamer().name());
            assertEquals(Message.PushKind.LIVE_ON, row.kind());
            assertEquals(Duration.ofMinutes(10), Duration.between(row.createdAt(), row.expiresAt()));
            assertTrue(row.atAll());
            assertArrayEquals(new byte[]{1, 2, 3}, store.readImage(row.id(), 1));
            assertFalse(events.isEmpty());
            assertFalse(events.toString().contains("仅收件人看得到"));
            verifyNoInteractions(http);
        });
    }

    @Test @DisplayName("写盘失败或会话被停用后仍报成功、重发残缺的纯文字，或阻塞其他去向")
    void failuresAreIsolatedAndDoNotDegrade() {
        application().run(context -> {
            var conversations = context.getBean(OutboxConversations.class);
            var sender = context.getBean(NovaMessageSender.class);
            long num = conversations.create("早班", "", 0, 0).id();
            long disabled = conversations.create("晚班", "", 0, 0).id();
            conversations.disable(disabled);
            for (long invalid : List.of(disabled, disabled + 1)) {
                var bad = message("outbox", invalid, "正文");
                assertNotEquals(0, sender.sendNow(bad).getIntValue("code"));
                assertTrue(bad.getFailureReason().contains(invalid == disabled ? "停用" : "不存在"));
            }
            Path images = data.resolve("outbox/images");
            Files.delete(images);
            Files.createFile(images);
            var failed = message("outbox", num, "仅收件人看得到{image_base64=AQID}");
            var result = sender.sendNow(failed);
            assertNotEquals(0, result.getIntValue("code"));
            assertTrue(failed.getFailureReason().contains("读写失败"));
            assertTrue(result.getBooleanValue(Sender.LocalDelivery.SKIP_IMAGE_FALLBACK));
            assertTrue(context.getBean(OutboxService.class).list(null, 0, 10, s -> true).isEmpty());
            assertFalse(events.toString().contains("仅收件人看得到"));

            var ordinary = message("ordinary", 32, "正常消息");
            ordinary.setReplyTo("source");
            registry.addSender(new Sender("ordinary", null, "sample", 0, (headers, params) -> {
                assertEquals(Map.of("Authorization", "Bearer sample"), headers);
                assertEquals(Map.of("platform", "ordinary", "num", 32L, "type", ordinary.getType().getCode(),
                        "content", ordinary.getContent(), "sequence", ordinary.getSequence(), "reply_to", "source",
                        "create_time", ordinary.getCreateTime().toEpochMilli()), params);
                return JSONObject.of("code", 0, "id", "ok");
            }));
            assertEquals(0, sender.sendNow(ordinary).getIntValue("code"));
            assertEquals("ok", ordinary.getId());
        });
    }
}
