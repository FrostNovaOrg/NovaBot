package org.frostnova.nova.bilibili.listener;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.exception.NetworkException;
import org.frostnova.nova.bilibili.exception.ResponseCodeException;
import org.frostnova.nova.bilibili.health.BilibiliRiskMetrics;
import org.frostnova.nova.bilibili.model.Cookies;
import org.frostnova.nova.bilibili.service.BilibiliAccountService;
import org.frostnova.nova.bilibili.service.BilibiliBackupLivePushService;
import org.frostnova.nova.bilibili.service.BilibiliCredentialStore;
import org.frostnova.nova.bilibili.service.BilibiliDynamicService;
import org.frostnova.nova.bilibili.service.BilibiliLiveRoomService;
import org.frostnova.nova.bilibili.service.BilibiliStreamerSnapshotService;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.util.HttpUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.TaskScheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 哔哩哔哩启动监听器测试
 * <p>
 * 覆盖登录态复检与数据源变更重同步的调度接线。这些路径只在登录成功后才会执行，
 * 真机上需要一个可用的哔哩哔哩账号才能走到，因此以桩替身验证。
 */
@DisplayName("哔哩哔哩启动监听器")
class BilibiliStartupListenerTest {
    @Test
    @DisplayName("登录成功后应按配置的间隔注册登录态复检")
    void shouldScheduleLoginVerification() {
        NovaBilibiliProperties properties = new NovaBilibiliProperties();
        properties.getAccount().setVerifyInterval(120);

        BilibiliAccountService accountService = mock(BilibiliAccountService.class);
        when(accountService.login()).thenReturn(true);
        // 桩出 uid 已知的正常登录：login 成了而 uid 不说明的桩会被当成「凭据暂未确认」，误排补问
        when(accountService.getLoginUid()).thenReturn(90001L);

        TaskScheduler scheduler = inlineScheduler();
        listener(accountService, scheduler, properties).onApplicationReadyEvent();

        verify(scheduler).scheduleAtFixedRate(any(Runnable.class), any(Instant.class), eq(Duration.ofSeconds(120)));
    }

    @Test
    @DisplayName("复检间隔为 0 时不应注册复检任务")
    void shouldNotScheduleWhenDisabled() {
        NovaBilibiliProperties properties = new NovaBilibiliProperties();
        properties.getAccount().setVerifyInterval(0);

        BilibiliAccountService accountService = mock(BilibiliAccountService.class);
        when(accountService.login()).thenReturn(true);
        when(accountService.getLoginUid()).thenReturn(90001L);

        TaskScheduler scheduler = inlineScheduler();
        listener(accountService, scheduler, properties).onApplicationReadyEvent();

        verify(scheduler, never()).scheduleAtFixedRate(any(Runnable.class), any(Instant.class), any(Duration.class));
    }

    @Test
    @DisplayName("登录未完成时不应注册复检任务")
    void shouldNotScheduleWhenLoginFails() {
        NovaBilibiliProperties properties = new NovaBilibiliProperties();

        BilibiliAccountService accountService = mock(BilibiliAccountService.class);
        when(accountService.login()).thenReturn(false);

        TaskScheduler scheduler = inlineScheduler();
        listener(accountService, scheduler, properties).onApplicationReadyEvent();

        verify(scheduler, never()).scheduleAtFixedRate(any(Runnable.class), any(Instant.class), any(Duration.class));
    }

    @Test
    @DisplayName("注册的定时任务应真正调用账号服务的例行维护方法")
    void scheduledTaskShouldInvokeMaintain() {
        NovaBilibiliProperties properties = new NovaBilibiliProperties();

        BilibiliAccountService accountService = mock(BilibiliAccountService.class);
        when(accountService.login()).thenReturn(true);
        when(accountService.getLoginUid()).thenReturn(90001L);

        TaskScheduler scheduler = inlineScheduler();
        listener(accountService, scheduler, properties).onApplicationReadyEvent();

        // 取出注册进去的任务并执行，确认它接的是例行维护（复检 + 按需续期）而不是别的方法
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).scheduleAtFixedRate(task.capture(), any(Instant.class), any(Duration.class));
        task.getValue().run();

