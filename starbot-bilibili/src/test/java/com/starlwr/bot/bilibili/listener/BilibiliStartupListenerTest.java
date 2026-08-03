package com.starlwr.bot.bilibili.listener;

import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.bilibili.service.BilibiliAccountService;
import com.starlwr.bot.bilibili.service.BilibiliBackupLivePushService;
import com.starlwr.bot.bilibili.service.BilibiliDynamicService;
import com.starlwr.bot.bilibili.service.BilibiliLiveRoomService;
import com.starlwr.bot.core.datasource.AbstractDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.TaskScheduler;

import java.time.Duration;
import java.time.Instant;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 哔哩哔哩启动监听器测试
 * <p>
 * 覆盖登录态复检的调度接线。该路径只在登录成功后才会执行，真机上需要一个可用的哔哩哔哩账号
 * 才能走到，因此以桩替身验证：既确认周期任务确实被注册，也确认关闭开关时不会注册。
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

    /**
     * 构造被测监听器
     */
    private BilibiliStartupListener listener(BilibiliAccountService accountService, TaskScheduler scheduler, StarBotBilibiliProperties properties) {
        return new BilibiliStartupListener(
                accountService,
                mock(BilibiliLiveRoomService.class),
                mock(BilibiliBackupLivePushService.class),
                mock(BilibiliDynamicService.class),
                mock(AbstractDataSource.class),
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
