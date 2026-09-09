package org.frostnova.nova.core.protocol;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.frostnova.nova.core.protocol.NovaEventSlowConsumerHarness.CLOCK_GAP_SLACK_MS;
import static org.frostnova.nova.core.protocol.NovaEventSlowConsumerHarness.CLOCK_SELF_CHECK_TICKS;
import static org.frostnova.nova.core.protocol.NovaEventSlowConsumerHarness.PING;
import static org.frostnova.nova.core.protocol.NovaEventSlowConsumerHarness.milliTickToString;
import static org.frostnova.nova.core.protocol.NovaEventSlowConsumerHarness.REAL_PIN_FLOOR_MILLI_TICK;
import static org.frostnova.nova.core.protocol.NovaEventSlowConsumerHarness.HOLE_SIGNATURE_LAG_MILLI_TICK;
import static org.frostnova.nova.core.protocol.NovaEventSlowConsumerHarness.OBSERVE_TICKS;
import static org.frostnova.nova.core.protocol.NovaEventSlowConsumerHarness.reading;
import static org.frostnova.nova.core.protocol.NovaEventSlowConsumerHarness.LAG_TOLERANCE_MILLI_TICK;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 台架自己那几把尺
 * <p>
 * 量的是<b>台架好不好使</b>，不是被测物好不好使——两件事混在同一组绿里，
 * 尺坏了的那天没人看得出来。
 */
@DisplayName("台架自己的几把尺")
class NovaEventHarnessSelfCheckTest {

    /**
     * 🔴 阈值的自验尺要装在<b>生效值</b>上
     * <p>
     * 判据 4 改判相对之后，唯一还留着的那个数是
     * {@link NovaEventSlowConsumerHarness#LAG_TOLERANCE_MILLI_TICK}——「后簇落后多久算陪葬」。
     * 谁把它从 400 改成 4000，判据 4 当场变成永远绿的，而全套读数一声不吭。
     * <p>
     * <b>护栏装在门口，而门在别处。</b>所以这一格就地量生效值。
     */
    @Test
    @DisplayName("判据 4 的线必须小于「后簇陪葬」的签名，否则那一格永远绿")
    void lagToleranceCatchesHole() {
        reading("台架自检-判据4的线", Map.of(
                "观测窗口（对照格）", OBSERVE_TICKS,
                "线（格）", milliTickToString(LAG_TOLERANCE_MILLI_TICK),
                "洞的签名（格）", milliTickToString(HOLE_SIGNATURE_LAG_MILLI_TICK),
                "检出余地（格）", milliTickToString(HOLE_SIGNATURE_LAG_MILLI_TICK - LAG_TOLERANCE_MILLI_TICK),
                "🔴 量的是生效值", "这里用的就是判据 4 在用的那两个常量，不是复制品",
                "🔴 线是怎么来的", "不是从噪声里量的，是从两边的量级里分的："
                        + "修后的落后是遍历中间的快照假象（微秒到几十毫秒，远不到一格），"
                        + "修前是整段阻塞（顶满整段窗口）。线放在中间，只挡量级错。"));

        assertTrue(LAG_TOLERANCE_MILLI_TICK < HOLE_SIGNATURE_LAG_MILLI_TICK,
                "判据 4 的线 " + milliTickToString(LAG_TOLERANCE_MILLI_TICK) + " 格不小于「后簇陪葬」的签名 "
                        + milliTickToString(HOLE_SIGNATURE_LAG_MILLI_TICK) + " 格（＝顶满整段观测窗口）——"
                        + "这个阈值抓不住它要抓的东西，那一格会永远绿。"
                        + "改 落后容忍_千分格（" + LAG_TOLERANCE_MILLI_TICK + "）之前先想清楚这一条。");

        // 🔴 光「小于」不够：线要留在<b>量级</b>的中间。贴着签名的线，
        //    机器抖一下就够到签名附近，两边的余地一样窄——那时它挡的又成了机器。
        assertTrue(LAG_TOLERANCE_MILLI_TICK * 2 <= HOLE_SIGNATURE_LAG_MILLI_TICK,
                "判据 4 的线 " + milliTickToString(LAG_TOLERANCE_MILLI_TICK) + " 格虽然小于签名 "
                        + milliTickToString(HOLE_SIGNATURE_LAG_MILLI_TICK) + " 格，但没留下一倍以上的余地——"
                        + "线要放在两个量级的**中间**，贴着签名放，机器抖一下就够得着。");
    }

