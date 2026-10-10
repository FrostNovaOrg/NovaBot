package org.frostnova.nova.adapter.onebot.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.adapter.onebot.config.OneBotAdapterPluginProperties;
import org.frostnova.nova.adapter.onebot.config.OneBotEndpointAddresses;
import org.frostnova.nova.adapter.onebot.converter.OneBotIncomingMessage;
import org.frostnova.nova.adapter.onebot.model.OneBotSender;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.frostnova.nova.adapter.onebot.health.OneBotConnectionState;
import org.frostnova.nova.adapter.onebot.health.OneBotLivenessTracker;
import org.frostnova.nova.core.event.remote.NovaRemoteMessageEvent;
import org.frostnova.nova.core.lang.StringUtil;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.WebSocketContainer;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.socket.*;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * OneBot Websocket 服务
 */
@Slf4j
@NovaComponent
public class OneBotWebsocketService {
    /**
     * 存活检测的执行周期
     */
    private static final Duration CHECK_INTERVAL = Duration.ofSeconds(30);

    /**
     * 连着的会话出错或断开之后，等多久再试第一次
     */
    private static final Duration RECONNECT_DELAY = Duration.ofSeconds(1);

    /**
     * 一条消息的全部分片拼起来最多多少个字符
     * <p>
     * 与给容器设的文本缓冲同一个数、同一个单位：容器那一份按字符计，只管单片；这一份管整条。
     * 按字符而不按字节，是因为拼接缓冲本身就是字符串，数它的长度不必每片再编码一遍。
     * 不设的话，对端一直发不收尾的分片，内存会一直涨到整个程序退出。
     */
    static final int MAX_MESSAGE_CHARS = 8 * 1024 * 1024;

    private final TaskScheduler taskScheduler;

    private final ThreadPoolTaskExecutor executor;

    private final OneBotAdapterPluginProperties properties;

    /**
     * HTTP 体检
     * <p>
     * 连上那一刻要立刻补做一次：刚扫码登录完的人看着没变的旧读数，会以为扫码没成。
     */
    private final OneBotHttpService httpService;

    private final OneBotConnectionState state;

    /**
     * 收到聊天消息时发布远程消息事件，供各平台模块实现消息命令
     * <p>
     * 适配器对聊天消息只做这一件事。<b>不在这里直接回话</b>：那样会绕过核心统一把关的几条
     * 横切约束——有没有 @、这个会话配没配推送、冷却、菜单，一条都不过。
     */
    private final ApplicationEventPublisher publisher;

    /**
     * 各推送平台当前在用的那一代连接
     * <p>
     * 连接、重连与断开分别发生在不同线程上，注册与取消都可能并发发生，因此不能用普通 HashMap
     */
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();

    /**
     * 已提示过「未推送心跳」的推送平台
     */
    private final Set<String> noHeartbeatWarned = ConcurrentHashMap.newKeySet();

    @Autowired
    public OneBotWebsocketService(TaskScheduler taskScheduler, @Qualifier("oneBotThreadPool") ThreadPoolTaskExecutor executor, OneBotAdapterPluginProperties properties, OneBotHttpService httpService, OneBotConnectionState state, ApplicationEventPublisher publisher) {
        this.taskScheduler = taskScheduler;
        this.executor = executor;
        this.properties = properties;
        this.httpService = httpService;
        this.state = state;
        this.publisher = publisher;
    }

    /**
     * 连接 OneBot Websocket
     */
    @Order(-10000)
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReadyEvent() {
        for (OneBotSender sender : properties.getSenders()) {
            start(sender);
        }
    }

    /**
     * 按这个推送平台<b>当前的</b>配置把 Websocket 接上
     * <p>
     * 🔴 <b>启动那条路走的也是这里。</b>「开机时连一次」与「运行期改了配置再连一次」
     * 若各写一份，两份迟早分家——而分家之后，从界面上配出来的那条连接与从配置文件里
     * 起出来的那条会在细节上不一样（补不补检测任务、Token 空了怎么记），
     * 且只在其中一条路上表现出来。
     * <p>
     * 反复调用是正常用法（使用者会连按保存）：每次都先把旧的那一代断掉，
     * 因此调三次之后仍然只有<b>一条</b>连接。
     * @param sender OneBot 推送平台信息
     */
    public synchronized void start(OneBotSender sender) {
        // 先断旧，且不论接下来连不连得成：改成「不启用 Websocket」之后旧连接还挂着的话，
        // 界面上写着未启用而消息照收，那是最难看出来的一种不一致
        stop(sender.getName());

        if (!sender.isWebsocket()) {
            state.websocketDisabled(sender.getName());
            return;
        }

        if (StringUtil.isBlank(sender.getOneBotWebsocketToken())) {
            log.warn("推送平台 {} 尚未配置 OneBot Websocket Token, 配好前推送不可用", sender.getName());
            state.websocketDisconnected(sender.getName(), "未配置 Websocket Token");
            return;
        }

        Connection connection = new Connection(sender);
        connections.put(sender.getName(), connection);
        reconnect(connection, Duration.ZERO);
    }

