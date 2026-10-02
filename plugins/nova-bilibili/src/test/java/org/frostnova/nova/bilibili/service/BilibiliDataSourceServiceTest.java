package org.frostnova.nova.bilibili.service;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.bilibili.model.Up;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.event.datasource.base.NovaDataSourceChangeEvent;
import org.frostnova.nova.core.model.PushUser;
import org.frostnova.nova.core.datasource.DataSourceService.StreamerWithFans;
import org.frostnova.nova.core.service.NovaStateStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 查主播「补全＋粉丝数」往平台打的趟数
 * <p>
 * 昵称、房间号与粉丝数出自主播信息接口（MASTER_INFO_API）的同一份响应。
 * 此前补全一趟、粉丝数再一趟，查一位主播要打两趟；本族钉住一趟这个数——
 * 桩上给粉丝数单留的那趟若又被调，这里的读数会先于任何人看见地翻倍。
 */
@DisplayName("查主播连粉丝数")
class BilibiliDataSourceServiceTest {
    private static final long UID = 10001L;

    /**
     * 接口替身查回来的房间号
     */
    private static final long ROOM_FROM_API = 50004L;

    private BilibiliApiUtil api;

    /**
     * 往主播信息接口去的趟数：getUpInfoByUid 与 getFansCount 打的都是它，桩上各计一票
     */
    private final AtomicInteger masterInfoTrips = new AtomicInteger();

    @BeforeEach
    void setUp() {
        api = mock(BilibiliApiUtil.class);
    }

    @Test
    @DisplayName("一次「补全＋粉丝数」只向主播信息接口去一趟")
    void completesAndFetchesFansInOneMasterInfoTrip() {
        Up up = new Up(UID, "主播甲", 20002L, "https://example.invalid/face.jpg", 243L);
        when(api.getUpInfoByUid(UID)).thenAnswer(invocation -> {
            masterInfoTrips.incrementAndGet();
            return up;
        });
        when(api.getFansCount(UID)).thenAnswer(invocation -> {
            masterInfoTrips.incrementAndGet();
            return Optional.of(243L);
        });

        PushUser user = new PushUser();
        user.setUid(UID);
        user.setPlatform("bilibili");
        StreamerWithFans found = new BilibiliDataSourceService(api).completeStreamerWithFans(user);

        assertEquals("主播甲", found.user().getUname(), "补全真的发生了，不是一趟都没打");
        assertEquals(243L, found.fans(), "粉丝数随同一趟响应带回");
        assertEquals(1, masterInfoTrips.get(), "昵称、房间号与粉丝数在主播信息接口的同一份响应里，一趟就该全拿到");
    }

    @Test
    @DisplayName("按直播间号查主播不另打粉丝数那一趟")
    void lookupByRoomIdDoesNotCallGetFansCount() {
        long room = 20002L;
        Up up = new Up(UID, "主播甲", room, "https://example.invalid/face.jpg", 243L);
        AtomicInteger roomInfoTrips = new AtomicInteger();
        AtomicInteger fansCountTrips = new AtomicInteger();
        when(api.getUpInfoByRoomId(room)).thenAnswer(invocation -> {
            roomInfoTrips.incrementAndGet();
            return up;
        });
        when(api.getFansCount(UID)).thenAnswer(invocation -> {
            fansCountTrips.incrementAndGet();
            return Optional.of(243L);
        });

        StreamerWithFans found = new BilibiliDataSourceService(api).lookupByRoomIdWithFans(room);

        assertEquals("主播甲", found.user().getUname(), "补全真的发生了，不是一趟都没打");
        assertEquals(room, found.user().getRoomId(), "房间号随同一趟带回");
        assertEquals(243L, found.fans(), "粉丝数随同一趟响应带回");
        assertEquals(1, roomInfoTrips.get(), "按直播间号查只该打房间信息这一趟");
        assertEquals(0, fansCountTrips.get(), "粉丝数已在主播信息那份响应里，不得再打 getFansCount");
    }

    @Test
    @DisplayName("控制台查主播补上房间号，不发数据源变更")
    void lookupCompletionDoesNotPublishRoomReady() {
        Up up = new Up(UID, "主播甲", 20002L, "https://example.invalid/face.jpg", 243L);
        when(api.getUpInfoByUid(UID)).thenReturn(up);
        List<NovaDataSourceChangeEvent> events = new ArrayList<>();
        ApplicationEventPublisher publisher = event -> {
            if (event instanceof NovaDataSourceChangeEvent change) {
                events.add(change);
            }
        };

        PushUser user = new PushUser();
        user.setUid(UID);
        user.setPlatform("bilibili");
        StreamerWithFans found = new BilibiliDataSourceService(api, publisher).completeStreamerWithFans(user);

        assertEquals("主播甲", found.user().getUname(), "查到的昵称照旧带回");
        assertEquals(20002L, found.user().getRoomId(), "原来没有的房间号照旧补上");
        assertEquals(243L, found.fans(), "粉丝数照旧随这一趟带回");
        assertEquals(0, events.size(),
                "在控制台查一个还没有房间号的主播，补上房间号之后不该发数据源变更；发出去工程日志会多一行「推送配置已变更」，并白跑一次重新同步");
    }

