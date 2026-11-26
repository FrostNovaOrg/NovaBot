package org.frostnova.nova.adapter.outbox;

import org.frostnova.nova.core.model.Message.PushKind;
import org.frostnova.nova.core.timeline.TimelineEvent;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.frostnova.nova.adapter.outbox.OutboxRecord.Status.*;
import static org.junit.jupiter.api.Assertions.*;

class OutboxServiceTest {
    @TempDir Path root;
    Instant now = Instant.parse("2026-01-12T10:00:00Z");
    final List<TimelineEvent> events = new ArrayList<>();
    final OutboxRecord.Streamer owner = new OutboxRecord.Streamer("live", 101L, "主播甲");
    OutboxService service;
    OutboxConversations conversations;
    long conversation;

    OutboxSettings settings(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bindOrCreate("novabot.adapter.outbox", Bindable.of(OutboxSettings.class));
    }

    @BeforeEach void start() {
        reopen(settings(Map.of()));
        conversation = conversations.create("早班", "通知", 0, 0).id();
    }

    void reopen(OutboxSettings settings) {
        var db = new OutboxDatabase(root);
        conversations = new OutboxConversations(db);
        service = new OutboxService(db, settings, Clock.fixed(now, ZoneOffset.UTC), events::add);
        service.maintain();
    }

    List<OutboxRecord> put(String text) {
        return service.enqueue(conversation, owner, PushKind.LIVE_ON, text);
    }

    @Test @DisplayName("拆条后顺序错乱、文字夹着提醒标签，或大图取回时残缺")
    void orderedPartsAndFullSizeImage() throws Exception {
        var bytes = new byte[8 * 1024 * 1024];
        new Random(19).nextBytes(bytes);
        var records = put("{at=all}开{face=1}播{at=123}{image_base64=" + Base64.getEncoder().encodeToString(bytes)
                + "}{next}下条{image_url=https://example.invalid/a.png?size=原图}");
        assertEquals(2, records.size());
        var first = records.get(0);
        assertEquals(owner, first.streamer());
        assertEquals("早班", first.conversationName());
        assertEquals(PushKind.LIVE_ON, first.kind());
        assertTrue(first.atAll());
        assertEquals(List.of(new OutboxRecord.Segment(OutboxRecord.SegmentType.TEXT, "开播"),
                new OutboxRecord.Segment(OutboxRecord.SegmentType.IMAGE_BYTES, null)), first.segments());
        var second = records.get(1);
        assertTrue(second.id() > first.id());
        assertFalse(second.atAll());
        assertEquals("https://example.invalid/a.png?size=原图", second.segments().get(1).value());
        assertEquals(OutboxRecord.SegmentType.IMAGE_URL, second.segments().get(1).type());
        assertArrayEquals(bytes, service.readImage(first.id(), 1));
        try (var images = Files.list(root.resolve("images"))) {
            assertEquals(List.of((long) bytes.length), images.map(path -> path.toFile().length()).toList());
        }
        reopen(settings(Map.of()));
        assertArrayEquals(bytes, service.readImage(first.id(), 1));
    }

