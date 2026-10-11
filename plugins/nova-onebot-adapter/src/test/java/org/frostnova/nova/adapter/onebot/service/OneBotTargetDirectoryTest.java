package org.frostnova.nova.adapter.onebot.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.adapter.onebot.config.OneBotAdapterPluginProperties;
import org.frostnova.nova.adapter.onebot.http.OneBotHttpAdapter;
import org.frostnova.nova.adapter.onebot.model.OneBotSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 推送目标名单的缓存与失败处理
 *
 * <h2>这里守的是三件事</h2>
 * <ol>
 *   <li><b>有效期内不重复问</b>——挑选界面会随输入反复查询，每次都问一遍
 *       意味着敲十个字就是十轮往返</li>
 *   <li><b>刷新真的重问</b>——不重问的刷新按钮比没有按钮更糟：使用者点过之后
 *       会认定看到的就是最新的</li>
 *   <li><b>取不到时保留旧表</b>——清空之后界面显示「一个群都没有」，
 *       与「机器人真的不在任何群里」长得一模一样，而两者该做的事完全相反</li>
 * </ol>
 */
@DisplayName("推送目标名单")
class OneBotTargetDirectoryTest {
    private static final String SENDER = "qq-onebot";

    private OneBotHttpAdapter http;

    private MovableClock clock;

    private OneBotTargetDirectory directory;

    /**
     * 一把手拨的钟：有效期是一段真实时间，不拨钟就只能靠 sleep 去等它过去
     */
    private static final class MovableClock extends Clock {
        private Instant now = Instant.parse("2026-09-04T00:00:00Z");