    /**
     * 断开这个推送平台的 Websocket，并回收它的重连与存活检测任务
     * <p>
     * 作废整一代而不是只关一个套接字：正在退避等待的重试循环、已经发出去还没回来的握手、
     * 挂着的存活检测，都属于这一代。只关套接字的话，那条重试循环会<b>接着往旧地址重连</b>，
     * 并把状态改回它自己那一份——界面上于是看到新地址连上了又断、断了又连。
     * @param platformName 推送平台名
     */
    public synchronized void stop(String platformName) {
        Connection previous = connections.remove(platformName);
        if (previous == null) {
            return;
        }

        previous.retired = true;
        stopDetect(previous);
        ScheduledFuture<?> retry = previous.retryTask;
        if (retry != null) {
            retry.cancel(false);
        }

        WebSocketSession session = previous.session;
        if (session != null && session.isOpen()) {
            try {
                session.close();
            } catch (Exception e) {
                log.warn("断开 {} 的旧 OneBot Websocket 连接异常", platformName, e);
            }
        }
    }

    /**
     * 这一代连接唯一的重连入口
     * <p>
     * 首次连接、传输出错、连接关闭都走这里。断线时出错与关闭两个回调常常先后都来，
     * 各起一段重连的话会连出两条会话：同一条消息处理两遍，而 {@link #stop} 只关得掉其中一条。
     * 所以已有一段重连排着或正在试时，再来的请求直接忽略；连上之后才复位
     * （见 {@link OneBotWebSocketHandler#afterConnectionEstablished}）。
     * @param connection 这一代连接
     * @param delay 等多久再试第一次
     * @return 这次请求有没有起一段新的重连；已有一段在跑、或这一代已作废时为 {@code false}
     */
    private boolean reconnect(Connection connection, Duration delay) {
        if (connection.retired) {
            return false;
        }
        Outage outage = new Outage();
        if (!connection.outage.compareAndSet(null, outage)) {
            return false;
        }
        scheduleAttempt(connection, outage, delay);
        return true;
    }

    /**
     * 到点提交一次试连
     * <p>
     * 退避等待交给调度器，不在线程池里睡：池是各个推送平台共用的，消息的解析与分发、
     * 存活检测、HTTP 体检都在里面排队。连不上的平台若占着池线程睡退避，
     * 两个这样的平台就能把默认的两个核心线程占满，别的平台的消息只能一直排着。
     */
    private void scheduleAttempt(Connection connection, Outage outage, Duration delay) {
        Runnable submit = () -> executor.submit(() -> attempt(connection, outage));
        if (delay.isZero()) {
            submit.run();
            return;
        }
        try {
            connection.retryTask = taskScheduler.schedule(submit, Instant.now().plus(delay));
        } catch (RejectedExecutionException e) {
            // 调度器已关，进程在退出
            log.info("已停止重连 {} 的 OneBot Websocket 服务", connection.sender.getName());
        }
    }