        verify(accountService).maintain();
    }

    @Test
    @DisplayName("启动完成后的数据源变更应重新同步直播间连接")
    void changeEventAfterStartupShouldResync() {
        BilibiliAccountService accountService = mock(BilibiliAccountService.class);
        when(accountService.login()).thenReturn(true);
        when(accountService.getLoginUid()).thenReturn(90001L);
        BilibiliLiveRoomService liveRoomService = mock(BilibiliLiveRoomService.class);
        AbstractDataSource dataSource = mock(AbstractDataSource.class);

        BilibiliStartupListener listener = listener(accountService, inlineScheduler(), new NovaBilibiliProperties(), liveRoomService, dataSource);
        listener.onApplicationReadyEvent();
        listener.onDataSourceChangeEvent();

        // 启动同步一次 + 变更重同步一次
        verify(liveRoomService, times(2)).sync(dataSource);
    }

    @Test
    @DisplayName("连发的数据源变更应合并为一次重同步")
    void burstOfChangeEventsShouldCoalesce() {
        BilibiliAccountService accountService = mock(BilibiliAccountService.class);
        when(accountService.login()).thenReturn(true);
        when(accountService.getLoginUid()).thenReturn(90001L);
        BilibiliLiveRoomService liveRoomService = mock(BilibiliLiveRoomService.class);
        AbstractDataSource dataSource = mock(AbstractDataSource.class);

        // 启动阶段就地执行；启动完成后转为收集模式，模拟尚未到期的延迟任务
        AtomicBoolean inline = new AtomicBoolean(true);
        List<Runnable> deferred = new ArrayList<>();
        TaskScheduler scheduler = mock(TaskScheduler.class);
        when(scheduler.schedule(any(Runnable.class), any(Instant.class))).thenAnswer(invocation -> {
            Runnable task = invocation.getArgument(0, Runnable.class);
            if (inline.get()) {
                task.run();
            } else {
                deferred.add(task);
            }
            return null;
        });

        BilibiliStartupListener listener = listener(accountService, scheduler, new NovaBilibiliProperties(), liveRoomService, dataSource);
        listener.onApplicationReadyEvent();
        inline.set(false);

        listener.onDataSourceChangeEvent();
        listener.onDataSourceChangeEvent();
        listener.onDataSourceChangeEvent();

        assertEquals(1, deferred.size(), "合并窗口内的连发事件只应挂起一个同步任务");

        deferred.get(0).run();
        verify(liveRoomService, times(2)).sync(dataSource);

        // 挂起任务执行完毕后，新的变更应能再次触发同步
        listener.onDataSourceChangeEvent();
        assertEquals(2, deferred.size(), "上一轮同步完成后应能再次挂起新任务");
    }

    @Test
    @DisplayName("启动完成前的数据源变更不应触发同步")
    void changeEventBeforeStartupShouldBeIgnored() {
        BilibiliAccountService accountService = mock(BilibiliAccountService.class);
        BilibiliLiveRoomService liveRoomService = mock(BilibiliLiveRoomService.class);
        AbstractDataSource dataSource = mock(AbstractDataSource.class);

        BilibiliStartupListener listener = listener(accountService, inlineScheduler(), new NovaBilibiliProperties(), liveRoomService, dataSource);
        listener.onDataSourceChangeEvent();

        verify(liveRoomService, never()).sync(any());
    }

    @Test
    @DisplayName("平常启动: 启动载入的新增事件不该再补一轮同步")
    void loadTimeAddEventsShouldNotTriggerExtraSync() {
        BilibiliAccountService accountService = mock(BilibiliAccountService.class);
        when(accountService.login()).thenReturn(true);
        BilibiliLiveRoomService liveRoomService = mock(BilibiliLiveRoomService.class);
        AbstractDataSource dataSource = mock(AbstractDataSource.class);

        BilibiliStartupListener listener = listener(accountService, inlineScheduler(), new NovaBilibiliProperties(), liveRoomService, dataSource);

        // 启动载入时每位主播各发一个新增事件(AbstractDataSource.load 的做法), 首次同步本来看得到
        listener.onDataSourceChangeEvent();
        listener.onDataSourceChangeEvent();
        listener.onDataSourceChangeEvent();

        // 启动完成
        listener.onApplicationReadyEvent();

        // 只有启动同步那一次。以前次次记下「有过变更」, 每次启动都多补一轮同步、多打一行「推送配置已变更」,
        // 还没放行的房间被重复排进建连闸门
        verify(liveRoomService, times(1)).sync(dataSource);
    }

    @Test
    @DisplayName("首次同步读过名单之后到的变更不丢, 启动完成后补一次同步")
    void changeAfterFirstSyncStillSyncesAfter() {
        BilibiliAccountService accountService = mock(BilibiliAccountService.class);
        when(accountService.login()).thenReturn(true);
        BilibiliLiveRoomService liveRoomService = mock(BilibiliLiveRoomService.class);
        AbstractDataSource dataSource = mock(AbstractDataSource.class);

        // 房间号在启动窗口里晚到(后台补全拿到的), 落在首次同步读过名单之后
        AtomicBoolean eventFired = new AtomicBoolean(false);
        AtomicReference<BilibiliStartupListener> listenerRef = new AtomicReference<>();
        doAnswer(invocation -> {
            if (eventFired.compareAndSet(false, true)) {
                listenerRef.get().onDataSourceChangeEvent();
            }
            return null;
        }).when(liveRoomService).sync(any());

        BilibiliStartupListener listener = listener(accountService, inlineScheduler(), new NovaBilibiliProperties(), liveRoomService, dataSource);
        listenerRef.set(listener);
        // 启动完成(内含首次同步)
        listener.onApplicationReadyEvent();

        // 启动同步一次 + 补一次同步, 否则那位主播一直连不上
        verify(liveRoomService, times(2)).sync(dataSource);
    }

    @Test
    @DisplayName("⚠️ 登录失败也要照常连直播间：直播采集不依赖登录态，不该被连坐")
    void failedLoginShouldNotBlockLiveChannel() {
        BilibiliAccountService accountService = mock(BilibiliAccountService.class);
        when(accountService.login()).thenThrow(new IllegalStateException("网络不通"));
        BilibiliLiveRoomService liveRoomService = mock(BilibiliLiveRoomService.class);
        BilibiliBackupLivePushService backupService = mock(BilibiliBackupLivePushService.class);
        BilibiliStreamerSnapshotService snapshotService = mock(BilibiliStreamerSnapshotService.class);
        BilibiliDynamicService dynamicService = mock(BilibiliDynamicService.class);
        AbstractDataSource dataSource = mock(AbstractDataSource.class);

        new BilibiliStartupListener(accountService, liveRoomService, backupService, dynamicService,
                snapshotService, dataSource, inlineScheduler(), new NovaBilibiliProperties())
                .onApplicationReadyEvent();

        verify(liveRoomService).sync(dataSource);
        verify(backupService).start(dataSource);
        verify(snapshotService).start(dataSource);

        // 动态推送确实要登录态，没有就别启动: 未登录时接口返回的是空列表而不是错误，
        // 启动了也只会安安静静一条不推，看着像坏了
        verify(dynamicService, never()).start(any());
    }

    @Test
    @DisplayName("登录失败后的数据源变更仍应触发重同步")
    void changeEventAfterFailedLoginShouldStillResync() {
        BilibiliAccountService accountService = mock(BilibiliAccountService.class);
        when(accountService.login()).thenReturn(false);
        BilibiliLiveRoomService liveRoomService = mock(BilibiliLiveRoomService.class);
        AbstractDataSource dataSource = mock(AbstractDataSource.class);

        BilibiliStartupListener listener = listener(accountService, inlineScheduler(), new NovaBilibiliProperties(), liveRoomService, dataSource);
        listener.onApplicationReadyEvent();
        listener.onDataSourceChangeEvent();

        verify(liveRoomService, times(2)).sync(dataSource);
    }

    @Test
    @DisplayName("匿名模式下应照常采集直播，但不启动动态推送与登录态复检")
    void anonymousModeShouldCollectLiveOnly() {
        NovaBilibiliProperties properties = new NovaBilibiliProperties();
        properties.getAccount().setAnonymous(true);

        BilibiliAccountService accountService = mock(BilibiliAccountService.class);
        when(accountService.login()).thenReturn(false);
        when(accountService.isAnonymous()).thenReturn(true);

        BilibiliLiveRoomService liveRoomService = mock(BilibiliLiveRoomService.class);
        BilibiliDynamicService dynamicService = mock(BilibiliDynamicService.class);
        AbstractDataSource dataSource = mock(AbstractDataSource.class);
        TaskScheduler scheduler = inlineScheduler();

        new BilibiliStartupListener(accountService, liveRoomService, mock(BilibiliBackupLivePushService.class),
                dynamicService, mock(BilibiliStreamerSnapshotService.class), dataSource, scheduler, properties)
                .onApplicationReadyEvent();

        verify(liveRoomService).sync(dataSource);
        verify(dynamicService, never()).start(any());

        // 没有凭据可复检，也没有可续期的东西，注册了只会每十分钟白打一次接口
        verify(scheduler, never()).scheduleAtFixedRate(any(Runnable.class), any(Instant.class), any(Duration.class));
    }

    // ============ 凭据暂未确认时的补问 ============
    // 启动验证没拿到答复（网络不通等）会保留凭据按已登录启动，uid 留空。这里钉住：
    // 一分钟后先问一次，没答复逐次加倍再等，封顶为复检间隔；复检关闭时也要问；
    // 拿到 uid 走复检同一条路补上，问出明确未登录走既有失效告警，有明确答复就停。

    @Test
    @DisplayName("凭据暂未确认且复检关闭：一分钟后补问，没答复加倍再等，拿到 uid 当场补上并停")
    void unverifiedCredentialsReaskEvenWhenRecheckDisabled() {
        NovaBilibiliProperties properties = new NovaBilibiliProperties();
        properties.getAccount().setVerifyInterval(0);

        // 真账号服务＋只在查账号这一路上失败的假接口：补问要落到真实补 uid 的那条路上
        BilibiliRiskMetrics metrics = new BilibiliRiskMetrics();
        UnstableMyInfoApi api = new UnstableMyInfoApi(metrics);
        api.failure = new NetworkException("连接超时");

        BilibiliCredentialStore store = mock(BilibiliCredentialStore.class);
        when(store.load()).thenReturn(Optional.of(new Cookies("sess", "jct", "buvid")));
        BilibiliAccountService accountService = new BilibiliAccountService(api, store, properties);

        ReaskRecorder reasks = new ReaskRecorder();
        listener(accountService, reasks.scheduler, properties).onApplicationReadyEvent();

        assertTrue(accountService.isLoggedIn(), "前提: 网络故障应保留凭据按已登录启动");
        assertNull(accountService.getLoginUid(), "前提: uid 尚未确认");

        assertEquals(1, reasks.tasks.size(), "复检关闭时也要登记补问, 否则 uid 永远补不上");
        long firstDelay = Duration.between(Instant.now(), reasks.at.get(0)).toSeconds();
        assertTrue(firstDelay >= 55 && firstDelay <= 60,
                "第一次补问应在一分钟左右, 实际 " + firstDelay + " 秒后");

        // 到点补问，仍未得到答复：加倍再等
        reasks.tasks.get(0).run();
        assertEquals(2, reasks.tasks.size(), "没答复应再排一次");
        long secondDelay = Duration.between(Instant.now(), reasks.at.get(1)).toSeconds();
        assertTrue(secondDelay >= 110 && secondDelay <= 120,
                "没答复应加倍再等, 实际 " + secondDelay + " 秒后");

        // 网络恢复，这次补问拿到账号身份：uid 当场补上，不再排
        api.failure = null;
        api.uid = 90001L;
        reasks.tasks.get(1).run();
        assertEquals(90001L, accountService.getLoginUid(), "补问拿到 uid 应走复检同一条路补上");
        assertEquals(2, reasks.tasks.size(), "拿到明确答复就停, 不再排补问");
    }

    @Test
    @DisplayName("补问问出「明确未登录」：置回未登录并停，失效告警走复检那条路")
    void reaskStopsWhenServerClearlySaysLoggedOut() {
        NovaBilibiliProperties properties = new NovaBilibiliProperties();
        properties.getAccount().setVerifyInterval(0);

        BilibiliRiskMetrics metrics = new BilibiliRiskMetrics();
        UnstableMyInfoApi api = new UnstableMyInfoApi(metrics);
        api.failure = new NetworkException("连接超时");

        BilibiliCredentialStore store = mock(BilibiliCredentialStore.class);
        when(store.load()).thenReturn(Optional.of(new Cookies("sess", "jct", "buvid")));
        BilibiliAccountService accountService = new BilibiliAccountService(api, store, properties);

        ReaskRecorder reasks = new ReaskRecorder();
        listener(accountService, reasks.scheduler, properties).onApplicationReadyEvent();
        assertEquals(1, reasks.tasks.size());

        // 这次补问得到服务端明确答复：凭据确已失效，置回未登录（既有失效告警那条路），不再排
        api.failure = new ResponseCodeException(BilibiliApiUtil.CODE_NOT_LOGGED_IN, "账号未登录");
        reasks.tasks.get(0).run();

        assertFalse(accountService.isLoggedIn(), "明确未登录应置回未登录");
        assertEquals(1, reasks.tasks.size(), "有明确答复就停");
    }

    @Test
    @DisplayName("补问没答复逐次加倍，封顶为复检间隔")
    void reaskBackoffCappedAtVerifyInterval() {
        NovaBilibiliProperties properties = new NovaBilibiliProperties();
        properties.getAccount().setVerifyInterval(90);

        BilibiliAccountService accountService = mock(BilibiliAccountService.class);
        when(accountService.login()).thenReturn(true);
        when(accountService.isLoggedIn()).thenReturn(true);
        // 一直问不到账号身份
        when(accountService.getLoginUid()).thenReturn(null);

        ReaskRecorder reasks = new ReaskRecorder();
        listener(accountService, reasks.scheduler, properties).onApplicationReadyEvent();

        assertEquals(1, reasks.tasks.size());
        reasks.tasks.get(0).run();

        // 加倍本该等到 120 秒，但封顶为复检间隔 90 秒
        assertEquals(2, reasks.tasks.size(), "没答复应再排一次");
        long delay = Duration.between(Instant.now(), reasks.at.get(1)).toSeconds();
        assertTrue(delay >= 80 && delay <= 90, "等待应封顶在复检间隔, 实际 " + delay + " 秒后");
        verify(accountService).verify();
    }

    @Test
    @DisplayName("uid 已知时不排补问")
    void noReaskWhenUidAlreadyKnown() {
        BilibiliAccountService accountService = mock(BilibiliAccountService.class);
        when(accountService.login()).thenReturn(true);
        when(accountService.getLoginUid()).thenReturn(90001L);

        // 全部就地执行：若误排了补问，它会当场跑起来，调度笔数会超过一笔
        AtomicInteger dispatched = new AtomicInteger();
        TaskScheduler scheduler = mock(TaskScheduler.class);
        when(scheduler.schedule(any(Runnable.class), any(Instant.class))).thenAnswer(invocation -> {
            dispatched.incrementAndGet();
            invocation.getArgument(0, Runnable.class).run();
            return null;
        });

        listener(accountService, scheduler, new NovaBilibiliProperties()).onApplicationReadyEvent();

        assertEquals(1, dispatched.get(), "正常登录（uid 已知）不该再排补问");
    }

    /**
     * 构造被测监听器
     */
    private BilibiliStartupListener listener(BilibiliAccountService accountService, TaskScheduler scheduler, NovaBilibiliProperties properties) {
        return listener(accountService, scheduler, properties, mock(BilibiliLiveRoomService.class), mock(AbstractDataSource.class));
    }

    /**
     * 构造被测监听器（可注入直播间服务与数据源桩，用于验证重同步接线）
     */
    private BilibiliStartupListener listener(BilibiliAccountService accountService, TaskScheduler scheduler, NovaBilibiliProperties properties,
                                             BilibiliLiveRoomService liveRoomService, AbstractDataSource dataSource) {
        return new BilibiliStartupListener(
                accountService,
                liveRoomService,
                mock(BilibiliBackupLivePushService.class),
                mock(BilibiliDynamicService.class),
                mock(BilibiliStreamerSnapshotService.class),
                dataSource,
                scheduler,
                properties
        );
    }

    /**
     * 构造一个同步执行任务的调度器桩
     * <p>
     * 启动流程被投递到调度器上执行，若不让它就地跑完，测试就观察不到后续的注册动作。
     * @return 调度器桩
     */
    private TaskScheduler inlineScheduler() {
        TaskScheduler scheduler = mock(TaskScheduler.class);
        when(scheduler.schedule(any(Runnable.class), any(Instant.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        });
        return scheduler;
    }

    /**
     * 补问观测台：第一笔调度就地执行（那是启动流程本身，跑完才能观察到后续登记），
     * 之后的每一笔连同到点时刻收起来，由测试手动到点执行
     */
    private static final class ReaskRecorder {
        final List<Runnable> tasks = new ArrayList<>();

        final List<Instant> at = new ArrayList<>();

        final TaskScheduler scheduler = mock(TaskScheduler.class);

        ReaskRecorder() {
            AtomicInteger dispatched = new AtomicInteger();
            when(scheduler.schedule(any(Runnable.class), any(Instant.class))).thenAnswer(invocation -> {
                if (dispatched.incrementAndGet() == 1) {
                    invocation.getArgument(0, Runnable.class).run();
                } else {
                    tasks.add(invocation.getArgument(0, Runnable.class));
                    at.add(invocation.getArgument(1, Instant.class));
                }
                return null;
            });
        }
    }

    /**
     * 只应答「查登录账号」一路的假接口：应答可切换，先模拟网络故障，再模拟拿到账号身份。
     * 其余网络行为不模拟，登录态的解析与记账走真实路径
     */
    private static final class UnstableMyInfoApi extends BilibiliApiUtil {
        private RuntimeException failure;

        private Long uid;

        UnstableMyInfoApi(BilibiliRiskMetrics riskMetrics) {
            super(mock(HttpUtil.class), new NovaBilibiliProperties(), riskMetrics);
        }

        @Override
        public JSONObject requestBilibiliApi(String url, String method, Map<String, String> headers,
                                             Map<String, Object> params) {
            if (failure != null) {
                throw failure;
            }
            if (uid == null) {
                // 接口通了但没给出账号身份
                return new JSONObject();
            }
            return JSONObject.of("profile", JSONObject.of("mid", uid));
        }
    }
}
