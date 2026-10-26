package org.frostnova.nova.core.service;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.assistant.AssistantProperties;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.enums.PushTargetType;
import org.frostnova.nova.core.handler.PreLiveReminderPushHandler;
import org.frostnova.nova.core.model.*;
import org.frostnova.nova.core.sender.NovaMessageSender;
import org.frostnova.nova.core.sender.PushGate;
import org.frostnova.nova.core.sender.FirstPushTipService;
import org.frostnova.nova.core.health.PushActivityRecorder;
import org.frostnova.nova.core.timeline.TimelineWriter;
import org.frostnova.nova.core.util.HttpUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PreLiveReminderLedgerTest {
    @TempDir Path dir;
    static final long HOST = 910000001L, VIEWER = 920000000L;
    static final LocalDate TODAY = LocalDate.of(2026, 10, 26);
    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @Test @DisplayName("多主播到点且常客始终缺昵称时整轮只恢复扫描一次，避免排队过久漏提醒")
    void recoversLedgerOncePerRound() throws Exception {
        var fixture = fixture(2, 12, 20);
        Path root = dir.resolve("audience-ledger");
        var walks = new AtomicInteger();
        try (var files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            files.when(() -> Files.walk(root)).thenAnswer(call -> {
                walks.incrementAndGet();
                return call.callRealMethod();
            });
            fixture.service().check();
            assertEquals(2, fixture.sent().size());
            assertTrue(fixture.sent().get(0).getDisplay().contains("合成观众1"));
            assertFalse(fixture.sent().get(0).getDisplay().contains("合成观众0"));
            assertEquals(1, walks.get(), "整轮只能全量扫描账本目录一次");
        }
        // 已发过的订阅不再读账本；独立查询仍须恢复，不能把恢复永久缓存。
        walks.set(0);
        try (var files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            files.when(() -> Files.walk(root)).thenAnswer(call -> {
                walks.incrementAndGet();
                return call.callRealMethod();
            });
            fixture.service().check();
            assertEquals(0, walks.get());
            fixture.ledger().sessions("test", HOST);
            assertEquals(1, walks.get());
            assertEquals(2, fixture.sent().size());
        }
    }

    @Test @EnabledIfSystemProperty(named = "reminder.benchmark", matches = "true")
    @DisplayName("二十位主播同时到点的合成归档测量，确认长历史不会拖延提醒")
    void measuresTwentyDueStreamers() throws Exception {
        var fixture = fixture(20, 200, 200);
        var materialStarted = new CountDownLatch(1);
        var readerStarted = new CountDownLatch(1);
        doAnswer(call -> {
            materialStarted.countDown();
            assertTrue(readerStarted.await(10, TimeUnit.SECONDS), "账本读取线程未开始");
            return call.callRealMethod();
        }).when(fixture.recap()).preLiveAudience(anyString(), anyLong(), anyLong());
        var reader = Executors.newSingleThreadExecutor();
        try {
            var wait = reader.submit(() -> {
                assertTrue(materialStarted.await(10, TimeUnit.SECONDS), "提醒未开始取素材");
                long begin = System.nanoTime();
                readerStarted.countDown();
                assertEquals(200, fixture.ledger().sessions("test", HOST).size());
                return (System.nanoTime() - begin) / 1_000_000;
            });
            long begin = System.nanoTime();
            fixture.service().check();
            long elapsed = (System.nanoTime() - begin) / 1_000_000;
            assertEquals(20, fixture.sent().size());
            System.out.println("Reminder round: streamers=20 sessionsPerStreamer=200 viewersPerSession=200 elapsedMs="
                    + elapsed + " ledgerReadWaitMs=" + wait.get(30, TimeUnit.SECONDS));
        } finally {
            reader.shutdownNow();
            assertTrue(reader.awaitTermination(10, TimeUnit.SECONDS), "账本读取线程未退出");
        }
    }

    @Test @DisplayName("满队列挤出小结时与小结读账本都能结束，避免提醒和控制台一起卡死")
    void fullQueueCallbackDoesNotDeadlockLedger() throws Exception {
        Path output = dir.resolve("lock-order-output.txt");
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-javaagent:" + Path.of(org.mockito.Mockito.class.getProtectionDomain().getCodeSource().getLocation().toURI()),
                "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                LockOrderProbe.class.getName(), dir.toString()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(process.waitFor(45, TimeUnit.SECONDS), "锁顺序检查进程未结束");
            assertEquals(0, process.exitValue(), Files.readString(output));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(10, TimeUnit.SECONDS), "锁顺序检查进程未退出");
            }
        }
    }

    public static class LockOrderProbe {
        public static void main(String[] args) throws Exception {
            var test = new PreLiveReminderLedgerTest();
            test.dir = Path.of(args[0]);
            var properties = new NovaCoreProperties(); properties.getPush().setEnabled(true);
            var destinations = mock(NovaSenderService.class);
            when(destinations.getSender("qq-onebot")).thenReturn(Optional.of(new Sender()));
            var sender = new NovaMessageSender(mock(HttpUtil.class), destinations,
                    new PushActivityRecorder(TimelineWriter.NONE), new PushGate(properties), TimelineWriter.NONE,
                    new AtAllQuotaService(properties), mock(ObjectProvider.class), mock(FirstPushTipService.class));
            var fixture = test.fixture(1, 12, 20, sender);
            var recapDelivery = mock(SessionRecapDeliveryService.class);
            var materialStarted = new CountDownLatch(1);
            var recapHolding = new CountDownLatch(1);
            var finished = new CountDownLatch(2);
            var failure = new AtomicReference<Throwable>();
            var callbackRan = new java.util.concurrent.atomic.AtomicBoolean();
            var queue = new LinkedBlockingQueue<Message>(500);
            var oldest = Message.create("qq-onebot", PushTargetType.FRIEND, 930000001L, "合成小结").get(0);
            oldest.addOnFailureCallback(() -> {
                synchronized (recapDelivery) { callbackRan.set(true); }
            });
            queue.add(oldest);
            for (int i = 1; i < 500; i++)
                queue.add(Message.create("qq-onebot", PushTargetType.FRIEND, 930000001L, "合成排队消息").get(0));
            // 保持平台队列满载，不启动平台线程，直接覆盖真实的入队与挤出回调。
            ReflectionTestUtils.setField(sender, "queueMap", new ConcurrentHashMap<>(Map.of("qq-onebot", queue)));
            doAnswer(call -> {
                materialStarted.countDown();
                assertTrue(recapHolding.await(10, TimeUnit.SECONDS), "小结线程未持锁");
                return call.callRealMethod();
            }).when(fixture.recap()).preLiveAudience(anyString(), anyLong(), anyLong());
            Thread round = new Thread(() -> {
                try { fixture.service().check(); }
                catch (Throwable error) { failure.compareAndSet(null, error); }
                finally { finished.countDown(); }
            }, "reminder-round");
            Thread recap = new Thread(() -> {
                try {
                    assertTrue(materialStarted.await(10, TimeUnit.SECONDS), "提醒线程未开始取素材");
                    synchronized (recapDelivery) {
                        recapHolding.countDown();
                        fixture.ledger().sessions("test", HOST);
                    }
                } catch (Throwable error) { failure.compareAndSet(null, error); }
                finally { finished.countDown(); }
            }, "recap-ledger-read");
            // 旧实现死锁时也让独立进程退出，把失败报告给测试进程。
            round.setDaemon(true); recap.setDaemon(true);
            round.start(); recap.start();
            assertTrue(finished.await(20, TimeUnit.SECONDS),
                    "提醒与小结未在限时内结束: round=" + round.getState() + ", recap=" + recap.getState());
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
            assertTrue(callbackRan.get(), "必须真正挤出最旧的小结并执行失败回调");
            assertEquals(1, sender.getDroppedCount());
            assertEquals(500, sender.getPendingCount());
        }
    }

    record Fixture(PreLiveReminderService service, AudienceLedgerService ledger, SessionRecapService recap, List<Message> sent) {}

    Fixture fixture(int hosts, int sessions, int viewers) throws Exception {
        return fixture(hosts, sessions, viewers, null);
    }

    Fixture fixture(int hosts, int sessions, int viewers, NovaMessageSender actualSender) throws Exception {
        var properties = new NovaCoreProperties();
        properties.getLive().setLiveDataPath(dir.resolve("live.json").toString());
        properties.getPush().setEnabled(true);
        var privacy = new AssistantProperties();
        var users = new ArrayList<PushUser>();
        int absent = viewers == 200 ? 30 : 10;
        try (var archive = Files.newBufferedWriter(dir.resolve("sessions.jsonl"))) {
            for (int host = 0; host < hosts; host++) {
                var user = new PushUser(); user.setPlatform("test"); user.setUid(HOST + host);
                var target = new PushTarget(); target.setPlatform("qq-onebot"); target.setNum(930000001L + host);
                target.setType(PushTargetType.FRIEND); target.setStreamerSelf(true); target.setUser(user);
                var notice = new PushMessage(); notice.setHandler(PreLiveReminderPushHandler.class.getName());
                target.setMessages(new ArrayList<>(List.of(notice))); user.setTargets(new ArrayList<>(List.of(target)));
                users.add(user);
                for (int day = 1; day <= sessions; day++) {
                    long start = TODAY.minusDays(day).atTime(20, day == 7 ? 0 : (day % 6) * 10).atZone(ZONE).toInstant().toEpochMilli();
                    var present = new ArrayList<Long>();
                    var names = new ArrayList<JSONObject>();
                    for (int viewer = day <= 5 ? absent : 0; viewer < viewers; viewer++) {
                        present.add(VIEWER + viewer);
                        // 一位缺席常客在全部历史中都没有昵称，迫使查询走完历史。
                        if (viewer != 0) names.add(JSONObject.of("uid", VIEWER + viewer, "uname", "合成观众" + viewer));
                    }
                    if (day == 1) {
                        present.add(VIEWER + viewers); present.add(VIEWER + viewers + 1);
                    }
                    archive.write(JSONObject.of("platform", "test", "uid", user.getUid(), "startTime", start,
                            "endTime", start + 3600000, "durationSeconds", 3600, "endReason", "NORMAL",
                            "userSets", Map.of("enter_users", present), "anonymousCounts", Map.of()).toJSONString());
                    archive.newLine();
                    Path detail = Files.createDirectories(dir.resolve("details/test-" + user.getUid() + "-" + start));
                    Files.writeString(detail.resolve("detail.json"), JSONObject.of("rankings", Map.of("enter_users", names)).toJSONString());
                    if (day == 1) {
                        var lines = new StringBuilder();
                        for (int n = 0; n < 4; n++) lines.append(JSONObject.of("uid", VIEWER + viewers + (n == 0 ? 0 : 1),
                                "at", start + n + 1, "uname", n == 0 ? "合成新人甲" : "合成新人乙").toJSONString()).append('\n');
                        Files.writeString(detail.resolve("danmu.jsonl"), lines);
                    }
                }
            }
        }
        var ledger = new AudienceLedgerService(properties, privacy);
        ledger.rebuild();
        var archive = new LiveSessionArchive(properties);
        var recap = spy(new SessionRecapService(ledger, archive, new LiveDetailArchive(properties), privacy));
        var source = mock(AbstractDataSource.class); when(source.getAllUsers()).thenReturn(users);
        var live = mock(LiveDataService.class); when(live.getLiveStatus(anyString(), anyLong())).thenReturn(Optional.of(false));
        var profiles = mock(StreamerProfileStore.class);
        when(profiles.read(anyString(), anyLong())).thenReturn(StreamerProfile.empty(StreamerProfile.Status.READY));
        var sent = new ArrayList<Message>();
        var sender = mock(NovaMessageSender.class);
        doAnswer(call -> {
            Message message = call.getArgument(0); sent.add(message);
            message.markDelivered("synthetic-reminder").forEach(Runnable::run); return null;
        }).when(sender).sendScheduled(any(), any());
        var service = new PreLiveReminderService(source, archive, live, ledger, recap, profiles, privacy,
                actualSender == null ? sender : actualSender,
                new PushGate(properties), new PreLiveReminderStore(dir.resolve("pre-live-reminder-deliveries.json")),
                Clock.fixed(TODAY.atTime(19, 0).atZone(ZONE).toInstant(), ZoneOffset.UTC));
        return new Fixture(service, ledger, recap, sent);
    }
}
