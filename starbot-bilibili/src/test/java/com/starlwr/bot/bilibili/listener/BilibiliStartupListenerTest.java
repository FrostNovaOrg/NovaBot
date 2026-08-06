package com.starlwr.bot.bilibili.listener;

import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.bilibili.service.BilibiliAccountService;
import com.starlwr.bot.bilibili.service.BilibiliBackupLivePushService;
import com.starlwr.bot.bilibili.service.BilibiliDynamicService;
import com.starlwr.bot.bilibili.service.BilibiliLiveRoomService;
import com.starlwr.bot.bilibili.service.BilibiliStreamerSnapshotService;
import com.starlwr.bot.core.datasource.AbstractDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.TaskScheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        StarBotBilibiliProperties properties = new StarBotBilibiliProperties();
        properties.getAccount().setVerifyInterval(120);

        BilibiliAccountService accountService = mock(BilibiliAccountService.class);
        when(accountService.login()).thenReturn(true);

        TaskScheduler scheduler = inlineScheduler();
        listener(accountService, scheduler, properties).onApplicationReadyEvent();

        verify(scheduler).scheduleAtFixedRate(any(Runnable.class), any(Instant.class), eq(Duration.ofSeconds(120)));
    }

    @Test
    @DisplayName("复检间隔为 0 时不应注册复检任务")
    void shouldNotScheduleWhenDisabled() {
        StarBotBilibiliProperties properties = new StarBotBilibiliProperties();
        properties.getAccount().setVerifyInterval(0);

        BilibiliAccountService accountService = mock(BilibiliAccountService.class);
        when(accountService.login()).thenReturn(true);

        TaskScheduler scheduler = inlineScheduler();
        listener(accountService, scheduler, properties).onApplicationReadyEvent();

        verify(scheduler, never()).scheduleAtFixedRate(any(Runnable.class), any(Instant.class), any(Duration.class));
    }

    @Test
    @DisplayName("登录未完成时不应注册复检任务")
    void shouldNotScheduleWhenLoginFails() {
        StarBotBilibiliProperties properties = new StarBotBilibiliProperties();

        BilibiliAccountService accountService = mock(BilibiliAccountService.class);
        when(accountService.login()).thenReturn(false);

        TaskScheduler scheduler = inlineScheduler();
        listener(accountService, scheduler, properties).onApplicationReadyEvent();

        verify(scheduler, never()).scheduleAtFixedRate(any(Runnable.class), any(Instant.class), any(Duration.class));
    }

    @Test
    @DisplayName("注册的定时任务应真正调用账号服务的例行维护方法")
    void scheduledTaskShouldInvokeMaintain() {
        StarBotBilibiliProperties properties = new StarBotBilibiliProperties();

        BilibiliAccountService accountService = mock(BilibiliAccountService.class);
        when(accountService.login()).thenReturn(true);

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
        BilibiliLiveRoomService liveRoomService = mock(BilibiliLiveRoomService.class);
        AbstractDataSource dataSource = mock(AbstractDataSource.class);

        BilibiliStartupListener listener = listener(accountService, inlineScheduler(), new StarBotBilibiliProperties(), liveRoomService, dataSource);
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

        BilibiliStartupListener listener = listener(accountService, scheduler, new StarBotBilibiliProperties(), liveRoomService, dataSource);
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

        BilibiliStartupListener listener = listener(accountService, inlineScheduler(), new StarBotBilibiliProperties(), liveRoomService, dataSource);
        listener.onDataSourceChangeEvent();

        verify(liveRoomService, never()).sync(any());
    }

    @Test
    @DisplayName("登录未完成时的数据源变更不应触发同步")
    void changeEventAfterFailedLoginShouldBeIgnored() {
        BilibiliAccountService accountService = mock(BilibiliAccountService.class);
        when(accountService.login()).thenReturn(false);
        BilibiliLiveRoomService liveRoomService = mock(BilibiliLiveRoomService.class);
        AbstractDataSource dataSource = mock(AbstractDataSource.class);

        BilibiliStartupListener listener = listener(accountService, inlineScheduler(), new StarBotBilibiliProperties(), liveRoomService, dataSource);
        listener.onApplicationReadyEvent();
        listener.onDataSourceChangeEvent();

        verify(liveRoomService, never()).sync(any());
    }

    /**
     * 构造被测监听器
     */
    private BilibiliStartupListener listener(BilibiliAccountService accountService, TaskScheduler scheduler, StarBotBilibiliProperties properties) {
        return listener(accountService, scheduler, properties, mock(BilibiliLiveRoomService.class), mock(AbstractDataSource.class));
    }

    /**
     * 构造被测监听器（可注入直播间服务与数据源桩，用于验证重同步接线）
     */
    private BilibiliStartupListener listener(BilibiliAccountService accountService, TaskScheduler scheduler, StarBotBilibiliProperties properties,
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
}
