package com.starlwr.bot.core.protocol;

import com.starlwr.bot.core.protocol.NovaEventSlowConsumerHarness.BackRowGauge;
import com.starlwr.bot.core.protocol.NovaEventSlowConsumerHarness.BackRowReading;
import com.starlwr.bot.core.protocol.NovaEventSlowConsumerHarness.OrderReading;
import com.starlwr.bot.core.protocol.NovaEventSlowConsumerHarness.AdvanceReading;
import com.starlwr.bot.core.protocol.NovaEventSlowConsumerHarness.PinGate;
import com.starlwr.bot.core.protocol.NovaEventSlowConsumerHarness.BlockReading;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.starlwr.bot.core.protocol.NovaEventSlowConsumerHarness.PING;
import static com.starlwr.bot.core.protocol.NovaEventSlowConsumerHarness.HEALTHY_CONNECTIONS;
import static com.starlwr.bot.core.protocol.NovaEventSlowConsumerHarness.CLOSE_FRAME_AUTH;
import static com.starlwr.bot.core.protocol.NovaEventSlowConsumerHarness.CLOSE_FRAME_CLIENT_TIMEOUT;
import static com.starlwr.bot.core.protocol.NovaEventSlowConsumerHarness.CLOSE_FRAME_GRACE;
import static com.starlwr.bot.core.protocol.NovaEventSlowConsumerHarness.CLOSE_FRAME_TIMINGS;
import static com.starlwr.bot.core.protocol.NovaEventSlowConsumerHarness.milliTickToString;
import static com.starlwr.bot.core.protocol.NovaEventSlowConsumerHarness.REPRO_WAIT_TICKS;
import static com.starlwr.bot.core.protocol.NovaEventSlowConsumerHarness.SLOW_CLIENT_ID;
import static com.starlwr.bot.core.protocol.NovaEventSlowConsumerHarness.priorGaugeSomeoneStuckWritingCloseFrame;
import static com.starlwr.bot.core.protocol.NovaEventSlowConsumerHarness.HOLE_SIGNATURE_LAG_MILLI_TICK;
import static com.starlwr.bot.core.protocol.NovaEventSlowConsumerHarness.OBSERVE_TICKS;
import static com.starlwr.bot.core.protocol.NovaEventSlowConsumerHarness.reading;
import static com.starlwr.bot.core.protocol.NovaEventSlowConsumerHarness.LAG_TOLERANCE_MILLI_TICK;
import static com.starlwr.bot.core.protocol.NovaEventSlowConsumerHarness.measureHealthyConnectionAdvance;
import static com.starlwr.bot.core.protocol.NovaEventSlowConsumerHarness.measureBackClusterLag;
import static com.starlwr.bot.core.protocol.NovaEventSlowConsumerHarness.BLOCK_MARGIN_MILLI_TICK;
import static com.starlwr.bot.core.protocol.NovaEventSlowConsumerHarness.BLOCK_WAIT_TICKS;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 超时清理时的关闭帧不许钉住全局共享的心跳线程
 * <p>
 * 与「慢消费者」那一组同源、<b>不同洞</b>：那一组是锁——阻塞写与 {@code goLive} 共用
 * per-client 监视器；这一组<b>不经由任何锁</b>——{@code heartbeat()} 顺序遍历连接，
 * 对超时的那条直接调 {@code close}，而真 WebSocket 的 close 要发一帧关闭帧，
 * 落到写不动的 socket 上就是阻塞写。上一组的写锁一点也挡不住它。
 * <p>
 * 伤害面比「线程卡住」多一层：遍历是顺序的，卡住时<b>排在后面的连接这一轮连 ping 都轮不到</b>，
 * 而谁排后面由 id 的散列定、不归我们挑。判据 4 专量这一层。
 *
 * <h2>🔴 这一组的尺一律判相对，不判绝对</h2>
 * 从前判据 4 拿墙钟绝对时长当尺：每条健康连接的相邻两次 ping 不许超过「心跳周期＋余量」
 * ＝ 300ms。<b>机器一有负载，判的就不是代码是机器</b>——实测在空载下已经量到六条健康连接
 * <b>齐齐 320ms</b>。六条一起偏移是「整机被推了一把」的形状，不是「谁被落下了」的形状，
 * 而那把绝对尺分不出这两件事。
 * <p>
 * 现在每一格量的都是<b>差</b>与<b>比</b>：
 * <ul>
 *   <li>判据 4 量「后簇比前簇少收轮次这个状态<b>持续</b>了多久」——负载把两簇一起拖慢，差不动；</li>
 *   <li>判据 1／2／3 的窗口以 {@link NovaEventSlowConsumerHarness.ReferenceClock} 的<b>格</b>计——
 *       那把表和心跳同机器、同负载，只是不经过心跳线程：机器慢它跟着慢，心跳被钉住它照走。
 *       🔴 它<b>不补齐</b>：补齐的表会把欠下的格紧挨着补出来，「走了 2 格」就不再意味着
 *       「墙钟走过两个心跳周期」，判据 1 会在心跳好端端的时候判红——那正是 09-04 那两次偶红；</li>
 *   <li>判据 0 本来就只比线程号，与时间无关，量法不动。</li>
 * </ul>
 * 还留着的那几个数（{@link NovaEventSlowConsumerHarness#LAG_TOLERANCE_MILLI_TICK} 等）都放在<b>只挡量级错</b>
 * 的位置上：两边差着好几倍，线放在中间，且线的来历写在常量的注释里、由台架自检当场断言。
 *
 * <h2>🔴 每一格都配一格阳性对照</h2>
 * 判据 0～4 问的都是「洞不在的时候别人好不好」，而<b>「洞不在」和「格子不咬人」的绿长得一样</b>。
 * 所以每一格另有一格：用 {@link NovaEventSlowConsumerHarness.PinGate} 把共享心跳线程<b>真钉住</b>一次，
 * 看那一格红不红，红完就把钉子拔掉。阳性对照走的是<b>判据在走的那一份量法</b>
 * （{@code measureHealthyConnectionAdvance}／{@code measureBackClusterLag}／同一支探针），不另写一份差不多的——
 * 两把尺量同一件事必生漂移，漂移之后阳性对照证的就不是判据那一格了。
 */
@DisplayName("超时清理时的关闭帧不许钉住共享心跳线程")
class NovaEventCloseFramePinTest {

    @TempDir
    Path dir;

    private NovaEventSlowConsumerHarness harness;

    private BackRowGauge gauge;

    private NovaEventEndpointTest.FakeSession silent;

    private NovaEventEndpointTest.FakeSession otherConnection;

    private NovaEventSlowConsumerHarness.KeepAlive otherConnectionSeen;

    /** 判据 4 的两簇：排在被清理那条前面／后面的健康连接 */
    private OrderReading order;

    @AfterEach
    void tearDown() {
        if (gauge != null) {
            gauge.close();
            gauge = null;
        }
        if (harness != null) {
            harness.close();
            harness = null;
        }
    }

    /**
     * 支起台架
     * <p>
     * 🔴 <b>次序要紧</b>：健康连接必须<b>先</b>接上并开始收 ping，慢客户端<b>后</b>接。
     * 反过来的话，清理会在健康连接接上之前就触发，它们一个 ping 也没收到过，
     * 两簇无从比较——<b>那看起来像夹具搭不起来，其实是次序排反了</b>。
     */
    private void bringUp(boolean slowClientReads) throws Exception {
        harness = new NovaEventSlowConsumerHarness(dir, slowClientReads, CLOSE_FRAME_TIMINGS, SLOW_CLIENT_ID, true);

        Map<String, NovaEventEndpointTest.FakeSession> healthyOnes = new LinkedHashMap<>();
        for (int i = 0; i < HEALTHY_CONNECTIONS; i++) {
            healthyOnes.put("healthy-" + i, harness.connectAndAuthenticate("healthy-" + i));
        }
        gauge = new BackRowGauge(harness.endpoint, healthyOnes);

        // 让健康连接先实实在在收几轮 ping。等的是**对照格**不是毫秒：
        // 机器慢时该多等一会儿，等的是「心跳该走过几轮了」，不是「墙上过了多久」。
        harness.clock.awaitTicks(3);

        if (slowClientReads) {
            // 阴性对照：对端一直在读，**灌不满**是应该的，别去灌。
            // 头一版照阳性那条路走，撞了「10 秒内灌不满」——那不是发现问题，是路走错了。
            harness.endpoint.afterConnectionEstablished(harness.slowClient);
            harness.authenticated(harness.slowClient, harness.tokens.issue("正常读的客户端"));
        } else {
            harness.bringUpSlowClient();           // 灌满 → 连 → 认证；它不回 pong，故会被超时清理
        }

        // 🔴 沉默连接与他连在**慢客户端支起之后**才接上：它们的闸要在卡住那一刻还悬着。
        //    接得太早的话，卡住之前它们自己就办完了，判据 2／3 会在洞还在时变绿。
        silent = harness.connection("silent");
        otherConnection = harness.connectAndAuthenticate("other");
        otherConnectionSeen = harness.keepAliveRunner(otherConnection);

        order = NovaEventSlowConsumerHarness.computeOrder(SLOW_CLIENT_ID, HEALTHY_CONNECTIONS, "silent", "other");

        // 🔴 这一觉睡的是**端点自己那条时限**：超时清理按墙钟到点，负载改不了它到点的时刻，
        //    所以这里仍旧是毫秒。睡完之后**等复现成立**那一段才按对照格算。
        Thread.sleep(CLOSE_FRAME_CLIENT_TIMEOUT);
        if (slowClientReads) {
            // 阴性对照：慢端一直在读，**本来就不该有人卡住**。
            // 这里不空等那个上限——等一个注定不来的东西，等满了也只是慢，说明不了任何事。
            // 换成一句反向读数：短暂确认之后仍然没有人卡在关闭帧的写里。
            harness.clock.awaitTicks(1);
            reading("开窗-阴性对照", Map.of(
                    "先睡毫秒", CLOSE_FRAME_CLIENT_TIMEOUT,
                    "又等了对照格", 1,
                    "此刻复现成立", NovaEventSlowConsumerHarness.reproHolds(priorGaugeSomeoneStuckWritingCloseFrame()),
                    "🔴 这一格要的正是不成立", "慢端正常读时没有阻塞可复现；"
                            + "这里若成立，说明夹具把不该卡的也卡住了，四条全绿就不作数"));
            return;
        }
        // 🔴 窗口的起点要**等到复现成立**再开，不是等一个拍出来的固定时长。
        //    拍固定时长的话，阻塞若在窗口开了之后才成立，排在慢客户端**前面**那几条
        //    会在窗口内收到那一轮 ping——判据 1 的修前红就成了掷骰子。
        //
        //    能这么等，正是因为先验尺是**相无关**的：它问「有没有人卡在关闭帧的写里」，
        //    修前是心跳线程、修后是发送线程，两相都成立。
        //    换成等「心跳线程被钉住」就不行了——那是修好之后永远等不到的东西。
        long awaitStart = System.currentTimeMillis();
        Object done = harness.clock.await(REPRO_WAIT_TICKS,
                () -> NovaEventSlowConsumerHarness.reproHolds(priorGaugeSomeoneStuckWritingCloseFrame()) ? Boolean.TRUE : null);
        reading("开窗-等复现成立", Map.of(
                "先睡毫秒", CLOSE_FRAME_CLIENT_TIMEOUT,
                "又等了毫秒", System.currentTimeMillis() - awaitStart,
                "等的预算（对照格）", REPRO_WAIT_TICKS,
                "等到上限就不等了", done == null,
                "此刻复现成立", NovaEventSlowConsumerHarness.reproHolds(priorGaugeSomeoneStuckWritingCloseFrame()),
                "🔴 为什么不是固定时长", "阻塞在窗口开了之后才成立的话，前簇那几条会在窗口内"
                        + "收到那一轮 ping，判据 1 的修前红就成了掷骰子"));
    }

    /**
     * 阳性对照共用的夹具：慢客户端正常读（不制造真阻塞），钉子由钉住闸自己下
     * <p>
     * 🔴 钉子的 id <b>算着挑</b>，不写死一个顺眼的：位次由散列定，
     * 写死的那个换个 JDK 就落到最末尾，两簇有一边空掉——而空掉之后它看起来还是绿的。
     */
    private PinGate bringUpAndPin() throws Exception {
        bringUp(true);
        OrderReading picked = NovaEventSlowConsumerHarness.pickMiddleId("pin-", HEALTHY_CONNECTIONS,
                List.of(SLOW_CLIENT_ID, "silent", "other"));
        assertTrue(picked != null, "挑不出一个落在健康连接中间的钉子 id —— "
                + "两簇总有一边是空的，判据 4 的阳性对照就成了空真。先查算序（多半是换了 JDK）。");
        order = picked;
        return harness.new PinGate(picked.slowId());
    }

    // ══════════════════════════ 先验尺 ══════════════════════════

    /**
     * 每一格判据的<b>前提戳</b>：这一跑复现成立吗
     * <p>
     * 🔴 判据 1～4 问的都是「洞在的时候，别人还好不好」。<b>洞不在，这一问就没有意义</b>——
     * 而「没复现出来」和「修好了」在判据的绿上长得一模一样。
     * 所以每一格开头都要盖一次戳，不是只在判据 0 那一格盖。
     * <p>
     * 这把先验尺是<b>修前修后都要的</b>戳（它问的是「有没有人卡在关闭帧的写里」，
     * 修前是心跳线程、修后是发送线程，两相都成立）。
     * <b>只在红时才检查的前提，会让绿变成不带前提的绿。</b>
     *
     * @param 格名 落读数用
     * @return 先验尺的全量读数
     */
    private List<Map<String, Object>> premiseStamp(String tickName) {
        List<Map<String, Object>> gaugeReading = priorGaugeSomeoneStuckWritingCloseFrame();
        reading(tickName + "-前提戳", Map.of(
                "复现成立", NovaEventSlowConsumerHarness.reproHolds(gaugeReading),
                "卡住的线程条数", gaugeReading.size(),
                "实录", String.valueOf(gaugeReading)));
        assertTrue(NovaEventSlowConsumerHarness.reproHolds(gaugeReading),
                "这一跑没有任何人卡在关闭帧的写里 —— **复现根本没成立**，这一格不算数。"
                        + "没复现出阻塞时，判据的绿和修好了的绿长得一样。");
        assertTrue(!NovaEventSlowConsumerHarness.gaugeUnseenState(gaugeReading),
                "先验尺见到了它没见过的线程状态：**这把尺没见过这个形态**，"
                        + "它量出来的东西不作数。先查这个状态是怎么来的。实录：" + gaugeReading);
        return gaugeReading;
    }

    /**
     * 收尾戳：窗口走完之后，阻塞<b>还</b>在吗
     * <p>
     * 🔴 前提只在<b>开窗那一刻</b>成立是不够的。判据 1～4 问的都是
     * 「洞<b>在的那段时间里</b>，别人还好不好」——阻塞若在窗口中途自行解开，
     * 闸会在剩下的时间里自己办完，判据当场变绿，<b>而那不是修好了，是这一跑没撑住</b>。
     * <p>
     * 实测撞到过两次：2703ms／窗口 3000ms，2973ms／窗口 3000ms——后者只差 27ms。
     * 观测窗口后来改成按对照格开、且只开 {@link NovaEventSlowConsumerHarness#OBSERVE_TICKS} 格，
     * 正是为了让它稳稳落在阻塞之内。
     * <p>
     * 🔴 <b>另开一格量「阻塞能撑多久」挡不住它</b>：那一格量的是<b>它自己那一跑</b>，
     * 判据是另一个夹具实例、另一次阻塞。尺和判据量的不是同一次事件，
     * 尺再准也管不着判据那一跑——前提得在<b>本格自己的窗口</b>上验。
     *
     * @param 格名 落读数用
     */
    private void endStamp(String tickName) {
        List<Map<String, Object>> gaugeReading = priorGaugeSomeoneStuckWritingCloseFrame();
        boolean stillStuck = NovaEventSlowConsumerHarness.reproHolds(gaugeReading);
        reading(tickName + "-收尾戳", Map.of(
                "窗口走完后阻塞还在", stillStuck,
                "观测窗口（对照格）", OBSERVE_TICKS,
                "🔴 为什么要收尾再看一次", "阻塞中途解开的话，闸会在剩下的时间里自己办完，"
                        + "判据变绿——而那不是修好了，是这一跑没撑住"));
        assertTrue(stillStuck, "窗口（" + OBSERVE_TICKS + " 个对照格）还没走完，阻塞就自行解开了。"
                + "剩下的时间里闸能自己办完，这一格的结论**不算数**——"
                + "不是修好了，是这一跑没撑住。（机制：对端不读，但环回 TCP 的接收缓冲"
                + "会被内核随时间放大，窗口一开那 125 字节就写出去了。）");
    }

    @Test
    @DisplayName("先验尺：这一跑里有人卡在关闭帧的写里（复现成立）")
    void priorGaugeSomeoneStuckInCloseFrameWrite() throws Exception {
        bringUp(false);
        List<Map<String, Object>> gaugeReading = priorGaugeSomeoneStuckWritingCloseFrame();
        reading("先验尺-关闭帧", Map.of(
                "有人卡在关闭帧的写里", NovaEventSlowConsumerHarness.reproHolds(gaugeReading),
                "卡住的线程条数", gaugeReading.size(),
                "实录", String.valueOf(gaugeReading),
                "🔴 这把尺只量现象", "卡住的是**哪条**线程是判据 0 的事，不是这把尺的事。"
                        + "把归属写进复现尺，修好那天它必死——而死掉的尺让「修好了」"
                        + "和「压根没灌满」长得一样。"));
        assertTrue(NovaEventSlowConsumerHarness.reproHolds(gaugeReading),
                "这一跑没有任何人卡在关闭帧的写里 —— **复现根本没成立**。"
                        + "拿不到它就不许采信下面几条判据。");
        assertTrue(!NovaEventSlowConsumerHarness.gaugeUnseenState(gaugeReading),
                "线程状态不在白名单内。这不是红也不是绿：**这把尺没见过这个形态**，"
                        + "它量出来的东西不作数。实录：" + gaugeReading);
    }

    @Test
    @DisplayName("余量尺：这一次阻塞撑得过观测窗口（判据 2／3／4 的修前红全靠它）")
    void marginGaugeBlockOutlastsObserveWindow() throws Exception {
        bringUp(false);
        premiseStamp("余量尺");

        BlockReading r = NovaEventSlowConsumerHarness.blockHeldFor(harness.clock, BLOCK_WAIT_TICKS);
        Map<String, Object> Reading = new LinkedHashMap<>(r.reading());
        Reading.put("🔴 为什么要量这个", "判据的修前红全靠「阻塞撑得过窗口」。撑不过的那一轮，"
                + "闸会在窗口内自己办完，红就变成绿——而那不是修好了，是这一跑没撑住。"
                + "曾实测到 2703ms 对 3000ms 窗口，只差 297ms。");
        Reading.put("🔴 这是全套里唯一被负载往坏处推的量", "阻塞那两三秒是内核按**时间**放大接收缓冲"
                + "放出来的，不随 CPU 负载变长；而窗口按对照格算、格随负载变长。"
                + "所以窗口只开 " + OBSERVE_TICKS + " 格，留出的正是这一格量的余量。");
        reading("余量尺-阻塞时长", Reading);

        assertTrue(r.marginMilliTick() >= BLOCK_MARGIN_MILLI_TICK,
                "这一次阻塞只撑了 " + r.heldMs() + "ms ＝ " + milliTickToString(r.heldMilliTick())
                        + " 个对照格（本跑一格 " + r.segment().runTickMs() + "ms），观测窗口 "
                        + OBSERVE_TICKS + " 格，余量 " + milliTickToString(r.marginMilliTick())
                        + " 格不到要求的 " + milliTickToString(BLOCK_MARGIN_MILLI_TICK) + " 格。"
                        + "余量不够时，判据的修前红是**掷骰子**：阻塞早解开一点，"
                        + "闸就在窗口内自己办完了。这一跑不算数——不是修好了，是没撑住。");
    }

    // ══════════════════════════ 判据 0 ══════════════════════════

    /**
     * 判据 0 的量法：卡住的那几条线程里，有没有一条是夹具登记在案的共享心跳线程
     * <p>
     * 🔴 <b>这一格天生就是相对的</b>：它比的是线程号，一个毫秒都不掺。
     * 负载再高也改不了「卡住的是哪条线程」，所以它不用改量法——
     * <b>本来就没病的格子不该跟着动刀。</b>
     */
    private List<Map<String, Object>> measureHeartbeatThreadPinned(String tickName,
                                                  List<Map<String, Object>> gaugeReading) {
        // 🔴 判的是「**没有任何一条**是心跳线程」，不是「抽中的那条不是」。
        //    尺收全量正是为了这一句：只看第一条命中的话，同时有两条卡住时
        //    可能抽中发送线程而判绿，而这一格自称判的是前者。
        List<Map<String, Object>> heartbeatLines = gaugeReading.stream()
                .filter(x -> ((Long) x.get("线程号")) == harness.heartbeatThread.getId())
                .toList();
        reading(tickName + "-归属", Map.of(
                "卡住的线程条数", gaugeReading.size(),
                "卡住的线程名", gaugeReading.stream().map(x -> String.valueOf(x.get("线程名"))).toList(),
                "夹具登记的心跳线程号", harness.heartbeatThread.getId(),
                "夹具登记的心跳线程名", harness.heartbeatThread.getName(),
                "其中是共享心跳线程的条数", heartbeatLines.size(),
                "🔴 身份怎么判的", "与夹具登记在案的那条线程比线程号，不按名字前缀猜；"
                        + "且判的是**全量里没有一条**，不是「抽中的那条不是」"));
        return heartbeatLines;
    }

    @Test
    @DisplayName("判据 0：卡住的线程里没有一条是共享心跳线程")
    void criterion0StuckThreadIsNotSharedHeartbeat() throws Exception {
        bringUp(false);
        List<Map<String, Object>> gaugeReading = premiseStamp("判据0");
        List<Map<String, Object>> heartbeatLines = measureHeartbeatThreadPinned("判据0", gaugeReading);

        assertTrue(heartbeatLines.isEmpty(), "关闭帧的阻塞写把**全局共享的心跳线程**（"
                + harness.heartbeatThread.getName() + "）钉住了。它一停，所有连接的 ping、认证时限、"
                + "回补窗口一起停；还多一层——遍历是顺序的，排在它后面的连接这一轮连 ping 都轮不到。"
                + "实录：" + heartbeatLines);
    }

    @Test
    @DisplayName("阳性对照·判据 0：真把心跳线程钉住时，这一格必须红")
    void positiveControlCriterion0() throws Exception {
        try (PinGate pin = bringUpAndPin()) {
            List<Map<String, Object>> heartbeatLines =
                    measureHeartbeatThreadPinned("阳性对照-判据0", priorGaugeSomeoneStuckWritingCloseFrame());
            assertTrue(!heartbeatLines.isEmpty(),
                    "心跳线程已经被钉住了，判据 0 的量法却一条都没认出来——**这一格不咬人**。"
                            + "它的绿从此说明不了任何事。先查身份判定（线程号是怎么登记的）。");
        }
    }

    // ══════════════════════════ 四条判据 ══════════════════════════

    @Test
    @DisplayName("判据 1：清理一个写不动的客户端时，健康连接仍收得到 ping")
    void criterion1HealthyConnectionsStillGetPing() throws Exception {
        bringUp(false);
        premiseStamp("判据1");
        AdvanceReading r = measureHealthyConnectionAdvance(gauge, harness.clock, OBSERVE_TICKS);
        endStamp("判据1");
        Map<String, Object> Reading = new LinkedHashMap<>(r.reading());
        Reading.put("此刻连接数", harness.connectionCount());
        Reading.put("心跳间隔毫秒", PING);
        Reading.put("🔴 量的是推进不是绝对间隔", "问的是「对照钟走了 " + OBSERVE_TICKS
                + " 格，心跳走了没有」——两把表比着看，比的是同一份负载");
        // 🔴 这两栏答的是「这一跑的窗口有没有资格问那一问」：窗口里一个心跳周期都不满时，
        //    心跳一轮都不欠，红的是尺不是被测。改前撞到过一次（2 格 / 247ms / 格长 123ms）。
        //    只落读数，不在这里再设一道闸——闸在台架自检那一格上，
        //    同一形态设两道防线，第二道会把第一道的死藏起来。
        Reading.put("窗口覆盖了几个心跳周期", milliTickToString(r.segment().wallClockMs() * 1000 / PING));
        Reading.put("对照钟相邻两格最短间隔毫秒", harness.clock.minTickGapMs());
        if (r.advancedCount() == 0) {
            // 🔴 心跳线程只是排不上号时，它一条也不会出现在先验尺的实录里——
            //    「没被钉住」在读数上是沉默的，而沉默和「尺没量」长得一样
            Reading.put("零推进时的心跳线程", NovaEventSlowConsumerHarness.threadSnapshot(harness.heartbeatThread));
        }
        reading("判据1-ping", Reading);

        assertTrue(r.advancedCount() > 0, "清理那个写不动的客户端时，" + r.totalCount()
                + " 条健康连接在对照钟走过 " + OBSERVE_TICKS + " 格（实测 " + r.segment().wallClockMs()
                + "ms）里**一轮 ping 都没推进**——心跳线程卡在它的关闭帧写里了。"
                + "对照钟与心跳同机器同负载，它走得动而心跳走不动，就不是机器慢。");
    }

    @Test
    @DisplayName("阳性对照·判据 1：真把心跳线程钉住时，这一格必须红")
    void positiveControlCriterion1() throws Exception {
        try (PinGate pin = bringUpAndPin()) {
            AdvanceReading r = measureHealthyConnectionAdvance(gauge, harness.clock, OBSERVE_TICKS);
            reading("阳性对照-判据1", r.reading());
            assertTrue(pin.stillPinned(), "钉子在窗口走完前就松了，这一格的红绿都不算数");
            assertTrue(r.advancedCount() == 0,
                    "心跳线程已经被钉住了，却还有 " + r.advancedCount() + " 条健康连接在推进 ping——"
                            + "**这一格不咬人**，它的绿说明不了任何事。实录：" + r.reading());
        }
    }

    @Test
    @DisplayName("判据 2：清理一个写不动的客户端时，未认证连接仍在时限内被关")
    void criterion2AuthGateStillCloses() throws Exception {
        bringUp(false);
        premiseStamp("判据2");
        long startMillis = System.currentTimeMillis();
        Object closed = harness.clock.await(OBSERVE_TICKS, () -> silent.closedWith);
        endStamp("判据2");
        reading("判据2-认证闸", Map.of("此刻连接数", harness.connectionCount(),
                "认证闸时限毫秒", CLOSE_FRAME_AUTH, "观测窗口（对照格）", OBSERVE_TICKS,
                "关上了", closed != null, "实际关闭耗时毫秒", System.currentTimeMillis() - startMillis,
                "🔴 窗口为什么按格算", "认证闸是派在心跳线程上的一次性活；问的是"
                        + "「对照钟走了 " + OBSERVE_TICKS + " 格，那件活办了没有」，不是「几毫秒内办没办」"));

        assertTrue(closed != null, "清理那个写不动的客户端时，未认证连接在对照钟走过 "
                + OBSERVE_TICKS + " 格里没有被关——认证闸（" + CLOSE_FRAME_AUTH
                + " 毫秒）派在心跳线程上，而它卡住了");
    }

    @Test
    @DisplayName("阳性对照·判据 2：真把心跳线程钉住时，这一格必须红")
    void positiveControlCriterion2() throws Exception {
        try (PinGate pin = bringUpAndPin()) {
            // 🔴 要一条**闸还悬着**的未认证连接：钉子下去之前就被关掉的那条，
            //    证不了「钉住时关不掉」，只证得了「它早就关掉了」。
            NovaEventEndpointTest.FakeSession newSilent = harness.connection("silent-2");
            Object closed = harness.clock.await(OBSERVE_TICKS, () -> newSilent.closedWith);
            reading("阳性对照-判据2", Map.of("认证闸时限毫秒", CLOSE_FRAME_AUTH,
                    "观测窗口（对照格）", OBSERVE_TICKS, "关上了", closed != null));
            assertTrue(pin.stillPinned(), "钉子在窗口走完前就松了，这一格的红绿都不算数");
            assertTrue(closed == null,
                    "心跳线程已经被钉住了，未认证连接却还是被关掉了——**这一格不咬人**。"
                            + "认证闸没派在那条被钉住的线程上，或者探针问错了东西。");
        }
    }

    @Test
    @DisplayName("判据 3：清理一个写不动的客户端时，其它连接仍能转进实时流")
    void criterion3OtherConnectionStillEntersLiveStream() throws Exception {
        bringUp(false);
        premiseStamp("判据3");
        harness.stream.publish(NovaEventSlowConsumerHarness.event());
        Object reached = harness.clock.await(OBSERVE_TICKS, () -> otherConnectionSeen.seen("danmaku") ? Boolean.TRUE : null);
        endStamp("判据3");
        reading("判据3-转实时流", Map.of("此刻连接数", harness.connectionCount(),
                "回补窗口毫秒", CLOSE_FRAME_GRACE, "观测窗口（对照格）", OBSERVE_TICKS,
                "他连收到事件", reached != null));

        assertTrue(reached != null, "清理那个写不动的客户端时，其它连接在对照钟走过 "
                + OBSERVE_TICKS + " 格里没能转进实时流——goLive（回补窗口 " + CLOSE_FRAME_GRACE
                + " 毫秒）派在心跳线程上，而它卡住了");
    }

    @Test
    @DisplayName("阳性对照·判据 3：真把心跳线程钉住时，这一格必须红")
    void positiveControlCriterion3() throws Exception {
        try (PinGate pin = bringUpAndPin()) {
            // 🔴 同判据 2：要一条**回补窗口还悬着**的连接。钉子下去之前就 goLive 了的那条，
            //    早已在实时流上，收得到事件证不了任何事。
            NovaEventEndpointTest.FakeSession newOtherConnection = harness.connectAndAuthenticate("other-2");
            NovaEventSlowConsumerHarness.KeepAlive newOtherConnectionSeen = harness.keepAliveRunner(newOtherConnection);
            harness.stream.publish(NovaEventSlowConsumerHarness.event());
            Object reached = harness.clock.await(OBSERVE_TICKS, () -> newOtherConnectionSeen.seen("danmaku") ? Boolean.TRUE : null);
            reading("阳性对照-判据3", Map.of("回补窗口毫秒", CLOSE_FRAME_GRACE,
                    "观测窗口（对照格）", OBSERVE_TICKS, "他连收到事件", reached != null));
            assertTrue(pin.stillPinned(), "钉子在窗口走完前就松了，这一格的红绿都不算数");
            assertTrue(reached == null,
                    "心跳线程已经被钉住了，新连接却还是转进了实时流——**这一格不咬人**。"
                            + "goLive 没派在那条被钉住的线程上，或者探针问错了东西。");
        }
    }

    /**
     * 判据 4 的两簇先要都不空
     * <p>
     * 🔴 判据 4 有一条<b>与相无关</b>的前提：这一跑里<b>真的有人排在后面</b>。
     * 被清理那条要是恰好排在遍历的最末尾，后排一个人都没有，
     * 「没有人陪葬」这句话就是空真——换个 id（比如 slow-8）修后照样绿，
     * 而这条绿证不了「后排不陪葬」。
     * <p>
     * 前簇同样不能空：<b>前簇是后簇的对照</b>。没有前簇就没有「比谁」，
     * 那把相对的尺当场退回成绝对的。
     */
    private void bothClustersNonEmpty(String tickName) {
        reading(tickName + "-两簇", order.reading());
        assertTrue(!order.backCluster().isEmpty(), "这一组 id 算出来的遍历次序里，被清理那条后面"
                + "**一个健康连接都没有**：" + order.reading() + "。后排是空的，「后排不陪葬」"
                + "这一格就是空真——换个 id 再来。");
        assertTrue(!order.frontCluster().isEmpty(), "这一组 id 算出来的遍历次序里，被清理那条前面"
                + "**一个健康连接都没有**：" + order.reading() + "。没有前簇就没有对照，"
                + "这把相对的尺当场退回成绝对的——换个 id 再来。");
    }

    @Test
    @DisplayName("判据 4：后排不陪葬 —— 后簇不许持续落在前簇后面")
    void criterion4BackRowNotDraggedDown() throws Exception {
        bringUp(false);
        premiseStamp("判据4");
        bothClustersNonEmpty("判据4");

        BackRowReading r = measureBackClusterLag(gauge, order.frontCluster(), order.backCluster(), harness.clock, OBSERVE_TICKS);
        endStamp("判据4");
        Map<String, Object> Reading = new LinkedHashMap<>(r.reading());
        Reading.put("此刻连接数", harness.connectionCount());
        Reading.put("各条轮次", gauge.eachRounds());
        reading("判据4-后排", Reading);

        assertTrue(r.maxLagMilliTick() < LAG_TOLERANCE_MILLI_TICK, NovaEventSlowConsumerHarness.criterion4FailureMessage(r));
    }

    @Test
    @DisplayName("阳性对照·判据 4：真把心跳线程钉在遍历中间时，这一格必须红")
    void positiveControlCriterion4() throws Exception {
        try (PinGate pin = bringUpAndPin()) {
            bothClustersNonEmpty("阳性对照-判据4");
            BackRowReading r = measureBackClusterLag(gauge, order.frontCluster(), order.backCluster(), harness.clock, OBSERVE_TICKS);
            reading("阳性对照-判据4", r.reading());
            assertTrue(pin.stillPinned(), "钉子在窗口走完前就松了，这一格的红绿都不算数");
            assertTrue(r.maxLagMilliTick() >= LAG_TOLERANCE_MILLI_TICK,
                    "心跳线程已经被钉在遍历中间了，后簇却没有持续落后——**这一格不咬人**，"
                            + "它的绿说明不了任何事。最长落后 " + milliTickToString(r.maxLagMilliTick())
                            + " 格，线 " + milliTickToString(LAG_TOLERANCE_MILLI_TICK) + " 格。实录：" + r.reading());
            assertTrue(r.maxLagMilliTick() >= HOLE_SIGNATURE_LAG_MILLI_TICK / 2,
                    "后簇是落后了，但只落后了 " + milliTickToString(r.maxLagMilliTick())
                            + " 格，离洞的签名（" + milliTickToString(HOLE_SIGNATURE_LAG_MILLI_TICK)
                            + " 格，＝整段窗口）差得远。真被钉住时后簇要等整段阻塞才轮得到，"
                            + "所以这更像**钉子没钉在遍历中间**——先查钉住闸走的是哪一支 close。");
        }
    }

    // ══════════════════════════ 阴性对照 ══════════════════════════

    @Test
    @DisplayName("阴性对照：同一夹具、慢客户端正常读 —— 四条判据必须全绿")
    void negativeControlAllFourGreenWhenReadingNormally() throws Exception {
        bringUp(true);
        bothClustersNonEmpty("阴性对照");
        harness.stream.publish(NovaEventSlowConsumerHarness.event());

        AdvanceReading one = measureHealthyConnectionAdvance(gauge, harness.clock, OBSERVE_TICKS);
        Object two = harness.clock.await(OBSERVE_TICKS, () -> silent.closedWith);
        boolean three = otherConnectionSeen.seen("danmaku");
        BackRowReading four = measureBackClusterLag(gauge, order.frontCluster(), order.backCluster(), harness.clock, OBSERVE_TICKS);

        List<String> notGreenOnes = new ArrayList<>();
        if (one.advancedCount() <= 0) {
            notGreenOnes.add("判据1");
        }
        if (two == null) {
            notGreenOnes.add("判据2");
        }
        if (!three) {
            notGreenOnes.add("判据3");
        }
        if (four.maxLagMilliTick() >= LAG_TOLERANCE_MILLI_TICK) {
            notGreenOnes.add("判据4");
        }
        reading("阴性对照", Map.of("此刻连接数", harness.connectionCount(), "慢客户端是否正常读", true,
                "判据1", one.reading(), "判据2 关上了", two != null, "判据3 收到事件", three,
                "判据4", four.reading(), "不绿的", notGreenOnes));

        assertTrue(one.advancedCount() > 0, "阴性对照：客户端正常读时判据 1 也不绿，"
                + "说明红的是夹具本身把服务端跑垮了。实录：" + one.reading());
        assertTrue(two != null, "阴性对照：客户端正常读时判据 2 也不绿");
        assertTrue(three, "阴性对照：客户端正常读时判据 3 也不绿");
        assertTrue(four.maxLagMilliTick() < LAG_TOLERANCE_MILLI_TICK,
                "阴性对照：客户端正常读时判据 4 也不绿。"
                        + "🔴 这一格从前是拿墙钟绝对间隔判的（心跳周期＋余量＝300ms），"
                        + "空载都会撞上——实测六条健康连接齐齐 320ms，**六条一起偏移是机器的形状**。"
                        + "现在判的是后簇比前簇落后多久。若还是红在这里，"
                        + "那才真是夹具把服务端跑垮了。" + NovaEventSlowConsumerHarness.criterion4FailureMessage(four));
    }
}
