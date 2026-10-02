package org.frostnova.nova.bilibili.service;

import org.frostnova.nova.bilibili.BilibiliPlatform;
import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.health.BilibiliDisconnectDigest;
import org.frostnova.nova.bilibili.health.BilibiliRiskMetrics;
import org.frostnova.nova.bilibili.enums.ConnectStatus;
import org.frostnova.nova.bilibili.model.Up;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.datasource.MonitorLimit;
import org.frostnova.nova.core.model.PushMessage;
import org.frostnova.nova.core.model.PushTarget;
import org.frostnova.nova.core.model.PushUser;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.frostnova.nova.core.service.LiveDataService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.client.WebSocketClient;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * 直播间连接管理服务
 * <p>
 * 依据推送配置维护每个直播间的长连接，并按配置的间隔逐个建立连接，避免短时间内大量连接触发风控。
 */
@Slf4j
@NovaComponent
public class BilibiliLiveRoomService {
    /**
     * 直播事件所在的包名前缀，用于判断某个推送目标是否订阅了直播事件
     */
    private static final String LIVE_EVENT_PACKAGE = "org.frostnova.nova.bilibili.event.live";

    private final BilibiliApiUtil api;

    private final BilibiliEventParser parser;

    private final NovaBilibiliProperties properties;

    private final ApplicationEventPublisher publisher;

    private final TaskScheduler scheduler;

    private final BilibiliLiveStateGate stateGate;

    /**
     * 全局连接放行闸门，首连与重连共用同一条时间轴
     */
    private final BilibiliConnectGate connectGate;

    private final BilibiliRiskMetrics riskMetrics;

    private final BilibiliDisconnectDigest disconnectDigest;

    /**
     * 传给每个连接器，用于记录单房断线造成的采集缺口
     */
    private final LiveDataService liveDataService;

    /**
     * 直播间号到连接器的映射
     */
    private final Map<Long, BilibiliLiveRoomConnector> connectors = new ConcurrentHashMap<>();

    /**
     * 风控检测周期任务是否已注册。sync 会随推送配置热重载被反复调用，
     * 若不加守卫，每次调用都会再注册一个周期任务
     */
    private final AtomicBoolean riskDetectionStarted = new AtomicBoolean(false);

    /**
     * 停机开始后不再新建连接器。建连任务与 {@code computeIfAbsent} 都看这一面。
     */
    private final AtomicBoolean acceptingConnectors = new AtomicBoolean(true);

    /**
     * 全部直播间共享的长连接客户端
     * <p>
     * 每个 StandardWebSocketClient 实例都会持有独立的 WebSocket 容器与线程池，
     * 因此只创建一个并在所有连接器之间复用。
     */
    private final WebSocketClient webSocketClient = new StandardWebSocketClient();

    @Autowired
    public BilibiliLiveRoomService(BilibiliApiUtil api,
                                   BilibiliEventParser parser,
                                   NovaBilibiliProperties properties,
                                   ApplicationEventPublisher publisher,
                                   @Qualifier("bilibiliTaskScheduler") TaskScheduler scheduler,
                                   BilibiliLiveStateGate stateGate,
                                   BilibiliConnectGate connectGate,
                                   BilibiliRiskMetrics riskMetrics,
                                   BilibiliDisconnectDigest disconnectDigest,
                                   LiveDataService liveDataService) {
        this.api = api;
        this.parser = parser;
        this.properties = properties;
        this.publisher = publisher;
        this.scheduler = scheduler;
        this.stateGate = stateGate;
        this.connectGate = connectGate;
        this.riskMetrics = riskMetrics;
        this.disconnectDigest = disconnectDigest;
        this.liveDataService = liveDataService;
    }

