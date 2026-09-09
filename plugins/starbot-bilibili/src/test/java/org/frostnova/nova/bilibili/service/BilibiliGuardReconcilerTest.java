package org.frostnova.nova.bilibili.service;

import org.frostnova.nova.bilibili.event.live.BilibiliCaptainEvent;
import org.frostnova.nova.bilibili.model.BilibiliUserInfo;
import org.frostnova.nova.core.event.live.NovaBaseLiveEvent;
import org.frostnova.nova.core.model.LiveStreamerInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 大航海消息归并测试
 * <p>
 * 这里要防两件事，两件都不会在运行时报错：
 * <ul>
 *   <li><b>把一次开通记成两次</b>——同一次开通最多由三条消息播报，营收与榜单会凭空翻倍</li>
 *   <li><b>把挂牌价当成实际成交价</b>——{@code GUARD_BUY} 恒定先到且金额恒为挂牌价，
 *       不压住等 toast 就必然取到它，实测高估 15.4%</li>
 * </ul>
 */
@DisplayName("大航海消息归并")
class BilibiliGuardReconcilerTest {
    private static final LiveStreamerInfo SOURCE = new LiveStreamerInfo(19805387116684L, "主播", 47731877194803L);
    private static final Instant NOW = Instant.parse("2026-08-06T12:00:00Z");

    /**
     * 测试用的等待时长。真值是 5 秒，这里缩短到让测试跑得快，行为不变
     */
    private static final Duration GRACE = Duration.ofMillis(150);

    private final List<NovaBaseLiveEvent> published = new CopyOnWriteArrayList<>();

    private ScheduledExecutorService scheduler;
    private BilibiliGuardReconciler reconciler;

    @BeforeEach
    void setUp() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        reconciler = new BilibiliGuardReconciler(event -> published.add((NovaBaseLiveEvent) event), scheduler, GRACE);
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    private NovaBaseLiveEvent captainEvent(long uid) {
        return new BilibiliCaptainEvent(SOURCE, new BilibiliUserInfo(uid, "大哥", null), 198.0, 1, null, NOW);
    }