    @Test
    @DisplayName("单项补全时三项齐全的不再打接口, 缺房间号的照旧打一趟")
    void skipsMasterInfoWhenNicknameRoomAndFaceAlreadyFilled() {
        Map<Long, Integer> tripsByUid = new HashMap<>();
        when(api.getUpInfoByUid(anyLong())).thenAnswer(invocation -> {
            long uid = invocation.getArgument(0);
            tripsByUid.merge(uid, 1, Integer::sum);
            return new Up(uid, "接口查回来的名字", ROOM_FROM_API, "https://example.invalid/from-api.jpg", 1L);
        });

        PushUser full = filled(20001L, "主播甲", 30001L, "https://example.invalid/a.jpg");
        PushUser missingRoom = filled(20004L, "主播丁", null, "https://example.invalid/d.jpg");

        BilibiliDataSourceService service = new BilibiliDataSourceService(api);
        service.completePushUser(full);
        service.completePushUser(missingRoom);

        assertEquals(0, tripsByUid.getOrDefault(20001L, 0), "昵称、房间号与头像都齐的主播，那一趟什么也不改，不该打接口");
        assertEquals(1, tripsByUid.getOrDefault(20004L, 0), "缺房间号的照旧补，只打这一趟");
        assertEquals(ROOM_FROM_API, missingRoom.getRoomId(), "缺的房间号照旧补上");
        assertEquals("主播丁", missingRoom.getUname(), "已有的昵称不被接口查回来的覆盖");
    }

    @Test
    @DisplayName("推送配置里的主播补上房间号后仍发数据源变更")
    void pushUserCompletionPublishesRoomReady() {
        Up up = new Up(UID, "主播甲", 20002L, "https://example.invalid/face.jpg", 243L);
        when(api.getUpInfoByUid(UID)).thenReturn(up);
        List<NovaDataSourceChangeEvent> events = new ArrayList<>();
        ApplicationEventPublisher publisher = event -> {
            if (event instanceof NovaDataSourceChangeEvent change) {
                events.add(change);
            }
        };

        PushUser user = new PushUser();
        user.setUid(UID);
        user.setPlatform("bilibili");
        new BilibiliDataSourceService(api, publisher).completePushUser(user);

        assertEquals(20002L, user.getRoomId(), "房间号照旧补上");
        assertEquals(1, events.size(),
                "推送配置里的主播补上房间号后要发数据源变更，直播间连接才按新配置重新同步");
    }

    /**
     * 一位昵称、房间号、头像按给出的值先填好的推送用户；房间号传空即构造「只缺房间号」的那一位
     */
    private static PushUser filled(long uid, String uname, Long roomId, String face) {
        PushUser user = new PushUser();
        user.setUid(uid);
        user.setPlatform("bilibili");
        user.setUname(uname);
        user.setRoomId(roomId);
        user.setFace(face);
        return user;
    }

    /**
     * 造一个状态存储，状态文件落在临时目录里，别写进仓库树
     */
    private static NovaStateStore newStore(Path dir) {
        NovaCoreProperties properties = new NovaCoreProperties();
        properties.getLive().setLiveDataPath(dir.resolve("data.json").toString());
        return new NovaStateStore(properties);
    }

    @Test
    @DisplayName("启动补全不等全部打完接口, 登录那一步就能开始")
    void startupCompletionDoesNotBlockLogin(@TempDir Path dir) throws Exception {
        int apiDelayMs = 200;
        int userCount = 3;
        CountDownLatch apiDone = new CountDownLatch(userCount);
        when(api.getUpInfoByUid(anyLong())).thenAnswer(invocation -> {
            Thread.sleep(apiDelayMs);
            apiDone.countDown();
            return new Up(invocation.getArgument(0), "名字", 100L, "https://example.invalid/f.jpg", 1L);
        });

        List<PushUser> users = new ArrayList<>();
        for (long i = 1; i <= userCount; i++) {
            users.add(filled(i, null, null, null));
        }

        long start = System.currentTimeMillis();
        new BilibiliDataSourceService(api, null, newStore(dir)).completePushUsers(users);
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(elapsed < apiDelayMs,
                "completePushUsers 不该等打完接口才返回, 耗时 " + elapsed + "ms; "
                        + "现在它在就绪事件主线程上逐个打接口, 登录那一步要等全部补全做完才开始");
        assertTrue(apiDone.await(3, TimeUnit.SECONDS), "后台补全该把接口打完");
    }

