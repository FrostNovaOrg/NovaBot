package com.starlwr.bot.bilibili.service;

import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.bilibili.enums.ConnectStatus;
import com.starlwr.bot.bilibili.model.Up;
import com.starlwr.bot.bilibili.util.BilibiliApiUtil;
import com.starlwr.bot.core.datasource.AbstractDataSource;
import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.model.PushMessage;
import com.starlwr.bot.core.model.PushTarget;
import com.starlwr.bot.core.model.PushUser;
import com.starlwr.bot.core.plugin.StarBotComponent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.client.WebSocketClient;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

import java.time.Duration;
import java.time.Instant;
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
@StarBotComponent
public class BilibiliLiveRoomService {
    /**
     * 直播事件所在的包名前缀，用于判断某个推送目标是否订阅了直播事件
     */
    private static final String LIVE_EVENT_PACKAGE = "com.starlwr.bot.bilibili.event.live";

    private final BilibiliApiUtil api;

    private final BilibiliEventParser parser;

    private final StarBotBilibiliProperties properties;

    private final ApplicationEventPublisher publisher;

    private final TaskScheduler scheduler;

    private final BilibiliLiveStateGate stateGate;

    /**
     * 全局连接放行闸门，首连与重连共用同一条时间轴
     */
    private final BilibiliConnectGate connectGate;

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
     * 全部直播间共享的长连接客户端
     * <p>
     * 每个 StandardWebSocketClient 实例都会持有独立的 WebSocket 容器与线程池，
     * 因此只创建一个并在所有连接器之间复用。
     */
    private final WebSocketClient webSocketClient = new StandardWebSocketClient();

    @Autowired
    public BilibiliLiveRoomService(BilibiliApiUtil api,
                                   BilibiliEventParser parser,
                                   StarBotBilibiliProperties properties,
                                   ApplicationEventPublisher publisher,
                                   @Qualifier("bilibiliTaskScheduler") TaskScheduler scheduler,
                                   BilibiliLiveStateGate stateGate,
                                   BilibiliConnectGate connectGate) {
        this.api = api;
        this.parser = parser;
        this.properties = properties;
        this.publisher = publisher;
        this.scheduler = scheduler;
        this.stateGate = stateGate;
        this.connectGate = connectGate;
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

        Set<Up> targets = dataSource.getUsers(LivePlatform.BILIBILI.getName()).stream()
                .filter(user -> !Boolean.FALSE.equals(user.getEnabled()))
                .filter(user -> !properties.getLive().isOnlyConnectNecessaryRooms() || subscribesLiveEvent(user))
                .map(Up::new)
                .filter(up -> up.getRoomId() != null)
                .collect(Collectors.toSet());

        // 断开已不在配置中的直播间
        connectors.keySet().stream()
                .filter(roomId -> targets.stream().noneMatch(up -> roomId.equals(up.getRoomId())))
                .toList()
                .forEach(this::disconnect);

        for (Up up : targets) {
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
     * 建立到指定直播间的连接
     * @param up UP 主信息
     */
    private void connect(Up up) {
        connectors.computeIfAbsent(up.getRoomId(), roomId -> {
            BilibiliLiveRoomConnector connector =
                    new BilibiliLiveRoomConnector(up, api, parser, properties, publisher, scheduler, webSocketClient, stateGate, connectGate);
            connector.connect();
            return connector;
        });
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
     * 断开全部连接
     */
    public void disconnectAll() {
        connectors.keySet().stream().toList().forEach(this::disconnect);
    }

    /**
     * 对全部连接执行一次风控检测
     */
    private void detectRisk() {
        connectors.values().stream()
                .filter(BilibiliLiveRoomConnector::detectRisk)
                .forEach(connector -> log.warn(
                        "直播间 {} 已被数据风控, 该直播间的弹幕、礼物等事件将无法接收, 开播下播推送仍可通过备用直播推送保障",
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