    /**
     * 试连一次 OneBot Websocket 服务，失败就按退避排下一次
     * <p>
     * 一次试连占着池线程最多到握手超时为止（见 {@link OneBotWebSocketHandler#awaitConnection}）。
     * <p>
     * 连不上的时候日志要按量级合并：退避封顶 60 秒之后重连是每分钟一轮，每轮照打
     * 「准备连接」「连接地址」加一行失败，一小时就是一百八十多行同样的话，
     * 而排障真正要的只有「现在连不上」和「什么时候恢复的」。
     * 合并口径见 {@link #shouldLogRetryFailure}。
     * @param connection 这一代连接
     * @param outage 这一段重连
     */
    private void attempt(Connection connection, Outage outage) {
        OneBotSender sender = connection.sender;
        if (connection.retired) {
            log.info("已停止重连 {} 的 OneBot Websocket 服务", sender.getName());
            return;
        }

        // 退避里每趟都是同一个地址，日志行与 URI 共用
        String url = OneBotEndpointAddresses.websocketUrl(sender.getName(), sender.getOneBotAddress(), sender.getOneBotWebsocketPort());
        log.debug("准备连接 {} 的 OneBot Websocket 服务", sender.getName());
        if (!outage.addressLogged) {
            log.info("{} 的 OneBot Websocket 连接地址: {}/", sender.getName(), url);
            outage.addressLogged = true;
        }

        CompletableFuture<WebSocketSession> sessionFuture = null;
        try {
            WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
            headers.add("Authorization", "Bearer " + sender.getOneBotWebsocketToken());

            WebSocketContainer container = ContainerProvider.getWebSocketContainer();
            container.setDefaultMaxTextMessageBufferSize(MAX_MESSAGE_CHARS);
            StandardWebSocketClient webSocketClient = new StandardWebSocketClient(container);
            OneBotWebSocketHandler handler = new OneBotWebSocketHandler(this, connection);
            sessionFuture = webSocketClient.execute(handler, headers, URI.create(url));

            if (!handler.awaitConnection()) {
                throw new TimeoutException();
            }
            sessionFuture.get();
            // 第一次就连上说明没断过，谈不上「恢复」
            if (outage.failures > 0) {
                log.info("{} 的 OneBot Websocket 连接已恢复: 中断了 {}, 重试 {} 次后连上",
                        sender.getName(), formatSilence(Duration.between(outage.since, Instant.now())), outage.failures);
            }
        } catch (Exception e) {
            if (connection.retired || Thread.currentThread().isInterrupted() || e instanceof InterruptedException) {
                // 中断即视为要求停止重连（线程池正在关闭）：恢复中断标志，不再排下一次
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                log.info("已停止重连 {} 的 OneBot Websocket 服务", sender.getName());
                return;
            }
            if (connection.outage.get() != outage) {
                // 这一段已经连上过（握手成功之后才出的错），不再往下排
                return;
            }

            int retryCount = ++outage.failures;
            outage.interval = Math.min(outage.interval * 2, 60);
            int retryInterval = outage.interval;
            String elapsed = formatSilence(Duration.between(outage.since, Instant.now()));

            if (e instanceof TimeoutException) {
                if (shouldLogRetryFailure(retryCount)) {
                    log.warn("连接 {} 的 OneBot Websocket 服务超时, 已连续失败 {} 次、持续 {}, 将在 {} 秒后进行第 {} 次重试",
                            sender.getName(), retryCount, elapsed, retryInterval, retryCount);
                }
                state.websocketDisconnected(sender.getName(), "连接超时，正在重试（第 " + retryCount + " 次）");
                // 以 false 取消：握手跑在客户端自己的线程上，这个 Future 是 CompletableFuture，
                // 传 true 也不会去中断谁。早先写 false 是怕把当前线程的中断标志置位、
                // 让随后的退避等待立刻抛出而空转；如今退避不在这个线程上等，那一条已不成立，
                // 但池线程会接着去跑别的平台的活，不该在它身上留任何中断标志，所以仍写 false
                sessionFuture.cancel(false);
            } else {
                String message = "{} 的 OneBot Websocket 服务不可用, 请检查配置和服务状态, 已连续失败 {} 次、持续 {}, 将在 {} 秒后进行第 {} 次重试";
                if (shouldLogRetryFailure(retryCount)) {
                    if (outage.failureStackLogged) {
                        log.error(message, sender.getName(), retryCount, elapsed, retryInterval, retryCount);
                    } else {
                        log.error(message, sender.getName(), retryCount, elapsed, retryInterval, retryCount, e);
                        outage.failureStackLogged = true;
                    }
                }
                state.websocketDisconnected(sender.getName(), "连接失败，正在重试（第 " + retryCount + " 次）: " + e.getMessage());
            }

            scheduleAttempt(connection, outage, Duration.ofSeconds(retryInterval));
        }
    }

    /**
     * 这一轮的失败要不要占工程日志的一行
     * <p>
     * 前三轮照打：排障要先看得见「连不上」，以及它长什么样（超时还是不可用）。
     * 还没失败过（0）不报。
     * 之后只在连续失败满 10、100、1000… 次时再报一声，并且每次都写清
     * 已连续失败几次、持续多久。退避封顶之后重连是每分钟一轮，按轮数报就是
     * 每分钟一行同样的话，把要看的那几行淹了。
     * @param consecutiveFailures 连续失败了几轮，从 1 数起
     * @return 这一轮要不要报
     */
    static boolean shouldLogRetryFailure(int consecutiveFailures) {
        return consecutiveFailures > 0
                && (consecutiveFailures <= 3 || isPowerOfTen(consecutiveFailures));
    }