    /**
     * 🔴 说法线要<b>高于</b>红绿线，否则两支诊断塌成一支
     * <p>
     * 判据 4 红有两种成因，红形长得一模一样：后簇真被落下了（落后到<b>格</b>这个量级），
     * 与某条投递线程被剥了一会儿 CPU（落后只比红绿线高一点）。失败语靠
     * {@link NovaEventSlowConsumerHarness#REAL_PIN_FLOOR_MILLI_TICK} 把两者分开。
     * <p>
     * 那条说法线要是<b>掉到红绿线上或以下</b>，凡红必被说成「更像真被落下了」——
     * <b>看着还有两支，其实只剩一支，而这一支永远说同一句话</b>。
     * 它不会红、不会抛，只会在往后每一条假红上把人指向错的方向。所以就地钉住。
     * <p>
     * 还要<b>低于</b>洞的签名：说法线要是爬到签名之上，真被钉住反而落进「机器抖」那一档——
     * <b>两支说反了比没有更费事</b>。
     */
    @Test
    @DisplayName("说法线必须夹在红绿线与洞的签名之间，否则失败语要么塌成一支、要么说反")
    void claimLineSitsBetweenTheTwo() {
        reading("台架自检-两条线", Map.of(
                "红绿线（格）", milliTickToString(LAG_TOLERANCE_MILLI_TICK),
                "说法线（格）", milliTickToString(REAL_PIN_FLOOR_MILLI_TICK),
                "洞的签名（格）", milliTickToString(HOLE_SIGNATURE_LAG_MILLI_TICK),
                "「更像机器抖了一下」那一档的宽度（格）",
                milliTickToString(REAL_PIN_FLOOR_MILLI_TICK - LAG_TOLERANCE_MILLI_TICK),
                "心跳周期毫秒", PING,
                "🔴 量的是生效值", "这里用的就是失败语在用的那两个常量，不是复制品"));

        assertTrue(REAL_PIN_FLOOR_MILLI_TICK > LAG_TOLERANCE_MILLI_TICK,
                "说法线 " + milliTickToString(REAL_PIN_FLOOR_MILLI_TICK) + " 格没有高于红绿线 "
                        + milliTickToString(LAG_TOLERANCE_MILLI_TICK) + " 格——"
                        + "凡是红的都会被说成「更像真被落下了」，两支诊断塌成一支。"
                        + "它不会让任何一格变红，只会在往后每一条假红上把人指错方向。");

        assertTrue(REAL_PIN_FLOOR_MILLI_TICK < HOLE_SIGNATURE_LAG_MILLI_TICK,
                "说法线 " + milliTickToString(REAL_PIN_FLOOR_MILLI_TICK) + " 格不低于洞的签名 "
                        + milliTickToString(HOLE_SIGNATURE_LAG_MILLI_TICK) + " 格——"
                        + "真被钉住反而会被说成「更像机器抖了一下」，两支说反了。");
    }

