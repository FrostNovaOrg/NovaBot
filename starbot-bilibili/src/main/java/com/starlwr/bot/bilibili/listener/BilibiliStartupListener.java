package com.starlwr.bot.bilibili.listener;

import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.bilibili.service.BilibiliAccountService;
import com.starlwr.bot.bilibili.service.BilibiliBackupLivePushService;
import com.starlwr.bot.bilibili.service.BilibiliDynamicService;
import com.starlwr.bot.bilibili.service.BilibiliLiveRoomService;
import com.starlwr.bot.bilibili.service.BilibiliStreamerSnapshotService;
import com.starlwr.bot.core.datasource.AbstractDataSource;
import com.starlwr.bot.core.event.datasource.base.StarBotDataSourceChangeEvent;
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
import java.util.concurrent.atomic.AtomicBoolean;

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

    private final BilibiliStreamerSnapshotService snapshotService;

    private final AbstractDataSource dataSource;

    private final TaskScheduler scheduler;

    private final StarBotBilibiliProperties properties;

    /**
     * 各项服务是否已完成启动。启动完成前收到的数据源变更事件来自初始化加载本身，无需响应
     */
    private final AtomicBoolean servicesStarted = new AtomicBoolean(false);

    /**
     * 是否已有待执行的重新同步任务。一次热重载会对每个用户各发一个变更事件，
     * 以此把短时间内的连发事件合并为一次同步
     */
    private final AtomicBoolean resyncPending = new AtomicBoolean(false);

    /**
     * 重新同步的合并窗口
     */
    private static final Duration RESYNC_DEBOUNCE = Duration.ofSeconds(2);

    @Autowired
    public BilibiliStartupListener(BilibiliAccountService accountService,
                                   BilibiliLiveRoomService liveRoomService,
                                   BilibiliBackupLivePushService backupLivePushService,
                                   BilibiliDynamicService dynamicService,
                                   BilibiliStreamerSnapshotService snapshotService,
                                   AbstractDataSource dataSource,
                                   @Qualifier("bilibiliTaskScheduler") TaskScheduler scheduler,
                                   StarBotBilibiliProperties properties) {
        this.accountService = accountService;
        this.liveRoomService = liveRoomService;
        this.backupLivePushService = backupLivePushService;
        this.dynamicService = dynamicService;
        this.snapshotService = snapshotService;
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

        try {
            snapshotService.start(dataSource);
        } catch (Exception e) {
            log.error("启动主播基础数据留档失败", e);
        }

        startLoginStateVerification();

        servicesStarted.set(true);
        log.info("StarBotBilibili 已就绪");
    }

    /**
     * 数据源内容变更后重新同步直播间连接
     * <p>
     * 推送配置支持热重载，但直播间长连接不会自己跟着变：为既有用户补配直播事件后，
     * 新房间要到下次重启才会建立连接。此处监听数据源变更事件补上这一环。
     * 启动完成前的变更事件一律忽略——彼时登录尚未完成，提前建连会以匿名身份取令牌，
     * 与登录态身份不符，认证会被服务端拒绝。
     */
    @EventListener(StarBotDataSourceChangeEvent.class)
    public void onDataSourceChangeEvent() {
        if (!servicesStarted.get() || accountService.isStopping()) {
            return;
        }

        if (!resyncPending.compareAndSet(false, true)) {
            return;
        }

        scheduler.schedule(() -> {
            // 先复位再同步：同步期间又有变更进来时，能再触发一轮而不是被吞掉
            resyncPending.set(false);
            log.info("推送配置已变更, 重新同步直播间连接");
            try {
                liveRoomService.sync(dataSource);
            } catch (Exception e) {
                log.error("重新同步直播间连接失败", e);
            }
        }, Instant.now().plus(RESYNC_DEBOUNCE));
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