    @Test
    @DisplayName("缓存里有房间号的主播, 启动填上就能连, 不等补全")
    void cacheFilledStreamersConnectImmediately(@TempDir Path dir) throws Exception {
        NovaStateStore store = newStore(dir);
        store.write("StreamerProfile", data -> {
            JSONObject profile = new JSONObject();
            profile.put("roomId", 30001L);
            profile.put("uname", "主播甲");
            profile.put("face", "https://example.invalid/a.jpg");
            data.put("bilibili:20001", profile);
        });

        CountDownLatch apiDone = new CountDownLatch(1);
        when(api.getUpInfoByUid(anyLong())).thenAnswer(invocation -> {
            apiDone.countDown();
            return new Up(invocation.getArgument(0), "接口名字", 40001L, "https://example.invalid/api.jpg", 1L);
        });

        PushUser user = filled(20001L, null, null, null);
        new BilibiliDataSourceService(api, null, store).completePushUsers(List.of(user));

        assertEquals(30001L, user.getRoomId(), "缓存里的房间号启动填上就能连, 不等补全");
        assertEquals("主播甲", user.getUname(), "缓存里的昵称填上");
        assertEquals("https://example.invalid/a.jpg", user.getFace(), "缓存里的头像填上");

        assertTrue(apiDone.await(2, TimeUnit.SECONDS), "后台补全该把接口打完");
    }

    @Test
    @DisplayName("缓存里没有的主播, 补全拿到房间号后发数据源变更")
    void nonCachedStreamersFireChangeAfterCompletion(@TempDir Path dir) throws Exception {
        CountDownLatch eventFired = new CountDownLatch(1);
        when(api.getUpInfoByUid(anyLong())).thenAnswer(invocation ->
                new Up(invocation.getArgument(0), "名字", 50001L, "https://example.invalid/f.jpg", 1L));

        List<NovaDataSourceChangeEvent> events = new ArrayList<>();
        ApplicationEventPublisher publisher = event -> {
            if (event instanceof NovaDataSourceChangeEvent change) {
                events.add(change);
                eventFired.countDown();
            }
        };

        PushUser user = filled(10001L, null, null, null);
        new BilibiliDataSourceService(api, publisher, newStore(dir)).completePushUsers(List.of(user));

        assertTrue(eventFired.await(3, TimeUnit.SECONDS),
                "缓存里没有的主播, 补全拿到房间号后要发数据源变更");
        assertEquals(1, events.size(), "缓存里没有的主播, 补全拿到房间号后要发数据源变更");
        assertEquals(50001L, user.getRoomId(), "房间号补上");
    }

    @Test
    @DisplayName("接口给空时, 已按缓存连上的房间号与缓存都不变, 昵称头像不被清空")
    void nullRoomFromApiDoesNotOverwrite(@TempDir Path dir) throws Exception {
        NovaStateStore store = newStore(dir);
        store.write("StreamerProfile", data -> {
            JSONObject profile = new JSONObject();
            profile.put("roomId", 30001L);
            profile.put("uname", "主播甲");
            profile.put("face", "https://example.invalid/a.jpg");
            data.put("bilibili:20001", profile);
        });

        CountDownLatch apiDone = new CountDownLatch(1);
        when(api.getUpInfoByUid(anyLong())).thenAnswer(invocation -> {
            apiDone.countDown();
            return new Up(invocation.getArgument(0), null, null, null, null);
        });

        PushUser user = filled(20001L, null, null, null);
        new BilibiliDataSourceService(api, null, store).completePushUsers(List.of(user));

        assertTrue(apiDone.await(2, TimeUnit.SECONDS), "后台补全该把接口打完");
        Thread.sleep(300);

        assertEquals(30001L, user.getRoomId(),
                "接口给空房间号后, 已按缓存连上的房间号不该被改成空; 一次接口抽风就把在连的直播间断掉");
        assertEquals("主播甲", user.getUname(), "接口给空昵称时, 已填上的昵称不该被清空");
        assertEquals("https://example.invalid/a.jpg", user.getFace(), "接口给空头像时, 已填上的头像不该被清空");

        Long cachedRoom = store.<Long>read("StreamerProfile", "bilibili:20001",
                data -> data.getJSONObject("bilibili:20001").getLong("roomId")).orElse(null);
        assertEquals(30001L, cachedRoom,
                "接口给空时缓存里的房间号不该被清空; 缓存清了下次重启就退回首装行为");
    }