    @Test @DisplayName("网址图片入箱时访问了对方的服务器")
    void remoteImageIsOnlyAReference() throws Exception {
        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (SocketException unavailable) {
            String reason = String.valueOf(unavailable.getMessage()).toLowerCase(java.util.Locale.ROOT);
            if (!reason.contains("permission denied") && !reason.contains("operation not permitted")) throw unavailable;
            Assumptions.abort("无法绑定本机端口来观察图片请求：" + unavailable.getMessage());
            return;
        }
        var requests = new AtomicInteger();
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/picture?v=2";
            var record = put("{image_url=" + url + "}").get(0);
            assertEquals(url, record.segments().get(0).value());
            assertThrows(IllegalArgumentException.class, () -> service.readImage(record.id(), 0));
            assertEquals(0, requests.get());
        } finally { server.stop(0); }
    }

    @Test @DisplayName("认领碰撞时通知被发送两次，或重启把正在发送的通知重新交了出去")
    void claimOnlyOnceAcrossRestart() throws Exception {
        var first = put("一{next}二").get(0);
        var pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Boolean> claim = () -> {
                try { service.claim(first.id(), Thread.currentThread().getName()); return true; }
                catch (IllegalStateException e) { assertTrue(e.getMessage().contains("发送中")); return false; }
            };
            var results = pool.invokeAll(List.of(claim, claim));
            assertNotEquals(results.get(0).get(), results.get(1).get());
        } finally { pool.shutdownNow(); }
        reopen(settings(Map.of()));
        var sending = service.list(SENDING, 0, 10, owner::equals);
        assertEquals(1, sending.size());
        assertEquals(now, sending.get(0).claimedAt());
        assertNotNull(sending.get(0).claimant());
        assertEquals(1, service.list(PENDING, 0, 10, owner::equals).size());
        var sent = service.receipt(first.id(), sending.get(0).claimNumber(), SENT, "完成");
        assertEquals("完成", sent.note());
        assertThrows(IllegalStateException.class, () -> service.claim(first.id(), "乙"));
    }

    @Test @DisplayName("停机期间过期的开播通知仍能认领，超时未报结果的通知被自动重发")
    void deadlinesAndRecovery() {
        var records = put("私有正文甲{next}私有正文乙");
        long one = records.get(0).id(), two = records.get(1).id();
        service.claim(two, "设备");
        now = now.plusSeconds(600);
        reopen(settings(Map.of()));
        assertTrue(service.list(PENDING, 0, 10, owner::equals).isEmpty());
        assertTrue(assertThrows(IllegalStateException.class, () -> service.claim(one, "设备")).getMessage().contains("过期"));
        assertEquals(two, service.list(SENDING, 0, 10, owner::equals).get(0).id());
        now = now.plusSeconds(300);
        reopen(settings(Map.of()));
        assertEquals(two, service.list(UNKNOWN, 0, 10, owner::equals).get(0).id());
        assertThrows(IllegalStateException.class, () -> service.receipt(two, 1, SENT, ""));
        assertEquals(now.plusSeconds(600), service.requeue(one).expiresAt());
        assertNull(service.requeue(two).claimant());
        var sending = service.claim(one, "设备");
        service.receipt(one, sending.claimNumber(), FAILED, "私有原文");
        service.requeue(one);
        assertEquals(VOID, service.voidPending(one).status());
        assertEquals(3, events.size());
        assertTrue(events.stream().allMatch(e -> !e.toString().contains("私有") && "101".equals(e.detail().get("streamerUid"))));
    }

    @Test @DisplayName("旧设备迟报结果，覆盖了管理员重新入箱后另一设备的发送状态")
    void rejectsResultFromEarlierClaim() {
        var first = service.claim(put("通知").get(0).id(), "设备甲");
        now = now.plusSeconds(900);
        reopen(settings(Map.of()));
        service.requeue(first.id());
        service.claim(first.id(), "设备乙");
        assertThrows(IllegalStateException.class, () -> service.receipt(first.id(), first.claimNumber(), SENT, null));
        assertEquals("设备乙", service.list(SENDING, 0, 1, owner::equals).get(0).claimant());
    }

    @Test @DisplayName("待发通知被报成已发，或已结束的记录被误作废")
    void rejectsInvalidTransitions() {
        long id = put("通知").get(0).id();
        assertTrue(assertThrows(IllegalStateException.class, () -> service.receipt(id, 0, SENT, null)).getMessage().contains("待发"));
        assertThrows(IllegalStateException.class, () -> service.requeue(id));
        var sending = service.claim(id, "设备");
        assertThrows(IllegalArgumentException.class, () -> service.receipt(id, sending.claimNumber(), PENDING, null));
        assertEquals(SKIPPED, service.receipt(id, sending.claimNumber(), SKIPPED, "重复").status());
        assertThrows(IllegalStateException.class, () -> service.voidPending(id));
    }

    @Test @DisplayName("重启或分条绕过会话限量，或者改名后历史记录跟着变名")
    void enforcesConversationLimitsPerRecord() {
        conversations.update(conversation, "早班", "", 30, 3);
        var records = put("一{next}二");
        assertEquals(PENDING, records.get(0).status());
        assertEquals(LIMITED, records.get(1).status());
        assertTrue(records.get(1).note().contains("间隔"));
        now = now.plusSeconds(30);
        reopen(settings(Map.of()));
        conversations.update(conversation, "午班", "", 0, 3);
        records = put("三{next}四{next}五");
        assertEquals(List.of(PENDING, PENDING, LIMITED), records.stream().map(OutboxRecord::status).toList());
        assertTrue(records.get(2).note().contains("每日"));
        assertEquals("早班", service.list(PENDING, 0, 1, owner::equals).get(0).conversationName());
        now = now.plus(Duration.ofDays(1));
        reopen(settings(Map.of()));
        assertEquals(PENDING, put("次日").get(0).status());
    }

    @Test @DisplayName("记录保留时间较短时，清理不应缩短最小间隔或重置当天额度")
    void limitsSurviveRecordRetention() {
        var settings = settings(Map.of("novabot.adapter.outbox.retention", "1m"));
        conversations.update(conversation, null, null, 600, 1);
        put("第一条");
        now = now.plusSeconds(120);
        reopen(settings);
        var second = put("第二条").get(0);
        assertEquals(LIMITED, second.status());
        assertTrue(second.note().contains("间隔"));
        now = now.plusSeconds(600);
        reopen(settings);
        assertTrue(put("第三条").get(0).note().contains("每日"));
    }

    @Test @DisplayName("成员翻页看到了别的主播，或同编号的另一个平台的主播")
    void filtersBeforePaging() {
        service.enqueue(conversation, new OutboxRecord.Streamer("other", owner.uid(), owner.name()), PushKind.OTHER, "隐藏");
        var records = put("一{next}二");
        assertEquals(records.get(0), service.list(PENDING, 0, 1, owner::equals).get(0));
        assertEquals(List.of(records.get(1)), service.list(null, records.get(0).id(), 1, owner::equals));
    }

    @Test @DisplayName("未取到主播昵称时，成员的时间线上缺少本人的外发失败摘要")
    void timelineKeepsStreamerWithoutNickname() {
        var record = service.enqueue(conversation, new OutboxRecord.Streamer("live", 123L, null),
                PushKind.OTHER, "通知").get(0);
        var claim = service.claim(record.id(), "设备");
        service.receipt(record.id(), claim.claimNumber(), FAILED, "无法发送");
        assertEquals("123", events.get(0).streamer());
        assertEquals("live", events.get(0).detail().get("streamerPlatform"));
    }

    @Test @DisplayName("清理后仍能取出老图片，或旧编号被新通知占用")
    void purgesRecordAndImage() throws Exception {
        long id = put("{image_base64=AQID}").get(0).id();
        now = now.plus(Duration.ofDays(14));
        reopen(settings(Map.of()));
        assertTrue(service.list(null, 0, 10, owner::equals).isEmpty());
        try (var files = Files.list(root.resolve("images"))) { assertEquals(0, files.count()); }
        assertThrows(IllegalArgumentException.class, () -> service.readImage(id, 0));
        assertTrue(put("新通知").get(0).id() > id);
    }

    @Test @DisplayName("不同种类的通知过早失效，或修改的有效期和超时时间没有生效")
    void configuredLifetimes() {
        var minutes = Map.of(PushKind.LIVE_ON, 10, PushKind.LIVE_OFF, 30, PushKind.DYNAMIC, 60,
                PushKind.LIVE_REPORT, 360, PushKind.REWARD_DIGEST, 360, PushKind.OTHER, 60);
        minutes.forEach((kind, ttl) -> assertEquals(now.plus(Duration.ofMinutes(ttl)),
                service.enqueue(conversation, owner, kind, "正文").get(0).expiresAt()));
        var settings = settings(Map.of("novabot.adapter.outbox.live-on-ttl", "2m",
                "novabot.adapter.outbox.claim-timeout", "2s", "novabot.adapter.outbox.retention", "1h"));
        reopen(settings);
        var record = put("通知").get(0);
        assertEquals(now.plusSeconds(120), record.expiresAt());
        service.claim(record.id(), "设备");
        now = now.plusSeconds(2);
        reopen(settings);
        assertEquals(1, service.list(UNKNOWN, 0, 10, owner::equals).size());
        now = now.plusSeconds(3600);
        reopen(settings);
        assertTrue(service.list(null, 0, 10, owner::equals).isEmpty());
    }
}
