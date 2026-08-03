package com.starlwr.bot.bilibili.listener;

import com.starlwr.bot.bilibili.service.BilibiliAccountService;
import com.starlwr.bot.bilibili.service.BilibiliBackupLivePushService;
import com.starlwr.bot.bilibili.service.BilibiliDynamicService;
import com.starlwr.bot.bilibili.service.BilibiliLiveRoomService;
import com.starlwr.bot.core.datasource.AbstractDataSource;
import com.starlwr.bot.core.plugin.StarBotComponent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.TaskScheduler;

import java.time.Instant;

/**
 * StarBotBilibili 启动监听器
 * <p>
 * 在主程序启动完毕后依次完成登录与各项服务的启动。登录可能需要等待用户扫码，
 * 因此整个流程放在调度线程上执行，不阻塞主程序启动。
 */
@Slf4j
@StarBotComponent
public class BilibiliStartupListener {
    private final BilibiliAccountService accountService;

    private final BilibiliLiveRoomService liveRoomService;

    private final BilibiliBackupLivePushService backupLivePushService;

    private final BilibiliDynamicService dynamicService;

    private final AbstractDataSource dataSource;

    private final TaskScheduler scheduler;

    @Autowired
    public BilibiliStartupListener(BilibiliAccountService accountService,
                                   BilibiliLiveRoomService liveRoomService,
                                   BilibiliBackupLivePushService backupLivePushService,
                                   BilibiliDynamicService dynamicService,
                                   AbstractDataSource dataSource,
                                   @Qualifier("bilibiliTaskScheduler") TaskScheduler scheduler) {
        this.accountService = accountService;
        this.liveRoomService = liveRoomService;
        this.backupLivePushService = backupLivePushService;
        this.dynamicService = dynamicService;
        this.dataSource = dataSource;
        this.scheduler = scheduler;
    }

    /**
     * 启动哔哩哔哩相关服务
     */
    @Order(1000)
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReadyEvent() {
        scheduler.schedule(this::start, Instant.now());
    }

    /**
     * 依次执行登录与服务启动
     */
    private void start() {
        try {
            accountService.login();
        } catch (Exception e) {
            log.error("哔哩哔哩登录失败, 相关功能将不可用", e);
            return;
        }

        try {
            liveRoomService.sync(dataSource);
        } catch (Exception e) {
            log.error("同步直播间连接失败", e);
        }

        try {
            backupLivePushService.start(dataSource);
        } catch (Exception e) {
            log.error("启动备用直播推送失败", e);
        }

        try {
            dynamicService.start(dataSource);
        } catch (Exception e) {
            log.error("启动动态推送失败", e);
        }

        log.info("StarBotBilibili 已就绪");
    }
}
