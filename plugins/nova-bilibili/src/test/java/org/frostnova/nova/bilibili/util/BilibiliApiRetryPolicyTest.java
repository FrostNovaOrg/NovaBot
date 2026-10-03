package org.frostnova.nova.bilibili.util;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.bilibili.account.BilibiliAccountLoginProvider;
import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.exception.ResponseCodeException;
import org.frostnova.nova.bilibili.health.BilibiliRiskMetrics;
import org.frostnova.nova.bilibili.service.BilibiliAccountService;
import org.frostnova.nova.bilibili.service.BilibiliDataSourceService;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.datasource.DataSourceService.StreamerWithFans;
import org.frostnova.nova.core.datasource.DataSourceServiceRegistry;
import org.frostnova.nova.core.event.datasource.base.NovaDataSourceChangeEvent;
import org.frostnova.nova.core.model.PushUser;
import org.frostnova.nova.core.util.HttpUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.client.HttpClientErrorException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 只读接口暂时失败要当次再试，被拦下则先歇着，业务码要记下。
 * <p>
 * 重试间隔在测试里写成 0，不靠睡眠撞时序。
 */
@DisplayName("接口错误码重试")
class BilibiliApiRetryPolicyTest {
    private static final String URL =
            "https://api.bilibili.com/x/relation/followings?vmid=1&pn=1";

    private static final String PATH = "https://api.bilibili.com/x/relation/followings";

    private static final Instant T0 = Instant.parse("2026-09-26T08:00:00Z");

    private BilibiliApiUtil api(HttpUtil http, BilibiliRiskMetrics metrics) {
        NovaBilibiliProperties properties = new NovaBilibiliProperties();
        properties.getNetwork().setApiRetryMaxTimes(3);
        properties.getNetwork().setApiRetryInterval(0);
        return new BilibiliApiUtil(http, properties, metrics);
    }

