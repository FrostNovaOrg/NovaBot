package org.frostnova.nova.bilibili.service;

import org.frostnova.nova.bilibili.BilibiliPlatform;
import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.event.live.BilibiliLiveOffEvent;
import org.frostnova.nova.bilibili.event.live.BilibiliLiveOnEvent;
import org.frostnova.nova.bilibili.model.Room;
import org.frostnova.nova.bilibili.model.Up;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.model.PushUser;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.frostnova.nova.core.service.LiveDataService;
import org.frostnova.nova.core.service.LiveSessionRecovery;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.TaskScheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.Map;
import java.util.Optional;
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

    /**
     * 停机空当里结束的场次，按「刚下播」处理的窗口
     * <p>
     * 起来后首轮见「账上在播、实际不在播」，说明这场是在停机空当里结束的，但接口不报
     * 下播时刻，只能拿「上次落盘时刻」当近似。落在窗口里就当主播刚下播——热升级交接
     * （旧版退出落盘、新版几秒内接上）与崩溃后被拉起都在这个量级里，照正常下播发通知、
     * 出报告图，下播时刻取上次落盘时刻。停得比这更久（比如关机一夜），「刚下播」就说不通了：
     * 那一时辰拿不出证据，当场按未闭合补档。
     * <p>
     * 窗口取 10 分钟，照自动落盘间隔（默认 300 秒）再放宽一档：正常空当里「上次落盘」与
     * 实际下播最多差一个落盘间隔加交接耗时，远小于 10 分钟。
     */
    private static final Duration RECENT_LIVE_OFF_WINDOW = Duration.ofMinutes(10);

    private final BilibiliApiUtil api;

    private final NovaBilibiliProperties properties;

    private final ApplicationEventPublisher publisher;

    private final TaskScheduler scheduler;

    private final BilibiliLiveStateGate stateGate;

    private final LiveDataService liveDataService;

    private final LiveSessionRecovery sessionRecovery;

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

    /**
     * 进程起来那一刻数据源里配着的主播 uid
     * <p>
     * 第一次真正跑起来的那轮轮询记下（数据源已就绪之后、「没有主播就返回」之前；空也照记，
     * 接口查询成不成都算）。只按 uid 记、不要求房间号：推送配置不存房间号，起来时房间号
     * 未必已到（资料缓存缺、后台补全未赶上），要求了，房间号晚到的就会掉出名单。
     * 用来分「停机空当里结束的那场」与「运行中加回」：前者是起来时
     * 就配着的主播，场在停机空当里结束，照 {@link #RECENT_LIVE_OFF_WINDOW} 窗分两路收；
     * 后者账上的在播是上一进程里在播时被移出配置留下的，加回来时多半早已下播，没有
     * 「刚下播」可言，不该再发下播通知。不能拿 {@link #initialized} 判：首轮接口查询
     * 没回到的主播要到第二轮才被看见，那时 initialized 已真，而人仍是起来时就配着的。
     */
    private volatile Set<Long> upsAtStartup;

    private volatile AbstractDataSource dataSource;

    /**
     * 退出之后不再发布开播、下播。和「是不是已经在发布」共用这一把锁，
     * 避免刚查完还没停、发布却发生在存盘之后。
     */
    private final Object publishGate = new Object();

    /**
     * 已经进入发布、尚未离开的条数
     */
    private int publishing;

    /**
     * 为真后，新的开播、下播不再发布
     */
    private boolean publishingCeased;

    @Autowired
    public BilibiliBackupLivePushService(BilibiliApiUtil api,
                                         NovaBilibiliProperties properties,
                                         ApplicationEventPublisher publisher,
                                         @Qualifier("bilibiliTaskScheduler") TaskScheduler scheduler,
                                         BilibiliLiveStateGate stateGate,
                                         LiveDataService liveDataService,
                                         LiveSessionRecovery sessionRecovery) {
        this.api = api;
        this.properties = properties;
        this.publisher = publisher;
        this.scheduler = scheduler;
        this.stateGate = stateGate;
        this.liveDataService = liveDataService;
        this.sessionRecovery = sessionRecovery;
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

        List<PushUser> users = source.getUsers(BilibiliPlatform.BILIBILI.id());

        // 首次真正跑起来的这轮把数据源里的主播记全（空也照记）：分「停机空当里结束的场」与
        // 「运行中加回」靠的是这份名单，必须赶在「没有主播就返回」之前——首轮空名单同样是名单。
        // 只按 uid 记、赶在下面房间号过滤之前：推送配置不存房间号，起来时人人都缺，房间号要等
        // 资料缓存同步或后台补全才有——照 ups 的口径滤，房间号晚到的起来时就配着的主播会掉出
        // 名单、被当成运行中加回，停得短也丢掉「刚下播」的通知与报告图
        if (upsAtStartup == null) {
            upsAtStartup = Set.copyOf(users.stream()
                    .filter(user -> !Boolean.FALSE.equals(user.getEnabled()))
                    .map(Up::new)
                    .filter(up -> up.getUid() != null)
                    .map(Up::getUid)
                    .collect(Collectors.toList()));
        }

        Map<Long, Up> ups = users.stream()
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

        // 此前没观测过而「账上在播、实际不在播」：要么是停机空当里结束的那场（起来时账上
        // 还挂着在播，实际主播早已下播），要么是运行中加回、账上挂的是上一进程留下的旧场。
        // 不处理的话账上那个在播永远翻不过来（下一轮起 previous 不再是 null，状态再也不会
        // 被当成变化），控制台一直显示在播，下播通知、报告图与下播时段的打赏汇总都没了。
        // 两种各怎么收看 {@link #closeStreamEndedWhileDown}
        if (previous == null && !living
                && liveDataService.getLiveStatus(BilibiliPlatform.BILIBILI.id(), up.getUid()).orElse(false)) {
            closeStreamEndedWhileDown(up, upsAtStartup.contains(up.getUid()));
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

        // 发布逐位兜错：事件处理在各位监听器手里，谁抛错都说不准。抛出来的不往外冒——
        // 冒出去本轮 forEach 就断在这里，排在后面的主播全被连累、要等下一轮；调度器那边
        // 虽还会排下一轮（异常被 Spring 的 LoggingErrorHandler 记一条后吞掉），但晚的就是一轮
        String change = living ? "开播" : "下播";
        if (!beginPublish()) {
            log.info("备用直播推送检测到 {} {}，程序正在退出，不再发布", up.getUname(), change);
            return;
        }
        try {
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
        } catch (Exception e) {
            log.error("备用直播推送发布 {} 的{}事件出错, 本次跳过", up.getUname(), change, e);
        } finally {
            endPublish();
        }
    }

    /**
     * 把「此前没观测过、账上在播、实际不在播」的那笔收掉
     * <p>
     * 按这位是不是进程起来时就配着的分两种：
     * <ul>
     *     <li>起来时就配着：场是在停机空当里结束的，按离上次落盘多久分两路：
     *         <ul>
     *             <li>停得短（{@link #RECENT_LIVE_OFF_WINDOW} 内）：主播多半就是刚下播，照正常下播走——
     *                 过状态闸门、发一次下播事件，通知与报告图由原来的监听照发。下播时刻取上次落盘时刻：
     *                 那是我们能证明的、账上还记着在播的最后一刻</li>
     *             <li>停得久、或取不到上次落盘时刻：下播时刻无从得知，当场按未闭合补档（不发下播事件、
     *                 不补发通知），再把账上改成不在播——这样下次开播不会再补一遍，控制台也不再挂着在播</li>
     *         </ul>
     *     </li>
     *     <li>运行中加回：账上那个在播是上一进程里在播时被移出配置留下的，加回来时没有停机空当
     *         可谈，停得短也不代表刚下播——一律当场按未闭合补档，不发过时的下播通知</li>
     * </ul>
     * @param up UP 主信息
     * @param configuredAtStartup 是否进程起来时就配着（{@link #upsAtStartup} 记于首轮轮询）
     */
    private void closeStreamEndedWhileDown(Up up, boolean configuredAtStartup) {
        String platform = BilibiliPlatform.BILIBILI.id();
        Optional<Long> lastSave = liveDataService.getLastSaveTime();
        long now = System.currentTimeMillis();
        if (!configuredAtStartup || lastSave.isEmpty() || now - lastSave.get() > RECENT_LIVE_OFF_WINDOW.toMillis()) {
            if (!configuredAtStartup) {
                log.warn("备用直播推送: {} 运行中加回, 账上在播、实际已下播, 当场按未闭合补档, 不发过时的下播通知", up.getUname());
            } else {
                log.warn("备用直播推送: {} 账上在播、实际已下播, 下播时刻无从得知, 当场按未闭合补档", up.getUname());
            }
            sessionRecovery.archiveUnclosedIfAny(platform, up, now);
            liveDataService.setLiveStatus(platform, up.getUid(), false);
            return;
        }

        // 同一次变化长连接可能已经推过，跨路径的去重照样过共享闸门；发布也照旧逐位兜错
        if (!stateGate.admit(up.getUid(), false)) {
            log.debug("备用直播推送检测到 {} 状态变化, 但长连接已推送, 跳过", up.getUname());
            return;
        }
        if (!beginPublish()) {
            log.info("备用直播推送检测到 {} 下播，程序正在退出，不再发布", up.getUname());
            return;
        }
        try {
            Instant offAt = Instant.ofEpochMilli(lastSave.get());
            log.info("备用直播推送检测到 {} 下播（停机空当里结束, 下播时刻取上次落盘时刻）", up.getUname());
            publisher.publishEvent(new BilibiliLiveOffEvent(up, offAt));
        } catch (Exception e) {
            log.error("备用直播推送发布 {} 的下播事件出错, 本次跳过", up.getUname(), e);
        } finally {
            endPublish();
        }
    }

    /**
     * 从现在起不再发布开播、下播。已经在发布中的那一趟由 {@link #awaitPublishFinished} 等。
     */
    void ceasePublishing() {
        synchronized (publishGate) {
            publishingCeased = true;
        }
    }

    /**
     * 等已经在发布中的那一趟离开。
     * @param deadlineNanos 截止时刻，按 {@link System#nanoTime()} 计
     * @return 截止前是否已经没有正在发布的事件
     */
    boolean awaitPublishFinished(long deadlineNanos) {
        synchronized (publishGate) {
            while (publishing > 0) {
                long waitMs = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
                if (waitMs <= 0) {
                    return false;
                }
                try {
                    publishGate.wait(waitMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * 占住发布名额。已经停止发布时返回 false，调用方不得再发布。
     */
    private boolean beginPublish() {
        synchronized (publishGate) {
            if (publishingCeased) {
                return false;
            }
            publishing++;
            return true;
        }
    }

    /**
     * 一次发布结束。没有正在发布的时候叫醒正在等的退出。
     */
    private void endPublish() {
        synchronized (publishGate) {
            publishing--;
            if (publishing <= 0) {
                publishing = 0;
                publishGate.notifyAll();
            }
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