    /**
     * 等到发出指定条数为止，超时即失败
     */
    private void awaitPublished(int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline && published.size() < expected) {
            Thread.sleep(10);
        }
        assertEquals(expected, published.size());
    }

    /**
     * 等过整个等待窗口，用于确认「什么都没发出」
     */
    private void awaitGraceElapsed() throws InterruptedException {
        Thread.sleep(GRACE.toMillis() * 4);
    }

    @Test
    @DisplayName("等到 toast 就撤销 GUARD_BUY——挂牌价不该盖过实际成交价")
    void toastCancelsPendingGuardBuy() throws InterruptedException {
        reconciler.holdGuardBuy(1L, 3, NOW, captainEvent(1L));
        assertTrue(reconciler.acceptToast("flow-1", 1L, 3, NOW.plusSeconds(1)));

        awaitGraceElapsed();
        assertTrue(published.isEmpty(), "toast 已经带回了实际成交价，再发一条挂牌价的就是把这笔算两遍");
    }

    @Test
    @DisplayName("等不到 toast 才发出 GUARD_BUY")
    void guardBuyIsPublishedWhenNoToastArrives() throws InterruptedException {
        reconciler.holdGuardBuy(1L, 3, NOW, captainEvent(1L));

        assertTrue(published.isEmpty(), "还在等 toast 的时候不该发出");
        awaitPublished(1);
    }

    @Test
    @DisplayName("toast 先到时，随后的 GUARD_BUY 应被压掉")
    void toastArrivingFirstSuppressesGuardBuy() throws InterruptedException {
        // 实测 25/25 都是 GUARD_BUY 先到，但不能因此认定顺序永远如此
        //（「没观测到」当成「不会发生」已经错过一次）
        assertTrue(reconciler.acceptToast("flow-1", 1L, 3, NOW));
        reconciler.holdGuardBuy(1L, 3, NOW.plusSeconds(1), captainEvent(1L));

        awaitGraceElapsed();
        assertTrue(published.isEmpty());
    }

    @Test
    @DisplayName("同一 payflow_id 的新老两种格式只认第一条")
    void samePayflowIsAcceptedOnce() {
        assertTrue(reconciler.acceptToast("flow-1", 1L, 3, NOW));
        assertFalse(reconciler.acceptToast("flow-1", 1L, 3, NOW.plusSeconds(1)),
                "USER_TOAST_MSG 与 USER_TOAST_MSG_V2 是同一笔的两种格式");
    }

    @Test
    @DisplayName("同一流水号即便两条消息的时刻差很远也算同一笔")
    void samePayflowIgnoresTimestamps() {
        // v1 的时刻取自 send_time（毫秒），V2 缺 send_time 时回退到 guard_info.start_time（秒）。
        // 两个来源本就可能对不上，此时若还按时间窗判重就会双双放行，把同一笔记成两笔——
        // 流水号已经唯一标识了一笔交易，不该再拿时间去否决它
        assertTrue(reconciler.acceptToast("flow-1", 1L, 3, NOW));
        assertFalse(reconciler.acceptToast("flow-1", 1L, 3, NOW.plus(Duration.ofDays(900))));
    }

    @Test
    @DisplayName("不同 payflow_id 是不同的两笔")
    void differentPayflowsAreIndependent() {
        assertTrue(reconciler.acceptToast("flow-1", 1L, 3, NOW));
        assertTrue(reconciler.acceptToast("flow-2", 1L, 3, NOW.plusSeconds(1)));
    }

    @Test
    @DisplayName("缺流水号时退回按「人 + 等级 + 时间窗」判重")
    void missingPayflowFallsBackToWindow() {
        assertTrue(reconciler.acceptToast(null, 1L, 3, NOW));
        assertFalse(reconciler.acceptToast(null, 1L, 3, NOW.plusSeconds(2)),
                "没有流水号可比时不能直接放行，否则两种格式各记一次");
        assertTrue(reconciler.acceptToast(null, 1L, 3, NOW.plus(Duration.ofMinutes(5))),
                "隔得足够久就是另一次开通了");
        assertTrue(reconciler.acceptToast(null, 2L, 3, NOW), "另一个人");
        assertTrue(reconciler.acceptToast(null, 1L, 2, NOW), "另一个等级");
    }

    @Test
    @DisplayName("认不出是谁买的就直接发出——漏记也好过整条丢掉")
    void unknownSenderIsPublishedImmediately() {
        reconciler.holdGuardBuy(null, 3, NOW, captainEvent(0L));
        assertEquals(1, published.size(), "没法归并的只能立即发出，压住等一个永远等不到的 toast 更糟");

        assertTrue(reconciler.acceptToast(null, null, 3, NOW));
        assertTrue(reconciler.acceptToast(null, 1L, null, NOW));
    }

    @Test
    @DisplayName("同一人同等级连着两条 GUARD_BUY 只发一次")
    void repeatedGuardBuyPublishesOnce() throws InterruptedException {
        reconciler.holdGuardBuy(1L, 3, NOW, captainEvent(1L));
        reconciler.holdGuardBuy(1L, 3, NOW.plusSeconds(1), captainEvent(1L));

        awaitPublished(1);
        awaitGraceElapsed();
        assertEquals(1, published.size(), "重复播报的第二条不该另发一次");
    }

    @Test
    @DisplayName("记录数不应随运行时间无限增长")
    void doesNotGrowUnbounded() {
        for (int i = 0; i < 2000; i++) {
            reconciler.acceptToast("flow-" + i, (long) i, 3, NOW.plusSeconds(i));
        }

        // 撑爆之后仍要能正确判重，说明清理没有把有效记录一起扔掉
        Instant late = NOW.plusSeconds(3000);
        assertTrue(reconciler.acceptToast("flow-late", 99999L, 3, late));
        assertFalse(reconciler.acceptToast("flow-late", 99999L, 3, late.plusSeconds(1)));
    }
}
