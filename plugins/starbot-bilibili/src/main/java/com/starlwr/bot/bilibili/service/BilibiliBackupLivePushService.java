package com.starlwr.bot.bilibili.service;

import com.starlwr.bot.bilibili.BilibiliPlatform;
import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.bilibili.event.live.BilibiliLiveOffEvent;
import com.starlwr.bot.bilibili.event.live.BilibiliLiveOnEvent;
import com.starlwr.bot.bilibili.model.Room;
import com.starlwr.bot.bilibili.model.Up;
import com.starlwr.bot.bilibili.util.BilibiliApiUtil;
import com.starlwr.bot.core.datasource.AbstractDataSource;
import com.starlwr.bot.core.plugin.NovaComponent;
import com.starlwr.bot.core.service.LiveDataService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.TaskScheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 备用直播推送服务
 * <p>
 * 直播间长连接可能因风控或网络原因失效，此时开播与下播事件会漏推。本服务以轮询接口的方式
 * 独立判断开播状态，作为长连接的兜底。为避免与长连接重复推送，同一直播间的状态变化只会推送一次。
 */
@Slf4j
@NovaComponent
public class BilibiliBackupLivePushService {
    /**
     * 单次请求可查询的最大 uid 数量，超出时分批请求
     */
    private static final int BATCH_SIZE = 100;

    private final BilibiliApiUtil api;

    private final StarBotBilibiliProperties properties;

    private final ApplicationEventPublisher publisher;

    private final TaskScheduler scheduler;

    private final BilibiliLiveStateGate stateGate;

    private final LiveDataService liveDataService;

    /**
     * uid 到上次已知开播状态的映射
     * <p>
     * 只用于识别「轮询自己看到的状态变了」，不能用作与长连接之间的去重依据——
     * 它对长连接推过什么一无所知。跨路径的去重由 {@link BilibiliLiveStateGate} 负责
     */
    private final Map<Long, Boolean> livingStates = new ConcurrentHashMap<>();

    /**
     * 是否已完成首轮状态采集
     * <p>
     * 首轮只记录状态而不推送，否则程序每次启动都会把当前正在直播的主播全部当作刚开播推送一遍。
     */
    private volatile boolean initialized;

    private volatile AbstractDataSource dataSource;

    @Autowired
    public BilibiliBackupLivePushService(BilibiliApiUtil api,
                                         StarBotBilibiliProperties properties,
                                         ApplicationEventPublisher publisher,
                                         @Qualifier("bilibiliTaskScheduler") TaskScheduler scheduler,
                                         BilibiliLiveStateGate stateGate,
                                         LiveDataService liveDataService) {
        this.api = api;
        this.properties = properties;
        this.publisher = publisher;
        this.scheduler = scheduler;
        this.stateGate = stateGate;
        this.liveDataService = liveDataService;
    }

    /**
     * 启动轮询
     * @param dataSource 数据源
     */
    public void start(AbstractDataSource dataSource) {
        if (!properties.getLive().isBackupLivePush()) {
            log.info("备用直播推送已关闭");
            return;
        }

        this.dataSource = dataSource;

        Duration interval = Duration.ofSeconds(Math.max(5, properties.getLive().getBackupLivePushInterval()));
        scheduler.scheduleAtFixedRate(this::poll, interval);

        log.info("备用直播推送已启动, 检测间隔 {} 秒", interval.toSeconds());
    }

    /**
     * 执行一轮状态检测
     */
    private void poll() {
        AbstractDataSource source = this.dataSource;
        if (source == null) {
            return;
        }

        Map<Long, Up> ups = source.getUsers(BilibiliPlatform.BILIBILI.id()).stream()
                .filter(user -> !Boolean.FALSE.equals(user.getEnabled()))
                .map(Up::new)
                .filter(up -> up.getUid() != null && up.getRoomId() != null)
                .collect(Collectors.toMap(Up::getUid, up -> up, (first, second) -> first));

        if (ups.isEmpty()) {
            return;
        }

        Map<Long, Room> rooms = new java.util.HashMap<>();
        for (Set<Long> batch : partition(ups.keySet())) {
            try {
                rooms.putAll(api.getLiveInfoByUids(batch));
            } catch (Exception e) {
                log.debug("备用直播推送查询直播间状态失败: {}", e.getMessage());
            }
        }

        rooms.forEach((uid, room) -> handleStateChange(ups.get(uid), room));

        initialized = true;
    }

