package com.starlwr.bot.bilibili.protocol;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONException;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.starlwr.bot.core.service.EventStreamTokenService;
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
import java.util.concurrent.RejectedExecutionException;
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
     * 开着口令时，连上之后等 {@code auth} 帧的窗口，单位：毫秒
     * <p>
     * 超时即断。<b>不能不设</b>：认证挪到首帧之后，「已连上但没认证」成了一个真实存在的态，
     * 不设上限的话，任何人都能连上来一直挂着不发东西，占着连接与线程。
     * <p>
     * 取 10 秒而不是更短：客户端连上就发这一帧，正常情况远用不到，
     * 但网络抖动或客户端刚启动时慢一拍不该被误杀。
     */
    static final long AUTH_TIMEOUT_MS = 10_000;

    /**
     * 关停时留给发送线程发完**已排队关闭帧**的宽限
     * <p>
     * 不是等所有活干完：写不动的那条连接本来就发不出去，等它没有意义。
     */
    static final long SHUTDOWN_DRAIN_MS = 200;

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

    /**
     * 时限组
     * <p>
     * 生产恒为 {@link #DEFAULT}。开这个口子<b>不是修复</b>，也不改任何生产行为——
     * 它只让测试把秒换成毫秒：判据要量的是「慢消费者在场时 ping／认证闸／转实时流还走不走」，
     * 而按 15 秒的真周期跑，一条判据要等一分多钟。🔴 <b>等不起的判据最后不会有人跑。</b>
     *
     * @param pingInterval  心跳间隔
     * @param clientTimeout 多久不回话就断开
     * @param authTimeout   未认证超时
     * @param resumeGrace   回补窗口
     */
    record Timings(long pingInterval, long clientTimeout, long authTimeout, long resumeGrace) {
        static final Timings DEFAULT =
                new Timings(PING_INTERVAL_MS, CLIENT_TIMEOUT_MS, AUTH_TIMEOUT_MS, RESUME_GRACE_MS);
    }

    private final NovaEventStream stream;

    private final Timings timings;

    /**
     * 单个连接的发送队列容量，单位：条
     */
    private final int outboxCapacity;

    private final Map<String, Client> clients = new ConcurrentHashMap<>();

    /**
     * 心跳线程：全局定时器
     * <p>
     * 它<b>不做发送</b>，但它承接三类定时任务：周期性的 {@link #heartbeat()}、
     * 每条连接的未认证超时（{@code closeUnauthenticated}）、以及回补窗口到点后的
     * {@code goLive}。后两类是<b>某一条连接</b>的状态迁移，跑在这条<b>全局共享</b>的线程上。
     * <p>
     * 🔴 <b>只有一条线程</b>，所以派到这里的任务里<b>不许有任何等得没边的东西</b>——
     * 一旦有一个任务等在某条连接的锁上，全局 ping、别人的认证闸、别人的 goLive 一起停。
     * 参见 {@link Client#writeDirect} 为什么用自己的锁而不是这个对象的监视器。
     */
    private final ScheduledExecutorService heartbeats =
            Executors.newSingleThreadScheduledExecutor(daemon("nova-event-heartbeat"));

    /**
     * 每个连接一个发送线程
     */
    private final ExecutorService senders = Executors.newCachedThreadPool(daemon("nova-event-sender"));

    private volatile boolean running = true;

    /**
     * 只读口令服务。不要求口令时为 {@code null}
     */
    private final EventStreamTokenService tokens;

    public NovaEventEndpoint(NovaEventStream stream) {
        this(stream, null);
    }

    /**
     * @param stream 事件流
     * @param tokens 只读口令服务。传 {@code null} 表示不要求口令，连上即开始推
     */
    public NovaEventEndpoint(NovaEventStream stream, EventStreamTokenService tokens) {
        this(stream, tokens, Timings.DEFAULT);
    }

    /**
     * @param timings 时限组。生产不走这个构造器，见 {@link Timings}
     */
    NovaEventEndpoint(NovaEventStream stream, EventStreamTokenService tokens, Timings timings) {
        this.stream = stream;
        this.tokens = tokens;
        this.timings = timings;
        this.outboxCapacity = stream.getCapacity() + OUTBOX_HEADROOM;
        heartbeats.scheduleAtFixedRate(this::heartbeat,
                timings.pingInterval(), timings.pingInterval(), TimeUnit.MILLISECONDS);
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

        /**
         * 是否已通过认证
         * <p>
         * 不要求口令时构造即为 {@code true}。要求口令时，<b>在收到合法 {@code auth} 帧之前，
         * 这条连接拿不到 hello、拿不到事件、也不参与心跳</b>——
         * 「已连上」与「可以看数据」是两件事。
         */
        private volatile boolean authenticated;

        /**
         * 口令已经被判过一次不合格。用来挡住「两帧同时来、两条都去写 auth_failed」——
         * 从前靠 {@code close} 在监视器里先把 {@code closed} 立起来挡，
         * 而现在写与关都在监视器外面。
         */
        private volatile boolean authRejected;

        /**
         * 写锁
         * <p>
         * 🔴 <b>写要串起来，但不能用这个对象的监视器串。</b>
         * {@code sendMessage} 是阻塞写，客户端不读就一直不返回；而 {@code goLive} 与
         * {@code closeUnauthenticated} 是 synchronized，且跑在<b>全局共享的心跳线程</b>上。
         * 两者共用监视器的话，一个写不动的客户端就把心跳线程钉死在自己的锁上——
         * 全局 ping 停发、别人的认证闸不触发、别人的 goLive 不执行。
         * 所以写自己拿一把锁：<b>锁不跨阻塞写</b>。
         */
        private final Object writeMutex = new Object();

        private ScheduledFuture<?> grace;

        /**
         * 未认证超时。认证通过或连接关闭时取消
         */
        private ScheduledFuture<?> authDeadline;

        private Client(WebSocketSession session, long helloSeq, boolean authenticated) {
            this.session = session;
            this.helloSeq = helloSeq;
            this.authenticated = authenticated;
        }

        /**
         * 未认证超时到点
         * <p>
         * 与「口令错」区分开：这里<b>不发 {@code auth_failed}</b>，因为没人出示过任何东西。
         * 客户端看到的是一个带原因的正常关闭，而不是一个无声的断开。
         */
        private synchronized void closeUnauthenticated() {
            if (authenticated || closed) {
                return;
            }
            log.info("事件流客户端 {} 连上后 {} 毫秒内未发认证帧, 已断开", session.getId(), timings.authTimeout());
            close(CloseStatus.POLICY_VIOLATION.withReason("未在时限内认证"));
        }

        /**
         * 处理 {@code auth} 帧
         * <p>
         * 成功就走与「不要求口令时连上」完全相同的那条路：发 hello、开回补窗口。
         * 失败则先<b>同步</b>把 {@code auth_failed} 写出去再关连接——
         * 客户端要能分清「口令错」和「数据源没起」，这一位就是全部区别。
         * <p>
         * 🔴 <b>判与写分两段</b>：判在监视器里（含「拒过没有」这一位），
         * 写与关在监视器<b>外面</b>——{@code writeDirect} 是阻塞写，而这条连接的
         * {@code closeUnauthenticated} 正跑在<b>全局共享的心跳线程</b>上等同一个监视器。
         * 「先同步写完再关」一点没变，变的只是这两步持不持锁。
         */
        private void authenticate(String presented) {
            String reason;
            synchronized (this) {
                if (authenticated || closed || authRejected) {
                    // 重复发 auth 直接忽略：已经认过的连接不该被第二帧改变状态
                    return;
                }

                EventStreamTokenService.Verdict verdict = tokens.check(presented);
                if (verdict == EventStreamTokenService.Verdict.OK) {
                    authenticated = true;
                    if (authDeadline != null) {
                        authDeadline.cancel(false);
                    }

                    NovaEventStream.State state = stream.snapshot();
                    send(hello(state));
                    grace = heartbeats.schedule(() -> goLive(state.lastSeq()), timings.resumeGrace(), TimeUnit.MILLISECONDS);
                    log.info("事件流客户端 {} 已通过口令认证", session.getId());
                    return;
                }

                // 只在监视器里定下「拒了、拒的是哪一种」，写与关都挪到外面
                authRejected = true;
                reason = verdict.wire();
            }

            // 🔴 这一句是**阻塞写**，不许在监视器里做：客户端不读的话它一直不返回，
            //    而同一条连接的 closeUnauthenticated 正等在共享心跳线程上要这个监视器。
            //    仍然是同步写完再关——「说清为什么失败」这件事一点没变，变的只是持不持锁。
            JSONObject envelope = new JSONObject();
            envelope.put("v", NovaEventMapper.PROTOCOL_VERSION);
            envelope.put("kind", "auth_failed");
            envelope.put("reason", reason);
            writeDirect(envelope.toString(JSONWriter.Feature.WriteNulls));
            close(CloseStatus.NORMAL.withReason("认证失败"));
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

                if (!writeDirect(json)) {
                    return;
                }
            }
        }

        /**
         * 直接往 socket 写一帧
         * <p>
         * ⚠️ <b>加锁不是多余的。</b> 绝大多数帧由 {@link #pump()} 这一个线程写，
         * 但 {@code auth_failed} 必须在<b>入站线程</b>上同步写完再关连接——
         * 走队列的话 {@link #close} 会把队列清掉，客户端就只看到一个无缘无故的断开，
         * 而「说清为什么失败」正是这次改用认证帧要换来的东西。
         * 两个线程都可能写，就必须串起来。
         * <p>
         * 🔴 串用的是 {@link #writeMutex} 而<b>不是这个对象的监视器</b>——
         * 这里会阻塞任意久，而监视器还要给跑在共享心跳线程上的 {@code goLive} 用。
         * 见 {@link #writeMutex}。
         * @return 是否写成功；失败时连接已被关闭
         */
        private boolean writeDirect(String json) {
            synchronized (writeMutex) {
                return writeLocked(json);
            }
        }

        private boolean writeLocked(String json) {
            try {
                session.sendMessage(new TextMessage(json));
                return true;
            } catch (IOException | IllegalStateException e) {
                // 发不出去就是这条连接没救了。这里只记 debug: 关掉面板是常事，
                // 每次都打 warn 只会淹没真正的问题
                log.debug("事件流向 {} 发送失败, 关闭该连接", session.getId(), e);
                close(CloseStatus.SERVER_ERROR);
                return false;
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

        /**
         * 关闭这条连接，就地把关闭帧发出去
         * <p>
         * 与 {@link #closeAsync} <b>同签名</b>：两者可以整个替换而不牵动调用点。
         *
         * @return 这一次真的由它翻转成已关闭时为 {@code true}；早已关闭时为 {@code false}
         */
        private boolean close(CloseStatus status) {
            if (!beginClose()) {
                return false;
            }
            sendCloseFrame(status);
            return true;
        }

        /**
         * 同上，但**发关闭帧那一步**交给发送线程池，并回报**这一次是不是真的由它关上的**
         * <p>
         * 关一条 WebSocket 要发一帧关闭帧，那是一次<b>阻塞写</b>；落在一条写不动的连接上，
         * 调用方的线程就停在那儿。给心跳线程用的就是这一支：那条线程是<b>全局共享的单线程</b>，
         * 它停一下，所有连接的 ping、认证时限、回补窗口一起停。
         * <p>
         * 记账的部分（{@code closed}、退订、清空待发）仍旧就地做完，只有那一次写挪走：
         * 挪走的是会阻塞的那一步，不是「什么时候算关上了」。这样下一轮心跳看见 {@code closed}
         * 就直接跳过，不会反复往池子里塞活。
         * <p>
         * 返回值给调用方用来**只打一次日志**：关闭帧写不动时，这条连接会在册子里多待一会儿，
         * 心跳每个周期都会再看见它一次。
         *
         * @return 这一次真的由它翻转成已关闭时为 {@code true}；早已关闭时为 {@code false}
         */
        private boolean closeAsync(CloseStatus status) {
            if (!beginClose()) {
                return false;
            }
            try {
                senders.execute(() -> sendCloseFrame(status));
            } catch (RejectedExecutionException e) {
                // 正在关停，池子不收活了。这时阻不阻塞已经无所谓，就地关掉。
                sendCloseFrame(status);
            }
            return true;
        }

        /**
         * 关闭的记账部分：只做一次，返回是否由本次调用做的
         */
        private boolean beginClose() {
            if (closed) {
                return false;
            }
            closed = true;

            if (authDeadline != null) {
                authDeadline.cancel(false);
            }
            stream.unsubscribe(this);
            outbox.clear();
            return true;
        }

        private void sendCloseFrame(CloseStatus status) {
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
        Client client = new Client(session, state.lastSeq(), tokens == null);
        clients.put(session.getId(), client);

        senders.execute(client::pump);

        if (tokens != null) {
            // 🔴 要求口令时，这里**什么都不发**。
            // hello 里带着 sessionId、能力集与口径差异，是关于这个部署的信息——
            // 认证之前不该给出去。连上就发 hello 等于把「握手成功」当成了「认证成功」，
            // 而这次改用认证帧，要的正是把这两件事分开。
            client.authDeadline = heartbeats.schedule(
                    () -> client.closeUnauthenticated(), timings.authTimeout(), TimeUnit.MILLISECONDS);
            log.info("事件流客户端 {} 已连接, 等待认证帧, 当前连接数 {}", session.getId(), clients.size());
            return;
        }

        client.send(hello(state));
        client.grace = heartbeats.schedule(() -> client.goLive(client.helloSeq), timings.resumeGrace(), TimeUnit.MILLISECONDS);

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

        if ("auth".equals(kind)) {
            if (tokens == null) {
                // 不要求口令的部署收到 auth 也别报错：客户端不该为了两种部署写两套代码
                log.debug("事件流客户端 {} 发来 auth, 但本部署未要求口令, 已忽略", session.getId());
                return;
            }
            client.authenticate(request.getString("token"));
            return;
        }

        // 🔴 没认证之前，除 auth 外一律不理会。
        // 尤其是 resume——它能问出「缓冲窗口从哪一条起」，那是关于这个部署的信息。
        if (!client.authenticated) {
            log.debug("事件流客户端 {} 未认证就发来 {}, 已忽略", session.getId(), kind);
            return;
        }

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

        // 🔴 先给发送线程一小段宽限，把**已经排上队的关闭帧**发出去，再强杀。
        //    直接 shutdownNow 的话，那些已经记账为「已关闭」、关闭帧还排在队里的连接
        //    会被连任务一起丢掉——对端一帧关闭帧都收不到，只看得见连接断了。
        //    宽限有上限：写不动的那条本来就发不出去，等它也没有意义。
        senders.shutdown();
        try {
            if (!senders.awaitTermination(SHUTDOWN_DRAIN_MS, TimeUnit.MILLISECONDS)) {
                senders.shutdownNow();
            }
        } catch (InterruptedException e) {
            senders.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * @return 当前连接数
     */
    public int getClientCount() {
        return clients.size();
    }

    /**
     * 心跳：给每个客户端发 {@code ping}，并清理已经不回话的连接
     * <p>
     * 跑在<b>全局共享的单线程</b>上，所以这里<b>不许有阻塞调用</b>——发 ping 走的是每条连接
     * 自己的待发队列，清理走 {@link Client#closeAsync}。
     */
    private void heartbeat() {
        long now = System.currentTimeMillis();
        for (Client client : clients.values()) {
            // 未认证的连接不参与心跳：认证之前这条连接上不该有任何协议流量，
            // 它的清理由 AUTH_TIMEOUT_MS 那条更短的时限负责
            if (!client.authenticated) {
                continue;
            }
            if (now - client.lastSeenAt > timings.clientTimeout()) {
                // 🔴 用 closeAsync 而不是 close：这条线程是全局共享的单线程，
                //    而关一条连接要发关闭帧、那是阻塞写。就地关的话，
                //    一条写不动的连接会把**所有**连接的 ping、认证时限、回补窗口一起钉住；
                //    还多一层——遍历是顺序的，排在它后面的连接这一轮连 ping 都轮不到。
                //
                //    日志只在**真的由这一次关上**时打。关闭帧写不动的连接会在册子里多待一会儿，
                //    每个周期都会被再看见一次——无条件打的话，一条断开会打出几十行一模一样的，
                //    把日志面上真正的事淹掉。
                if (client.closeAsync(CloseStatus.POLICY_VIOLATION.withReason("未按协议回应心跳"))) {
                    log.info("事件流客户端 {} 超过 {} 毫秒未回应, 已断开",
                            client.session.getId(), timings.clientTimeout());
                }
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