    @Test
    @DisplayName("没有直播间的主播接口给 0 是正常答复, 不记警告, 昵称头像照进缓存")
    void noRoomStreamerGetsZeroIsNormalAnswer(@TempDir Path dir) throws Exception {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(BilibiliDataSourceService.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            NovaStateStore store = newStore(dir);
            CountDownLatch apiDone = new CountDownLatch(1);
            // 只订动态、本就没有直播间的主播: 原值为空, 接口给 0
            when(api.getUpInfoByUid(anyLong())).thenAnswer(invocation -> {
                apiDone.countDown();
                return new Up(invocation.getArgument(0), "主播戊", 0L, "https://example.invalid/e.jpg", 1L);
            });

            PushUser user = filled(30002L, null, null, null);
            new BilibiliDataSourceService(api, null, store).completePushUsers(List.of(user));

            assertTrue(apiDone.await(2, TimeUnit.SECONDS), "后台补全该把接口打完");
            Thread.sleep(300);

            long warnings = appender.list.stream()
                    .filter(e -> e.getLevel().toInt() >= ch.qos.logback.classic.Level.WARN.toInt())
                    .count();
            assertEquals(0, warnings,
                    "没有直播间的主播接口给 0 是正常答复, 不该记警告; 只订动态的主播每次启动都被刷一条");

            assertEquals("主播戊", user.getUname(), "接口给的昵称照常拿到");
            assertEquals("https://example.invalid/e.jpg", user.getFace(), "接口给的头像照常拿到");

            String cachedUname = store.<String>read("StreamerProfile", "bilibili:30002",
                    data -> data.getJSONObject("bilibili:30002").getString("uname")).orElse(null);
            assertEquals("主播戊", cachedUname, "昵称该进缓存, 不然下次重启还得再查");
            String cachedFace = store.<String>read("StreamerProfile", "bilibili:30002",
                    data -> data.getJSONObject("bilibili:30002").getString("face")).orElse(null);
            assertEquals("https://example.invalid/e.jpg", cachedFace, "头像该进缓存");
            Long cachedRoom = store.<Long>read("StreamerProfile", "bilibili:30002",
                    data -> data.getJSONObject("bilibili:30002").getLong("roomId")).orElse(null);
            assertNull(cachedRoom, "房间号该是空, 不然下次启动会按假房间号去连");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    @DisplayName("补全在名字看得出是补全的守护线程上跑, 非守护线程会在停机时拖住退出")
    void completionRunsOnNamedDaemonThread(@TempDir Path dir) throws Exception {
        AtomicReference<String> threadName = new AtomicReference<>("");
        AtomicBoolean daemon = new AtomicBoolean();
        CountDownLatch apiDone = new CountDownLatch(1);
        when(api.getUpInfoByUid(anyLong())).thenAnswer(invocation -> {
            threadName.set(Thread.currentThread().getName());
            daemon.set(Thread.currentThread().isDaemon());
            apiDone.countDown();
            return new Up(invocation.getArgument(0), "名字", 100L, "https://example.invalid/f.jpg", 1L);
        });

        new BilibiliDataSourceService(api, null, newStore(dir))
                .completePushUsers(List.of(filled(1L, null, null, null)));

        assertTrue(apiDone.await(2, TimeUnit.SECONDS), "后台补全该把接口打完");
        assertTrue(daemon.get(), "补全该在守护线程上跑, 非守护线程会在停机时拖住退出");
        assertTrue(threadName.get().contains("complete"),
                "补全线程的名字该看得出是补全, 实际是 " + threadName.get());
    }

    @Test
    @DisplayName("停机后后台补全随之停下, 余下几位不再打接口")
    void shutdownStopsRemainingCompletion(@TempDir Path dir) throws Exception {
        AtomicInteger apiCalls = new AtomicInteger();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch moreCalls = new CountDownLatch(2);
        CountDownLatch holdFirst = new CountDownLatch(1);

        when(api.getUpInfoByUid(anyLong())).thenAnswer(invocation -> {
            if (apiCalls.incrementAndGet() == 1) {
                firstStarted.countDown();
                try {
                    holdFirst.await(3, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("补全被停机打断", e);
                }
            } else {
                moreCalls.countDown();
            }
            return new Up(invocation.getArgument(0), "名字", 100L, "https://example.invalid/f.jpg", 1L);
        });

        List<PushUser> users = new ArrayList<>();
        for (long i = 1; i <= 3; i++) {
            users.add(filled(i, null, null, null));
        }
        BilibiliDataSourceService service = new BilibiliDataSourceService(api, null, newStore(dir));
        service.completePushUsers(users);

        assertTrue(firstStarted.await(3, TimeUnit.SECONDS), "第一位该开始打接口");
        // 走 @PreDestroy 那条真路停机, 不直接打断线程
        service.stopCompletion();
        // 放开第一位让它走完; 执行器没关的话余下两位会接着打接口
        holdFirst.countDown();
        assertFalse(moreCalls.await(500, TimeUnit.MILLISECONDS),
                "停机后余下几位不该再打接口; 现在停机后还逐位打接口刷 ERROR, 又打了 "
                        + (2 - moreCalls.getCount()) + " 位");
    }
}