    /**
     * 依据推送配置同步直播间连接
     * @param dataSource 数据源
     */
    public void sync(AbstractDataSource dataSource) {
        if (!properties.getLive().isEnableConnectLiveRoom()) {
            log.info("直播间连接已关闭, 将仅使用备用直播推送");
            return;
        }

        List<PushUser> enabled = dataSource.getUsers(BilibiliPlatform.BILIBILI.id()).stream()
                .filter(user -> !Boolean.FALSE.equals(user.getEnabled()))
                .toList();

        // 按数据源给出的先后排好再截。原先收进无序的 HashSet，超员时「留下哪几间」
        // 每次启动都可能不同，而使用者看到的是「同一份配置，今天连的和昨天不是同一批」
        List<Up> candidates = enabled.stream()
                .filter(user -> !properties.getLive().isOnlyConnectNecessaryRooms() || subscribesLiveEvent(user))
                .map(Up::new)
                .filter(up -> up.getRoomId() != null)
                .distinct()
                .toList();

        // 超出上限的要点名。否则那几间只是从此没有任何事件，而界面上一切正常，
        // 这种缺口没有痕迹可查
        if (candidates.size() > MonitorLimit.MAX_STREAMERS) {
            String dropped = candidates.subList(MonitorLimit.MAX_STREAMERS, candidates.size()).stream()
                    .map(up -> up.getUname() + "(UID: " + up.getUid() + ", 房间号: " + up.getRoomId() + ")")
                    .collect(Collectors.joining(", "));
            log.warn("同时监控的主播数上限为 {} 位, 以下直播间超出上限, 不予连接: {}", MonitorLimit.MAX_STREAMERS, dropped);
        }

        Set<Up> targets = candidates.stream()
                .limit(MonitorLimit.MAX_STREAMERS)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        // 被开关挡掉的房间要说出来。否则「我加了主播却什么都没发生」在日志里
        // 没有任何痕迹——纯监听房间（没有推送目标，只为把事件送进事件输出）
        // 正是最容易撞上这一条的用法
        if (properties.getLive().isOnlyConnectNecessaryRooms()) {
            List<String> skipped = enabled.stream()
                    .filter(user -> !subscribesLiveEvent(user))
                    .map(user -> user.getUname() + "(" + user.getRoomId() + ")")
                    .toList();
            if (!skipped.isEmpty()) {
                log.info("以下直播间未配置直播推送, 已按 only-connect-necessary-rooms 跳过连接: {}; " +
                        "若希望只采集不推送, 请把该项改为 false", String.join(", ", skipped));
            }
        }

        // 断开已不在配置中的直播间
        connectors.keySet().stream()
                .filter(roomId -> targets.stream().noneMatch(up -> roomId.equals(up.getRoomId())))
                .toList()
                .forEach(this::disconnect);

        // 账上在播的排在最前建连，其余照配置顺序跟上。升级或重启正赶上有人直播时，
        // 在播的那间若排在后面连，就要多漏那十几秒的弹幕、礼物与醒目留言。
        // 先收在播再收其余，两拨各自保持 targets 的先后，接起来就是稳定排序；
        // 取不到在播状态的按不在播处理
        List<Up> liveFirst = new ArrayList<>(targets.size());
        List<Up> rest = new ArrayList<>(targets.size());
        for (Up up : targets) {
            if (isLiveOnAccount(up)) {
                liveFirst.add(up);
            } else {
                rest.add(up);
            }
        }
        liveFirst.addAll(rest);

        for (Up up : liveFirst) {
            if (connectors.containsKey(up.getRoomId())) {
                continue;
            }

            // 交给全局闸门排队。这里不再自己累加延迟：首连若走自己的时间轴，
            // 就会和正在退避重连的房间撞在一起，多房间同时断线时叠成请求洪峰
            connectGate.submit(() -> connect(up));
        }

        if (properties.getLive().isAutoDetectLiveRoomRisk() && riskDetectionStarted.compareAndSet(false, true)) {
            scheduler.scheduleAtFixedRate(this::detectRisk,
                    Duration.ofSeconds(Math.max(10, properties.getLive().getAutoDetectLiveRoomRiskInterval())));
        }
    }

    /**
     * 判断推送用户是否订阅了直播相关事件
     * @param user 推送用户
     * @return 是否订阅了直播事件
     */
    private boolean subscribesLiveEvent(PushUser user) {
        return user.getTargets().stream()
                .filter(target -> !Boolean.FALSE.equals(target.getEnabled()))
                .map(PushTarget::getMessages)
                .flatMap(List::stream)
                .map(PushMessage::getEventClass)
                .filter(Objects::nonNull)
                .anyMatch(eventClass -> eventClass.getName().startsWith(LIVE_EVENT_PACKAGE));
    }

    /**
     * 账上是否记着这位在播
     * <p>
     * 问的是上个进程存下来的直播状态，不是现查平台：首连发生在启动时刻，
     * 该先连的是「账上以为还在播」的那几间。取不到状态的按不在播处理
     * @param up UP 主信息
     * @return 账上在播返回 true
     */
    private boolean isLiveOnAccount(Up up) {
        return liveDataService.getLiveStatus(BilibiliPlatform.BILIBILI.id(), up.getUid())
                .orElse(false);
    }