    /**
     * 🔴 值里再出现一个叫「读数」的键，会把<b>名字</b>顶掉
     * <p>
     * 顶掉之后那条读数看起来跟正常的一模一样——外面按名字找就永远找不到它。
     * 这一格钉的就是那个形态：它真咬中过一次（定余量那 20 轮停在「没报先验尺读数」，
     * 查了才发现是名字被值顶掉了）。
     */
    @Test
    @DisplayName("读数：值里再带一个「读数」键就当场抛（它会把名字顶掉）")
    void readingThrowsOnNameClash() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> reading("先验尺-关闭帧", Map.of("读数", "顶掉名字的那个值")));
        assertTrue(e.getMessage().contains("顶掉"),
                "失败语要说清后果（名字会被顶掉），不能只说「键重复」——"
                        + "只说重复的话，下一个人会以为换个写法就行了。实录：" + e.getMessage());
    }

    /** 阴性对照：只验「撞名会抛」证不了它在分辨。 */
    @Test
    @DisplayName("读数：不带那个键时照常出一条，不抛")
    void readingDoesNotThrowWithoutNameClash() {
        assertDoesNotThrow(() -> reading("台架自检-阴性对照", Map.of("实录", "x")));
    }

    /**
     * 🔴 对照钟是全套判据的<b>预算</b>，而预算不许瞬间烧完
     * <p>
     * 判据 1 那条推理链是：「对照钟走满 {@link NovaEventSlowConsumerHarness#OBSERVE_TICKS} 格 ⇒
     * 这段时间里心跳该走过至少一轮」。<b>后半句是墙钟上的事</b>——端点的 {@code heartbeats}
     * 按墙钟到点，一轮 ping 要隔一个心跳周期。所以那条链只有在
     * 「N 格 ⇒ 至少 (N−1) 个心跳周期的墙钟」成立时才立得住。
     * <p>
     * 补齐型的表（{@code scheduleAtFixedRate}）让它<b>不成立</b>：ticker 那条线程被剥了
     * 一会儿 CPU，欠下的几格会紧挨着补出来，「走了 2 格」于是可能只对应一百多毫秒。
     * <b>实测（改前，整类连跑 25 轮）撞到过一次</b>：窗口 2 格 / 247ms，本跑实测格长 123ms，
     * 六条健康连接一轮未推进——而同一份读数里，卡在关闭帧写里的是发送线程，
     * 心跳线程一根汗毛没动。
     * <p>
     * 🔴 <b>这一格量的是生效值那把表本身</b>，不是复制品：起一把真的
     * {@link NovaEventSlowConsumerHarness.ReferenceClock}，把 ticker 挤下 CPU，
     * 看它相邻两格最短隔了多久。改前它落在几毫秒到几十毫秒，改后落在一个周期以上。
     */
    @Test
    @DisplayName("对照钟不许补齐：相邻两格之间至少隔一个心跳周期")
    void referenceClockMustNotCatchUp() throws Exception {
        // 挤 CPU 的自旋线程。要多于核数，ticker 才真排不上号——
        // 一比一的话它照样能按点跑，这一格就成了空跑
        int hogCount = Runtime.getRuntime().availableProcessors() * 4;
        AtomicBoolean stop = new AtomicBoolean(false);
        List<Thread> hogs = new ArrayList<>();
        long minGapMs;
        long walked;
        long wallMs;
        try (NovaEventSlowConsumerHarness.ReferenceClock clock =
                     new NovaEventSlowConsumerHarness.ReferenceClock(PING)) {
            for (int i = 0; i < hogCount; i++) {
                Thread hog = new Thread(() -> {
                    long x = 1;
                    while (!stop.get()) {
                        x = x * 6364136223846793005L + 1442695040888963407L;
                    }
                    if (x == 0) {
                        Thread.yield();   // 只为不让整段自旋被优化掉
                    }
                }, "nova-test-cpu-hog-" + i);
                hog.setDaemon(true);
                hog.start();
                hogs.add(hog);
            }
            NovaEventSlowConsumerHarness.TimeSegment segment = clock.awaitTicks(CLOCK_SELF_CHECK_TICKS);
            minGapMs = clock.minTickGapMs();
            walked = segment.ticksWalked();
            wallMs = segment.wallClockMs();
        } finally {
            stop.set(true);
            for (Thread hog : hogs) {
                hog.join(5_000);
            }
        }

        long floorMs = PING - CLOCK_GAP_SLACK_MS;
        reading("台架自检-对照钟", Map.of(
                "挤 CPU 的线程数", hogCount,
                "本机核数", Runtime.getRuntime().availableProcessors(),
                "走了几格", walked,
                "这一段墙钟毫秒", wallMs,
                "相邻两格最短间隔毫秒", minGapMs,
                "线（毫秒）", floorMs,
                "心跳周期毫秒", PING,
                "🔴 量的是生效值", "起的是判据在用的那一把 ReferenceClock，不是复制品",
                "🔴 为什么量最短而不量平均", "补齐型的表平均值分毫不差（欠下的都补回来了），"
                        + "坏的只是**分布**：紧挨着补出来的那两格之间没有时间。"
                        + "平均值看不见它，最短值一眼就见"));

        assertTrue(walked >= CLOCK_SELF_CHECK_TICKS, "对照钟没走满 " + CLOCK_SELF_CHECK_TICKS
                + " 格就到墙钟死线了（实走 " + walked + " 格 / " + wallMs + "ms）——"
                + "这一格量不到东西，先查这把表是不是压根没在走。");

        assertTrue(minGapMs >= floorMs, "对照钟相邻两格最短只隔了 " + minGapMs + "ms，不到一个心跳周期（"
                + PING + "ms，线 " + floorMs + "ms）——**这把表会补齐**。"
                + "它是全套判据的预算，补齐意味着预算能瞬间烧完："
                + "「走了 " + OBSERVE_TICKS + " 格」不再意味着「心跳该走过至少一轮」，"
                + "判据 1 于是会在心跳好端端的时候判红。"
                + "改回 scheduleAtFixedRate 之前先想清楚这一条。实录：走了 " + walked
                + " 格 / " + wallMs + "ms，挤 CPU 的线程 " + hogCount + " 条。");
    }
}
