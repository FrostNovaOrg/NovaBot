package com.starlwr.bot.core.protocol;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.config.LiveProperties;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * 慢消费者用例的台架与三条判据的<b>探针本体</b>
 * <p>
 * 🔴 探针只写这一份。{@link NovaEventSlowConsumerTest}（量「修好没有」）与
 * {@link NovaEventProbeIndependenceTest}（量「三条判据互不代劳」）<b>共用</b>它——
 * <b>两把尺量同一件事必生漂移</b>，而这里漂移的后果是：独立性那组量的其实是另一支探针，
 * 于是它证明的独立性对真正在用的那三条<b>一句都不算</b>。
 * <p>
 * 台架自己也要能被拆开：{@link #bringUpSlowClient()} 不亮先验尺就直接判失败，
 * 因为<b>没复现出阻塞时，三条判据的绿和修好了的绿长得一样</b>。
 */
final class NovaEventSlowConsumerHarness implements AutoCloseable {
    /** 读数行的前缀。改它要两头同改——外部收集方按这个串匹配 */
    static final String READING_MARK = "慢消费者读数 ";

    /** 心跳周期。取小值，好让「一个周期都没等到」在秒级窗口里看得出来 */
    static final long PING = 200;

    /** 认证时限 */
    static final long AUTH = 500;

    /** 回补窗口 */
    static final long GRACE = 300;

    /** 客户端超时。取大值：本组量的不是超时清理，别让它插进来 */
    static final long CLIENT_TIMEOUT = 60_000;

    /** 判据的等待上限。取心跳周期的十几倍——够宽，红了就不是「再等等就好了」 */
    /**
     * 判据的观测窗口
     * <p>
     * 🔴 <b>3000ms 曾经太长。</b>阻塞撑不过窗口时，闸会在剩下的时间里自己办完，
     * 修前的红当场变绿——实测撞到过两次：2703ms 与 2973ms，
     * 后者离 3000 只差 27ms。
     * <p>
     * 现在取 1200ms：实测阻塞最短约 2700ms，余 ~1500ms。
     * 光靠这个数不够——它是**分布的下沿**，不是保证；所以每一格另有**收尾戳**，
     * 窗口走完时再确认一次阻塞还在，撑不住的那一跑记作不算数。
     * <p>
     * 窗口够不够用：心跳周期 200ms，1200ms 是 6 轮 ping；
     * 认证闸 1000ms、回补窗口 1000ms，都在窗口内。
     */
    static final long CRITERION_WAIT = 1_200;

    /**
     * 破坏用的大数。派到这个时限上的那件事在 {@link #CRITERION_WAIT} 内一定不会发生，
     * 而它<b>只</b>是那一条判据所量的下游。
     */
    static final long BREAK = 60_000;

    static final NovaEventEndpoint.Timings STANDARD_TIMINGS =
            new NovaEventEndpoint.Timings(PING, CLIENT_TIMEOUT, AUTH, GRACE);

    // ══════ 「超时清理时的关闭帧」那一组：时限**反过来配** ══════
    // 上一组特意把超时清理挡在画面外（CLIENT_TIMEOUT 取 60 秒）；这一组要量的正是那一刻。
    // 🔴 认证闸与回补窗口都要**排在超时清理之后**到点，否则它们在卡住之前就自己办完了，
    //    判据 2／3 会在洞还在的时候变绿——那不是「修好了」，是「还没轮到出事」。

    /**
     * 断连时限：要它在测试里到点
     * <p>
     * 🔴 取值比「支起慢客户端」那一段耗时**多留 700 毫秒**：沉默连接与他连要在慢客户端支起
     * <b>之后</b>才接上，它们的闸才可能在卡住那一刻还悬着。头一版取 600 毫秒，
     * 支起那一段就花掉了 800 毫秒——<b>卡住之前认证闸与 goLive 都自己办完了，判据 2／3 假绿</b>。
     */
    static final long CLOSE_FRAME_CLIENT_TIMEOUT = 1200;

    /**
     * 认证闸：从沉默连接接上算起，要**晚于**卡住那一刻到点
     * <p>
     * 沉默连接在慢客户端支起之后才接上，那时距卡住还有约 700 毫秒；取 1000 毫秒即悬在卡住之后。
     */
    static final long CLOSE_FRAME_AUTH = 1000;

    /** 回补窗口：同上，要晚于卡住那一刻到点 */
    static final long CLOSE_FRAME_GRACE = 1000;

    /**
     * 关闭时真写多少字节。取协议里关闭帧载荷的上限。
     * <p>
     * 🔴 <b>量的东西要和要防的东西同尺寸。</b> 管道灌满时写 1 个字节也卡得住，
     * 但「1 字节卡住」证不了「真实关闭帧会卡住」。
     */
    static final int CLOSE_FRAME_BYTES = 125;

    /** 健康连接条数。要多于一条，后排那层伤害才有可能被排出来 */
    static final int HEALTHY_CONNECTIONS = 6;

    /**
     * 慢客户端的 id
     * <p>
     * 🔴 端点里的 {@code clients} 是 {@code ConcurrentHashMap}，遍历次序由 id 的散列定，
     * <b>同一组 id 每跑都一样</b>。这个 id 要是恰好散到最后，后排就永远是空的，
     * 判据 4 当场变成空真——而那看起来像「夹具搭不起来」，其实是「id 选得不巧」。
     * <p>
     * 这个值是<b>定出来的，不是挑顺眼的</b>：把这一组连接的 id 原样放进一个
     * {@code ConcurrentHashMap} 里，直接算出遍历次序，看慢客户端落在第几位。
     * {@code slow-0/1/2/3/6/7/10/11} 都排在六条健康连接<b>之前</b>（后排 6 条、前排 0 条），
     * {@code slow-8} 排在最后（前排 6 条、后排 0 条）——这两种都缺一簇。
     * {@code slow-4} 落在第 4 位：<b>前 2 条、后 4 条</b>，两边都不空。
     * <p>
     * 🔴 算出来的次序<b>不许只当注释信</b>：它依赖 JDK 的散列实现，换个 JDK 就可能重排。
     * 所以判据 4 每一跑开头都由 {@code bothClustersNonEmpty} 就地再验一次——
     * <b>验的是生效值，不是这段注释</b>。
     */
    static final String SLOW_CLIENT_ID = "slow-4";

    // ═══════════════════ 负载免疫：判相对，不判绝对 ═══════════════════
    //
    // 🔴 这一组判据从前拿**墙钟绝对时长**当尺：判据 4 要求每条健康连接的相邻两次 ping
    //    不许超过「心跳周期＋余量」＝ 300ms。机器一有负载，判的就不是代码是机器——
    //    实测在**空载**下已经量到六条健康连接**齐齐 320ms**。
    //    六条一起偏移，那是整机被推了一把的形状，不是「谁被落下了」的形状；
    //    可那把绝对尺分不出这两件事，于是它红在了机器上。
    //
    //    改法只有一条：**判相对**。这个洞的伤害本来就是差分的——
    //    心跳线程被钉住时，遍历前半段的连接收到了那一轮，后半段一条都没收到。
    //    负载会把**所有**连接一起拖慢，差分不变；钉住只拖后半段，差分立刻现形。
    //    于是判据量的不再是「多少毫秒」，而是「**后簇比前簇落后了多少**」。
    //
    //    还剩下的那点「多久算久」，一律折算成 {@link ReferenceClock} 的格数——
    //    那把表和心跳跑在同一台机器、同一份负载上，只是<b>不经过心跳线程</b>。
    //    机器慢，它跟着慢；心跳被钉住，它照走。

    /**
     * 对照钟：和端点心跳<b>同周期、同机器、同负载，但不经过心跳线程</b>的一把表
     * <p>
     * 🔴 <b>它是这一组判据全部「时长」的单位。</b>负载一高，墙钟毫秒说明不了任何事：
     * 「1200 毫秒内没收到 ping」在空载是洞，在满载是机器。而「对照钟走了两格、
     * 心跳一格没走」在两种负载下都是同一句话——因为这把表和心跳吃的是同一份 CPU。
     * <p>
     * 用 {@code ScheduledExecutorService} 而不是 {@code sleep} 循环，是为了和被量的那条
     * （端点的 {@code heartbeats}）<b>同一种机器</b>：同样的定时器实现、同样的线程模型。
     * <b>对照要和被对照的东西同形，差出来的才是被测的那件事。</b>
     *
     * <h2>🔴 派活用 {@code scheduleWithFixedDelay}，<b>不</b>用 {@code scheduleAtFixedRate}</h2>
     * 从前两边都用补齐型，理由是「同形」。那句话本身没错，可它管不着这一处：
     * 这把表在判据里的角色是<b>预算</b>，而<b>补齐型的预算会瞬间烧完</b>。
     * ticker 那条线程被剥了一会儿 CPU，欠下的几格会紧挨着补出来——
     * 「走了 2 格」于是可能只对应一百多毫秒。
     * <p>
     * 而判据 1 要的是「这段时间里心跳该走过至少一轮」，那是<b>墙钟</b>上的事：
     * 端点的 {@code heartbeats} 按墙钟到点，一轮 ping 隔一个心跳周期。
     * 两件事之间还差着<b>两次线程切换</b>——心跳线程入队、泵线程写出去、探针线程读出来记一笔，
     * 而对照钟那一格只是一次原子自增。两边一起被剥 CPU、一起恢复时，<b>对照钟必先到</b>，
     * 窗口就在那一轮 ping 落地之前关上了。
     * <p>
     * 实录（改前，整类连跑 25 轮撞到一次）：窗口 2 格 / 247ms、<b>本跑实测格长 123ms</b>，
     * 六条健康连接一轮未推进；同一份读数里卡在关闭帧写里的是 {@code nova-event-sender-*}，
     * <b>心跳线程一根汗毛没动</b>。另在 75 跑原样码里量到 5 次窗口墙钟短于一个心跳周期
     * （165／177／181／183／186ms）——那种窗口里心跳一轮都不欠，判据 1 的绿纯属侥幸。
     * <p>
     * 换成不补齐之后，相邻两格之间至少隔一个周期的<b>真跑时间</b>，
     * N 格的窗口必覆盖 ≥ (N−1) 个心跳周期。<b>「负载免疫」一点没丢</b>：机器慢时格自己变长、
     * 预算跟着变长；丢掉的只是「预算可以瞬间烧完」。
     * 这一条由台架自检 {@code referenceClockMustNotCatchUp} 拿<b>生效值</b>就地量着。
     */
    static final class ReferenceClock implements AutoCloseable {
        private final java.util.concurrent.ScheduledExecutorService ticker;

        private final java.util.concurrent.atomic.AtomicLong tick =
                new java.util.concurrent.atomic.AtomicLong();

        private final long tickMs;

        /** 上一格落在什么时刻。只由 ticker 那一条线程写 */
        private volatile long lastTickAt = 0;

        /**
         * 相邻两格之间<b>最短</b>隔了多久
         * <p>
         * 🔴 这把表在判据里的角色是<b>预算</b>，而这个数就是「预算最快能烧多快」。
         * 它小于一个心跳周期时，「走了 N 格」这句话就不再意味着「心跳该走过 N 轮」——
         * 判据 1 的整条推理链是断的。见 {@link NovaEventHarnessSelfCheckTest} 里就地量它的那一格。
         */
        private final java.util.concurrent.atomic.AtomicLong minGapMs =
                new java.util.concurrent.atomic.AtomicLong(Long.MAX_VALUE);

        ReferenceClock(long periodMs) {
            this.tickMs = periodMs;
            this.ticker = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "nova-test-control-clock");
                t.setDaemon(true);
                return t;
            });
            // 🔴 fixedDelay 而非 fixedRate：见类注释。补齐型的表会把欠下的格紧挨着补出来，
            //    「走了 N 格」于是不再意味着「墙钟走过 N 个心跳周期」，判据 1 当场假红
            ticker.scheduleWithFixedDelay(this::onTick, periodMs, periodMs, TimeUnit.MILLISECONDS);
        }

        private void onTick() {
            long now = System.currentTimeMillis();
            long previous = lastTickAt;
            lastTickAt = now;
            if (previous != 0) {
                long gap = now - previous;
                minGapMs.updateAndGet(m -> Math.min(m, gap));
            }
            tick.incrementAndGet();
        }

        /** 相邻两格最短隔了多久；还没走满两格时为 -1 */
        long minTickGapMs() {
            long v = minGapMs.get();
            return v == Long.MAX_VALUE ? -1 : v;
        }

        long read() {
            return tick.get();
        }

        long nominalTickMs() {
            return tickMs;
        }

        /**
         * 开一段观测：从此刻起，等这把表再走满 {@code tickCount} 格
         *
         * @param 格数 等几格
         * @return 这一段的起止（含本跑实测的格长）
         */
        TimeSegment awaitTicks(long tickCount) throws InterruptedException {
            long startTick = read();
            long startMs = System.currentTimeMillis();
            long deadlineAt = startMs + deadlineMs(tickCount);
            while (read() - startTick < tickCount && System.currentTimeMillis() < deadlineAt) {
                Thread.sleep(5);
            }
            return new TimeSegment(startTick, read(), startMs, System.currentTimeMillis(), tickMs);
        }

        /**
         * 在这把表走满 {@code tickCount} 格之前反复问 {@code probe}；拿到非 {@code null} 就提前返回
         * <p>
         * 🔴 这是 {@link NovaEventSlowConsumerHarness#await(long, java.util.function.Supplier)} 的负载免疫版：
         * 预算按<b>对照格</b>算，机器慢时窗口自己变长。判「窗口内那件事有没有发生」的格子
         * 一律走这一支——拿墙钟毫秒当预算，负载一高判的就是机器。
         */
        <T> T await(long tickCount, java.util.function.Supplier<T> probe) throws InterruptedException {
            long startTick = read();
            long deadlineAt = System.currentTimeMillis() + deadlineMs(tickCount);
            while (true) {
                T v = probe.get();
                if (v != null) {
                    return v;
                }
                if (read() - startTick >= tickCount || System.currentTimeMillis() >= deadlineAt) {
                    return null;
                }
                Thread.sleep(5);
            }
        }

        /**
         * 墙钟死线：只为「对照钟自己死了别把夹具挂死」
         * <p>
         * 🔴 <b>它不是判据</b>，取得极宽（名义格长的二十倍）。够不到它就是表坏了，
         * 不是机器慢——真慢到二十倍，红在哪一格都无所谓了。
         */
        private long deadlineMs(long tickCount) {
            return Math.max(1, tickCount) * tickMs * 20;
        }

        @Override
        public void close() {
            ticker.shutdownNow();
        }
    }

    /**
     * 一段观测：起止的对照格与墙钟
     * <p>
     * 🔴 {@link #runTickMs()} 是<b>这一跑实测</b>的一格有多长，不是配的那个数。
     * 判据要拿毫秒和「一格」比时，比的必须是这个实测值——
     * 拿名义值去比，负载一高就又回到判绝对了。
     */
    record TimeSegment(long startTick, long endTick, long startMs, long endMs, long nominalTickMs) {
        long ticksWalked() {
            return endTick - startTick;
        }

        long wallClockMs() {
            return endMs - startMs;
        }

        /** 本跑实测的一格有多长；一格都没走时退回名义值（只会发生在窗口开得极短时） */
        long runTickMs() {
            return ticksWalked() <= 0 ? nominalTickMs : Math.max(1, wallClockMs() / ticksWalked());
        }

        /** 把一段毫秒折算成「几个千分之一格」——判据用的单位 */
        long toMilliTick(long ms) {
            return ms * 1000 / runTickMs();
        }

        Map<String, Object> reading() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("走了几格", ticksWalked());
            m.put("墙钟毫秒", wallClockMs());
            m.put("本跑实测格长毫秒", runTickMs());
            m.put("名义格长毫秒", nominalTickMs);
            return m;
        }
    }

    /**
     * 量「相邻两格隔了多久」时留的余量（毫秒）
     * <p>
     * 🔴 <b>只为毫秒取整与调度落点留的一点点</b>，不是判据的松紧钮：格是按 {@code nanoTime}
     * 的死线排的，而这里拿 {@code currentTimeMillis} 的差去量，两把表在整数毫秒上会差一格。
     * 补齐型的表量出来的是<b>几毫秒到几十毫秒</b>（欠下的格紧挨着补出来），
     * 不补齐的是<b>一个周期以上</b>——两边差着量级，这点余量挡不住洞。
     */
    static final long CLOCK_GAP_SLACK_MS = 5;

    /**
     * 台架自检量对照钟时走几格
     * <p>
     * 取 15 格：够长，让被剥 CPU 的 ticker 至少赶上一次；又不至于把自检拖成一场压测。
     */
    static final long CLOCK_SELF_CHECK_TICKS = 15;

    /**
     * 判据的观测窗口，以<b>对照格</b>计
     * <p>
     * 🔴 不写成毫秒：负载一高，心跳一格就变长，窗口也该跟着变长——
     * 窗口按格算，「窗口里该发生几轮」这件事就不随负载变。
     * <p>
     * 取 2 格：判据 1 只要「有人推进过至少一轮」，判据 2／3 要等的那件事在开窗时
     * <b>早已到点</b>（认证闸与回补窗口都排在超时清理之前），两格是宽打宽算。
     * <p>
     * 🔴 <b>不能取大。</b>这一组的复现（对端不读的 socket 写）只撑得住两三秒，
     * 而那两三秒<b>不随负载变长</b>（内核放大接收缓冲是按时间走的，不按 CPU 走）。
     * 窗口按格算、格随负载变长，窗口开太多格就会在满载时伸出复现之外——
     * 那时红的是「这一跑没撑住」，不是判据。见 {@link #BLOCK_MARGIN_MILLI_TICK}。
     */
    static final long OBSERVE_TICKS = 2;

    /**
     * 后簇比前簇落后，允许<b>持续</b>多久（单位：千分之一个对照格）
     * <p>
     * <b>这个数不是从噪声里量出来的，是从两边的量级里分出来的。</b>
     * <ul>
     *   <li><b>修后</b>那一点点落后是「心跳正走在遍历中间」的快照假象：
     *       {@code heartbeat()} 对每条连接只做一次入队，整趟走完是微秒级；
     *       加上各连接投递线程的调度差，也就几毫秒到几十毫秒——<b>远不到一格</b>。</li>
     *   <li><b>修前</b>那一段落后是<b>整个阻塞</b>：后簇要等心跳从关闭帧的写里出来才轮得到，
     *       实测两三秒，也就是<b>好几格</b>（{@link #HOLE_SIGNATURE_LAG_MILLI_TICK}）。</li>
     * </ul>
     * 两边差着量级，线放在中间：0.4 格。<b>它只挡量级错，不挡毫秒抖动。</b>
     * <p>
     * 🔴 为什么可以按格算而不按毫秒算：负载一高，那点快照假象和一格<b>一起</b>变长，
     * 比值不动；而阻塞那两三秒是墙钟固定的，格一变长它反而占更少格——
     * 也就是说负载只会让这条线<b>更难红</b>，不会让它假红。
     * <p>
     * 🔴 定完要验它抓不抓得住洞：见台架自检里那一格（{@code lagToleranceCatchesHole}）。
     */
    static final long LAG_TOLERANCE_MILLI_TICK = 400;

    /**
     * 「后簇陪葬」这个洞在读数上长什么样：后簇会落后<b>整段窗口</b>
     * <p>
     * 心跳被钉在关闭帧的写里时，遍历停在慢客户端那一步，后簇一轮也轮不到，
     * 直到阻塞解开为止——而阻塞撑得过窗口（{@link #BLOCK_MARGIN_MILLI_TICK} 就是在保这一条）。
     * 所以量到的落后会一路顶到窗口长度。
     * <p>
     * {@link #LAG_TOLERANCE_MILLI_TICK} 必须<b>小于</b>它，否则那一格从出生起就永远绿。
     */
    static final long HOLE_SIGNATURE_LAG_MILLI_TICK = OBSERVE_TICKS * 1000;

    /**
     * 落后到多少就该往「真被钉住了」上想，而不是往「机器抖了一下」上想
     * <p>
     * 🔴 这个数<b>只决定失败语怎么说</b>，不决定红不红：红绿仍然只看
     * {@link #LAG_TOLERANCE_MILLI_TICK}。
     * <p>
     * 🔴 <b>它必须比红绿线高</b>：两条线要是重合了，凡红必被说成「像真钉住」，
     * 两支说法塌成一支，这条诊断就没用了。也必须<b>低于</b>洞的签名，
     * 否则真钉住反而被说成机器抖——两支说反了比没有更费事。
     */
    static final long REAL_PIN_FLOOR_MILLI_TICK = LAG_TOLERANCE_MILLI_TICK * 2;

    /**
     * 判据 4 红了的时候，把「这一跑落后了多少」和「线是怎么来的」一起说清楚
     * <p>
     * 🔴 <b>一句指错方向的失败信息比没有更费事。</b>这一格红有两种成因，红形长得一样：
     * <ul>
     *   <li><b>后簇真被落下了</b>：落后一路顶到窗口长度，是<b>格</b>这个量级；</li>
     *   <li><b>机器抖了一下</b>：某条投递线程被剥了一会儿 CPU，落后只比线高一点。</li>
     * </ul>
     * 所以把<b>本跑实测</b>的落后连同线的来历一并打出来——
     * 线写死在源码里，落后只有跑起来才有。措辞一律用「<b>更像</b>」：只指方向，不下定论。
     */
    static String criterion4FailureMessage(BackRowReading Reading) {
        String looksLike = Reading.maxLagMilliTick() >= REAL_PIN_FLOOR_MILLI_TICK
                ? "落后 " + milliTickToString(Reading.maxLagMilliTick()) + " 格，到了**格**这个量级"
                  + "——心跳被钉住时后簇要等整段阻塞才轮得到，所以这更像**真被落下了**，"
                  + "正是这一格要抓的那件事。"
                : "落后 " + milliTickToString(Reading.maxLagMilliTick()) + " 格，只比线（"
                  + milliTickToString(LAG_TOLERANCE_MILLI_TICK) + " 格）高一点，离一整格还远"
                  + "——**更像某条投递线程被剥了一会儿 CPU**。"
                  + "挑机器闲的时候再跑一遍；连着好几跑都在这一档，才说明这条线定低了。";
        return "后簇（排在被清理那条**后面**的健康连接）持续落在前簇后面："
                + "\n  最长落后 " + Reading.maxLagMs() + "ms ＝ " + milliTickToString(Reading.maxLagMilliTick())
                + " 个对照格（本跑实测一格 " + Reading.segment().runTickMs() + "ms）"
                + "\n  线 ＝ " + milliTickToString(LAG_TOLERANCE_MILLI_TICK) + " 格。线不是按噪声定的，是按两边的量级定的："
                + "修后的落后是遍历中间的快照假象（微秒到几十毫秒），修前是整段阻塞（好几格）"
                + "\n  前簇 " + Reading.frontCluster() + " 收到的轮次 " + Reading.frontClusterRounds()
                + "\n  后簇 " + Reading.backCluster() + " 收到的轮次 " + Reading.backClusterRounds()
                + "\n  " + looksLike;
    }

    /** 把千分格印成人读得懂的「几点几格」 */
    static String milliTickToString(long milliTick) {
        return (milliTick / 1000) + "." + String.format("%03d", Math.abs(milliTick % 1000));
    }

    /**
     * 余量尺等阻塞解开的上限，以<b>对照格</b>计：等到这么久还卡着就不等了
     * <p>
     * 只是个封顶，不是判据；判据看的是 {@link #BLOCK_MARGIN_MILLI_TICK}。
     */
    static final long BLOCK_WAIT_TICKS = 40;

    /**
     * 开窗前等「复现成立」的上限，以<b>对照格</b>计
     * <p>
     * 🔴 上限存在的意义是<b>不许无限等</b>：等不到就该红在「复现没成立」上，
     * 而不是把夹具挂死在那里，让人以为是别的问题。
     * <p>
     * 按格算而不按毫秒算：负载高时该多等一会儿，等的是「机器又走了这么多步」，
     * 不是「墙上又过了这么多秒」。
     */
    static final long REPRO_WAIT_TICKS = 15;

    /**
     * 阻塞至少要比观测窗口多撑这么多（千分格），判据 2／3／4 的修前红才算钉得住
     * <p>
     * 🔴 <b>「卡住能撑满窗口」是个从来没人量过的默认。</b>撑不过的那一轮，
     * 闸会在窗口剩下的时间里自己办完，红当场变绿——而那不是修好了，是这一跑没撑住。
     * 实测撞到过 2703ms 阻塞对 3000ms 窗口。
     * <p>
     * 半格：窗口本身按格算，余量也按格算，两者才是同一把尺上的数。
     */
    static final long BLOCK_MARGIN_MILLI_TICK = 500;

    static final NovaEventEndpoint.Timings CLOSE_FRAME_TIMINGS = new NovaEventEndpoint.Timings(
            PING, CLOSE_FRAME_CLIENT_TIMEOUT, CLOSE_FRAME_AUTH, CLOSE_FRAME_GRACE);

    final NovaEventStream stream;

    final NovaEventEndpoint endpoint;

    final EventStreamTokenService tokens;

    final NovaEventSlowConsumerTest.SocketSession slowClient;

    private final List<NovaEventSlowConsumerTest.SocketSession> realSession = new ArrayList<>();

    /**
     * 夹具<b>登记在案</b>的那条共享心跳线程
     * <p>
     * 🔴 判据 0 的身份判定以它为准，<b>不按名字前缀猜</b>：名字是端点内部的线程工厂给的，
     * 哪天改了措辞，按前缀比会静悄悄地判成「不是心跳线程」——那是一个假绿。
     * <p>
     * 必须在<b>任何连接被灌满之前</b>登记：登记的办法是往那条线程上派一件小活问它是谁，
     * 而它一旦被钉住，这件活就永远排不上号。
     */
    final Thread heartbeatThread;

    /**
     * 这一跑的对照钟
     * <p>
     * 🔴 <b>和夹具同生共死</b>：它要和被量的那条心跳吃同一份负载，
     * 所以在夹具支起来的那一刻就开始走，收摊时一起停。
     * 判据临用时才起一把新表的话，量到的是「表刚起来那几格」，不是这一跑的机器。
     */
    final ReferenceClock clock = new ReferenceClock(PING);

    private static Thread registerHeartbeatThread(NovaEventEndpoint endpoint) {
        try {
            java.lang.reflect.Field f = NovaEventEndpoint.class.getDeclaredField("heartbeats");
            f.setAccessible(true);
            java.util.concurrent.ScheduledExecutorService ex =
                    (java.util.concurrent.ScheduledExecutorService) f.get(endpoint);
            return ex.submit(Thread::currentThread).get(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("登记不到那条共享心跳线程 —— 判据 0 的身份判定就没有依据，"
                    + "这一跑不算数。不许退回按名字前缀猜：名字对不上时那是静悄悄的假绿。", e);
        }
    }

    NovaEventSlowConsumerHarness(Path dir, boolean slowClientReads, NovaEventEndpoint.Timings timings) throws IOException {
        this(dir, slowClientReads, timings, "slow", false);
    }

    NovaEventSlowConsumerHarness(Path dir, boolean slowClientReads, NovaEventEndpoint.Timings timings,
                   String slowId, boolean reallyWriteOnClose) throws IOException {
        LiveProperties live = new LiveProperties();
        live.setLiveDataPath(dir.resolve("data.json").toString());
        this.tokens = new EventStreamTokenService(live);
        this.stream = new NovaEventStream(64);
        this.endpoint = new NovaEventEndpoint(stream, tokens, timings);
        this.heartbeatThread = registerHeartbeatThread(this.endpoint);
        this.slowClient = new NovaEventSlowConsumerTest.SocketSession(
                slowId, 1024, 1024, slowClientReads, reallyWriteOnClose);
        realSession.add(this.slowClient);
    }

    @Override
    public void close() {
        clock.close();
        endpoint.shutdown();
        for (NovaEventSlowConsumerTest.SocketSession s : realSession) {
            s.closeIt();
        }
        realSession.clear();
    }


    // ══════════════════════════ 台架动作 ══════════════════════════

    /** 此刻端点上挂着几条连接。读数要带上它——同一条判据在 2 条连接和 200 条连接上不是一件事 */
    int connectionCount() {
        return connected.size();
    }

    private final List<String> connected = new ArrayList<>();

    NovaEventEndpointTest.FakeSession connection(String id) {
        connected.add(id);
        NovaEventEndpointTest.FakeSession session = new NovaEventEndpointTest.FakeSession(id);
        endpoint.afterConnectionEstablished(session);
        return session;
    }

    void authenticated(WebSocketSession session, String token) {
        endpoint.handleTextMessage(session, new TextMessage(
                "{\"kind\":\"auth\",\"v\":2,\"token\":\"" + token + "\"}"));
    }

    /**
     * 只回 pong、不记读数的保活跑者
     * <p>
     * 「超时清理」那一组把断连时限压到了几百毫秒。不回 pong 的连接会被<b>正常清理掉</b>，
     * 于是判据 2／3 在修后也红——那不是洞，是这条连接自己没活着。
     */
    KeepAlive keepAliveRunner(NovaEventEndpointTest.FakeSession s) {
        KeepAlive mark = new KeepAlive();
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    JSONObject m = s.next();
                    if (m == null) {
                        continue;
                    }
                    // 🔴 跑者把队列抽干了，别人再去 next() 就抢不到——
                    //    所以「见过什么」由它自己记下来，不留第二个读队列的人。
                    mark.seen.add(String.valueOf(m.getString("kind")));
                    endpoint.handleTextMessage(s, new TextMessage("{\"kind\":\"pong\"}"));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (RuntimeException ignored) {
                    return;
                }
            }
        }, "nova-test-keepalive-" + s.getId());
        t.setDaemon(true);
        t.start();
        return mark;
    }

    /** 保活跑者替这条连接记下「见过哪几种帧」 */
    static final class KeepAlive {
        private final java.util.Set<String> seen = ConcurrentHashMap.newKeySet();

        boolean seen(String kind) {
            return seen.contains(kind);
        }
    }

    /** 连上、认证、把 hello 收掉。返回这条会话 */
    NovaEventEndpointTest.FakeSession connectAndAuthenticate(String id) throws Exception {
        NovaEventEndpointTest.FakeSession s = connection(id);
        authenticated(s, tokens.issue(id));
        if (s.next() == null) {
            fail("客户端 " + id + " 认证之后没收到 hello——台架自己就没起来，往下的读数都不算数");
        }
        return s;
    }

    /**
     * 把慢客户端支起来：灌满管道 → 连上 → 认证 → 等到它的发送线程真的卡在写里。
     *
     * @return 灌进去的字节数
     */
    long bringUpSlowClient() throws Exception {
        connected.add(slowClient.getId());
        long fillStart = System.currentTimeMillis();
        long fill = slowClient.filled();
        long fillElapsedMs = Math.max(1, System.currentTimeMillis() - fillStart);
        endpoint.afterConnectionEstablished(slowClient);
        authenticated(slowClient, tokens.issue("慢客户端"));

        List<String> stack = await(CRITERION_WAIT, NovaEventSlowConsumerHarness::priorGaugeSenderThreadStuckInWrite);
        if (stack == null) {
            fail("先验尺不亮：没抓到「发送线程卡在 socket 写里且持着该客户端的监视器」这个读数。"
                    + "拿不到它就不许采信三条判据——没复现出阻塞时，三条判据的绿和修好了的绿长得一样。"
                    + "（已灌 " + fill + " 字节）");
        }
        // 🔴 等过慢客户端 goLive 的到点时刻再开量。
        //    不等的话，判据 1 会在心跳线程还没被派到 goLive 之前就收到 ping 而变绿——
        //    **那不是「修好了」，那是「还没轮到出事」**。
        //    这里等的是一个**固定时刻**（回补窗口 + 余量），修前修后都等同样长：
        //    等「直到心跳线程被钉住」的话，修好之后这一等会永远等下去。
        Thread.sleep(GRACE + 200);

        List<String> heartbeatStack = heartbeatThreadStuckOnAnotherMonitor();
        reading("先验尺", Map.of(
                "灌入字节", fill,
                // 发送速率与连接数：换台机器复现时，这两个数决定了背压是不是同一回事。
                // 🔴 速率是**量出来的**（灌满字节 ÷ 灌满耗时），不是配的——
                //    配一个数上去，机器换了它还是那个数。
                "灌满耗时毫秒", fillElapsedMs,
                "发送速率字节每秒", fill * 1000L / fillElapsedMs,
                "此刻连接数（支起慢客户端这一刻，健康连接尚未接入）", connectionCount(),
                "发送线程栈摘录", stack,
                "等过回补窗口毫秒", GRACE + 200,
                "此刻心跳线程被钉住吗", heartbeatStack != null,
                "心跳线程栈摘录", String.valueOf(heartbeatStack),
                "此刻卡了毫秒", slowClient.stuckForHowLong()));
        return fill;
    }

    // ══════════════════════════ 三条判据的探针本体 ══════════════════════════

    /**
     * 一条判据的读数
     *
     * @param 绿       这条判据这一次是不是绿
     * @param 耗时毫秒 量到的耗时；红时为 -1
     */
    record Reading(boolean green, long elapsedMs) {
    }

    /** 判据 1 的探针：健康客户端在 {@link #CRITERION_WAIT} 内收不收得到 ping */
    Reading probe1HealthyClientGetsPing(NovaEventEndpointTest.FakeSession healthy) throws Exception {
        long origin = System.currentTimeMillis();
        long deadline = origin + CRITERION_WAIT;
        while (System.currentTimeMillis() < deadline) {
            JSONObject m = healthy.next();
            if (m == null) {
                break;
            }
            if ("ping".equals(m.getString("kind"))) {
                return new Reading(true, System.currentTimeMillis() - origin);
            }
        }
        return new Reading(false, -1);
    }

    /** 判据 2 的探针：未认证连接在 {@link #CRITERION_WAIT} 内关不关得掉 */
    Reading probe2AuthGateCloses(NovaEventEndpointTest.FakeSession silent) throws Exception {
        long origin = System.currentTimeMillis();
        CloseStatus closeStatus = await(CRITERION_WAIT, () -> silent.closedWith);
        return new Reading(closeStatus != null, closeStatus == null ? -1 : System.currentTimeMillis() - origin);
    }

    /**
     * 判据 3 的探针：他连在 {@link #CRITERION_WAIT} 内转不转得进实时流
     * <p>
     * 🔴 这里睡的是<b>名义上的</b>回补窗口 {@link #GRACE}，不是端点上配着的那个值——
     * 破坏组把端点的回补窗口推到 {@link #BREAK} 毫秒，探针要是跟着睡就变成睡一分钟，
     * 而<b>探针不该跟着被测物一起被破坏</b>。
     */
    Reading probe3OtherConnectionEntersLiveStream(NovaEventEndpointTest.FakeSession otherConnection) throws Exception {
        return probe3OtherConnectionEntersLiveStream(otherConnection, GRACE);
    }

    Reading probe3OtherConnectionEntersLiveStream(NovaEventEndpointTest.FakeSession otherConnection, long replayWindow) throws Exception {
        long origin = System.currentTimeMillis();
        Thread.sleep(replayWindow + 100);
        stream.publish(event());

        long deadline = origin + CRITERION_WAIT;
        while (System.currentTimeMillis() < deadline) {
            JSONObject m = otherConnection.next();
            if (m == null) {
                break;
            }
            if ("danmaku".equals(m.getString("kind"))) {
                return new Reading(true, System.currentTimeMillis() - origin);
            }
        }
        return new Reading(false, -1);
    }

    static JSONObject event() {
        JSONObject j = new JSONObject();
        j.put("v", NovaEventEndpoint.PROTOCOL_VERSION);
        j.put("kind", "danmaku");
        j.put("ts", 1786111565000L);
        j.put("room", 47731877194803L);
        return j;
    }

    /**
     * 算序：把这一组 id 原样放进一个 {@code ConcurrentHashMap}，直接算出遍历次序
     * <p>
     * 🔴 判据 4 改判相对之后，这两张名单<b>从选料参考变成了判据的一部分</b>：
     * 前簇是后簇的对照，两簇都不空这一条由 {@code bothClustersNonEmpty} 在每一跑开头就地断言。
     * <p>
     * 🔴 它依赖 JDK 的散列实现，换个 JDK 就可能重排——所以<b>算完要在真跑里验</b>，
     * 不许挑一个好看的次序写进注释就当数。阳性对照那一格更进一步：
     * 心跳真被钉在这个位次上时，量到的落后必须顶到洞的签名那个量级，
     * 次序要是算错了，落后就出不来，那一格当场红。
     */
    static OrderReading computeOrder(String slowId, int healthyCount, String... other) {
        List<String> all = new ArrayList<>();
        for (int i = 0; i < healthyCount; i++) {
            all.add("healthy-" + i);
        }
        all.add(slowId);
        all.addAll(List.of(other));
        return computeOrder(slowId, all);
    }

    /**
     * 遍历次序的读数：谁排在被清理那条<b>前面</b>、谁排在<b>后面</b>
     * <p>
     * 🔴 判据 4 改判相对之后，这两张名单从「选料参考」变成了<b>判据的一部分</b>：
     * 前簇是后簇的对照。两簇都不能空——后簇空了，「后排不陪葬」就是空真。
     */
    record OrderReading(String slowId, int index, int total, List<String> frontCluster, List<String> backCluster, String jdk) {
        Map<String, Object> reading() {
            Map<String, Object> outMap = new LinkedHashMap<>();
            outMap.put("被清理那条的 id", slowId);
            outMap.put("位次", index + "/" + total);
            outMap.put("算出来·排在前面的健康连接", frontCluster);
            outMap.put("算出来·排在后面的健康连接", backCluster);
            outMap.put("JDK", jdk);
            outMap.put("🔴", "算序依赖 JDK 的散列实现；换 JDK 就可能重排，两簇不空由判据自己再验一次");
            return outMap;
        }
    }

    static OrderReading computeOrder(String slowId, java.util.Collection<String> allIds) {
        java.util.Map<String, Integer> m = new ConcurrentHashMap<>();
        allIds.forEach(k -> m.put(k, 1));
        List<String> order = new ArrayList<>(m.keySet());
        int index = order.indexOf(slowId);
        List<String> before = order.subList(0, index).stream().filter(x -> x.startsWith("healthy-")).toList();
        List<String> after = order.subList(index + 1, order.size()).stream()
                .filter(x -> x.startsWith("healthy-")).toList();
        return new OrderReading(slowId, index, order.size(), before, after,
                System.getProperty("java.version") + " / " + System.getProperty("java.vm.name"));
    }

    /**
     * 挑一个<b>落在健康连接中间</b>的 id：前后两簇都不空
     * <p>
     * 🔴 阳性对照那把钉子要钉在遍历<b>中间</b>才量得出「后簇陪葬」——
     * 钉在最末尾的话后簇是空的，那一格就是空真。位次由 id 的散列定、不归我们挑，
     * 所以这里<b>算着挑</b>，而不是写死一个顺眼的：写死的那个换个 JDK 就作废，
     * 而作废之后它看起来还是绿的。
     *
     * @param 候选前缀 试哪一族 id（会一路试 {@code 前缀0}、{@code 前缀1}……）
     * @param 旁的     同时挂在端点上的其它 id
     * @return 挑中的 id 与它的两簇；一个都挑不出来时返回 {@code null}
     */
    static OrderReading pickMiddleId(String candidatePrefix, int healthyCount, List<String> others) {
        for (int i = 0; i < 200; i++) {
            String candidates = candidatePrefix + i;
            List<String> all = new ArrayList<>();
            for (int j = 0; j < healthyCount; j++) {
                all.add("healthy-" + j);
            }
            all.addAll(others);
            all.add(candidates);
            OrderReading s = computeOrder(candidates, all);
            if (!s.frontCluster().isEmpty() && !s.backCluster().isEmpty()) {
                return s;
            }
        }
        return null;
    }

    // ══════════════════════════ 判据的量法（判据格与阳性对照格共用同一份） ══════════════════════════
    //
    // 🔴 <b>阳性对照必须走判据在走的那一段代码。</b>另写一份「差不多的」去证明格子咬人，
    //    证的是那一份，不是真正在用的这一份——两把尺量同一件事必生漂移，
    //    而漂移之后，阳性对照的绿和判据的绿说的已经不是同一句话了。

    /** 判据 1 的读数 */
    record AdvanceReading(long advancedCount, long totalCount, Map<String, Integer> startRounds,
                Map<String, Integer> endRounds, TimeSegment segment) {
        Map<String, Object> reading() {
            Map<String, Object> outMap = new LinkedHashMap<>();
            outMap.put("窗口内推进过至少一轮的健康连接数", advancedCount);
            outMap.put("健康连接总数", totalCount);
            outMap.put("窗口起点各条轮次", startRounds);
            outMap.put("窗口终点各条轮次", endRounds);
            outMap.put("观测段", segment.reading());
            return outMap;
        }
    }

    /**
     * 判据 1 的量法：对照钟走 {@code tickCount} 格的这一段里，有几条健康连接<b>推进了</b>至少一轮 ping
     * <p>
     * 🔴 量的是<b>推进</b>（终点轮次 − 起点轮次），不是「窗口里有没有 ping」。
     * 后者要靠窗口两端的绝对时刻去截，负载一高截出来的就是机器；
     * 前者问的是「对照钟走了两格，心跳走了没有」——<b>两把表比着看，比的是同一份负载</b>。
     */
    static AdvanceReading measureHealthyConnectionAdvance(BackRowGauge gauge, ReferenceClock clock, long tickCount) throws InterruptedException {
        Map<String, Integer> roundsAtStart = gauge.eachRounds();
        TimeSegment segment = clock.awaitTicks(tickCount);
        Map<String, Integer> roundsAtEnd = gauge.eachRounds();
        long advanced = roundsAtStart.keySet().stream()
                .filter(id -> roundsAtEnd.getOrDefault(id, 0) > roundsAtStart.getOrDefault(id, 0))
                .count();
        return new AdvanceReading(advanced, roundsAtStart.size(), roundsAtStart, roundsAtEnd, segment);
    }

    /**
     * <b>旧</b>量法的红绿线：一条健康连接的相邻两次 ping 不许超过「心跳周期＋周期／2」
     * <p>
     * 🔴 <b>这个数只进读数，一格断言都不挂。</b>留着它不是为了判谁，是为了让每一跑都带上
     * 「同一段窗口，旧尺会怎么判」这条对照——<b>说自己治好了，得让病在同一份读数里现形</b>。
     * <p>
     * 它就是这一组从前红在机器上的那把尺：实测在空载下量到六条健康连接齐齐 320ms > 300ms，
     * 六条一起偏移是整机被推了一把的形状，而这把绝对尺分不出它和「谁被落下了」。
     */
    static final long OLD_METHOD_CAP_MS = PING + PING / 2;

    /** 判据 4 的读数 */
    record BackRowReading(long maxLagMs, long maxLagMilliTick, List<String> frontCluster, List<String> backCluster,
                Map<String, Integer> frontClusterRounds, Map<String, Integer> backClusterRounds,
                long SAMPLE_COUNT, TimeSegment segment, long oldMethodMaxGapMs, List<String> oldMethodWouldBeRed) {
        Map<String, Object> reading() {
            Map<String, Object> outMap = new LinkedHashMap<>();
            outMap.put("最长落后毫秒", maxLagMs);
            outMap.put("最长落后千分格", maxLagMilliTick);
            outMap.put("最长落后（格）", milliTickToString(maxLagMilliTick));
            outMap.put("线（格）", milliTickToString(LAG_TOLERANCE_MILLI_TICK));
            outMap.put("洞的签名（格）", milliTickToString(HOLE_SIGNATURE_LAG_MILLI_TICK));
            outMap.put("前簇", frontCluster);
            outMap.put("后簇", backCluster);
            outMap.put("前簇终点轮次", frontClusterRounds);
            outMap.put("后簇终点轮次", backClusterRounds);
            outMap.put("采样次数", SAMPLE_COUNT);
            outMap.put("观测段", segment.reading());
            outMap.put("🔴 判的是什么", "后簇最快的那条比前簇最快的那条少收轮次，这个状态**持续**了多久；"
                    + "持续时长折成对照格，负载把两簇一起拖慢时它不动");
            // 🔴 同一段窗口上，**旧尺**会怎么判。只作读数，一格断言都不挂——
            //    留着它是为了让「治好了」这句话在每一跑的读数里都拿得出对照。
            outMap.put("旧量法·最大 ping 间隔毫秒", oldMethodMaxGapMs);
            outMap.put("旧量法·上限毫秒（心跳周期＋周期／2）", OLD_METHOD_CAP_MS);
            outMap.put("旧量法·会判红的连接", oldMethodWouldBeRed);
            outMap.put("旧量法·这一跑在这段窗口上会红吗", !oldMethodWouldBeRed.isEmpty());
            outMap.put("🔴 旧量法只作读数", "它拿墙钟绝对间隔判，负载一高判的是机器不是代码。"
                    + "留在读数里是为了让每一跑都带上对照：同一段窗口，新尺说什么、旧尺说什么。");
            outMap.put("🔴 这条对照读的是什么", "量的是**这一段（" + segment.wallClockMs()
                    + "ms）**上旧尺的读数，不是旧那一格的原样复跑——"
                    + "旧那一格开的是 1200ms 的窗，窗越长越容易撞上那条 " + OLD_METHOD_CAP_MS
                    + "ms 的线。所以这条读数是**下界**：它说红，旧那一格必红；它说不红，"
                    + "旧那一格未必不红。要看的是它**随负载游走**（而新尺钉在 0 上）。");
            return outMap;
        }
    }

    /**
     * 判据 4 的量法：这一段里，「后簇比前簇少收轮次」这个状态最长<b>连续</b>撑了多久
     * <p>
     * 🔴 <b>为什么判持续时长而不判轮次差。</b>心跳被钉住时后簇也只落后<b>一轮</b>
     * （心跳起不了下一轮，前簇同样停着），轮次差量级不够；
     * 而修后那一轮之差是「心跳正走在遍历中间」的快照假象，几毫秒就抹平。
     * <b>两者差的不是差多少，是差了多久</b>——所以量的是这个状态的连续时长。
     * <p>
     * 🔴 时长再折成<b>对照格</b>：负载一高，快照假象和一格一起变长，比值不动。
     *
     * @param 尺   后排尺
     * @param 前簇 排在被清理那条前面的健康连接
     * @param 后簇 排在后面的健康连接
     * @param 钟   对照钟
     * @param 格数 观测窗口，以对照格计
     */
    static BackRowReading measureBackClusterLag(BackRowGauge gauge, List<String> frontCluster, List<String> backCluster,
                       ReferenceClock clock, long tickCount) throws InterruptedException {
        long startTick = clock.read();
        long startMs = System.currentTimeMillis();
        long deadlineAt = startMs + Math.max(1, tickCount) * clock.nominalTickMs() * 20;
        long maxLag = 0;
        long segmentStart = -1;
        long sample = 0;
        Map<String, Integer> eachOne = gauge.eachRounds();
        while (clock.read() - startTick < tickCount && System.currentTimeMillis() < deadlineAt) {
            eachOne = gauge.eachRounds();
            long frontFastest = BackRowGauge.clusterFastest(eachOne, frontCluster);
            long backFastest = BackRowGauge.clusterFastest(eachOne, backCluster);
            long now = System.currentTimeMillis();
            sample++;
            if (backFastest < frontFastest) {
                if (segmentStart < 0) {
                    segmentStart = now;
                }
                maxLag = Math.max(maxLag, now - segmentStart);
            } else {
                segmentStart = -1;
            }
            Thread.sleep(5);
        }
        TimeSegment segment = new TimeSegment(startTick, clock.read(), startMs, System.currentTimeMillis(), clock.nominalTickMs());
        Map<String, Integer> beforeTable = new java.util.TreeMap<>();
        Map<String, Integer> afterTable = new java.util.TreeMap<>();
        for (String id : frontCluster) {
            beforeTable.put(id, eachOne.getOrDefault(id, 0));
        }
        for (String id : backCluster) {
            afterTable.put(id, eachOne.getOrDefault(id, 0));
        }
        // 🔴 同一段窗口，再用**旧尺**量一遍——只作读数。
        //    新旧两把尺量的是同一段、同一批连接，读数并排放着，「治好了」才有对照可看。
        long oldMax = 0;
        List<String> oldWouldBeRed = new ArrayList<>();
        for (BackRowGauge.PingGapItem c : gauge.receive(segment.startMs(), segment.endMs())) {
            oldMax = Math.max(oldMax, c.maxGapMs());
            if (c.maxGapMs() > OLD_METHOD_CAP_MS) {
                oldWouldBeRed.add(c.id() + "＝" + c.maxGapMs() + "ms");
            }
        }
        return new BackRowReading(maxLag, segment.toMilliTick(maxLag), frontCluster, backCluster, beforeTable, afterTable, sample, segment,
                oldMax, oldWouldBeRed);
    }

    // ══════════════════════════ 后排尺（判据 4） ══════════════════════════

    /**
     * 判据 4 的量具：按连接分别记 ping 的到达时刻
     * <p>
     * 🔴 只记总数看不出「谁漏了」。判据 1～3 问「有没有人收到」，判据 4 问「<b>有没有人漏收</b>」，
     * 后者非按连接分开记不可。
     * <p>
     * 每条连接配一个跑者线程：收到任何一帧就<b>回一帧 pong</b>——
     * 这一组把断连时限压到了 {@link #CLOSE_FRAME_CLIENT_TIMEOUT} 毫秒，不回 pong 的话
     * 健康连接自己也会被清理掉，那量的就成了别的事。
     */
    static final class BackRowGauge implements AutoCloseable {
        /**
         * 一条连接在某一段窗口上的读数
         * <p>
         * 🔴 判据 4 改判相对之后，这张读数只剩<b>一个用处</b>：给「旧尺会怎么判」那条
         * 对照读数用（{@link #OLD_METHOD_CAP_MS}）。判据自己走的是 {@link #eachRounds()}。
         */
        record PingGapItem(String id, long maxGapMs, int pingCount) {
        }

        private final Map<String, List<Long>> pingAt = new ConcurrentHashMap<>();
        private final List<Thread> runners = new ArrayList<>();
        private volatile boolean stop = false;

        BackRowGauge(NovaEventEndpoint boundEndpoint, Map<String, NovaEventEndpointTest.FakeSession> members) {
            members.forEach((id, s) -> {
                pingAt.put(id, java.util.Collections.synchronizedList(new ArrayList<>()));
                Thread t = new Thread(() -> {
                    while (!stop) {
                        try {
                            JSONObject m = s.next();
                            if (m == null) {
                                continue;
                            }
                            if ("ping".equals(m.getString("kind"))) {
                                pingAt.get(id).add(System.currentTimeMillis());
                            }
                            // 回一帧让 lastSeenAt 保鲜：这一组的断连时限只有几百毫秒
                            boundEndpoint.handleTextMessage(s, new TextMessage("{\"kind\":\"pong\"}"));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        } catch (RuntimeException ignored) {
                            // 连接已关等情况，跑者退场即可
                            return;
                        }
                    }
                }, "nova-test-poller-" + id);
                t.setDaemon(true);
                t.start();
                runners.add(t);
            });
        }

        @Override
        public void close() {
            stop = true;
            runners.forEach(Thread::interrupt);
        }

        /**
         * 各条连接<b>到此刻为止</b>收到的 ping 轮次
         * <p>
         * 🔴 判据 4 改判相对之后，量的就是这一张表：
         * 「后簇最快的那条」比「前簇最快的那条」少收几轮。
         * 数轮次而不数毫秒，是因为<b>负载会把所有连接一起拖慢，轮次差不动</b>；
         * 而心跳被钉在遍历中间时，只有后簇的轮次停着不涨。
         * <p>
         * 🔴 <b>簇内取最快的那条，不取最慢的。</b>取最慢的话，
         * 六条里随便哪条投递线程被剥一会儿 CPU 就能伪造出「后簇落后」——
         * 而这个洞是<b>整簇一起</b>轮不到，不是某一条掉队。
         * 拿簇内最快的去比，一条掉队伪造不出簇的形状。
         */
        Map<String, Integer> eachRounds() {
            Map<String, Integer> outMap = new java.util.TreeMap<>();
            pingAt.forEach((id, members) -> {
                synchronized (members) {
                    outMap.put(id, members.size());
                }
            });
            return outMap;
        }

        /** 一簇里走得最快的那条收到了几轮；簇为空时返回 -1 */
        static long clusterFastest(Map<String, Integer> eachOne, List<String> cluster) {
            long fastest = -1;
            for (String id : cluster) {
                Integer n = eachOne.get(id);
                if (n != null) {
                    fastest = Math.max(fastest, n);
                }
            }
            return fastest;
        }

        /**
         * 收尺：算出每条连接在 [观测起, 观测止] 窗口内的最大 ping 间隔
         * <p>
         * 🔴 窗口两端也算进间隔里：只算「相邻两次 ping 之间」的话，
         * <b>一次 ping 都没收到的那条连接会算出 0 间隔</b>，读起来像最健康的那条。
         * <p>
         * 🔴 判据 4 已经<b>不走这条</b>了——它判的是两簇的轮次差（{@link #eachRounds()}）。
         * 这里只剩「旧尺会怎么判」那条对照读数在用。
         */
        List<PingGapItem> receive(long observeStart, long observeEnd) {
            List<PingGapItem> outMap = new ArrayList<>();
            new java.util.TreeMap<>(pingAt).forEach((id, members) -> {
                List<Long> window = new ArrayList<>();
                synchronized (members) {
                    members.forEach(t -> {
                        if (t >= observeStart && t <= observeEnd) {
                            window.add(t);
                        }
                    });
                }
                long maxSoFar = 0;
                long previous = observeStart;
                for (long t : window) {
                    maxSoFar = Math.max(maxSoFar, t - previous);
                    previous = t;
                }
                maxSoFar = Math.max(maxSoFar, observeEnd - previous);
                outMap.add(new PingGapItem(id, maxSoFar, window.size()));
            });
            return outMap;
        }

    }

    // ══════════════════════════ 先验尺 ══════════════════════════

    /**
     * 先验尺：慢客户端的发送线程<b>确实</b>卡在 socket 写里，且<b>正持着那个客户端的写锁</b>。
     * <p>
     * 🔴 拿不到这个读数就直接判失败，不进三条判据——
     * <b>没复现出阻塞时，三条判据的绿和修好了的绿长得一样。</b>
     *
     * @return 那条线程的栈摘录（不含任何值）
     */
    static List<String> priorGaugeSenderThreadStuckInWrite() {
        for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
            if (!e.getKey().getName().startsWith("nova-event-sender-")) {
                continue;
            }
            boolean inWrite = false;
            boolean lockHolder = false;
            List<String> excerpt = new ArrayList<>();
            for (StackTraceElement f : e.getValue()) {
                String line = f.getClassName() + "." + f.getMethodName();
                excerpt.add(line);
                if (line.contains("Socket") && f.getMethodName().contains("rite")) {
                    inWrite = true;
                }
                if (line.endsWith("NovaEventEndpoint$Client.writeLocked")
                        || line.endsWith("NovaEventEndpoint$Client.writeDirect")) {
                    lockHolder = true;
                }
            }
            if (inWrite && lockHolder) {
                return excerpt.subList(0, Math.min(12, excerpt.size()));
            }
        }
        return null;
    }

    /**
     * 关闭帧那把尺认的线程状态<b>白名单</b>
     * <p>
     * 🔴 写成名单，不写成「不是 {@code BLOCKED}」：后者会对<b>没见过的状态默默放行</b>——
     * {@code TIMED_WAITING}、或某个将来 JDK 的新形态，都会带着完全正确的栈帧悄悄过尺。
     * 量的是「在不在名单里」，不是「是不是那个已知的坏形态」。
     * <p>
     * {@code BLOCKED} 不在名单里，是因为它是<b>另一个洞</b>的形状（在等一把 Java 锁）——
     * 分界写在尺上，不写在心里。
     */
    static final java.util.Set<Thread.State> CLOSE_FRAME_STATE_ALLOWLIST =
            java.util.Set.of(Thread.State.WAITING, Thread.State.RUNNABLE);

    /**
     * 指名一条线程，看它此刻在干什么
     * <p>
     * 🔴 {@link #priorGaugeSomeoneStuckWritingCloseFrame()} 只收「卡在关闭帧写里」的那几条，
     * 心跳线程要是<b>只是被剥了 CPU</b>（{@code RUNNABLE} 排不上号）或者正停在定时器的
     * park 上，它一条也不会出现在那份实录里——于是「心跳没被钉住」这件事在读数上是
     * <b>沉默的</b>，而沉默和「尺没量」长得一样。这一支专补那一栏：判据 1 零推进时
     * 就地把心跳线程的状态与栈抄下来，红了不必再猜是钉住还是没排上号。
     *
     * @param thread 要看的那条线程
     * @return 名字／线程号／状态／栈摘录
     */
    static Map<String, Object> threadSnapshot(Thread thread) {
        Map<String, Object> outMap = new LinkedHashMap<>();
        outMap.put("线程名", thread.getName());
        outMap.put("线程号", thread.getId());
        outMap.put("线程状态", thread.getState().name());
        List<String> excerpt = new ArrayList<>();
        for (StackTraceElement f : thread.getStackTrace()) {
            excerpt.add(f.getClassName() + "." + f.getMethodName());
        }
        outMap.put("栈摘录", excerpt.subList(0, Math.min(12, excerpt.size())));
        return outMap;
    }

    /**
     * 同一份线程转储里，<b>持着 {@code NioSocketImpl} 那把写锁</b>的线程
     * <p>
     * 判别法：栈里有 {@code NioSocketImpl.write}，而<b>那一帧之上没有</b>
     * {@code ReentrantLock.lock}／{@code AQS.acquire}——也就是它已经<b>进了</b>锁，
     * 不是在等锁。
     * <p>
     * 🔴 取到它，「心跳线程在等 socket 写锁」才从推断变成实录：
     * 它等的是谁，名字与栈都钉在读数里。取不到就写明取不到，不含糊过去。
     */
    static Map<String, Object> writeLockHolderThread() {
        for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
            List<String> excerpt = new ArrayList<>();
            int writeFrame = -1;
            boolean waitingForLock = false;
            StackTraceElement[] fs = e.getValue();
            for (int i = 0; i < fs.length; i++) {
                String line = fs[i].getClassName() + "." + fs[i].getMethodName();
                excerpt.add(line);
                if (writeFrame < 0 && line.equals("sun.nio.ch.NioSocketImpl.write")) {
                    writeFrame = i;
                }
                if (writeFrame < 0 && (line.endsWith("ReentrantLock.lock")
                        || line.endsWith("AbstractQueuedSynchronizer.acquire"))) {
                    waitingForLock = true;   // 这几帧在 write 之上 ＝ 它在等这把锁，不是持有
                }
            }
            if (writeFrame >= 0 && !waitingForLock) {
                Map<String, Object> outMap = new LinkedHashMap<>();
                outMap.put("线程名", e.getKey().getName());
                outMap.put("线程状态", e.getKey().getState().name());
                outMap.put("栈摘录", excerpt.subList(0, Math.min(10, excerpt.size())));
                return outMap;
            }
        }
        return null;
    }

    /**
     * 先验尺：这一跑里<b>有人</b>卡在关闭帧的写里 —— 复现成立
     * <p>
     * 🔴 <b>复现尺量现象，判据量归属。</b>这把尺只问「关闭帧的写有没有真的卡住」，
     * 不问卡住的是<b>哪条</b>线程——那是判据 0 的事。
     * <p>
     * 原先它问的是「<b>心跳线程</b>卡在关闭帧写里」，把病灶的归属写进了现象的复现里。
     * 那样的尺在修好那天必死：修复要拿掉的正是「卡住的是心跳线程」这件事，
     * 于是修后它必红，而<b>一把死掉的尺让「修好了」和「压根没灌满」长得一样</b>。
     * 劈开之后修前修后都该绿，每一轮的数都自带「复现成立」的戳。
     * <p>
     * 状态走<b>白名单</b>：{@code {WAITING, RUNNABLE}} ＋ socket 写帧 ＋ {@code sendCloseFrame} 帧。
     * 不写成「<b>不是</b> {@code BLOCKED}」——那样会对没见过的状态默默放行：
     * {@code TIMED_WAITING}、或某个将来 JDK 的新形态，都会带着完全正确的栈帧悄悄过尺。
     * <b>名单外则尺自己举手</b>（{@code gaugeUnseenState}＝true），既不算红也不算绿，
     * 由外部收集方判 ABORT 带实录。
     * <p>
     * 实测：状态是 {@code WAITING}——它停在 {@code NioSocketImpl} 自己那把写锁上，
     * 不是自己停在 write 系统调用里。照「必须 RUNNABLE」写会次次假红。
     *
     * <p>
     * 🔴 <b>收全量，不是「第一条命中」。</b> {@code Thread.getAllStackTraces()} 的次序不定；
     * 只返回撞见的第一条，判据 0 判的就成了「<b>抽中的这条</b>不是心跳线程」，
     * 而它自称判的是「<b>没有</b>心跳线程卡在关闭帧写里」——两句话在绿的时候长得一样，
     * 只在「同时有两条卡住」那天分岔。
     *
     * @return 每条卡住的线程一份读数（名字／线程号／状态／栈摘录／它在等谁）；没人卡住时空表
     */
    static List<Map<String, Object>> priorGaugeSomeoneStuckWritingCloseFrame() {
        List<Map<String, Object>> everything = new ArrayList<>();
        for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
            List<String> excerpt = new ArrayList<>();
            boolean inWrite = false;
            boolean inCloseFrame = false;
            for (StackTraceElement f : e.getValue()) {
                String line = f.getClassName() + "." + f.getMethodName();
                excerpt.add(line);
                if (line.contains("Socket") && line.endsWith(".write")) {
                    inWrite = true;
                }
                if (line.contains("NovaEventEndpoint$Client")
                        && (f.getMethodName().equals("sendCloseFrame")
                            || f.getMethodName().equals("close"))) {
                    inCloseFrame = true;
                }
            }
            if (!(inWrite && inCloseFrame)) {
                continue;
            }
            Thread.State st = e.getKey().getState();
            Map<String, Object> outMap = new LinkedHashMap<>();
            outMap.put("线程名", e.getKey().getName());
            outMap.put("线程号", e.getKey().getId());
            outMap.put("线程状态", st.name());
            outMap.put("状态在白名单内", CLOSE_FRAME_STATE_ALLOWLIST.contains(st));
            outMap.put("尺没见过的状态", !CLOSE_FRAME_STATE_ALLOWLIST.contains(st));
            outMap.put("白名单", CLOSE_FRAME_STATE_ALLOWLIST.stream().map(Enum::name).sorted().toList());
            outMap.put("栈摘录", excerpt.subList(0, Math.min(14, excerpt.size())));
            Map<String, Object> holder = writeLockHolderThread();
            outMap.put("它在等谁（持 NioSocketImpl 写锁的线程）",
                    holder == null ? "取不到——同一份转储里没找出持锁的那条" : holder);
            outMap.put("JDK", System.getProperty("java.version") + " / "
                    + System.getProperty("java.vm.name"));
            everything.add(outMap);
        }
        return everything;
    }

    /**
     * 这一次阻塞<b>撑了多久</b>：从此刻起，等到再没有人卡在关闭帧的写里
     * <p>
     * 🔴 每一条「在窗口内没发生」型的判据（认证闸、转实时流）都<b>默认阻塞撑得过那个窗口</b>。
     * 那个默认从来没人量过——实测到过一次 2703ms 自行解开、窗口 3000ms，
     * 只差 297ms，于是那一轮的「修前红」变成了绿。
     * <b>撑不撑得过窗口，是量出来的，不是拍的。</b>
     * <p>
     * 机制上它本来就会自行解开：对端不读，但环回 TCP 的接收缓冲会随时间被内核放大，
     * 窗口一开，那 125 字节就写出去了。所以这个数是<b>分布</b>，要留余量。
     *
     * 🔴 上限按<b>对照格</b>算，量出来的时长也一并折成格：判据的窗口是按格开的，
     * 「撑不撑得过窗口」这一问只有在<b>同一把尺</b>上才答得了。
     * <p>
     * 🔴 <b>这是全套里唯一一条会被负载往坏处推的量。</b>阻塞那两三秒是内核按<b>时间</b>
     * 放大接收缓冲放出来的，不随 CPU 负载变长；而窗口按格算、格随负载变长。
     * 所以窗口不能开大（{@link #OBSERVE_TICKS} 取 2 就是为了这个），
     * 这一格量的正是「窗口还没伸出复现之外」。
     *
     * @param 钟   对照钟
     * @param 格数 等到对照钟走了这么多格还卡着就不等了
     * @return 阻塞撑了多久，以及折成格之后是多少
     */
    static BlockReading blockHeldFor(ReferenceClock clock, long tickCount) throws InterruptedException {
        long startTick = clock.read();
        long startMillis = System.currentTimeMillis();
        long deadlineAt = startMillis + Math.max(1, tickCount) * clock.nominalTickMs() * 20;
        boolean atCap = true;
        while (clock.read() - startTick < tickCount && System.currentTimeMillis() < deadlineAt) {
            if (!reproHolds(priorGaugeSomeoneStuckWritingCloseFrame())) {
                atCap = false;
                break;
            }
            Thread.sleep(25);
        }
        TimeSegment segment = new TimeSegment(startTick, clock.read(), startMillis, System.currentTimeMillis(), clock.nominalTickMs());
        return new BlockReading(segment.wallClockMs(), segment.toMilliTick(segment.wallClockMs()), atCap, segment);
    }

    /** 余量尺的读数 */
    record BlockReading(long heldMs, long heldMilliTick, boolean awaitCapReached, TimeSegment segment) {
        /** 撑过观测窗口之后还余下多少（千分格）。为负就是没撑住 */
        long marginMilliTick() {
            return heldMilliTick - OBSERVE_TICKS * 1000;
        }

        Map<String, Object> reading() {
            Map<String, Object> outMap = new LinkedHashMap<>();
            outMap.put("阻塞撑了毫秒", heldMs);
            outMap.put("阻塞撑了（格）", milliTickToString(heldMilliTick));
            outMap.put("观测窗口（格）", OBSERVE_TICKS);
            outMap.put("余量（格）", milliTickToString(marginMilliTick()));
            outMap.put("要求的最小余量（格）", milliTickToString(BLOCK_MARGIN_MILLI_TICK));
            outMap.put("等到上限就不等了", awaitCapReached);
            outMap.put("观测段", segment.reading());
            return outMap;
        }
    }

    /**
     * 先验尺的一句话结论：这一跑复现成立吗
     *
     * @param 尺读 {@link #priorGaugeSomeoneStuckWritingCloseFrame()} 的返回
     * @return 有人卡在关闭帧的写里就为真
     */
    static boolean reproHolds(List<Map<String, Object>> gaugeReading) {
        return gaugeReading != null && !gaugeReading.isEmpty();
    }

    /**
     * 尺见到了它没见过的线程状态吗
     * <p>
     * 🔴 这不是红也不是绿：<b>这把尺没见过这个形态</b>，它量出来的东西不作数，
     * 由外部收集方判 ABORT 带实录。
     */
    static boolean gaugeUnseenState(List<Map<String, Object>> gaugeReading) {
        return gaugeReading != null && gaugeReading.stream().anyMatch(x -> Boolean.TRUE.equals(x.get("尺没见过的状态")));
    }

    /** 心跳线程此刻卡在谁身上。返回栈摘录；没卡住时返回 null */
    static List<String> heartbeatThreadStuckOnAnotherMonitor() {
        for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
            if (!e.getKey().getName().startsWith("nova-event-heartbeat")) {
                continue;
            }
            if (e.getKey().getState() != Thread.State.BLOCKED) {
                continue;
            }
            List<String> excerpt = new ArrayList<>();
            for (StackTraceElement f : e.getValue()) {
                excerpt.add(f.getClassName() + "." + f.getMethodName());
            }
            return excerpt.subList(0, Math.min(8, excerpt.size()));
        }
        return null;
    }

    // ══════════════════════════ 阳性对照：人为把心跳线程钉住 ══════════════════════════

    /**
     * 钉住闸：<b>人为</b>把共享心跳线程钉在关闭帧的写里，用来证明各格咬得动
     * <p>
     * 🔴 <b>没有阳性对照的绿，说明不了任何事。</b>判据 0～4 全都在问「洞不在的时候别人好不好」，
     * 而「洞不在」和「格子不咬人」的绿长得一模一样。所以每一格另有一格：
     * 把心跳线程真钉住一次，看那一格红不红；红完就把钉子拔掉。
     * <p>
     * 🔴 <b>钉在哪一步要紧。</b>心跳线程承接两类活：
     * <ul>
     *   <li><b>遍历外</b>（认证闸、回补窗口这类派上去的一次性活）——钉在这里，
     *       全局 ping 停发，判据 1／2／3 会红，但<b>判据 4 不会</b>：
     *       所有连接一起停，两簇没有差分。</li>
     *   <li><b>遍历中间</b>（{@code heartbeat()} 挨个发 ping 那一趟）——钉在这里，
     *       排在后面的连接这一轮轮不到，判据 4 才咬得着。</li>
     * </ul>
     * 这把闸走的是后者：让心跳线程自己在遍历里踩进一条连接的同步 {@code close}。
     * 门是 {@code send} 的「待发队列满了就地关」那一支——<b>队列灌满，下一发 ping 就踩进去</b>。
     * <p>
     * 🔴 <b>钉子要拔得动、也不许自己松。</b>关闭帧那 125 字节会被内核放大的接收缓冲吃掉，
     * 两三秒后阻塞自行解开——<b>自己松开的钉子和「格子不咬人」长得一样</b>。
     * 所以钉子写一个对端永远吞不下的数（{@link #PIN_BYTES}），只由 {@link #unplug()} 关对端来解。
     */
    final class PinGate implements AutoCloseable {
        /** 钉子写多大一坨：要大到对端的缓冲永远吞不下，钉子才不会自己松 */
        static final int PIN_BYTES = 8 * 1024 * 1024;

        /** 这把钉子钉在哪条连接上；它的位次决定了判据 4 的两簇怎么分 */
        final OrderReading order;

        private final NovaEventSlowConsumerTest.SocketSession socketSession;

        private final Thread keepFresh;

        private volatile boolean received = false;

        private final Map<String, Object> pinnedReading = new LinkedHashMap<>();

        /**
         * @param id 钉子那条连接的 id。要落在健康连接<b>中间</b>，判据 4 的两簇才都不空——
         *           用 {@link NovaEventSlowConsumerHarness#pickMiddleId} 算着挑
         */
        PinGate(String id) throws Exception {
            List<String> allIds = new ArrayList<>(connected);
            allIds.add(id);
            this.order = computeOrder(id, allIds);
            // 先把 socket 灌到写不动：泵一写就卡住，往后的 ping 全堆在待发队列里。
            // 不灌的话泵会一直把队列抽干，队列永远满不了，钉子就钉不下去。
            this.socketSession = new NovaEventSlowConsumerTest.SocketSession(
                    id, 1024, 1024, false, true, PIN_BYTES);
            realSession.add(this.socketSession);
            long fill = this.socketSession.filled();
            connected.add(id);
            endpoint.afterConnectionEstablished(this.socketSession);
            authenticated(this.socketSession, tokens.issue(id));

            // 🔴 保鲜：不回 pong 的话，超时清理会先把它 closeAsync 掉——
            //    那一支是**派给发送线程**的，心跳线程根本不会被钉住，这把闸就成了摆设。
            this.keepFresh = new Thread(() -> {
                while (!received) {
                    try {
                        endpoint.handleTextMessage(socketSession, new TextMessage("{\"kind\":\"pong\"}"));
                        Thread.sleep(Math.max(10, PING / 4));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    } catch (RuntimeException ignored) {
                        return;
                    }
                }
            }, "nova-test-pin-keepalive-" + id);
            this.keepFresh.setDaemon(true);
            this.keepFresh.start();

            long fillQueue = fillPendingQueueNearFull(id);
            List<Map<String, Object>> gaugeReading = awaitHeartbeatThreadPinned();
            pinnedReading.put("钉子 id", id);
            pinnedReading.put("灌进 socket 的字节", fill);
            pinnedReading.put("灌进待发队列的条数", fillQueue);
            pinnedReading.put("钉住了吗", gaugeReading != null);
            pinnedReading.put("实录", String.valueOf(gaugeReading));
            pinnedReading.put("算序", order.reading());
            reading("阳性对照-钉住闸", pinnedReading);
            if (gaugeReading == null) {
                unplug();
                fail("钉住闸没能把共享心跳线程钉住 —— **阳性对照本身没成立**，"
                        + "这一格证不了「格子咬人」。先查这把闸：待发队列灌进了 " + fillQueue
                        + " 条（-1 ＝ 这条连接压根没转进实时流，灌什么都进不了队列），"
                        + "socket 灌了 " + fill + " 字节。");
            }
        }

        /**
         * 把这条连接的待发队列灌到<b>差一点就满</b>
         * <p>
         * 🔴 <b>不能灌到溢出。</b>溢出那一发触发的同步 {@code close} 跑在<b>灌的人</b>身上——
         * 灌的是本线程，钉住的就是本线程，心跳线程一根汗毛没动。
         * 留两格空位，让心跳自己发的那几帧 ping 去踩，钉子才钉在心跳线程上。
         * <p>
         * 🔴 <b>要先等它转进实时流再灌。</b>没订阅之前 {@code publish} 根本到不了这条连接的
         * 待发队列，灌多少都是空的；而<b>一边不停 publish 一边等 goLive，是等不到的</b>——
         * {@code goLive} 派在心跳线程上、要拿事件流的锁，一个不歇气的 publish 循环
         * 正好把它饿在那儿。头一版就是这么写的：灌了一千六百万条，队列一格没满，
         * 钉子钉不下去，而<b>钉不下去的阳性对照和「格子不咬人」长得一模一样</b>。
         */
        private long fillPendingQueueNearFull(String id) throws Exception {
            Object wentLive = clock.await(REPRO_WAIT_TICKS, () -> hasEnteredLiveStream(id) ? Boolean.TRUE : null);
            if (wentLive == null) {
                return -1;
            }
            long sent = 0;
            // 队列容量是「事件流缓冲＋余量」这个量级；给一倍多的余地就够灌满，
            // 再多就是这条路不通了，交给外面那句失败语去说。
            long cap = 8L * (pendingQueue(id) == null ? 0 : pendingQueue(id).remainingCapacity()) + 64;
            while (sent < cap) {
                java.util.concurrent.BlockingQueue<?> q = pendingQueue(id);
                if (q == null) {
                    break;
                }
                if (q.remainingCapacity() <= 2) {
                    return sent;
                }
                stream.publish(event());
                sent++;
            }
            return sent;
        }

        /** 等到先验尺里出现的那条卡住的线程<b>就是</b>共享心跳线程 */
        private List<Map<String, Object>> awaitHeartbeatThreadPinned() throws Exception {
            return clock.await(REPRO_WAIT_TICKS, () -> {
                List<Map<String, Object>> gaugeReading = priorGaugeSomeoneStuckWritingCloseFrame();
                boolean isPing = gaugeReading.stream()
                        .anyMatch(x -> ((Long) x.get("线程号")) == heartbeatThread.getId());
                return isPing ? gaugeReading : null;
            });
        }

        /** 此刻心跳线程还钉着吗——阳性对照自己的收尾戳 */
        boolean stillPinned() {
            return priorGaugeSomeoneStuckWritingCloseFrame().stream()
                    .anyMatch(x -> ((Long) x.get("线程号")) == heartbeatThread.getId());
        }

        /**
         * 拔钉子：关掉对端 socket，那一坨写不出去的字节当场报错，心跳线程走出来
         * <p>
         * 🔴 拔完要<b>确认拔动了</b>：拔不动的钉子会把后面每一格都拖红，
         * 而那些红看起来像是判据自己红的。
         */
        void unplug() throws Exception {
            received = true;
            keepFresh.interrupt();
            socketSession.closeIt();
            Object loosened = clock.await(REPRO_WAIT_TICKS, () -> stillPinned() ? null : Boolean.TRUE);
            reading("阳性对照-拔钉子", Map.of("拔动了", loosened != null,
                    "🔴 拔不动会怎样", "钉子留着，后面每一格都会被拖红，而那些红看起来像判据自己红的"));
        }

        @Override
        public void close() throws Exception {
            unplug();
        }
    }

    /**
     * 反射取一条连接的待发队列
     * <p>
     * 🔴 只<b>读</b>它的余量，不动它一条：钉住闸要知道「还差几格满」，
     * 才能把最后那两格留给心跳自己去踩。
     */
    java.util.concurrent.BlockingQueue<?> pendingQueue(String id) {
        Object v = connectionsField(id, "outbox");
        return (java.util.concurrent.BlockingQueue<?>) v;
    }

    /**
     * 这条连接转进实时流了吗
     * <p>
     * 🔴 钉住闸要靠它<b>先等再灌</b>：没订阅之前灌什么都进不了队列，
     * 而一边灌一边等，反倒把派在心跳线程上的 {@code goLive} 饿住了。
     */
    boolean hasEnteredLiveStream(String id) {
        Object v = connectionsField(id, "live");
        return Boolean.TRUE.equals(v);
    }

    private Object connectionsField(String id, String field) {
        try {
            java.lang.reflect.Field cf = NovaEventEndpoint.class.getDeclaredField("clients");
            cf.setAccessible(true);
            Map<?, ?> m = (Map<?, ?>) cf.get(endpoint);
            Object client = m.get(id);
            if (client == null) {
                return null;
            }
            java.lang.reflect.Field f = client.getClass().getDeclaredField(field);
            f.setAccessible(true);
            return f.get(client);
        } catch (Exception e) {
            throw new IllegalStateException("取不到连接 " + id + " 的 " + field
                    + " —— 钉住闸就下不去，阳性对照证不了「格子咬人」。", e);
        }
    }

    static <T> T await(long capMs, java.util.function.Supplier<T> probe) throws Exception {
        long deadline = System.currentTimeMillis() + capMs;
        while (System.currentTimeMillis() < deadline) {
            T v = probe.get();
            if (v != null) {
                return v;
            }
            Thread.sleep(10);
        }
        return null;
    }

    /**
     * 读数打到标准输出，由外部收集
     * <p>
     * 🔴 前缀里<b>不带编号</b>。编号形状是「这里有一套编号系统」的招牌，
     * 公开面的源码字符串里不该有；改前缀要<b>两头同改</b>（这里与外部收集方的匹配串）。
     */
    static void reading(String readingName, Map<String, Object> value) {
        // 🔴 值里再出现一个叫「读数」的键，就会把**名字**顶掉，
        //    而顶掉之后那条读数看起来跟正常的一模一样——外面按名字找就永远找不到它。
        //    （已经踩过一次：先验尺那条把栈实录塞在「读数」键里，定余量那 20 轮第一轮就停。）
        if (value.containsKey("读数")) {
            throw new IllegalArgumentException("读数「" + readingName + "」的值里有一个叫「读数」的键，"
                    + "它会把名字顶掉。换个键名（比如「实录」）——顶掉之后没人看得出来。");
        }
        JSONObject j = new JSONObject();
        j.put("读数", readingName);
        j.putAll(value);
        System.out.println(READING_MARK + j.toJSONString());
    }
}
