package com.starlwr.bot.bilibili.listener;

import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
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

import java.time.Duration;
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

    private final StarBotBilibiliProperties properties;

    @Autowired
    public BilibiliStartupListener(BilibiliAccountService accountService,
                                   BilibiliLiveRoomService liveRoomService,
                                   BilibiliBackupLivePushService backupLivePushService,
                                   BilibiliDynamicService dynamicService,
                                   AbstractDataSource dataSource,
                                   @Qualifier("bilibiliTaskScheduler") TaskScheduler scheduler,
                                   StarBotBilibiliProperties properties) {
        this.accountService = accountService;
        this.liveRoomService = liveRoomService;
        this.backupLivePushService = backupLivePushService;
        this.dynamicService = dynamicService;
        this.dataSource = dataSource;
        this.scheduler = scheduler;
        this.properties = properties;
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
            if (!accountService.login()) {
                // 登录流程被停机信号中止属于正常关闭，不是故障，不应以 ERROR 惊扰使用者
                if (accountService.isStopping()) {
                    log.info("正在停机, 已中止哔哩哔哩登录流程");
                } else {
                    log.warn("哔哩哔哩登录未完成, 动态推送将不可用");
                }
                return;
            }
        } catch (Exception e) {
            log.error("哔哩哔哩登录失败, 相关功能将不可用", e);
            return;
        }

        // 登录期间可能已开始停机，此时不必再启动后续服务
        if (accountService.isStopping()) {
            log.info("正在停机, 已跳过哔哩哔哩服务启动");
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

        startLoginStateVerification();

        log.info("StarBotBilibili 已就绪");
    }

    /**
     * 启动登录态定期复检
     * <p>
     * 凭据失效后动态推送会静默停摆，定期复检的意义在于把这种静默失败转为显式告警，
     * 并在配置界面的运行状态中体现出来。同一周期内还会顺带按需续期凭据，
     * 从源头上减少「某天突然掉登录」的发生。
     */
    private void startLoginStateVerification() {
        int interval = properties.getAccount().getVerifyInterval();
        if (interval <= 0) {
            log.info("登录态复检已关闭, 凭据失效后将不会有任何提示, 凭据自动续期也不会执行");
            return;
        }

        Duration period = Duration.ofSeconds(interval);
        scheduler.scheduleAtFixedRate(accountService::maintain, Instant.now().plus(period), period);

        log.info("登录态复检已启动, 每 {} 秒检查一次; 凭据自动续期{}",
                interval, properties.getAccount().isAutoRefreshCookie() ? "已启用" : "已关闭");
    }
}
