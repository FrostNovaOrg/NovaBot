package org.frostnova.nova.bilibili.service;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.health.BilibiliRiskMetrics;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.event.live.common.PkBattleEvent.Opponent;
import org.frostnova.nova.core.util.HttpUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class BilibiliPkOpponentServiceTest {
    private final HttpUtil http = mock(HttpUtil.class);
    private final BilibiliPkOpponentService service = new BilibiliPkOpponentService(
            new BilibiliApiUtil(http, new NovaBilibiliProperties(), new BilibiliRiskMetrics()));
    private final Opponent opponent = new Opponent(1002L, 10002L, null, null);

    @AfterEach
    void close() {
        service.close();
    }

    @Test
    @DisplayName("PK 开打时读取资料不会卡住进房消息，重复开打和结算查询共用在途请求")
    void fetchOnceOffEventThread() throws Exception {
        CountDownLatch requested = new CountDownLatch(1);
        CountDownLatch reply = new CountDownLatch(1);
        Thread caller = Thread.currentThread();
        when(http.getJson(anyString(), any())).thenAnswer(call -> {
            assertNotSame(caller, Thread.currentThread());
            requested.countDown();
            assertTrue(reply.await(10, TimeUnit.SECONDS));
            String url = call.getArgument(0);
            JSONObject data;
            if (url.contains("/Master/info?uid=10002")) {
                data = JSONObject.of("info", JSONObject.of("uname", "对手"), "follower_num", 150);
            } else if (url.contains("/guardTab/topListNew") && url.contains("roomid=1002") && url.contains("ruid=10002")) {
                data = JSONObject.of("info", JSONObject.of("num", 5));
            } else {
                assertTrue(url.contains("/rank/getOnlineGoldRank") && url.contains("roomId=1002") && url.contains("ruid=10002"));
                data = JSONObject.of("onlineNum", 12);
            }
            return JSONObject.of("code", 0, "data", data);
        });
        var pending = service.lookup(71L, opponent);
        try {
            assertTrue(requested.await(10, TimeUnit.SECONDS));
            var duplicate = service.lookup(71L, new Opponent(1002L, 10002L, 21L, 1));
            assertFalse(pending.isDone());
            reply.countDown();
            var profile = pending.get(10, TimeUnit.SECONDS);
            assertEquals("对手", profile.nickname());
            assertEquals(150L, profile.fans());
            assertEquals(5, profile.guards());
            assertEquals(12, profile.onlineRankCount());
            assertEquals(profile, duplicate.get(10, TimeUnit.SECONDS));
            assertEquals(profile, service.lookup(71L, opponent).get(10, TimeUnit.SECONDS));
            verify(http, times(3)).getJson(anyString(), any());
        } finally {
            reply.countDown();
        }
    }

    @Test
    @DisplayName("同一场多方 PK 能分别读到每个对手；缺粉丝数或榜单总数时不会错填零")
    void differentOpponentsHaveIndependentProfiles() throws Exception {
        when(http.getJson(anyString(), any())).thenAnswer(call -> {
            String url = call.getArgument(0);
            JSONObject data = new JSONObject();
            if (url.contains("/Master/info")) {
                data.put("info", JSONObject.of("uname", url.endsWith("10002") ? "对手甲" : "对手乙"));
            } else if (url.contains("/guardTab/")) {
                data.put("info", JSONObject.of("num", url.contains("roomid=1002") ? 15 : 7));
            } else {
                data.put("list", java.util.List.of());
            }
            return JSONObject.of("code", 0, "data", data);
        });
        var first = service.lookup(71L, opponent).get(10, TimeUnit.SECONDS);
        var second = service.lookup(71L, new Opponent(1003L, 10003L, null, null)).get(10, TimeUnit.SECONDS);
        assertEquals(new BilibiliPkOpponentService.Profile("对手甲", null, 15, null), first);
        assertEquals(new BilibiliPkOpponentService.Profile("对手乙", null, 7, null), second);
        verify(http, times(6)).getJson(anyString(), any());
    }

    @Test
    @DisplayName("大航海接口被拦只缺该项，下一场能更新其余资料，冷却期间不会再打被拦接口")
    void partialProfileDuringCooldown() throws Exception {
        AtomicInteger infoTrips = new AtomicInteger();
        when(http.getJson(anyString(), any())).thenAnswer(call -> {
            String url = call.getArgument(0);
            if (url.contains("/guardTab/")) {
                return JSONObject.of("code", -412);
            }
            JSONObject data = url.contains("/Master/info")
                    ? JSONObject.of("info", JSONObject.of("uname", "对手"), "follower_num", infoTrips.incrementAndGet())
                    : JSONObject.of("onlineNum", 0);
            return JSONObject.of("code", 0, "data", data);
        });
        for (int i = 1; i <= 2; i++) {
            var profile = service.lookup((long) i, opponent).get(10, TimeUnit.SECONDS);
            assertEquals("对手", profile.nickname());
            assertEquals((long) i, profile.fans());
            assertNull(profile.guards());
            assertEquals(0, profile.onlineRankCount());
        }
        verify(http, times(5)).getJson(anyString(), any());
    }
}