    private static boolean isPowerOfTen(int value) {
        if (value <= 0) {
            return false;
        }
        while (value % 10 == 0) {
            value /= 10;
        }
        return value == 1;
    }

    /**
     * 造一个不连任何东西的处理器，供判据直接驱动
     * <p>
     * 判据要量的是「收到一条上报之后适配器做了什么」，而那件事只发生在处理器里。
     * 有这个口，判据就不必按名字去反射一个私有构造器——那种判据认的是写法：
     * 构造器多一个参数它就整个跑不起来，报出来的还是一句与被测行为毫无关系的
     * {@code NoSuchMethodException}。
     * @param sender OneBot 推送平台信息
     * @return 处理器
     */
    WebSocketHandler handlerFor(OneBotSender sender) {
        return new OneBotWebSocketHandler(this, new Connection(sender));
    }

    /**
     * 在这个推送平台当前那一代连接上造一个处理器，像是这一代新连出来的一条会话
     * <p>
     * 与 {@link #handlerFor} 的分别在于它挂在 {@link #start} 起的那一代上：
     * 判据要量的「停掉之后」「重连几条」，都是这一代上的事。
     * @param platformName 推送平台名
     * @return 处理器；这个平台眼下没有连接时为 {@code null}
     */
    WebSocketHandler handlerOf(String platformName) {
        Connection connection = connections.get(platformName);
        return connection == null ? null : new OneBotWebSocketHandler(this, connection);
    }

    /**
     * 一个推送平台的一代 Websocket 连接
     * <p>
     * 断旧连新时整代作废：连接本身、正在退避的重试循环、存活检测任务都挂在这上面。
     * 作废之后这一代不再改动连接状态——否则一条已经被换掉的连接，会拿它自己的死讯
     * 去覆盖新连接刚写下的「已连接」。
     */
    private static final class Connection {
        private final OneBotSender sender;

        private volatile boolean retired;

        private volatile WebSocketSession session;

        private volatile ScheduledFuture<?> detectTask;

        /**
         * 存活检测是哪条会话挂的。旧会话的关闭回调只停它自己挂的那个
         */
        private volatile OneBotWebSocketHandler detectOwner;

        /**
         * 眼下连着的那条会话的处理器。换成了新会话之后，旧会话晚到的出错、关闭回调一概不理
         */
        private volatile OneBotWebSocketHandler handler;

        /**
         * 正在跑的那一段重连；为空即没在重连。见 {@link #reconnect}
         */
        private final AtomicReference<Outage> outage = new AtomicReference<>();

        /**
         * 排着的下一次试连，作废时一并取消
         */
        private volatile ScheduledFuture<?> retryTask;

        private Connection(OneBotSender sender) {
            this.sender = sender;
        }
    }

    /**
     * 一段重连：从断开到连上之间的那些试连
     * <p>
     * 同一段里的试连一次接一次排着，不会同时跑，所以这几个计数不用加锁；
     * 一次试连与下一次之间隔着调度器与线程池的提交，前一次写下的这一次看得见。
     */
    private static final class Outage {
        /**
         * 这一段从什么时候起断着。连上了要说得出断了多久
         */
        private final Instant since = Instant.now();

        private int failures;

        private int interval = 1;

        /**
         * 连接地址每轮都是同一个，只在第一次占一行
         */
        private boolean addressLogged;

        /**
         * 不可用那条的堆栈只在第一次带；后面每轮都带整段栈，是刷屏的另一半
         */
        private boolean failureStackLogged;
    }

