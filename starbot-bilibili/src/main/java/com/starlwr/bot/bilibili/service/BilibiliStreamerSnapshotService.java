package com.starlwr.bot.bilibili.service;

import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.bilibili.model.BilibiliStreamerMetric;
import com.starlwr.bot.bilibili.model.Up;
import com.starlwr.bot.bilibili.util.BilibiliApiUtil;
import com.starlwr.bot.core.datasource.AbstractDataSource;
import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.model.StreamerSnapshot;
import com.starlwr.bot.core.plugin.StarBotComponent;
import com.starlwr.bot.core.service.StreamerSnapshotArchive;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.TaskScheduler;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 主播基础数据定时采样
 * <p>
 * 粉丝数、粉丝团人数、大航海人数都不在弹幕流里，只能主动去问接口——于是此前它们只在
 * 开播那一刻被记过一次。<b>不播的日子里这些数字照样在动，而我们一无所知。</b>
 * 本服务按固定间隔采样，把这段空白补上。
 * <p>
 * <b>不播的时候也采。</b>这正是它存在的理由：直播期间的变化早已由开播快照与下播报告覆盖，
 * 真正缺的是两场之间。
 */
@Slf4j
@StarBotComponent
public class BilibiliStreamerSnapshotService {
    /**
     * 每位主播之间的请求间隔
     * <p>
     * 一轮要给每位主播打三个接口。串成一串连发容易撞上风控，而这是个后台任务，
     * <b>慢一点毫无代价</b>，没有任何人在等这个结果。
     */
    private static final Duration REQUEST_GAP = Duration.ofSeconds(2);

    private final BilibiliApiUtil api;

    private final StarBotBilibiliProperties properties;

    private final StreamerSnapshotArchive archive;

    private final TaskScheduler scheduler;

    private volatile AbstractDataSource dataSource;

    @Autowired
    public BilibiliStreamerSnapshotService(BilibiliApiUtil api, StarBotBilibiliProperties properties,
                                           StreamerSnapshotArchive archive,
                                           @Qualifier("bilibiliTaskScheduler") TaskScheduler scheduler) {
        this.api = api;
        this.properties = properties;
        this.archive = archive;
        this.scheduler = scheduler;
    }

    /**
     * 启动定时采样
     * @param dataSource 数据源
     */
    public void start(AbstractDataSource dataSource) {
        int hours = properties.getLive().getSnapshotInterval();
        if (hours <= 0) {
            log.info("主播基础数据留档已关闭");
            return;
        }

        this.dataSource = dataSource;

        Duration interval = Duration.ofHours(hours);
        scheduler.scheduleAtFixedRate(this::sample, interval);

        log.info("主播基础数据留档已启动, 采样间隔 {} 小时", hours);
    }

    /**
     * 执行一轮采样
     */
    private void sample() {
        AbstractDataSource source = this.dataSource;
        if (source == null) {
            return;
        }

        List<Up> ups = source.getUsers(LivePlatform.BILIBILI.getName()).stream()
                .filter(user -> !Boolean.FALSE.equals(user.getEnabled()))
                .map(Up::new)
                .filter(up -> up.getUid() != null)
                .toList();

        long at = System.currentTimeMillis();
        for (int i = 0; i < ups.size(); i++) {
            if (i > 0) {
                sleep();
            }
            archive.append(snapshot(ups.get(i), at));
        }

        if (!ups.isEmpty()) {
            log.debug("已留档 {} 位主播的基础数据", ups.size());
        }
    }

    /**
     * 采一位主播的数据
     * <p>
     * 三个接口各自失败互不影响，<b>取不到的项不写进快照而不是写成 0</b>——
     * 后者会在趋势图上留下一个假的断崖。
     */
    private StreamerSnapshot snapshot(Up up, long at) {
        Map<String, Double> metrics = new HashMap<>();

        try {
            api.getFansCount(up.getUid()).ifPresent(fans ->
                    metrics.put(BilibiliStreamerMetric.FANS, (double) fans));
        } catch (Exception e) {
            log.debug("采样 {} 的粉丝数失败: {}", up.getUid(), e.getMessage());
        }

        try {
            api.getFansMedalCount(up.getUid()).ifPresent(medal ->
                    metrics.put(BilibiliStreamerMetric.FANS_MEDAL, (double) medal));
        } catch (Exception e) {
            log.debug("采样 {} 的粉丝团人数失败: {}", up.getUid(), e.getMessage());
        }

        if (up.getRoomId() != null) {
            try {
                api.getGuardCount(up.getRoomId(), up.getUid()).ifPresent(guard ->
                        metrics.put(BilibiliStreamerMetric.GUARD, (double) guard));
            } catch (Exception e) {
                log.debug("采样 {} 的大航海人数失败: {}", up.getUid(), e.getMessage());
            }
        }

        return new StreamerSnapshot(LivePlatform.BILIBILI.getName(), up.getUid(), up.getUname(), at, metrics);
    }

    private void sleep() {
        try {
            Thread.sleep(REQUEST_GAP.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