    private static HttpClientErrorException http412() {
        return HttpClientErrorException.create(HttpStatus.PRECONDITION_FAILED, "blocked",
                HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("只读接口先回服务暂不可用、紧接着成功：这一次应当拿到数据")
    void transientCodeThenSuccessReturnsData() {
        HttpUtil http = mock(HttpUtil.class);
        JSONObject fail = new JSONObject();
        fail.put("code", -503);
        fail.put("message", "服务暂不可用");
        JSONObject data = new JSONObject();
        data.put("marker", "ok");
        JSONObject ok = new JSONObject();
        ok.put("code", 0);
        ok.put("data", data);
        when(http.getJson(anyString(), any())).thenReturn(fail, ok);

        JSONObject result = api(http, mock(BilibiliRiskMetrics.class)).requestBilibiliApi(URL);

        assertEquals("ok", result.getString("marker"),
                "只读接口回一次服务暂不可用就整次失败，下一秒恢复也拿不到数据");
        verify(http, times(2)).getJson(anyString(), any());
    }

    @Test
    @DisplayName("风控拦截不应当连着打三次")
    void http412DoesNotHammer() {
        HttpUtil http = mock(HttpUtil.class);
        when(http.getJson(anyString(), any())).thenThrow(http412());

        assertThrows(RuntimeException.class,
                () -> api(http, mock(BilibiliRiskMetrics.class)).requestBilibiliApi(URL));

        verify(http, times(1)).getJson(anyString(), any());
    }

    @Test
    @DisplayName("被拦下之后冷却还没到，不应当再去打接口")
    void coolingSkipsTheWire() {
        HttpUtil http = mock(HttpUtil.class);
        when(http.getJson(anyString(), any())).thenThrow(http412());
        BilibiliApiUtil api = api(http, mock(BilibiliRiskMetrics.class));

        RuntimeException first = assertThrows(RuntimeException.class, () -> api.requestBilibiliApi(URL));
        RuntimeException second = assertThrows(RuntimeException.class, () -> api.requestBilibiliApi(URL));

        assertTrue(first.getMessage().contains("被风控拦下"),
                "拦下之后调用方看不到还要等多久: " + first.getMessage());
        assertTrue(second.getMessage().contains("被风控拦下"),
                "冷却期内仍把请求打出去: " + second.getMessage());
        verify(http, times(1)).getJson(anyString(), any());
    }

    @Test
    @DisplayName("非 0 的业务码应当留下接口、码和原文")
    void nonZeroCodeLeavesATrace() {
        HttpUtil http = mock(HttpUtil.class);
        BilibiliRiskMetrics metrics = new BilibiliRiskMetrics();
        JSONObject body = new JSONObject();
        body.put("code", 10086001);
        body.put("message", "平台原文样例");
        when(http.getJson(anyString(), any())).thenReturn(body);

        ResponseCodeException error = assertThrows(ResponseCodeException.class,
                () -> api(http, metrics).requestBilibiliApi(URL));
        assertEquals(10086001, error.getCode());

        boolean traced = false;
        for (BilibiliRiskMetrics.Kind kind : BilibiliRiskMetrics.Kind.values()) {
            String detail = metrics.lastDetail(kind).orElse("");
            if (detail.contains(PATH) && detail.contains("10086001") && detail.contains("平台原文样例")) {
                traced = true;
            }
        }
        assertTrue(traced, "非 0 业务码不留接口、码和原文，事后无从知道平台回过什么");
    }

    @Test
    @DisplayName("同一接口同一码重复出现时，样本停在第 1 次，计数继续加")
    void repeatedBusinessCodeKeepsTheFirstSample() {
        HttpUtil http = mock(HttpUtil.class);
        BilibiliRiskMetrics metrics = new BilibiliRiskMetrics();
        JSONObject body = new JSONObject();
        body.put("code", 10086001);
        body.put("message", "平台原文样例");
        when(http.getJson(anyString(), any())).thenReturn(body);
        BilibiliApiUtil api = api(http, metrics);

        assertThrows(ResponseCodeException.class, () -> api.requestBilibiliApi(URL));
        assertThrows(ResponseCodeException.class, () -> api.requestBilibiliApi(URL));

        assertEquals(2, metrics.count(BilibiliRiskMetrics.Kind.BUSINESS_CODE, Duration.ofMinutes(1)));
        String detail = metrics.lastDetail(BilibiliRiskMetrics.Kind.BUSINESS_CODE).orElse("");
        assertTrue(detail.contains("count=1"), "第二次不该把样本换成 count=2: " + detail);
        assertTrue(detail.contains("平台原文样例"), detail);
    }

    @Test
    @DisplayName("另一个暂时性码同样当次再试")
    void otherTransientCodeThenSuccessReturnsData() {
        HttpUtil http = mock(HttpUtil.class);
        when(http.getJson(anyString(), any())).thenReturn(code(4101130, "加载错误，请稍后再试"), ok("loaded"));

        JSONObject result = api(http, mock(BilibiliRiskMetrics.class)).requestBilibiliApi(URL);

        assertEquals("loaded", result.getString("marker"));
        verify(http, times(2)).getJson(anyString(), any());
    }

    @Test
    @DisplayName("冷却从 1 分钟逐级加到 4 分钟，试探通过后马上回到能发")
    void cooldownDoublesThenResets() {
        AtomicReference<Instant> now = new AtomicReference<>(T0);
        HttpUtil http = mock(HttpUtil.class);
        when(http.getJson(anyString(), any())).thenThrow(http412());
        BilibiliApiUtil api = clocked(http, now);
        String url = "https://api.live.bilibili.com/room/v1/Room/get_info?room_id=1";

        assertBlocked(api, url, now, T0, "约 1 分钟");
        verify(http, times(1)).getJson(anyString(), any());

        assertBlocked(api, url, now, T0.plusSeconds(59), "被风控拦下");
        verify(http, times(1)).getJson(anyString(), any());

        assertBlocked(api, url, now, T0.plusSeconds(60), "约 2 分钟");
        verify(http, times(2)).getJson(anyString(), any());

        assertBlocked(api, url, now, T0.plus(Duration.ofMinutes(3)), "约 4 分钟");
        verify(http, times(3)).getJson(anyString(), any());

        doReturn(ok("back")).when(http).getJson(anyString(), any());
        now.set(T0.plus(Duration.ofMinutes(7)));
        assertEquals("back", api.requestBilibiliApi(url).getString("marker"), "到点的试探应当发出去");
        assertEquals("back", api.requestBilibiliApi(url).getString("marker"), "试探通过后不该再空等一轮");
        verify(http, times(5)).getJson(anyString(), any());

        doThrow(http412()).when(http).getJson(anyString(), any());
        assertBlocked(api, url, now, T0.plus(Duration.ofMinutes(7)), "约 1 分钟");
        verify(http, times(6)).getJson(anyString(), any());
    }

    @Test
    @DisplayName("冷却逐级翻倍，到 30 分钟封顶")
    void cooldownCapsAtThirtyMinutes() {
        AtomicReference<Instant> now = new AtomicReference<>(T0);
        HttpUtil http = mock(HttpUtil.class);
        when(http.getJson(anyString(), any())).thenThrow(http412());
        BilibiliApiUtil api = clocked(http, now);
        String url = "https://api.live.bilibili.com/room/v1/Room/get_info?room_id=2";

        assertBlocked(api, url, now, T0, "约 1 分钟");
        assertBlocked(api, url, now, T0.plus(Duration.ofMinutes(1)), "约 2 分钟");
        assertBlocked(api, url, now, T0.plus(Duration.ofMinutes(3)), "约 4 分钟");
        assertBlocked(api, url, now, T0.plus(Duration.ofMinutes(7)), "约 8 分钟");
        assertBlocked(api, url, now, T0.plus(Duration.ofMinutes(15)), "约 16 分钟");
        assertBlocked(api, url, now, T0.plus(Duration.ofMinutes(31)), "约 30 分钟");
        verify(http, times(6)).getJson(anyString(), any());

        assertBlocked(api, url, now, T0.plus(Duration.ofMinutes(61)).minusSeconds(1), "被风控拦下");
        verify(http, times(6)).getJson(anyString(), any());
    }

    @Test
    @DisplayName("一个接口被拦，别的接口照常发")
    void oneEndpointCoolingDoesNotBlockAnother() {
        HttpUtil http = mock(HttpUtil.class);
        when(http.getJson(anyString(), any())).thenAnswer(invocation -> {
            String target = invocation.getArgument(0);
            if (target.contains("followings")) {
                throw http412();
            }
            return ok("other");
        });
        BilibiliApiUtil api = api(http, mock(BilibiliRiskMetrics.class));

        assertThrows(RuntimeException.class, () -> api.requestBilibiliApi(URL));
        assertEquals("other", api.requestBilibiliApi("https://api.bilibili.com/x/space/v2/myinfo").getString("marker"));
        assertThrows(RuntimeException.class, () -> api.requestBilibiliApi(URL));

        verify(http, times(2)).getJson(anyString(), any());
    }

    @Test
    @DisplayName("添加主播只查一次，冷却结束后补查到昵称")
    void addingStreamerRetriesOnceAfterCooldown() {
        AtomicReference<Instant> now = new AtomicReference<>(T0);
        AtomicInteger hits = new AtomicInteger();
        HttpUtil http = mock(HttpUtil.class);
        when(http.getJson(anyString(), any())).thenAnswer(invocation -> {
            if (hits.incrementAndGet() == 1) {
                throw http412();
            }
            return master("星见");
        });
        BilibiliApiUtil api = clocked(http, now);
        PushUser user = new PushUser();
        user.setUid(42L);

        new BilibiliDataSourceService(api).completePushUser(user);

        assertNull(user.getUname(), "被拦下的这一次不该假装已经补上");
        assertEquals(1, hits.get());
        now.set(T0.plusSeconds(30));
        assertEquals(0, api.replayDue(now.get()));
        assertEquals(1, hits.get(), "冷却没到不该补发");

        now.set(T0.plusSeconds(60));
        assertEquals(1, api.replayDue(now.get()));
        assertEquals("星见", user.getUname(), "冷却结束后应当补上这一次");
        assertEquals(2, hits.get());
        assertEquals(0, api.replayDue(now.get()));
        assertEquals(2, hits.get(), "补发只此一次");
    }

    @Test
    @DisplayName("登录昵称只问一次，冷却结束后再问到")
    void accountNameRetriesOnceAfterCooldown() {
        AtomicReference<Instant> now = new AtomicReference<>(T0);
        AtomicInteger hits = new AtomicInteger();
        HttpUtil http = mock(HttpUtil.class);
        when(http.getJson(anyString(), any())).thenAnswer(invocation -> {
            if (hits.incrementAndGet() == 1) {
                throw http412();
            }
            return master("登录昵称");
        });
        BilibiliApiUtil api = clocked(http, now);
        BilibiliAccountService account = mock(BilibiliAccountService.class);
        when(account.isLoggedIn()).thenReturn(true);
        when(account.getLoginUid()).thenReturn(7L);
        BilibiliAccountLoginProvider provider = new BilibiliAccountLoginProvider(
                account, mock(TaskScheduler.class), api);

        assertTrue(provider.accountName().isEmpty());
        now.set(T0.plusSeconds(30));
        assertEquals(0, api.replayDue(now.get()));
        assertEquals(1, hits.get());

        now.set(T0.plusSeconds(60));
        assertEquals(1, api.replayDue(now.get()));
        assertEquals("登录昵称", provider.accountName().orElse(""));
        assertEquals(2, hits.get());
    }

    @Test
    @DisplayName("关注这种写操作遇到暂时性码，不当次重发")
    void postTransientCodeIsNotRetried() {
        HttpUtil http = mock(HttpUtil.class);
        when(http.postJsonAsForm(anyString(), any(), any())).thenReturn(code(4101130, "加载错误，请稍后再试"));
        BilibiliApiUtil api = api(http, mock(BilibiliRiskMetrics.class));

        ResponseCodeException error = assertThrows(ResponseCodeException.class, () -> api.requestBilibiliApi(
                "https://api.bilibili.com/x/relation/modify", "POST", Map.of(), Map.of("fid", 1)));

        assertEquals(4101130, error.getCode());
        verify(http, times(1)).postJsonAsForm(anyString(), any(), any());
    }

    @Test
    @DisplayName("风控码 -352 仍然只失败一次，不进入冷却连打")
    void riskCode352FailsOnce() {
        HttpUtil http = mock(HttpUtil.class);
        when(http.getJson(anyString(), any())).thenReturn(code(-352, "风控校验失败"));
        BilibiliApiUtil api = api(http, mock(BilibiliRiskMetrics.class));

        ResponseCodeException error = assertThrows(ResponseCodeException.class, () -> api.requestBilibiliApi(URL));

        assertEquals(-352, error.getCode());
        verify(http, times(1)).getJson(anyString(), any());
        assertThrows(ResponseCodeException.class, () -> api.requestBilibiliApi(URL));
        verify(http, times(2)).getJson(anyString(), any());
    }

    @Test
    @DisplayName("启动时主播资料被拦，冷却后补上房间号要触发重新同步")
    void roomFilledAfterCooldownTriggersResync() {
        AtomicReference<Instant> now = new AtomicReference<>(T0);
        AtomicInteger hits = new AtomicInteger();
        HttpUtil http = mock(HttpUtil.class);
        when(http.getJson(anyString(), any())).thenAnswer(invocation -> {
            if (hits.incrementAndGet() == 1) {
                throw http412();
            }
            return master("星见");
        });
        BilibiliApiUtil api = clocked(http, now);
        PushUser user = new PushUser();
        user.setUid(42L);
        List<NovaDataSourceChangeEvent> events = new ArrayList<>();
        ApplicationEventPublisher publisher = event -> {
            if (event instanceof NovaDataSourceChangeEvent change) {
                events.add(change);
            }
        };

        new BilibiliDataSourceService(api, publisher).completePushUser(user);

        assertNull(user.getRoomId(), "被拦下的这一次不该假装已经有房间号");
        assertEquals(0, events.size(), "被拦下时不该去同步直播间");
        now.set(T0.plusSeconds(60));
        assertEquals(1, api.replayDue(now.get()));
        assertEquals(9L, user.getRoomId(), "冷却结束后应当补上房间号");
        assertEquals(1, events.size(),
                "补上房间号之后没有通知重新同步，这场直播间一直连不上");
        assertEquals(9L, events.get(0).getUser().getRoomId());

        new BilibiliDataSourceService(api, publisher).completePushUser(user);
        assertEquals(1, events.size(), "房间号已经有了，不该再触发一次同步");
    }

    @Test
    @DisplayName("配置主播被拦后，冷却中查了同一个人，到点仍补上配置里的房间号")
    void configReplaySurvivesLookupDuringCooldown() {
        AtomicReference<Instant> now = new AtomicReference<>(T0);
        AtomicInteger hits = new AtomicInteger();
        HttpUtil http = mock(HttpUtil.class);
        when(http.getJson(anyString(), any())).thenAnswer(invocation -> {
            if (hits.incrementAndGet() == 1) {
                throw http412();
            }
            return master("星见");
        });
        BilibiliApiUtil api = clocked(http, now);
        PushUser configured = new PushUser();
        configured.setUid(42L);
        List<NovaDataSourceChangeEvent> events = new ArrayList<>();
        ApplicationEventPublisher publisher = event -> {
            if (event instanceof NovaDataSourceChangeEvent change) {
                events.add(change);
            }
        };
        BilibiliDataSourceService service = new BilibiliDataSourceService(api, publisher);

        service.completePushUser(configured);
        assertNull(configured.getRoomId(), "被拦下的这一次不该假装已经有房间号");
        assertEquals(0, events.size(), "被拦下时不该去同步直播间");

        PushUser lookedUp = new PushUser();
        lookedUp.setUid(42L);
        StreamerWithFans found = service.completeStreamerWithFans(lookedUp);
        assertNull(found.fans(), "查询撞上冷却时粉丝数为空，由人自己再查");
        assertNull(lookedUp.getRoomId(), "查询这一次没补上房间号");

        now.set(T0.plusSeconds(60));
        assertEquals(1, api.replayDue(now.get()));
        assertEquals(9L, configured.getRoomId(),
                "配置里的主播被拦下后，冷却中又查了同一个人，到点这位主播的房间号仍是空的，直播间连不上");
        assertEquals(1, events.size(), "到点补上房间号之后应当通知一次重新同步");
        assertEquals(configured, events.get(0).getUser());
    }

    @Test
    @DisplayName("控制台查询撞上冷却，到点不再发变更")
    void lookupDuringCooldownDoesNotPublishChange() {
        AtomicReference<Instant> now = new AtomicReference<>(T0);
        AtomicInteger hits = new AtomicInteger();
        HttpUtil http = mock(HttpUtil.class);
        when(http.getJson(anyString(), any())).thenAnswer(invocation -> {
            if (hits.incrementAndGet() == 1) {
                throw http412();
            }
            return master("星见");
        });
        BilibiliApiUtil api = clocked(http, now);
        List<NovaDataSourceChangeEvent> events = new ArrayList<>();
        ApplicationEventPublisher publisher = event -> {
            if (event instanceof NovaDataSourceChangeEvent change) {
                events.add(change);
            }
        };
        PushUser lookedUp = new PushUser();
        lookedUp.setUid(42L);

        StreamerWithFans found = new BilibiliDataSourceService(api, publisher).completeStreamerWithFans(lookedUp);

        assertNull(found.fans(), "查询撞上冷却时粉丝数为空");
        assertEquals(0, events.size(), "被拦下的这一次不该发变更");
        now.set(T0.plusSeconds(60));
        api.replayDue(now.get());
        assertEquals(0, events.size(),
                "控制台查主播撞上冷却，到点后仍多了一行「推送配置已变更」");
    }

    @Test
    @DisplayName("冷却中又保存一次推送配置，到点补的是配置里那位的房间号")
    void reloadDuringCooldownFillsUserStillInConfig() throws Exception {
        AtomicReference<Instant> now = new AtomicReference<>(T0);
        AtomicInteger hits = new AtomicInteger();
        HttpUtil http = mock(HttpUtil.class);
        when(http.getJson(anyString(), any())).thenAnswer(invocation -> {
            if (hits.incrementAndGet() == 1) {
                throw http412();
            }
            return master("星见");
        });
        // add 与 update 都把补全排进后台单线程（completePushUsers），测试线程看不见它们跑没跑完。
        // 冷却中被拦的那一趟不进接口桩，桩上数不到它；补发登记（scheduleReplay）是每趟补全
        // 最后一步有形动作，在替身上数它：等满两回（启动一趟、保存配置一趟）才拨时钟，先后就不再赌运气
        CountDownLatch replaysRegistered = new CountDownLatch(2);
        BilibiliApiUtil api = spy(clocked(http, now));
        doAnswer(invocation -> {
            replaysRegistered.countDown();
            return invocation.callRealMethod();
        }).when(api).scheduleReplay(anyString(), anyString(), any());
        List<NovaDataSourceChangeEvent> events = new ArrayList<>();
        ApplicationEventPublisher publisher = event -> {
            if (event instanceof NovaDataSourceChangeEvent change
                    && change.getClass() == NovaDataSourceChangeEvent.class) {
                events.add(change);
            }
        };
        AtomicReference<AbstractDataSource> table = new AtomicReference<>();
        BilibiliDataSourceService service = new BilibiliDataSourceService(api, publisher, table::get);
        DataSourceServiceRegistry registry = new DataSourceServiceRegistry(List.of(service));
        HoldingDataSource source = new HoldingDataSource(publisher, registry);
        table.set(source);

        PushUser configured = bareUser(42L);
        source.add(configured);
        assertNull(configured.getRoomId(), "启动被拦下时不该假装已经有房间号");
        assertSame(configured, source.getUser("bilibili", 42L).orElseThrow());

        PushUser reloaded = bareUser(42L);
        source.update(reloaded);
        assertSame(configured, source.getUser("bilibili", 42L).orElseThrow(),
                "资料还空、推送目标没改，重新加载不该换掉配置里的这位");

        assertTrue(replaysRegistered.await(5, TimeUnit.SECONDS),
                "拨时钟前该等到两趟后台补全都把补发登记好：启动一趟、保存配置一趟");
        now.set(T0.plusSeconds(60));
        assertEquals(1, api.replayDue(now.get()));
        PushUser inConfig = source.getUser("bilibili", 42L).orElseThrow();
        assertEquals(9L, inConfig.getRoomId(),
                "冷却中又保存了一次推送配置，到点配置里这位主播的房间号仍是空的，直播间连不上");
        assertEquals(1, events.size(), "到点应当通知一次重新同步");
        assertSame(inConfig, events.get(0).getUser());
    }

    @Test
    @DisplayName("同一接口三个已经发出的请求一起被拦，第一段冷却仍是 1 分钟")
    void concurrentBlocksStayAtOneMinute() throws Exception {
        AtomicReference<Instant> now = new AtomicReference<>(T0);
        AtomicInteger hits = new AtomicInteger();
        CountDownLatch allIn = new CountDownLatch(3);
        CountDownLatch release = new CountDownLatch(1);
        HttpUtil http = mock(HttpUtil.class);
        when(http.getJson(anyString(), any())).thenAnswer(invocation -> {
            hits.incrementAndGet();
            allIn.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("release timeout");
            }
            throw http412();
        });
        BilibiliApiUtil api = clocked(http, now);
        Thread[] threads = new Thread[3];
        try {
            for (int i = 0; i < threads.length; i++) {
                threads[i] = new Thread(() -> {
                    try {
                        api.requestBilibiliApi(URL);
                    } catch (RuntimeException ignored) {
                        // 被拦是预期
                    }
                });
                threads[i].setDaemon(true);
                threads[i].start();
            }
            assertTrue(allIn.await(5, TimeUnit.SECONDS), "三个已经发出的请求没有都到齐");
            release.countDown();
            for (Thread thread : threads) {
                thread.join(5000);
            }
            RuntimeException later = assertThrows(RuntimeException.class, () -> api.requestBilibiliApi(URL));
            assertTrue(later.getMessage().contains("约 1 分钟"),
                    "三个已经发出的请求一起被拦，第一段冷却被连着抬高: " + later.getMessage());
            assertEquals(3, hits.get(), "冷却期内不该再打出第四次");
        } finally {
            release.countDown();
        }
    }

    @Test
    @DisplayName("冷却到点同时来三个请求，只放一个试探")
    void cooldownExpiryLetsOneProbe() throws Exception {
        AtomicReference<Instant> now = new AtomicReference<>(T0);
        AtomicInteger hits = new AtomicInteger();
        CountDownLatch probeIn = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        HttpUtil http = mock(HttpUtil.class);
        when(http.getJson(anyString(), any())).thenAnswer(invocation -> {
            int n = hits.incrementAndGet();
            if (n == 1) {
                throw http412();
            }
            probeIn.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("release timeout");
            }
            return ok("probe");
        });
        BilibiliApiUtil api = clocked(http, now);
        assertThrows(RuntimeException.class, () -> api.requestBilibiliApi(URL));
        now.set(T0.plusSeconds(60));

        Thread[] threads = new Thread[3];
        try {
            for (int i = 0; i < threads.length; i++) {
                threads[i] = new Thread(() -> {
                    try {
                        api.requestBilibiliApi(URL);
                    } catch (RuntimeException ignored) {
                        // 没抢到试探名额会直接失败
                    }
                });
                threads[i].setDaemon(true);
                threads[i].start();
            }
            assertTrue(probeIn.await(5, TimeUnit.SECONDS), "到点没有试探发出去");
            for (Thread thread : threads) {
                thread.join(1000);
            }
            assertEquals(2, hits.get(),
                    "冷却到点同时来了三个请求，试探还没回来就都打了出去");
        } finally {
            release.countDown();
            for (Thread thread : threads) {
                if (thread != null) {
                    thread.join(2000);
                }
            }
        }
    }