    /**
     * 建立到指定直播间的连接
     * @param up UP 主信息
     */
    private void connect(Up up) {
        if (!acceptingConnectors.get()) {
            log.debug("退出中, 放弃连接直播间 {} (UID: {})", up.getRoomId(), up.getUid());
            return;
        }
        // 兜底。正常路径上 sync 已经截过一道，这里挡的是它拦不住的那一种：
        // 热重载换了一批主播，而上一批的建连任务还压在闸门里没放行，
        // 两批各自都不超限，凑到一起执行时才撞上名额。
        // 已在管理中的房间要放行——重连走的正是这条路，挡下它等于满员时断线就再也连不回来
        if (!connectors.containsKey(up.getRoomId()) && connectors.size() >= MonitorLimit.MAX_STREAMERS) {
            log.warn("同时监控的主播数已达上限 {} 位, 不再连接直播间 {} (UID: {})",
                    MonitorLimit.MAX_STREAMERS, up.getRoomId(), up.getUid());
            return;
        }

        // 建连放在写入之后。写在写入里面时，取连接信息会占着表里这一格，
        // 退出要摘走同一格里另一间，就得一直等到这次建连返回。
        // 记下这次新建的那一个：房间已经在表里时映射函数不会跑，这里保持空，不再对它建连。
        BilibiliLiveRoomConnector[] created = new BilibiliLiveRoomConnector[1];
        connectors.computeIfAbsent(up.getRoomId(), roomId -> {
            if (!acceptingConnectors.get()) {
                log.debug("退出中, 放弃连接直播间 {} (UID: {})", up.getRoomId(), up.getUid());
                return null;
            }
            BilibiliLiveRoomConnector connector = new BilibiliLiveRoomConnector(
                    up, api, parser, properties, publisher, scheduler, webSocketClient,
                    stateGate, connectGate, riskMetrics, disconnectDigest, liveDataService,
                    acceptingConnectors);
            created[0] = connector;
            return connector;
        });
        if (created[0] == null) {
            return;
        }
        created[0].connect();
        if (!acceptingConnectors.get()) {
            connectors.remove(up.getRoomId(), created[0]);
            created[0].close();
            log.debug("退出中, 放弃连接直播间 {} (UID: {})", up.getRoomId(), up.getUid());
        }
    }

    /**
     * 断开到指定直播间的连接
     * @param roomId 直播间号
     */
    public void disconnect(Long roomId) {
        BilibiliLiveRoomConnector connector = connectors.remove(roomId);
        if (connector != null) {
            connector.close();
            log.info("已断开直播间 {} 的连接", roomId);
        }
    }

    /**
     * 停机时先断开全部直播间，再让核心把本场数据存盘。
     * <p>
     * 顺序依据：直播数据的收尾保存是 {@code @Order(0)}
     * （{@code DefaultLiveDataService#onContextClosedEvent}）。
     * Spring 对同一个关闭事件按 {@code @Order} 从小到大调用，所以这里取 {@code -1}，
     * 断开并等手头那条消息处理完之后，存盘才开始。
     * 时间线上「正在退出」那条是 {@code Integer.MIN_VALUE}，比这里更早，只记一条日志。
     * <p>
     * 本方法第一行起不再新建连接器：排队中的首连、重连到点即放弃。
     * 等待从这一行量起。全部直播间，加上备用直播推送里已经开始发布的那一趟，
     * 共用 {@link BilibiliLiveRoomConnector#INTAKE_DRAIN_BUDGET}。正在建连的那一间也算在这段里，
     * 到点没等完就记一条警告，照常往下存盘。
     * 同一刻起，备用直播推送不再发布新的开播、下播。
     * <p>
     * 若先存盘，存盘之后才到的下播仍会归档并入累计，盘上却还记着在播，
     * 下次开播再按未闭合归档一次，同一场就被记两次。
     */
    @Order(-1)
    @EventListener(ContextClosedEvent.class)
    public void onContextClosed() {
        acceptingConnectors.set(false);
        connectGate.refuseFurtherConnects();
        long deadlineNanos = System.nanoTime() + BilibiliLiveRoomConnector.INTAKE_DRAIN_BUDGET.toNanos();
        BilibiliBackupLivePushService backup = currentBackupPush();
        if (backup != null) {
            backup.ceasePublishing();
        }
        int unfinished = disconnectAllUntil(deadlineNanos);
        boolean backupSettled = backup == null || backup.awaitPublishFinished(deadlineNanos);
        if (unfinished > 0) {
            log.warn("退出时有 {} 间直播间手头的消息没能在时限内办完，已继续保存本场数据", unfinished);
        }
        if (!backupSettled) {
            log.warn("退出时备用直播推送还有一条正在发布，已继续保存本场数据");
        }
    }

