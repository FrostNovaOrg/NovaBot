package com.starlwr.bot.bilibili.service;

import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.bilibili.event.dynamic.BilibiliDynamicUpdateEvent;
import com.starlwr.bot.bilibili.model.Dynamic;
import com.starlwr.bot.bilibili.model.Up;
import com.starlwr.bot.bilibili.util.BilibiliApiUtil;
import com.starlwr.bot.core.datasource.AbstractDataSource;
import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.plugin.StarBotComponent;
import com.starlwr.bot.core.util.FixedSizeSetQueue;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.TaskScheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 动态推送服务
 * <p>
 * 通过轮询当前账号的动态流发现新动态。由于动态流只包含已关注 UP 主的内容，
 * 需要先确保配置了动态推送的 UP 主均已被关注。
 */
@Slf4j
@StarBotComponent
public class BilibiliDynamicService {
    /**
     * 已推送动态 ID 的记忆容量，用于跨轮次去重
     */
    private static final int PUSHED_CACHE_SIZE = 1000;

    private final BilibiliApiUtil api;

    private final BilibiliAccountService accountService;

    private final StarBotBilibiliProperties properties;

    private final ApplicationEventPublisher publisher;

    private final TaskScheduler scheduler;

    /**
     * 已推送过的动态 ID
     */
    private final FixedSizeSetQueue<String> pushed = new FixedSizeSetQueue<>(PUSHED_CACHE_SIZE);

    /**
     * 是否已完成首轮采集
     * <p>
     * 首轮只记录动态 ID 而不推送，避免启动时把动态流里的历史动态全部补推一遍。
     */
    private volatile boolean initialized;

    private volatile AbstractDataSource dataSource;

    @Autowired
    public BilibiliDynamicService(BilibiliApiUtil api,
                                  BilibiliAccountService accountService,
                                  StarBotBilibiliProperties properties,
                                  ApplicationEventPublisher publisher,
                                  @Qualifier("bilibiliTaskScheduler") TaskScheduler scheduler) {
        this.api = api;
        this.accountService = accountService;
        this.properties = properties;
        this.publisher = publisher;
        this.scheduler = scheduler;
    }

    /**
     * 启动动态推送
     * @param dataSource 数据源
     */
    public void start(AbstractDataSource dataSource) {
        this.dataSource = dataSource;

        scheduler.scheduleAtFixedRate(this::poll,
                Duration.ofSeconds(Math.max(5, properties.getDynamic().getApiRequestInterval())));

        if (properties.getDynamic().isAutoFollow()) {
            scheduler.scheduleAtFixedRate(this::followConfiguredUps,
                    Duration.ofSeconds(Math.max(10, properties.getDynamic().getAutoFollowInterval())));
        }

        log.info("动态推送已启动, 检测间隔 {} 秒", Math.max(5, properties.getDynamic().getApiRequestInterval()));
    }

    /**
     * 执行一轮动态检测
     */
    private void poll() {
        AbstractDataSource source = this.dataSource;
        if (source == null || !accountService.isLoggedIn()) {
            return;
        }

        List<Dynamic> dynamics;
        try {
            dynamics = api.getDynamicUpdateList();
        } catch (Exception e) {
            log.debug("获取动态列表失败: {}", e.getMessage());
            return;
        }

        Map<Long, Up> ups = source.getUsers(LivePlatform.BILIBILI.getName()).stream()
                .filter(user -> !Boolean.FALSE.equals(user.getEnabled()))
                .map(Up::new)
                .filter(up -> up.getUid() != null)
                .collect(Collectors.toMap(Up::getUid, up -> up, (first, second) -> first));

        Instant earliest = Instant.now().minus(Duration.ofMinutes(Math.max(1, properties.getDynamic().getPushMinutes())));

        for (Dynamic dynamic : dynamics) {
            if (dynamic.getId() == null || pushed.contains(dynamic.getId())) {
                continue;
            }

            pushed.add(dynamic.getId());

            if (!initialized) {
                continue;
            }

            Up up = dynamic.getAuthorUid().map(ups::get).orElse(null);
            if (up == null) {
                continue;
            }

            // 过旧的动态不再推送，避免关注新 UP 主时把其历史动态全部补推
            Instant publishTime = dynamic.getPublishTime().orElse(Instant.now());
            if (publishTime.isBefore(earliest)) {
                continue;
            }

            log.info("检测到 {} 的新动态: {}", up.getUname(), dynamic.getUrl());
            publisher.publishEvent(new BilibiliDynamicUpdateEvent(up, dynamic, describeAction(dynamic), dynamic.getUrl(), publishTime));
        }

        initialized = true;
    }

    /**
     * 依据动态类型描述其动作
     * @param dynamic 动态
     * @return 动作描述
     */
    private String describeAction(Dynamic dynamic) {
        if (dynamic.getType() == null) {
            return "发布了动态";
        }

        return switch (dynamic.getType()) {
            case "DYNAMIC_TYPE_AV" -> "投稿了视频";
            case "DYNAMIC_TYPE_FORWARD" -> "转发了动态";
            case "DYNAMIC_TYPE_ARTICLE" -> "投稿了专栏";
            case "DYNAMIC_TYPE_MUSIC" -> "投稿了音频";
            case "DYNAMIC_TYPE_LIVE_RCMD" -> "开播了";
            case "DYNAMIC_TYPE_PGC", "DYNAMIC_TYPE_UGC_SEASON" -> "更新了番剧";
            default -> "发布了动态";
        };
    }

    /**
     * 关注配置中尚未关注的 UP 主
     * <p>
     * 动态流仅包含已关注 UP 主的动态，未关注则无法收到其动态更新。
     */
    private void followConfiguredUps() {
        AbstractDataSource source = this.dataSource;
        if (source == null || !accountService.isLoggedIn()) {
            return;
        }

        Set<Long> configured = source.getUsers(LivePlatform.BILIBILI.getName()).stream()
                .filter(user -> !Boolean.FALSE.equals(user.getEnabled()))
                .map(com.starlwr.bot.core.model.PushUser::getUid)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());

        if (configured.isEmpty()) {
            return;
        }

        Set<Long> following;
        try {
            following = api.getFollowingUps(accountService.getLoginUid()).stream()
                    .map(Up::getUid)
                    .collect(Collectors.toCollection(HashSet::new));
        } catch (Exception e) {
            log.debug("获取关注列表失败: {}", e.getMessage());
            return;
        }

        configured.stream()
                .filter(uid -> !following.contains(uid))
                .forEach(api::followUp);
    }
}