    /**
     * 处理单个主播的状态变化
     * @param up UP 主信息
     * @param room 直播间信息
     */
    private void handleStateChange(Up up, Room room) {
        if (up == null || room == null || room.getLiveStatus() == null) {
            return;
        }

        boolean living = room.isLiving();
        Boolean previous = livingStates.put(up.getUid(), living);

        // 此前没观测过这位主播：可能是运行期新增，也可能是进程首轮。观测到在播而账上
        // 不是在播时，这场直播会全程被当成没在播——直播报告、实时数据与控制台在播态
        // 读的都是那本账。此处把状态同步成在播，但不补推开播：人不是刚开播，补推是误报；
        // 长连接进房后迟到的开播消息也会被状态闸门按「账上已在播」拦下，不会漏出第二条。
        // 账上已在播的（如进程重启后延续的场次）不是新加入监听，跳过，也不动已有的本场数据。
        if (previous == null && living
                && !liveDataService.getLiveStatus(BilibiliPlatform.BILIBILI.id(), up.getUid()).orElse(false)) {
            adoptLivingStream(up, room);
            return;
        }

        // 首轮或状态未变化时不推送
        if (!initialized || previous == null || previous == living) {
            return;
        }

        // 同一次变化长连接可能已经推过。上面的 previous 只反映轮询自己的观测，
        // 跨路径的去重必须过共享闸门
        if (!stateGate.admit(up.getUid(), living)) {
            log.debug("备用直播推送检测到 {} 状态变化, 但长连接已推送, 跳过", up.getUname());
            return;
        }

        if (living) {
            Instant startTime = room.getLiveStartTime() == null
                    ? Instant.now()
                    : Instant.ofEpochSecond(room.getLiveStartTime());

            log.info("备用直播推送检测到 {} 开播", up.getUname());
            publisher.publishEvent(new BilibiliLiveOnEvent(up, startTime));
        } else {
            log.info("备用直播推送检测到 {} 下播", up.getUname());
            publisher.publishEvent(new BilibiliLiveOffEvent(up));
        }
    }

    /**
     * 把加入监听时已在播的主播按在播记账
     * <p>
     * 只做同步，不发开播事件——事件除了推送通知，还会触发监听器把本场数据整场重置，
     * 那会把已有的本场数据抹掉。已有本场起始时保持原值（如进程重启后延续的场次），
     * 没有时才按接口报的开播时间起一场；接口没报就按当前时刻。
     * @param up UP 主信息
     * @param room 直播间信息
     */
    private void adoptLivingStream(Up up, Room room) {
        String platform = BilibiliPlatform.BILIBILI.id();
        liveDataService.setLiveStatus(platform, up.getUid(), true);

        if (liveDataService.getLiveStartTime(platform, up.getUid()).isEmpty()) {
            Instant startTime = room.getLiveStartTime() == null
                    ? Instant.now()
                    : Instant.ofEpochSecond(room.getLiveStartTime());
            liveDataService.setLiveStartTime(platform, up.getUid(), startTime.toEpochMilli());
        }

        log.info("备用直播推送: {} 加入监听时已在播, 已按在播记账, 不补推开播", up.getUname());
    }

    /**
     * 将 uid 集合按批次大小切分
     * @param uids uid 集合
     * @return 分批后的集合列表
     */
    private java.util.List<Set<Long>> partition(Set<Long> uids) {
        java.util.List<Set<Long>> batches = new java.util.ArrayList<>();

        Set<Long> current = new HashSet<>();
        for (Long uid : uids) {
            current.add(uid);
            if (current.size() == BATCH_SIZE) {
                batches.add(current);
                current = new HashSet<>();
            }
        }

        if (!current.isEmpty()) {
            batches.add(current);
        }

        return batches;
    }
}
