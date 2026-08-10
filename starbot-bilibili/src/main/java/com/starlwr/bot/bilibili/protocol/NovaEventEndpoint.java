package com.starlwr.bot.bilibili.protocol;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONException;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 事件输出协议 v2 的 WebSocket 端点
 * <p>
 * 握手后先发 {@code hello}，随后把 {@link NovaEventStream} 的实时流转发给客户端，
 * 并按协议维持 15 秒一次的 {@code ping}。
 *
 * <h2>为什么握手后不立刻推实时流</h2>
 * 协议规定「服务端补发缺口后转入实时推送」，也就是说 {@code resume} 必须发生在实时流之前。
 * 若握手完就开推，客户端随后发来的 {@code resume} 只能把旧消息补在新消息<b>后面</b>，
 * seq 就不再递增了——而「严格递增」正是下游拿来去重的唯一依据。
 * <p>
 * 所以这里留出一个 {@value #RESUME_GRACE_MS} 毫秒的窗口：想回补的客户端在这段时间内
 * 发 {@code resume}，其余情况窗口一到就转入实时流。窗口期内产生的消息<b>不会丢</b>——
 * 转入实时流时是从握手那一刻的序号接着补的，而缓冲窗口远长于这两秒。
 * <p>
 * <b>协议文档没有写这个窗口</b>，它是我们为了让「先回补后实时」可实现而定的服务端约定。
 * 客户端若在窗口之后才发 {@code resume}，会收到一份新的 {@code hello}，按它重来即可。
 *
 * <h2>每个客户端一条发送队列</h2>
 * 事件是在采集线程上分发的，<b>绝不能让一个读得慢的客户端把采集线程堵住</b>——
 * 阻塞在 socket 写上没有超时可言，堵住的是整条弹幕流水线。
 * 所以每个连接自带一条有界队列与一个发送线程，分发侧只做入队。
 * 队列满说明这个客户端已经跟不上，<b>断开它</b>而不是丢消息：断开后它重新握手时
 * 能从 {@code hello} 看到缺口，而丢消息是它永远不会知道的。
 *
 * <h2>只接受回环连接</h2>
 * 拦截在握手阶段（见 {@link NovaEventStreamConfiguration}），<b>与 server.address 无关</b>：
 * 即使运维为了对外提供推送接口把监听地址改成 0.0.0.0，事件流也只认来自本机的连接。
 * 跨机访问请自行建立 SSH 隧道。
 */
@Slf4j
public class NovaEventEndpoint extends TextWebSocketHandler {
    /**
     * 心跳间隔，单位：毫秒。与协议的 PING_INTERVAL_MS 对齐，改动前先改协议
     */
    private static final long PING_INTERVAL_MS = 15_000;

    /**
     * 客户端多久没回 {@code pong} 即视为已死，单位：毫秒
     * <p>
     * 取三个心跳周期。协议只规定了客户端侧的 40 秒判据，服务端这侧不清理的话，
     * 断电、崩溃这类不发 FIN 的客户端会一直挂在订阅列表里。
     */
    private static final long CLIENT_TIMEOUT_MS = 3 * PING_INTERVAL_MS;

    /**
     * 握手后等待 {@code resume} 的窗口，单位：毫秒
     */
    static final long RESUME_GRACE_MS = 2_000;

    /**
     * 单个连接的发送队列在缓冲窗口之外额外留的余量，单位：条
     * <p>
     * 回补时可能一次性塞进整个缓冲窗口，队列必须装得下，否则刚补上就因为队列满被断开。
     */
    private static final int OUTBOX_HEADROOM = 256;

    /**
     * 源标识，写在 {@code hello} 里供客户端识别数据来源
     */
    private static final String SOURCE = "novabot";

    /**
     * 本源能提供的事件集
     * <p>
     * 缺项的语义是「展示层隐藏对应 UI」，所以这里只列真的会推的 kind。
     */
    private static final List<String> CAPABILITIES = List.of(
            "danmaku", "superchat", "gift", "guard", "enter", "follow", "share", "like", "room_stat");

    /**
     * 能力集之外、需要下游知道的口径差异
     * <p>
     * <b>这是协议正文之外新增的可选字段</b>，按协议 §6「新增可选字段不算破坏性变更、
     * 客户端必须忽略不认识的字段」的规定加在 {@code hello.data} 上。
     * 放进报文而不是只写在文档里，是因为口径差异要在运行时能被看见——
     * 对着连接排查的人不会先去翻我们的源码。
     */
    private static final List<String> NOTES = List.of(
            "like: 未聚合。每次点赞单独下发一条、count 恒为 1，协议注释所说的「源侧已按单用户短窗口聚合」尚未实现",
            "live_state: 只在开播、下播、标题变更时下发，没有中途接入的快照。客户端接上时若还没收到过，说明我们也还不知道",
            "source_state: disconnected 只在主播被移出数据源时下发。房间还在监听列表里时我们一直重连，那种情况一律是 reconnecting",
            "user.isAdmin: 只有弹幕消息带房管标志，其余消息恒为 false，含义是「这条消息没说」而不是「不是房管」",
            // 这一条是照着一次真实误读补的：下游按 emoji != null 判断「这条带表情」，
            // 于是内联表情弹幕全被漏掉。口径差异写在这里才能在排查时被看见
            "danmaku.emoji: 只在「整条弹幕就是一张表情图」时非空。判断「这条带表情」要读 inlineEmojis，"
                    + "按 emoji != null 判会漏掉全部内联表情弹幕",
            "rawJson: 协议之外的附加字段，为平台原始报文。字段随平台改版而变，不要当契约用");

    private final NovaEventStream stream;

    /**
     * 单个连接的发送队列容量，单位：条
     */
    private final int outboxCapacity;

    private final Map<String, Client> clients = new ConcurrentHashMap<>();

    /**
     * 心跳线程。只做定时，不做发送
     */
    private final ScheduledExecutorService heartbeats =
            Executors.newSingleThreadScheduledExecutor(daemon("nova-event-heartbeat"));

    /**
     * 每个连接一个发送线程
     */
    private final ExecutorService senders = Executors.newCachedThreadPool(daemon("nova-event-sender"));

    private volatile boolean running = true;

    public NovaEventEndpoint(NovaEventStream stream) {
        this.stream = stream;
        this.outboxCapacity = stream.getCapacity() + OUTBOX_HEADROOM;
        heartbeats.scheduleAtFixedRate(this::heartbeat, PING_INTERVAL_MS, PING_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private static ThreadFactory daemon(String name) {
        AtomicInteger index = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, name + "-" + index.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    /**
     * 单个客户端连接
     */
    private final class Client implements NovaEventStream.Subscriber {
        private final WebSocketSession session;

        /**
         * 握手时的最大序号。转入实时流时从它接着补，窗口期内的消息因此不会丢
         */
        private final long helloSeq;

        private final BlockingQueue<String> outbox = new ArrayBlockingQueue<>(outboxCapacity);

        private volatile boolean live = false;

        private volatile boolean closed = false;

        private volatile long lastSeenAt = System.currentTimeMillis();

        private ScheduledFuture<?> grace;

        private Client(WebSocketSession session, long helloSeq) {
            this.session = session;
            this.helloSeq = helloSeq;
        }

        @Override
        public void onFrame(NovaEventStream.Frame frame) {
            send(frame.json());
        }

        /**
         * 入队一条消息
         * <p>
         * <b>只入队，不发送。</b> 调用方可能是采集线程，也可能正持着事件流的锁。
         */
        private void send(String json) {
            if (closed) {
                return;
            }
            if (!outbox.offer(json)) {
                log.warn("事件流客户端 {} 消费不过来, 队列已满 {} 条, 断开连接", session.getId(), outboxCapacity);
                close(CloseStatus.SERVICE_OVERLOAD.withReason("客户端消费过慢"));
            }
        }

        /**
         * 发送循环，独占一个线程
         * <p>
         * 用带超时的 poll 而不是 take：连接关掉时不必再往队列里塞哨兵去唤醒它，
         * 最多多等一个周期就自己退出。
         */
        private void pump() {
            while (!closed) {
                String json;
                try {
                    json = outbox.poll(1, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }

                if (json == null) {
                    continue;
                }

                try {
                    session.sendMessage(new TextMessage(json));
                } catch (IOException | IllegalStateException e) {
                    // 发不出去就是这条连接没救了。这里只记 debug: 关掉面板是常事，
                    // 每次都打 warn 只会淹没真正的问题
                    log.debug("事件流向 {} 发送失败, 关闭该连接", session.getId(), e);
                    close(CloseStatus.SERVER_ERROR);
                    return;
                }
            }
        }

        /**
         * 处理 {@code resume}
         * @param fromSeq 客户端已持有的最大序号
         */
        private synchronized void resume(long fromSeq) {
            if (grace != null) {
                grace.cancel(false);
            }

            if (live) {
                // 已经在推实时流了，此时再补旧消息会让 seq 不再递增。
                // 重发 hello，客户端据此自行判断要不要重来
                send(hello());
                log.info("事件流客户端 {} 在转入实时流后才发 resume, 已重发 hello", session.getId());
                return;
            }
            goLive(fromSeq);
        }

        /**
         * 转入实时流
         * @param fromSeq 从此序号之后开始补
         */
        private synchronized void goLive(long fromSeq) {
            if (live || closed) {
                return;
            }
            live = true;

            if (stream.subscribe(this, fromSeq)) {
                return;
            }

            // 缺口补不上。重发一份 hello 让客户端看到新的窗口自行重置，然后从当下开始推。
            // 快照与订阅之间万一又产生了新消息，subscribe 会从缓冲里把它补上，不会漏
            NovaEventStream.State state = stream.snapshot();
            send(hello(state));
            stream.subscribe(this, state.lastSeq());
            log.info("事件流客户端 {} 请求的 seq {} 已超出缓冲窗口, 已要求其重新握手", session.getId(), fromSeq);
        }

        private void close(CloseStatus status) {
            if (closed) {
                return;
            }
            closed = true;

            stream.unsubscribe(this);
            outbox.clear();

            try {
                session.close(status);
            } catch (IOException | IllegalStateException e) {
                log.debug("关闭事件流客户端 {} 失败", session.getId(), e);
            }
        }
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        if (!running) {
            return;
        }

        // helloSeq 与 hello 里的 lastSeq 必须是同一个数: 转入实时流时从它接着补，
        // 两者不一致就意味着客户端要么缺一条要么重一条
        NovaEventStream.State state = stream.snapshot();
        Client client = new Client(session, state.lastSeq());
        clients.put(session.getId(), client);

        senders.execute(client::pump);
        client.send(hello(state));
        client.grace = heartbeats.schedule(() -> client.goLive(client.helloSeq), RESUME_GRACE_MS, TimeUnit.MILLISECONDS);

        log.info("事件流客户端 {} 已连接, 当前连接数 {}", session.getId(), clients.size());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        Client client = clients.get(session.getId());
        if (client == null) {
            return;
        }
        client.lastSeenAt = System.currentTimeMillis();

        JSONObject request;
        try {
            request = JSON.parseObject(message.getPayload());
        } catch (JSONException e) {
            log.debug("事件流客户端 {} 发来的不是合法 JSON, 已忽略", session.getId());
            return;
        }
        if (request == null) {
            return;
        }

        String kind = request.getString("kind");
        if ("resume".equals(kind)) {
            JSONObject data = request.getJSONObject("data");
            Long fromSeq = data == null ? null : data.getLong("fromSeq");
            if (fromSeq == null) {
                log.debug("事件流客户端 {} 的 resume 缺少 fromSeq, 已忽略", session.getId());
                return;
            }
            client.resume(fromSeq);
            return;
        }

        // pong 只需要刷新存活时间，上面已经刷过了；其余 kind 一律忽略——
        // 协议规定不认识的消息要忽略而不是断连
        if (!"pong".equals(kind)) {
            log.debug("事件流客户端 {} 发来了未知的 kind {}, 已忽略", session.getId(), kind);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Client client = clients.remove(session.getId());
        if (client == null) {
            return;
        }
        if (client.grace != null) {
            client.grace.cancel(false);
        }
        client.closed = true;
        stream.unsubscribe(client);
        client.outbox.clear();

        log.info("事件流客户端 {} 已断开 ({}), 当前连接数 {}", session.getId(), status.getCode(), clients.size());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.debug("事件流客户端 {} 传输异常", session.getId(), exception);

        Client client = clients.get(session.getId());
        if (client != null) {
            client.close(CloseStatus.SERVER_ERROR);
        }
    }

    /**
     * 关停端点，断开全部连接并停掉线程
     */
    public void shutdown() {
        running = false;
        for (Client client : clients.values()) {
            client.close(CloseStatus.GOING_AWAY);
        }
        clients.clear();
        heartbeats.shutdownNow();
        senders.shutdownNow();
    }

    /**
     * @return 当前连接数
     */
    public int getClientCount() {
        return clients.size();
    }

    /**
     * 心跳：给每个客户端发 {@code ping}，并清理已经不回话的连接
     */
    private void heartbeat() {
        long now = System.currentTimeMillis();
        for (Client client : clients.values()) {
            if (now - client.lastSeenAt > CLIENT_TIMEOUT_MS) {
                log.info("事件流客户端 {} 超过 {} 毫秒未回应, 已断开", client.session.getId(), CLIENT_TIMEOUT_MS);
                client.close(CloseStatus.POLICY_VIOLATION.withReason("未按协议回应心跳"));
                continue;
            }
            client.send(control("ping"));
        }
    }

    private String hello() {
        return hello(stream.snapshot());
    }

    /**
     * 组装 {@code hello}
     * @param state 流状态快照。{@code lastSeq} 与 {@code bufferedFrom} 必须取自同一瞬间，
     *              否则客户端会拿两个不同时刻的数去算能不能回补
     * @return JSON 文本
     */
    private String hello(NovaEventStream.State state) {
        JSONObject data = new JSONObject();
        data.put("sessionId", stream.getSessionId());
        data.put("source", SOURCE);
        data.put("capabilities", CAPABILITIES);
        data.put("lastSeq", state.lastSeq());
        data.put("bufferedFrom", state.bufferedFrom());
        data.put("notes", NOTES);

        JSONObject envelope = new JSONObject();
        envelope.put("v", NovaEventMapper.PROTOCOL_VERSION);
        envelope.put("kind", "hello");
        envelope.put("data", data);
        return envelope.toString(JSONWriter.Feature.WriteNulls);
    }

    /**
     * 组装不带 data 的控制类消息
     * @param kind 消息类型
     * @return JSON 文本
     */
    private String control(String kind) {
        JSONObject envelope = new JSONObject();
        envelope.put("v", NovaEventMapper.PROTOCOL_VERSION);
        envelope.put("kind", kind);
        return envelope.toString(JSONWriter.Feature.WriteNulls);
    }
}
