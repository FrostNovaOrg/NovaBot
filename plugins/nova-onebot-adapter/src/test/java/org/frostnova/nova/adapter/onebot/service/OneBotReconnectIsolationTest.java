package org.frostnova.nova.adapter.onebot.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.adapter.onebot.config.OneBotAdapterPluginProperties;
import org.frostnova.nova.adapter.onebot.config.OneBotThreadPoolConfig;
import org.frostnova.nova.adapter.onebot.health.OneBotConnectionState;
import org.frostnova.nova.adapter.onebot.model.OneBotSender;
import org.frostnova.nova.core.event.remote.NovaRemoteMessageEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 一台机器人的连接出了事，不许连累别的机器人，也不许连出多余的连接
 *
 * <h2>这里守的是四件事</h2>
 * <ol>
 *   <li><b>一台连不上，另一台照常回指令</b>——重连在退避里睡着时若占着线程池的线程，
 *       两台连不上的机器人就把默认的两个核心线程占满，健康那台的消息全在队列里排着</li>
 *   <li><b>断线时出错、关闭两个回调都来，只起一条重连</b>——各起一条的话会连出两条会话，
 *       同一条群消息被处理两遍，多出来那条之后还关不掉</li>
 *   <li><b>换掉的连接，再收到的消息不再执行</b>——刚停用那一刻，旧连接里的消息
 *       还会被当成指令执行，或把账号状态改成「掉线」</li>
 *   <li><b>旧会话晚到的关闭回调不停新会话的存活检测</b>——停了的话新会话此后再也不检测，
 *       界面上却一切正常</li>
 * </ol>
 *
 * <h2>不起套接字</h2>
 * 连不上的机器人用一个解析不了的地址（{@code bad host}），每一轮当场失败，不碰网络；
 * 会话一侧用 {@link OneBotWebsocketService#handlerOf} 在真的那一代连接上造处理器，
 * 由判据代替容器调它的回调。量的是适配器在这些回调之后做了什么，与套接字无关，
 * 因此在不许绑端口的环境里照样跑。
 */
@DisplayName("一台机器人断线不连累别的，重连只起一条")
class OneBotReconnectIsolationTest {
    private static final String SENDER = "qq-onebot";

    /**
     * 第一格的时限：健康那台的消息要在这么久之内发布出来
     */
    private static final Duration DELIVERY_LIMIT = Duration.ofSeconds(10);

    /**
     * 第一格里健康那台的消息最多等多久，仍算没在等别人的退避：第一轮退避是 2 秒
     */
    private static final Duration NOT_WAITING_FOR_BACKOFF = Duration.ofSeconds(1);

    private static final Duration PATIENCE = Duration.ofSeconds(8);

    private final List<NovaRemoteMessageEvent> published = Collections.synchronizedList(new ArrayList<>());

    private final List<String> started = new ArrayList<>();

    private RecordingScheduler scheduler;

    private ThreadPoolTaskExecutor executor;

    private OneBotAdapterPluginProperties properties;

    private OneBotConnectionState state;

    private OneBotWebsocketService websocketService;

    private ListAppender<ILoggingEvent> appender;

    private ch.qos.logback.classic.Logger logger;

    @BeforeEach
    void setUp() {
        scheduler = new RecordingScheduler();
        scheduler.setPoolSize(2);
        scheduler.setDaemon(true);
        scheduler.initialize();

        properties = new OneBotAdapterPluginProperties();
        properties.getDetect().setEnableHttpDetect(false);
        properties.getDetect().setEnableWebsocketDetect(false);

        state = new OneBotConnectionState();

        logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(OneBotWebsocketService.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        if (websocketService != null) {
            started.forEach(websocketService::stop);
        }
        logger.detachAppender(appender);
        appender.stop();
        if (executor != null) {
            executor.shutdown();
        }
        scheduler.shutdown();
    }

    /**
     * 线程池照生产的配法造：核心 2、最多 16、队列 128，就是使用者不改配置时的样子
     */
    private void useDefaultPool() {
        executor = new OneBotThreadPoolConfig(properties).oneBotThreadPool();
        newService();
    }

    private void usePool(int threads) {
        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(threads);
        executor.setMaxPoolSize(threads);
        executor.setQueueCapacity(128);
        executor.setDaemon(true);
        executor.initialize();
        newService();
    }

    private void newService() {
        ApplicationEventPublisher publisher = event -> {
            if (event instanceof NovaRemoteMessageEvent message) {
                published.add(message);
            }
        };
        websocketService = new OneBotWebsocketService(scheduler, executor, properties,
                mock(OneBotHttpService.class), state, publisher);
    }

    private static OneBotSender unreachable(String name) {
        OneBotSender sender = new OneBotSender();
        sender.setName(name);
        sender.setWebsocket(true);
        sender.setOneBotAddress("bad host");
        sender.setOneBotWebsocketPort(39999);
        sender.setOneBotWebsocketToken("ws-token");
        return sender;
    }

    private void start(String name) {
        started.add(name);
        websocketService.start(unreachable(name));
    }

    /**
     * 等到条件成立或超时
     * @return 超时前成立没有
     */
    private static boolean waitFor(Duration limit, BooleanSupplier condition) {
        Instant deadline = Instant.now().plus(limit);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        return condition.getAsBoolean();
    }

    private static void awaitUntil(String what, BooleanSupplier condition) {
        assertTrue(waitFor(PATIENCE, condition), what + "：等了 " + PATIENCE.toSeconds() + " 秒仍不成立");
    }

    /**
     * 第一轮已经失败过：状态里写着「正在重试」
     */
    private boolean failedOnce(String name) {
        OneBotConnectionState.Entry entry = state.all().get(name);
        return entry != null && entry.getWebsocket().detail().contains("重试");
    }

    /**
     * 起过几段重连：每段在第一轮打一行连接地址，之后同一段里不再打
     */
    private long reconnectEpisodes() {
        synchronized (appender) {
            return appender.list.stream()
                    .filter(e -> e.getLevel() == Level.INFO)
                    .filter(e -> e.getFormattedMessage().contains("连接地址"))
                    .count();
        }
    }

    /**
     * 往池里放一个空活，等它跑完：单线程池先进先出，它跑到时排在前面的都跑完了
     */
    private void drain() throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        executor.submit(done::countDown);
        assertTrue(done.await(PATIENCE.toSeconds(), java.util.concurrent.TimeUnit.SECONDS),
                "池里排着的活 " + PATIENCE.toSeconds() + " 秒内没跑完");
    }

    private static TextMessage groupMessage(String text) {
        return new TextMessage(new JSONObject()
                .fluentPut("post_type", "message")
                .fluentPut("message_type", "group")
                .fluentPut("group_id", 30003L)
                .fluentPut("user_id", 40004L)
                .fluentPut("self_id", 10001L)
                .fluentPut("raw_message", text)
                .toJSONString());
    }

    private static TextMessage lifecycleConnect() {
        return new TextMessage(new JSONObject()
                .fluentPut("post_type", "meta_event")
                .fluentPut("meta_event_type", "lifecycle")
                .fluentPut("sub_type", "connect")
                .toJSONString());
    }

    @Test
    @DisplayName("两台连不上时，健康那台的群消息照常 10 秒内发布")
    void unreachableBotsDoNotStarveHealthyOne() throws Exception {
        // 用户故障：配了三台机器人，两台连不上，好的那台回指令要迟到几分钟以上，新加的也连不上
        useDefaultPool();
        start("dead-1");
        start("dead-2");
        awaitUntil("两台都已失败过一轮", () -> failedOnce("dead-1") && failedOnce("dead-2"));

        OneBotSender healthy = unreachable("healthy");
        WebSocketHandler handler = websocketService.handlerFor(healthy);
        Instant sent = Instant.now();
        handler.handleMessage(mock(WebSocketSession.class), groupMessage("/ping"));

        assertTrue(waitFor(DELIVERY_LIMIT, () -> !published.isEmpty()),
                "健康那台的群消息 " + DELIVERY_LIMIT.toSeconds() + " 秒内没发布；线程池活跃 "
                        + executor.getActiveCount() + "、排队 " + executor.getThreadPoolExecutor().getQueue().size());
        Duration waited = Duration.between(sent, Instant.now());
        assertEquals("healthy", published.get(0).getPlatform());
        // 两台此刻都在第一轮退避里（2 秒）。退避若仍占着池线程，哪怕每轮让出一次，
        // 这条消息也得等到某台睡醒才轮得到：等过 1 秒就是在等别人的退避
        assertTrue(waited.compareTo(NOT_WAITING_FOR_BACKOFF) < 0,
                "健康那台的群消息等了 " + waited.toMillis() + " 毫秒，是在等连不上那两台的退避");
    }

    @Test
    @DisplayName("同一条会话先出错、再关闭，只起一条重连")
    void errorThenCloseReconnectsOnce() throws Exception {
        // 用户故障：断线重连后连出两条会话，同一条群消息被处理两遍，多出来那条之后还关不掉
        usePool(4);
        start(SENDER);
        awaitUntil("首次连接那一段起了", () -> reconnectEpisodes() == 1);

        WebSocketHandler handler = websocketService.handlerOf(SENDER);
        assertNotNull(handler);
        WebSocketSession session = mock(WebSocketSession.class);
        handler.afterConnectionEstablished(session);

        handler.handleTransportError(session, new IOException("connection reset"));
        handler.afterConnectionClosed(session, CloseStatus.SERVER_ERROR);

        awaitUntil("断线之后起了重连", () -> reconnectEpisodes() >= 2);
        // 两个回调各起一条的话，都是睡 1 秒后开连，多的那条若有，这段时间里一定到了
        boolean extra = waitFor(Duration.ofSeconds(3), () -> reconnectEpisodes() >= 3);
        assertFalse(extra, "出错、关闭两个回调各起了一条重连：一共起了 " + reconnectEpisodes() + " 段");
    }

    @Test
    @DisplayName("连接停掉之后，旧处理器再收到的消息不发布事件、不改状态")
    void framesOfStoppedConnectionAreDropped() throws Exception {
        // 用户故障：刚停用 WS 或改完连接设置，旧连接里的消息还被当成指令执行，或把账号记成掉线
        usePool(1);
        start(SENDER);
        awaitUntil("首轮失败过", () -> failedOnce(SENDER));

        WebSocketHandler handler = websocketService.handlerOf(SENDER);
        assertNotNull(handler);
        OneBotConnectionState.Status accountBefore = state.all().get(SENDER).getAccount();
        websocketService.stop(SENDER);

        WebSocketSession session = mock(WebSocketSession.class);
        handler.handleMessage(session, groupMessage("/ping"));
        handler.handleMessage(session, new TextMessage(new JSONObject()
                .fluentPut("post_type", "meta_event")
                .fluentPut("meta_event_type", "heartbeat")
                .fluentPut("interval", 5000L)
                .fluentPut("status", new JSONObject().fluentPut("online", false))
                .toJSONString()));
        handler.handleMessage(session, new TextMessage(new JSONObject()
                .fluentPut("post_type", "notice")
                .fluentPut("notice_type", "bot_offline")
                .fluentPut("reason", "kicked")
                .toJSONString()));
        drain();

        assertTrue(published.isEmpty(), "停掉之后，旧连接的群消息照样发布成了事件: " + published.size() + " 条");
        assertEquals(accountBefore, state.all().get(SENDER).getAccount(),
                "停掉之后，旧连接的心跳或下线通知改了账号状态");
    }

    @Test
    @DisplayName("旧会话晚到的关闭回调，不停新会话的存活检测")
    void lateCloseOfOldSessionKeepsNewDetect() throws Exception {
        // 用户故障：重连之后新连接再也不做存活检测，连接静默失效了界面上还写着正常
        properties.getDetect().setEnableWebsocketDetect(true);
        usePool(4);
        start(SENDER);
        WebSocketSession session = mock(WebSocketSession.class);

        WebSocketHandler old = websocketService.handlerOf(SENDER);
        old.afterConnectionEstablished(session);
        old.handleMessage(session, lifecycleConnect());
        awaitUntil("旧会话挂上检测", () -> scheduler.fixedRate.size() == 1);

        old.afterConnectionClosed(session, CloseStatus.SERVER_ERROR);

        WebSocketHandler fresh = websocketService.handlerOf(SENDER);
        fresh.afterConnectionEstablished(mock(WebSocketSession.class));
        fresh.handleMessage(session, lifecycleConnect());
        awaitUntil("新会话挂上检测", () -> scheduler.fixedRate.size() == 2);
        ScheduledFuture<?> freshDetect = scheduler.fixedRate.get(1);

        // 旧会话的关闭回调又来了一次：真实连接上它常晚于新会话建立
        old.afterConnectionClosed(session, CloseStatus.SERVER_ERROR);

        assertFalse(freshDetect.isCancelled(), "旧会话的关闭回调把新会话的存活检测停了");
    }

    /**
     * 记下每一次按固定周期挂的任务，好答「新会话的检测还在不在」
     */
    private static final class RecordingScheduler extends ThreadPoolTaskScheduler {
        private final List<ScheduledFuture<?>> fixedRate = Collections.synchronizedList(new ArrayList<>());

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Instant startTime, Duration period) {
            ScheduledFuture<?> future = super.scheduleAtFixedRate(task, startTime, period);
            fixedRate.add(future);
            return future;
        }
    }
}