    /**
     * 断开全部连接
     * <p>
     * 各间共用一个截止时刻，合计不超过 {@link BilibiliLiveRoomConnector#INTAKE_DRAIN_BUDGET}。
     * 到点没等完的记一条警告，连接照关。
     */
    public void disconnectAll() {
        long deadlineNanos = System.nanoTime() + BilibiliLiveRoomConnector.INTAKE_DRAIN_BUDGET.toNanos();
        int unfinished = disconnectAllUntil(deadlineNanos);
        if (unfinished > 0) {
            log.warn("退出时有 {} 间直播间手头的消息没能在时限内办完，已继续保存本场数据", unfinished);
        }
    }

    /**
     * 断开全部连接，各间共用调用方给的截止时刻。
     * @param deadlineNanos 截止时刻，按 {@link System#nanoTime()} 计
     * @return 截止时手头消息还没办完的直播间数
     */
    private int disconnectAllUntil(long deadlineNanos) {
        int unfinished = 0;
        for (Long roomId : connectors.keySet().stream().toList()) {
            BilibiliLiveRoomConnector connector = connectors.remove(roomId);
            if (connector == null) {
                continue;
            }
            if (!connector.closeUntil(deadlineNanos)) {
                unfinished++;
            }
            log.info("已断开直播间 {} 的连接", roomId);
        }
        return unfinished;
    }

    /**
     * 取出容器里的备用直播推送。测试里没登记这个组件时返回空。
     */
    private BilibiliBackupLivePushService currentBackupPush() {
        if (publisher instanceof ListableBeanFactory beans) {
            return beans.getBeanProvider(BilibiliBackupLivePushService.class).getIfAvailable();
        }
        return null;
    }

    /**
     * 对全部连接执行一次风控检测
     * <p>
     * <b>这行日志只陈述观测，不断言成因。</b> 检测器能看到的只是「业务消息断流」，
     * 而断流可能来自平台限制下发、协议改版导致解析不出、连接半死，也可能只是没人说话。
     * 此处一度写作「已被数据风控, 该直播间的弹幕、礼物等事件将无法接收」——
     * 2026-08-10 那次误报打出来的正是它，而当时采集根本没停，弹幕照常入库。
     * <b>把推断写成结论，会让人去查一个不存在的故障。</b>
     */
    private void detectRisk() {
        connectors.values().stream()
                .filter(BilibiliLiveRoomConnector::detectRisk)
                .forEach(connector -> log.warn(
                        "直播间 {} 业务消息断流, 原因未定（可能是平台限制下发、解析不出、连接半死或确实无人发言）, "
                                + "开播下播推送仍可通过备用直播推送保障",
                        connector.getSource().getRoomId()));
    }

    /**
     * 获取指定直播间的连接状态
     * @param roomId 直播间号
     * @return 连接状态
     */
    public Optional<ConnectStatus> getStatus(Long roomId) {
        return Optional.ofNullable(connectors.get(roomId)).map(BilibiliLiveRoomConnector::getStatus);
    }

    /**
     * 获取当前已建立连接的直播间数量
     * @return 直播间数量
     */
    public int getConnectedRoomCount() {
        return (int) connectors.values().stream()
                .filter(connector -> connector.getStatus() == ConnectStatus.CONNECTED)
                .count();
    }

    /**
     * 按连接状态统计各状态下的直播间数量
     * <p>
     * 供健康探针读取：只看「已连接几个」不足以判断问题所在，被风控与连不上需要区分对待。
     * @return 连接状态到数量的映射
     */
    public Map<ConnectStatus, Long> countByStatus() {
        return connectors.values().stream()
                .collect(Collectors.groupingBy(BilibiliLiveRoomConnector::getStatus, Collectors.counting()));
    }

    /**
     * 获取当前纳入连接管理的直播间总数
     * @return 直播间总数
     */
    public int getManagedRoomCount() {
        return connectors.size();
    }
}