    /**
     * Websocket 存活检测
     * <p>
     * 检测周期与判定超时是两件事：判定超时按心跳间隔取（见 {@link OneBotLivenessTracker}），
     * 而检查本身只是读两个时间戳，足够便宜，因此固定按 {@link #CHECK_INTERVAL} 频繁地查，
     * 以免超时早已到达却要等到下一个检测周期才被发现。
     * @param handler WebSocket 处理器
     */
    private void startDetect(OneBotWebSocketHandler handler) {
        Connection connection = handler.connection;
        String platformName = connection.sender.getName();
        Duration timeout = Duration.ofSeconds(properties.getDetect().getWebsocketSilenceTimeout());

        // 与 stop 里的作废、关闭回调里的停检测互斥：作废之后不能再挂上一个没人停的检测
        synchronized (connection) {
            if (connection.retired) {
                return;
            }
            stopDetect(connection);
            connection.detectOwner = handler;
            connection.detectTask = taskScheduler.scheduleAtFixedRate(() -> executor.submit(() -> {
                if (connection.retired) {
                    return;
                }

                OneBotLivenessTracker.Verdict verdict = handler.liveness.evaluate(Instant.now(), timeout);

                switch (verdict.state()) {
                    case ALIVE -> state.websocketConnected(platformName);
                    case SILENT -> {
                        String silence = formatSilence(verdict.silence());
                        log.warn("推送平台 {} 的 OneBot Websocket 已 {} 未收到任何数据帧（含心跳）, 连接很可能已失效", platformName, silence);
                        // 告警统一由健康探针与 HealthAlertMonitor 发出，此处只记录状态
                        state.websocketDisconnected(platformName, "已 " + silence + " 未收到任何数据帧（含心跳）");
                    }
                    case NO_HEARTBEAT -> warnNoHeartbeat(platformName);
                }
            }), Instant.now().plus(CHECK_INTERVAL), CHECK_INTERVAL);
        }
    }

    /**
     * 停止某一代连接的存活检测
     * <p>
     * 连接断开后必须停掉：检测任务持有的是旧连接的计时器，留着会在重连期间按旧数据把状态改回「已连接」，
     * 覆盖掉「正在重连」的真实状态。
     * <p>
     * 🔴 挂在<b>这一代</b>上而不是按平台名记一份：换地址时旧连接的关闭回调常常晚于新连接建立，
     * 按平台名去停，停掉的会是刚刚挂好的那一个——而它此后再也不检测，界面上却一切正常。
     * @param connection 这一代连接
     */
    private void stopDetect(Connection connection) {
        stopDetect(connection, null);
    }

    /**
     * 停止某一代连接上由某条会话挂的存活检测
     * <p>
     * 同一代里重连出的新会话会重新挂一个检测。旧会话的关闭回调常常晚于新会话挂好检测才到，
     * 这时它不能去停新会话的那一个：停了的话新会话此后再也不检测，界面上却一切正常。
     * @param connection 这一代连接
     * @param owner 只停这条会话挂的；为 {@code null} 时不论是谁挂的都停
     */
    private void stopDetect(Connection connection, OneBotWebSocketHandler owner) {
        synchronized (connection) {
            ScheduledFuture<?> previous = connection.detectTask;
            if (previous == null || (owner != null && connection.detectOwner != owner)) {
                return;
            }
            previous.cancel(false);
            connection.detectTask = null;
            connection.detectOwner = null;
        }
    }

    /**
     * 提示该实现未推送心跳
     * <p>
     * 每个周期都喊一遍只会淹没日志，这里每个推送平台只提示一次。
     */
    private void warnNoHeartbeat(String platformName) {
        if (noHeartbeatWarned.add(platformName)) {
            log.warn("推送平台 {} 的 OneBot 实现未推送心跳, 无法据此判断长连接是否已静默失效; " +
                    "请在 OneBot 实现中开启心跳（NapCat 为 heartInterval），否则只能依赖 HTTP 检测", platformName);
        }
    }

    /**
     * 把静默时长写成人话
     */
    private String formatSilence(Duration silence) {
        long minutes = silence.toMinutes();
        return minutes > 0 ? minutes + " 分钟" : silence.toSeconds() + " 秒";
    }

    /**
     * WebSocket 处理器
     */
    private static class OneBotWebSocketHandler implements WebSocketHandler {
        private final OneBotWebsocketService service;

        private final Connection connection;

        private final OneBotSender sender;

        private final ThreadPoolTaskExecutor executor;

        private final CountDownLatch latch = new CountDownLatch(1);

        /**
         * 正在拼的那一条消息
         * <p>
         * 🔴 只在 I/O 线程上动。线程池那边拿到的是拼好之后的一份拷贝，
         * 到它跑的时候这里多半已经在拼下一条了。
         */
        private final StringBuilder messageBuffer = new StringBuilder();

        /**
         * 这条连接已因一条消息过长而断开，超限那条消息剩余的分片一概不收
         * <p>
         * 断开要等对端回话才真断，这期间还会有分片进来，接着拼只会拼出半条来路不明的消息。
         * 断开那一下要是抛了异常，连接会一直挂着：等到这条消息的最后一片（isLast）到了，
         * 就把这一位复位，下一条消息照常收——不复位的话，此后的消息会永远丢下去。
         */
        private boolean oversized = false;