    @Test
    @DisplayName("当次重试中途接口被拦进冷却，剩下的重试不再发")
    void retryStopsWhenCooldownStartsMidway() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        CountDownLatch firstInside = new CountDownLatch(1);
        CountDownLatch cooled = new CountDownLatch(1);
        HttpUtil http = mock(HttpUtil.class);
        when(http.getJson(anyString(), any())).thenAnswer(invocation -> {
            int n = hits.incrementAndGet();
            if (n == 1) {
                firstInside.countDown();
                if (!cooled.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("cooled timeout");
                }
                return code(-503, "服务暂不可用");
            }
            throw http412();
        });
        BilibiliApiUtil api = api(http, mock(BilibiliRiskMetrics.class));
        AtomicReference<RuntimeException> caller = new AtomicReference<>();
        Thread retrying = new Thread(() -> {
            try {
                api.requestBilibiliApi(URL);
            } catch (RuntimeException e) {
                caller.set(e);
            }
        });
        retrying.setDaemon(true);
        try {
            retrying.start();
            assertTrue(firstInside.await(5, TimeUnit.SECONDS), "第一次请求没有发出去");
            RuntimeException blocked = assertThrows(RuntimeException.class, () -> api.requestBilibiliApi(URL));
            assertTrue(blocked.getMessage().contains("被风控拦下"), blocked.getMessage());
            assertEquals(2, hits.get());
            cooled.countDown();
            retrying.join(5000);
            assertEquals(2, hits.get(),
                    "重试中途这个接口被拦进冷却，剩下的重试还是打了出去");
            assertTrue(caller.get() != null, "还在重试的这一次应当停下来");
        } finally {
            cooled.countDown();
            retrying.join(2000);
        }
    }

    @Test
    @DisplayName("同一码前一天出过 100 次，次日第一次仍记一行")
    void businessCodeCountResetsEachDay() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-09-26T02:00:00Z"));
        HttpUtil http = mock(HttpUtil.class);
        BilibiliRiskMetrics metrics = new BilibiliRiskMetrics();
        when(http.getJson(anyString(), any())).thenReturn(code(10086001, "平台原文样例"));
        BilibiliApiUtil api = api(http, metrics);
        api.setClock(now::get);

        for (int i = 0; i < 100; i++) {
            assertThrows(ResponseCodeException.class, () -> api.requestBilibiliApi(URL));
        }
        String firstDay = metrics.lastDetail(BilibiliRiskMetrics.Kind.BUSINESS_CODE).orElse("");
        assertTrue(firstDay.endsWith("count=100"), firstDay);

        now.set(Instant.parse("2026-09-26T16:00:00Z"));
        assertThrows(ResponseCodeException.class, () -> api.requestBilibiliApi(URL));
        String nextDay = metrics.lastDetail(BilibiliRiskMetrics.Kind.BUSINESS_CODE).orElse("");
        assertTrue(nextDay.endsWith("count=1"),
                "同一码前一天出过 100 次，次日第一次不再记一行: " + nextDay);
    }

    @Test
    @DisplayName("登录昵称还在取的时候连刷首页，只发一次")
    void accountNameInFlightSendsOnce() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch extrasDone = new CountDownLatch(2);
        HttpUtil http = mock(HttpUtil.class);
        when(http.getJson(anyString(), any())).thenAnswer(invocation -> {
            hits.incrementAndGet();
            inside.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("release timeout");
            }
            return master("登录昵称");
        });
        BilibiliApiUtil api = api(http, mock(BilibiliRiskMetrics.class));
        BilibiliAccountService account = mock(BilibiliAccountService.class);
        when(account.isLoggedIn()).thenReturn(true);
        when(account.getLoginUid()).thenReturn(7L);
        BilibiliAccountLoginProvider provider = new BilibiliAccountLoginProvider(
                account, mock(TaskScheduler.class), api);

        Thread first = new Thread(() -> {
            try {
                provider.accountName();
            } catch (RuntimeException ignored) {
                // 取昵称失败只显 uid
            }
        });
        first.setDaemon(true);
        Thread[] extras = new Thread[2];
        try {
            first.start();
            assertTrue(inside.await(5, TimeUnit.SECONDS), "第一次没有发出去");
            for (int i = 0; i < extras.length; i++) {
                extras[i] = new Thread(() -> {
                    try {
                        provider.accountName();
                    } finally {
                        extrasDone.countDown();
                    }
                });
                extras[i].setDaemon(true);
                extras[i].start();
            }
            assertTrue(extrasDone.await(2, TimeUnit.SECONDS) || hits.get() > 1);
            assertEquals(1, hits.get(),
                    "登录昵称还在取的时候连刷首页，会把同一问连着打出去");
        } finally {
            release.countDown();
            first.join(2000);
            for (Thread extra : extras) {
                if (extra != null) {
                    extra.join(2000);
                }
            }
        }
    }

    @Test
    @DisplayName("业务码 -412、-509、-799、22015 各自进入冷却")
    void blockedBusinessCodesEnterCooldown() {
        for (int code : new int[] {-412, -509, -799, 22015}) {
            HttpUtil http = mock(HttpUtil.class);
            when(http.getJson(anyString(), any())).thenReturn(code(code, "拦下"));
            BilibiliApiUtil api = api(http, mock(BilibiliRiskMetrics.class));

            RuntimeException first = assertThrows(RuntimeException.class, () -> api.requestBilibiliApi(URL));
            assertTrue(first.getMessage().contains("被风控拦下"),
                    code + " 被拦下却没有进冷却: " + first.getMessage());
            assertThrows(RuntimeException.class, () -> api.requestBilibiliApi(URL));
            verify(http, times(1)).getJson(anyString(), any());
        }
    }

    private BilibiliApiUtil clocked(HttpUtil http, AtomicReference<Instant> now) {
        BilibiliApiUtil api = api(http, mock(BilibiliRiskMetrics.class));
        api.setClock(now::get);
        return api;
    }

    private static void assertBlocked(BilibiliApiUtil api, String url, AtomicReference<Instant> now,
                                      Instant at, String snippet) {
        now.set(at);
        RuntimeException error = assertThrows(RuntimeException.class, () -> api.requestBilibiliApi(url));
        assertTrue(error.getMessage().contains(snippet),
                "时刻 " + at + " 的失败说明应含「" + snippet + "」，实际: " + error.getMessage());
    }

    private static JSONObject code(int value, String message) {
        JSONObject body = new JSONObject();
        body.put("code", value);
        body.put("message", message);
        return body;
    }

    private static JSONObject ok(String marker) {
        JSONObject data = new JSONObject();
        data.put("marker", marker);
        JSONObject body = new JSONObject();
        body.put("code", 0);
        body.put("data", data);
        return body;
    }

    private static PushUser bareUser(long uid) {
        PushUser user = new PushUser();
        user.setUid(uid);
        user.setPlatform("bilibili");
        user.setEnabled(true);
        return user;
    }

    /**
     * 只把主播放进表里。补全仍交给注册上的哔哩哔哩数据源服务
     */
    private static final class HoldingDataSource extends AbstractDataSource {
        HoldingDataSource(ApplicationEventPublisher publisher, DataSourceServiceRegistry registry) {
            super(publisher, registry, null);
        }

        @Override
        public void load() {
        }
    }

    private static JSONObject master(String uname) {
        JSONObject info = new JSONObject();
        info.put("uname", uname);
        info.put("face", "https://example.invalid/face");
        JSONObject data = new JSONObject();
        data.put("info", info);
        data.put("room_id", 9);
        data.put("follower_num", 3);
        JSONObject body = new JSONObject();
        body.put("code", 0);
        body.put("data", data);
        return body;
    }
}