        void forward(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static OneBotSender sender() {
        OneBotSender sender = new OneBotSender();
        sender.setName(SENDER);
        sender.setOneBotAddress("127.0.0.1");
        sender.setOneBotHttpPort(3000);
        sender.setOneBotHttpToken("token");
        return sender;
    }

    @BeforeEach
    void setUp() {
        http = mock(OneBotHttpAdapter.class);
        clock = new MovableClock();

        OneBotSender sender = sender();
        OneBotAdapterPluginProperties properties = new OneBotAdapterPluginProperties();
        properties.setSenders(List.of(sender));

        OneBotHttpService httpService = mock(OneBotHttpService.class);
        when(httpService.getSender(SENDER)).thenReturn(sender);

        directory = new OneBotTargetDirectory(http, httpService, properties, clock);
    }

    /**
     * 两个群、两个好友，机器人在第一个群里是管理员、第二个群里是普通成员
     */
    private void oneBotReturnsTwoOfEach() {
        when(http.getLoginInfo(any(), any())).thenReturn(JSON.parseObject("{\"user_id\":10000001}"));
        when(http.getGroupList(any(), any())).thenReturn(groupRows());
        when(http.getFriendList(any(), any())).thenReturn(friendRows());
        when(http.getGroupMemberInfo(any(), argThatGroupIs(10000002L)))
                .thenReturn(JSON.parseObject("{\"role\":\"admin\"}"));
        when(http.getGroupMemberInfo(any(), argThatGroupIs(10000003L)))
                .thenReturn(JSON.parseObject("{\"role\":\"member\"}"));
    }

    private static JSONObject argThatGroupIs(long groupId) {
        return org.mockito.ArgumentMatchers.argThat(params ->
                params != null && Long.valueOf(groupId).equals(params.getLong("group_id")));
    }

    private static JSONArray groupRows() {
        return JSON.parseArray("""
                [{"group_id":10000002,"group_name":"开播提醒","member_count":42},
                 {"group_id":10000003,"group_name":"测试群","member_count":7}]
                """);
    }

    private static JSONArray friendRows() {
        return JSON.parseArray("""
                [{"user_id":10000004,"nickname":"甲","remark":"运维"},
                 {"user_id":10000005,"nickname":"乙","remark":""}]
                """);
    }

    private OneBotTargetDirectory.Snapshot only(boolean force) {
        List<OneBotTargetDirectory.Snapshot> snapshots = directory.snapshots(force);
        assertEquals(1, snapshots.size(), "配了一个推送平台就该有一份名单");
        return snapshots.get(0);
    }

    @Test
    @DisplayName("群与好友逐项解析, 管理员按群内角色判")
    void parsesBothTables() {
        oneBotReturnsTwoOfEach();

        OneBotTargetDirectory.Snapshot snapshot = only(false);

        assertEquals(2, snapshot.groups().size());
        assertEquals(10000002L, snapshot.groups().get(0).num());
        assertEquals("开播提醒", snapshot.groups().get(0).name());
        assertEquals(42, snapshot.groups().get(0).memberCount().intValue());
        assertEquals(Boolean.TRUE, snapshot.groups().get(0).admin());
        assertEquals(Boolean.FALSE, snapshot.groups().get(1).admin(), "普通成员不是管理员");

        assertEquals(2, snapshot.friends().size());
        assertEquals(10000004L, snapshot.friends().get(0).num());
        assertEquals("甲", snapshot.friends().get(0).nickname());
        assertEquals("运维", snapshot.friends().get(0).remark());

        assertFalse(snapshot.stale());
        assertEquals(clock.instant(), snapshot.fetchedAt());
    }

    /**
     * 🔴 查不出角色与「不是管理员」是两件事。合成 false 之后，界面会报出一个
     * 从来没查过的结论，而那正是使用者据以决定要不要配 @全体成员 的那一栏。
     */
    @Test
    @DisplayName("⚠️ 角色查不出时管理员一栏留空, 不算作「不是管理员」")
    void unknownRoleIsNotFalse() {
        when(http.getLoginInfo(any(), any())).thenReturn(JSON.parseObject("{\"user_id\":10000001}"));
        when(http.getGroupList(any(), any())).thenReturn(groupRows());
        when(http.getFriendList(any(), any())).thenReturn(new JSONArray());
        when(http.getGroupMemberInfo(any(), any())).thenThrow(new IllegalStateException("接口抖了一下"));

        OneBotTargetDirectory.Snapshot snapshot = only(false);

        assertEquals(2, snapshot.groups().size(), "角色查不出不该把群本身弄丢");
        assertNull(snapshot.groups().get(0).admin());
        assertFalse(snapshot.stale(), "名单本身是取到了的");
    }

    @Test
    @DisplayName("登录账号取不到时名单照出, 管理员一栏留空")
    void listSurvivesMissingSelfId() {
        when(http.getLoginInfo(any(), any())).thenThrow(new IllegalStateException("连不上"));
        when(http.getGroupList(any(), any())).thenReturn(groupRows());
        when(http.getFriendList(any(), any())).thenReturn(friendRows());

        OneBotTargetDirectory.Snapshot snapshot = only(false);

        assertEquals(2, snapshot.groups().size());
        assertNull(snapshot.groups().get(0).admin());
        verify(http, never()).getGroupMemberInfo(any(), any());
    }

    @Test
    @DisplayName("有效期内再查不重新拉取")
    void servesFromCacheWithinTtl() {
        oneBotReturnsTwoOfEach();

        only(false);
        clock.forward(OneBotTargetDirectory.TTL.minusSeconds(1));
        only(false);

        verify(http, times(1)).getGroupList(any(), any());
    }

    @Test
    @DisplayName("过了有效期再查会重新拉取")
    void refetchesAfterTtl() {
        oneBotReturnsTwoOfEach();

        only(false);
        clock.forward(OneBotTargetDirectory.TTL.plusSeconds(1));
        only(false);

        verify(http, times(2)).getGroupList(any(), any());
    }

    @Test
    @DisplayName("刷新在有效期内也强制重新拉取")
    void refreshIgnoresTtl() {
        oneBotReturnsTwoOfEach();

        only(false);
        clock.forward(Duration.ofSeconds(1));
        only(true);

        verify(http, times(2)).getGroupList(any(), any());
    }

    @Test
    @DisplayName("换号不等待在途拉取, 旧号回包不写回缓存")
    void invalidationDuringFetchDoesNotCacheOldAccount() throws Exception {
        CountDownLatch fetchStarted = new CountDownLatch(1);
        CountDownLatch releaseFetch = new CountDownLatch(1);
        when(http.getGroupList(any(), any())).thenAnswer(invocation -> {
            fetchStarted.countDown();
            assertTrue(releaseFetch.await(10, TimeUnit.SECONDS), "旧号拉取应被放行");
            return groupRows();
        }).thenReturn(JSON.parseArray("[{\"group_id\":20000002,\"group_name\":\"新号的群\"}]"));
        when(http.getFriendList(any(), any())).thenReturn(friendRows(),
                JSON.parseArray("[{\"user_id\":20000004,\"nickname\":\"新号的好友\"}]"));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<OneBotTargetDirectory.Snapshot> inFlight = executor.submit(() -> only(false));
            assertTrue(fetchStarted.await(5, TimeUnit.SECONDS), "先等旧号拉取开始");

            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> directory.invalidate(SENDER),
                    "作废缓存不能等待尚未放行的拉取");
            releaseFetch.countDown();

            OneBotTargetDirectory.Snapshot old = inFlight.get(5, TimeUnit.SECONDS);
            assertEquals(10000002L, old.groups().get(0).num(), "原调用方仍能拿到旧号回包");
            assertEquals(10000004L, old.friends().get(0).num());

            OneBotTargetDirectory.Snapshot fresh = only(false);
            verify(http, times(2)).getGroupList(any(), any());
            verify(http, times(2)).getFriendList(any(), any());
            assertEquals(20000002L, fresh.groups().get(0).num(), "换号后首次查询必须现拉新号名单");
            assertEquals(20000004L, fresh.friends().get(0).num());
            assertSame(fresh, only(false), "新号名单照常缓存");
            verify(http, times(2)).getGroupList(any(), any());
            verify(http, times(2)).getFriendList(any(), any());
        } finally {
            releaseFetch.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "拉取线程应已结束");
        }
    }

    /**
     * 🔴 拉取失败时清空名单，界面上会变成「一个群都没有」——那与机器人真的一个群都没进
     * 长得一模一样。旧表留着并标明它是什么时候取的，使用者才判得出眼前这份能不能信。
     */
    @Test
    @DisplayName("⚠️ 拉取失败保留旧表, 标记过期而取回时刻不变")
    void keepsPreviousTableWhenFetchFails() {
        oneBotReturnsTwoOfEach();

        OneBotTargetDirectory.Snapshot good = only(false);
        Instant fetchedAt = good.fetchedAt();

        when(http.getGroupList(any(), any())).thenThrow(new IllegalStateException("连不上 OneBot"));
        clock.forward(OneBotTargetDirectory.TTL.plusSeconds(1));

        OneBotTargetDirectory.Snapshot stale = only(false);

        assertEquals(2, stale.groups().size(), "旧表必须留着");
        assertEquals(2, stale.friends().size());
        assertTrue(stale.stale());
        assertEquals(fetchedAt, stale.fetchedAt(), "取回时刻跟着数据走, 不跟着这次失败的尝试走");
        assertEquals(clock.instant(), stale.attemptedAt(), "尝试时刻要更新, 否则每来一个请求都再撞一次超时");
        assertEquals("连不上 OneBot", stale.message());
    }

    /**
     * 🔴 回包里没有 data 时接口返回 null，而 null 被当成空表记成一份新鲜名单，
     * 上一次取到的群被「一个群都没有」盖掉。空数组才是真的一个群都没有。
     */
    @Test
    @DisplayName("⚠️ 群列表没返回时保留旧表, 标成取不到最新, 原因写明群列表")
    void missingGroupListKeepsPreviousTable() {
        oneBotReturnsTwoOfEach();

        OneBotTargetDirectory.Snapshot good = only(false);

        when(http.getGroupList(any(), any())).thenReturn(null);
        clock.forward(OneBotTargetDirectory.TTL.plusSeconds(1));

        OneBotTargetDirectory.Snapshot stale = only(false);

        assertEquals(2, stale.groups().size(), "上一次的名单必须留着: " + stale);
        assertEquals(2, stale.friends().size());
        assertTrue(stale.stale(), "没拿到数据就该标成取不到最新: " + stale);
        assertTrue(stale.message().contains("群列表"), "原因要写明是哪一张表没返回: " + stale.message());
        assertEquals(good.fetchedAt(), stale.fetchedAt(), "取回时刻跟着数据走");
    }

    @Test
    @DisplayName("⚠️ 好友列表没返回时保留旧表, 原因写明好友列表")
    void missingFriendListKeepsPreviousTable() {
        oneBotReturnsTwoOfEach();

        OneBotTargetDirectory.Snapshot good = only(false);

        when(http.getFriendList(any(), any())).thenReturn(null);
        clock.forward(OneBotTargetDirectory.TTL.plusSeconds(1));

        OneBotTargetDirectory.Snapshot stale = only(false);

        assertEquals(2, stale.groups().size(), "上一次的名单必须留着: " + stale);
        assertTrue(stale.stale(), "没拿到数据就该标成取不到最新: " + stale);
        assertTrue(stale.message().contains("好友列表"), "原因要写明是哪一张表没返回: " + stale.message());
        assertEquals(good.fetchedAt(), stale.fetchedAt(), "取回时刻跟着数据走");
    }

    /**
     * 空数组是真的一个群都没有，与「没返回」是两件事，这是上面的对照。
     */
    @Test
    @DisplayName("阴性对照：群列表回空数组时照旧当空表, 不标取不到")
    void emptyGroupListIsRealEmpty() {
        when(http.getLoginInfo(any(), any())).thenReturn(JSON.parseObject("{\"user_id\":10000001}"));
        when(http.getGroupList(any(), any())).thenReturn(new JSONArray());
        when(http.getFriendList(any(), any())).thenReturn(friendRows());

        OneBotTargetDirectory.Snapshot snapshot = only(false);

        assertTrue(snapshot.groups().isEmpty(), "真的一个群都没有就该是空表: " + snapshot);
        assertEquals(2, snapshot.friends().size(), "好友照出");
        assertFalse(snapshot.stale(), "空数组是取到了的");
        assertNull(snapshot.message(), "一切正常时不该有原因: " + snapshot);
    }

    @Test
    @DisplayName("一次都没取成过时给空表并标记过期")
    void neverFetchedYieldsEmptyStaleTable() {
        when(http.getGroupList(any(), any())).thenThrow(new IllegalStateException("连不上 OneBot"));

        OneBotTargetDirectory.Snapshot snapshot = only(false);

        assertTrue(snapshot.groups().isEmpty());
        assertTrue(snapshot.friends().isEmpty());
        assertTrue(snapshot.stale());
        assertNull(snapshot.fetchedAt(), "从没取到过就没有「取回时刻」可言");
    }

    @Test
    @DisplayName("失败之后有效期内不再反复重试")
    void failureAlsoRespectsTtl() {
        when(http.getGroupList(any(), any())).thenThrow(new IllegalStateException("连不上 OneBot"));

        only(false);
        clock.forward(Duration.ofSeconds(1));
        only(false);

        verify(http, times(1)).getGroupList(any(), any());
    }

    /**
     * 一次拉取要发的请求数＝群数加三。这个数决定了缓存值不值得——
     * 写下来是为了下次有人想去掉缓存时，先看见它。
     */
    @Test
    @DisplayName("一次拉取的接口调用数＝群数＋3")
    void callCountIsGroupsPlusThree() {
        oneBotReturnsTwoOfEach();

        only(false);

        verify(http, times(1)).getGroupList(any(), any());
        verify(http, times(1)).getFriendList(any(), any());
        verify(http, times(1)).getLoginInfo(any(), any());
        verify(http, times(2)).getGroupMemberInfo(any(), any());
    }

    @Test
    @DisplayName("没有群号的条目丢掉, 其余照出")
    void skipsRowsWithoutNumber() {
        when(http.getLoginInfo(any(), any())).thenReturn(JSON.parseObject("{\"user_id\":10000001}"));
        when(http.getGroupList(any(), any())).thenReturn(JSON.parseArray(
                "[{\"group_name\":\"没有群号\"},{\"group_id\":10000002,\"group_name\":\"开播提醒\"}]"));
        when(http.getFriendList(any(), any())).thenReturn(JSON.parseArray(
                "[{\"nickname\":\"没有账号\"},{\"user_id\":10000004,\"nickname\":\"甲\"}]"));
        when(http.getGroupMemberInfo(any(), any())).thenReturn(JSON.parseObject("{\"role\":\"member\"}"));

        OneBotTargetDirectory.Snapshot snapshot = only(false);

        assertEquals(1, snapshot.groups().size());
        assertEquals(10000002L, snapshot.groups().get(0).num());
        assertEquals(1, snapshot.friends().size());
        assertEquals(10000004L, snapshot.friends().get(0).num());
    }

    @Test
    @DisplayName("没注册的推送平台不出现在名单里")
    void unregisteredSenderIsSkipped() {
        OneBotSender configured = sender();
        OneBotAdapterPluginProperties properties = new OneBotAdapterPluginProperties();
        properties.setSenders(List.of(configured));

        OneBotHttpService httpService = mock(OneBotHttpService.class);
        when(httpService.getSender(eq(SENDER))).thenReturn(null);

        OneBotTargetDirectory unregistered = new OneBotTargetDirectory(http, httpService, properties, clock);

        assertTrue(unregistered.snapshots(false).isEmpty());
        verify(http, never()).getGroupList(any(), any());
    }
}