        private boolean connectTimeout = false;

        private Boolean tokenVerify = null;

        /**
         * 是否已就「上报里没有 self_id」报过一次
         */
        private boolean selfIdWarned = false;

        private final OneBotLivenessTracker liveness = new OneBotLivenessTracker(Instant.now());

        private OneBotWebSocketHandler(OneBotWebsocketService service, Connection connection) {
            this.service = service;
            this.connection = connection;
            this.sender = connection.sender;
            this.executor = service.executor;
        }

        /**
         * 等待 WebSocket 连接成功
         * @return 连接是否成功
         */
        public boolean awaitConnection() {
            synchronized (this) {
                try {
                    if (latch.await(3, TimeUnit.SECONDS)) {
                        return true;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                connectTimeout = true;
                return false;
            }
        }

        /**
         * 连接建立
         * @param session WebSocket 会话
         */
        @Override
        public void afterConnectionEstablished(@NonNull WebSocketSession session) {
            connection.session = session;
            latch.countDown();

            synchronized (this) {
                // 这一代已经被换掉时同样要关：断旧连新那一刻这个握手可能正在路上，
                // 关不掉的话，旧地址上会留着一条谁也不认识、却照常收消息的连接
                if (connectTimeout || connection.retired) {
                    try {
                        session.close();
                    } catch (Exception e) {
                        log.error("断开 {} 的超时 OneBot Websocket 服务异常", sender.getName(), e);
                    }
                    return;
                }
            }

            connection.handler = this;
            // 连上了，这一段重连到此为止；之后再断才起下一段
            connection.outage.set(null);
            service.state.websocketConnected(sender.getName());
            // 连上那一刻就补做一次体检，不等下一个周期：刚扫码登录完的人
            // 看着没变的旧读数，会以为扫码没成
            executor.submit(() -> {
                service.httpService.check(sender);
                log.info("已连接到 {} 的 OneBot Websocket 服务", sender.getName());
            });
        }

        /**
         * 消息处理
         * @param session WebSocket 会话
         * @param webSocketRawMessage WebSocket 消息
         */
        @Override
        public void handleMessage(@NonNull WebSocketSession session, @NonNull WebSocketMessage<?> webSocketRawMessage) {
            // 收到任何东西都说明链路是通的，因此在解析之前就先计时：内容能不能解析、是不是聊天消息，
            // 与「连接还活着吗」是两个问题
            liveness.frameReceived(Instant.now());

            try {
                if (webSocketRawMessage instanceof TextMessage webSocketMessage) {
                    if (oversized) {
                        // 超限那条消息剩下的分片照旧一概不收。它的最后一片（isLast）到了就把
                        // oversized 复位：断开那一下要是抛了异常，连接其实还挂着，不复位的话
                        // 这条连接此后只会永远丢消息——看着活着，实际已经聋了。超限的那一片
                        // 本身就是最后一片时，丢弃那一下已经当场复位过了（见 dropOversizedMessage）
                        if (webSocketMessage.isLast()) {
                            oversized = false;
                        }
                        return;
                    }

                    String payload = webSocketMessage.getPayload();
                    long total = (long) messageBuffer.length() + payload.length();
                    if (total > MAX_MESSAGE_CHARS) {
                        dropOversizedMessage(session, total, webSocketMessage.isLast());
                        return;
                    }
                    messageBuffer.append(payload);

                    if (webSocketMessage.isLast()) {
                        String fullMessage = messageBuffer.toString();
                        messageBuffer.setLength(0);

                        executor.submit(() -> {
                            // 排队期间这一代可能已被换掉或停用：那之后它的消息一概不处理，
                            // 否则旧连接里排着的一条会被当成指令执行，或把新连接的状态改掉
                            if (connection.retired) {
                                return;
                            }
                            try {
                                JSONObject rawMessage = JSON.parseObject(fullMessage);
                                if (tokenVerify == null) {
                                    if ("1403".equals(rawMessage.getString("retcode"))) {
                                        tokenVerify = false;
                                        log.error("{} 的 OneBot Websocket Token 配置不正确, 将无法处理消息, 请检查 Token 配置", sender.getName());
                                    }
                                    if ("meta_event".equals(rawMessage.getString("post_type")) && "lifecycle".equals(rawMessage.getString("meta_event_type")) && "connect".equals(rawMessage.getString("sub_type"))) {
                                        tokenVerify = true;
                                        log.info("{} 的 OneBot Websocket Token 认证成功", sender.getName());

                                        if (service.properties.getDetect().isEnableWebsocketDetect()) {
                                            service.startDetect(this);
                                        }
                                    }
                                }

                                if ("meta_event".equals(rawMessage.getString("post_type"))
                                        && "heartbeat".equals(rawMessage.getString("meta_event_type"))) {
                                    handleHeartbeat(rawMessage);
                                }

                                // 被踢下线时实现会发一条通知。接口与进程都还在，靠轮询最长要等一个检测周期才发现
                                if ("notice".equals(rawMessage.getString("post_type"))
                                        && "bot_offline".equals(rawMessage.getString("notice_type"))) {
                                    handleBotOffline(rawMessage);
                                }

                                if ("message".equals(rawMessage.getString("post_type"))
                                        && StringUtil.isNotBlank(rawMessage.getString("raw_message"))) {
                                    String messageType = rawMessage.getString("message_type");
                                    Long num = "group".equals(messageType)
                                            ? rawMessage.getLong("group_id")
                                            : rawMessage.getLong("user_id");
                                    // sender.role 取值为 owner / admin / member，管理类命令据此判权限。
                                    // 私聊没有群角色一说，取不到即为空
                                    JSONObject messageSender = rawMessage.getJSONObject("sender");
                                    String senderRole = messageSender == null ? null : messageSender.getString("role");

                                    warnOnceIfSelfIdMissing(rawMessage, messageType);
                                    OneBotIncomingMessage incoming = OneBotIncomingMessage.of(rawMessage);

                                    service.publisher.publishEvent(new NovaRemoteMessageEvent(
                                            sender.getName(), messageType, num,
                                            rawMessage.getLong("user_id"), incoming.text(),
                                            senderRole, incoming.mentionsBot()));
                                }
                            } catch (Exception e) {
                                // 不在这里清拼接缓冲：这条自己的那份早在 I/O 线程上取走了，
                                // 此刻缓冲里是下一条的前半截，清掉它下一条就只剩后半截
                                log.error("处理 {} 的 OneBot Websocket 消息时发生异常", sender.getName(), e);
                            }
                        });
                    }
                }
            } catch (Exception e) {
                log.error("处理 {} 的 OneBot Websocket 分片消息发生异常", sender.getName(), e);
                messageBuffer.setLength(0);
            }
        }

        /**
         * 一条消息拼到超过上限：丢掉已拼的部分，断开这条连接
         * <p>
         * 断开之后照连接断开的老路重连（{@link #afterConnectionClosed}），这里不另起重连。
         * 日志只写累计多长，不写内容：内容来自对端，可能是任何东西。
         * @param session WebSocket 会话
         * @param total 算上刚到的这一片，累计多少个字符
         * @param lastFragment 这一片是不是那条消息的最后一片
         */
        private void dropOversizedMessage(WebSocketSession session, long total, boolean lastFragment) {
            oversized = true;
            messageBuffer.setLength(0);
            // 不清的话这一大块要等这个处理器被回收才还
            messageBuffer.trimToSize();
            log.warn("{} 的 OneBot Websocket 一条消息的分片累计已达 {} 个字符, 超过上限 {}, 已丢弃并断开连接, 随后将重新连接",
                    sender.getName(), total, MAX_MESSAGE_CHARS);
            try {
                session.close(CloseStatus.TOO_BIG_TO_PROCESS);
            } catch (Exception e) {
                log.warn("断开 {} 的 OneBot Websocket 连接异常", sender.getName(), e);
            }
            if (lastFragment) {
                // 超限的这一片就是那条消息的最后一片：消息已经收完，之后再来的都是新消息。
                // 断开没断成时连接还挂着，不复位的话下一条整条会被当成这条的剩余分片丢掉
                oversized = false;
            }
        }

        /**
         * 群消息里缺 {@code self_id} 时报一次
         * <p>
         * 群聊命令要靠「@ 的是不是我」来判，而这一判需要知道自己的账号。缺了它，
         * <b>全部群聊命令会一起失灵，且不报任何错</b>——机器人只是从此不再应答，
         * 谁也说不上来是从哪一刻起变成这样的。所以在这里响一声；
         * 每条连接只响一次，免得把日志刷满。
         * @param rawMessage 收到的消息事件
         * @param messageType 消息类型
         */
        private void warnOnceIfSelfIdMissing(JSONObject rawMessage, String messageType) {
            if (selfIdWarned || !"group".equals(messageType) || rawMessage.getLong("self_id") != null) {
                return;
            }
            selfIdWarned = true;
            log.warn("{} 上报的群消息里没有 self_id, 无法判断消息是否 @ 了机器人, 群聊命令将不会响应", sender.getName());
        }

        /**
         * 处理心跳
         * <p>
         * 心跳除了证明链路还在，还顺带捎了一份 {@code status.online}：账号掉线时 OneBot 实现本身一切正常，
         * 接口照常返回，只是消息谁也收不到。靠这个字段能在半分钟内发现，而定时轮询接口最长要等一个检测周期。
         * @param rawMessage 心跳消息
         */
        private void handleHeartbeat(JSONObject rawMessage) {
            Long interval = rawMessage.getLong("interval");
            liveness.heartbeatReceived(Instant.now(), interval == null ? 0L : interval);

            JSONObject status = rawMessage.getJSONObject("status");
            Boolean online = status == null ? null : status.getBoolean("online");
            if (online == null) {
                // 字段缺失只说明这个实现不上报，不能据此断定账号掉线
                return;
            }
            if (connection.retired) {
                return;
            }

            if (online) {
                service.state.accountOnline(sender.getName(), "在线");
            } else {
                service.state.accountOffline(sender.getName(), "QQ 账号已掉线");
            }
        }

        /**
         * 处理被踢下线的通知
         * <p>
         * 走的是与定时体检查到 {@code online=false} 同一个状态入口，健康页、时间线与告警照现有路子反应。
         * 原因写进状态说明：光说「掉线」，看的人不知道该重新扫码还是去查网络。
         * @param rawMessage 通知消息
         */
        private void handleBotOffline(JSONObject rawMessage) {
            if (connection.retired) {
                return;
            }
            String reason = rawMessage.getString("reason");
            String detail = StringUtil.isBlank(reason) ? "QQ 账号已掉线" : "QQ 账号已掉线（" + reason + "）";
            log.warn("{} 的 QQ 账号被踢下线: {}", sender.getName(), detail);
            service.state.accountOffline(sender.getName(), detail);
        }

        /**
         * 传输错误
         * @param session WebSocket 会话
         * @param exception 异常
         */
        @Override
        public void handleTransportError(@NonNull WebSocketSession session, @NonNull Throwable exception) {
            // 这一代已换上了新会话时，旧会话晚到的回调不再起重连
            if (connection.retired || connection.handler != this) {
                return;
            }

            if (service.reconnect(connection, RECONNECT_DELAY)) {
                log.warn("与 {} 的 Websocket 连接异常, 将在 1 秒后重新连接", sender.getName(), exception);
            } else {
                log.warn("与 {} 的 Websocket 连接异常, 已在重连", sender.getName(), exception);
            }
        }

        /**
         * 连接关闭
         * @param session WebSocket 会话
         * @param closeStatus 关闭状态
         */
        @Override
        public void afterConnectionClosed(@NonNull WebSocketSession session, @NonNull CloseStatus closeStatus) {
            // 存活检测认的是这个连接的计时器，连接没了就得停，否则它会拿旧数据把状态改回「已连接」。
            // 只停这条会话自己挂的：同一代里重连出的新会话可能已经挂好了它的那一个
            service.stopDetect(connection, this);

            // 这一代是被主动换掉的：既不重连，也不写连接状态。写了的话，
            // 一条已经作废的连接会拿它的死讯覆盖新连接刚记下的「已连接」。
            // 同一代里已换上新会话时同理，旧会话晚到的关闭回调什么也不做
            if (connectTimeout || connection.retired || connection.handler != this) {
                return;
            }

            if (Boolean.FALSE.equals(tokenVerify)) {
                service.state.websocketDisconnected(sender.getName(), "Token 校验失败");
                return;
            }

            service.state.websocketDisconnected(sender.getName(),
                    "连接断开（" + closeStatus.getCode() + "），正在重连");

            if (service.reconnect(connection, RECONNECT_DELAY)) {
                log.warn("与 {} 的 Websocket 连接断开 ({}: {}), 将在 1 秒后重新连接", sender.getName(), closeStatus.getCode(), closeStatus.getReason());
            }
        }

        /**
         * 是否支持部分消息
         * @return 是否支持部分消息
         */
        @Override
        public boolean supportsPartialMessages() {
            return true;
        }
    }
}
