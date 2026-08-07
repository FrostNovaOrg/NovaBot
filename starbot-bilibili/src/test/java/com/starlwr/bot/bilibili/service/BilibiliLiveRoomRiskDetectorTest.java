package com.starlwr.bot.bilibili.service;

import com.starlwr.bot.bilibili.service.BilibiliLiveRoomRiskDetector.Window;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 直播间数据风控判定测试
 * <p>
 * 这些边界是拿实测数据定的，改阈值必须同时改这些断言：
 * <ul>
 *   <li>真降级（2026-08-07 登录态对照，被限制的那条连接）：29.3 分钟 6 条弹幕，
 *       环境消息照收 —— <b>必须报</b></li>
 *   <li>误报案例（同日，41 万人气游戏区）：进房占比 53%，但每分钟 71 条弹幕 ——
 *       <b>必须不报</b></li>
 *   <li>冷清房间：环境与业务都很少 —— <b>必须不报</b>，「没人说话」不等于「收不到」</li>
 * </ul>
 */
@DisplayName("直播间数据风控判定")
class BilibiliLiveRoomRiskDetectorTest {
    private static final int WINDOWS = 3;

    private BilibiliLiveRoomRiskDetector detector() {
        return new BilibiliLiveRoomRiskDetector(WINDOWS);
    }

    private Optional<String> feed(BilibiliLiveRoomRiskDetector d, Window... windows) {
        Optional<String> last = Optional.empty();
        for (Window w : windows) {
            last = d.accept(w);
        }
        return last;
    }

    @Test
    @DisplayName("业务消息断流而环境消息照收：报")
    void reportsWhenBusinessSilentButAmbientFlowing() {
        // 真降级的形状：进房、排行、点赞照收，弹幕礼物为零
        Optional<String> r = feed(detector(),
                new Window(20, 0, 15),
                new Window(18, 0, 13),
                new Window(22, 0, 17));

        assertTrue(r.isPresent());
        assertTrue(r.get().contains("业务消息"), "描述里要写明是业务消息为零");
    }

    @Test
    @DisplayName("窗口数不够时不下结论")
    void waitsForEnoughWindows() {
        BilibiliLiveRoomRiskDetector d = detector();

        assertFalse(d.accept(new Window(20, 0, 15)).isPresent(), "第 1 个窗口就报等于把抖动当故障");
        assertFalse(d.accept(new Window(20, 0, 15)).isPresent(), "第 2 个窗口仍不够");
        assertTrue(d.accept(new Window(20, 0, 15)).isPresent(), "第 3 个窗口才够");
    }

    @Test
    @DisplayName("中间只要有一条业务消息就不报")
    void singleBusinessMessageBreaksTheStreak() {
        Optional<String> r = feed(detector(),
                new Window(20, 0, 15),
                new Window(18, 1, 13),
                new Window(22, 0, 17));

        assertFalse(r.isPresent(), "断流的判据是零，不是少");
    }

    @Test
    @DisplayName("误报案例：进房占比过半但弹幕照收，不报")
    void doesNotReportBusyRoomWithManyEntrances() {
        // 2026-08-07 实测：41 万人气游戏区，进房占比 53%，每分钟 71 条弹幕，到达率 93.3%
        Optional<String> r = feed(detector(),
                new Window(134, 71, 71),
                new Window(140, 74, 74),
                new Window(128, 68, 68));

        assertFalse(r.isPresent(), "进房占比高不是判据，这个房间完全正常");
    }

    @Test
    @DisplayName("冷清房间不报：没人说话不等于收不到")
    void doesNotReportQuietRoom() {
        Optional<String> r = feed(detector(),
                new Window(3, 0, 2),
                new Window(2, 0, 1),
                new Window(4, 0, 3));

        assertFalse(r.isPresent(), "总量不足下限时应判为冷清而非断流");
    }

    @Test
    @DisplayName("样本量下限的边界")
    void minTotalBoundary() {
        int min = BilibiliLiveRoomRiskDetector.MIN_TOTAL;

        // 差一条不到下限
        assertFalse(feed(detector(),
                new Window(min - 1, 0, min - 1),
                new Window(0, 0, 0),
                new Window(0, 0, 0)).isPresent());

        // 正好到下限
        assertTrue(feed(detector(),
                new Window(min, 0, min),
                new Window(0, 0, 0),
                new Window(0, 0, 0)).isPresent());
    }

    @Test
    @DisplayName("描述只陈述观测，不断言原因")
    void messageStatesObservationOnly() {
        String msg = feed(detector(),
                new Window(20, 0, 15),
                new Window(18, 0, 13),
                new Window(22, 0, 17)).orElseThrow();

        assertTrue(msg.contains("60") || msg.contains("条"), "要给出具体数字");
        for (String forbidden : new String[]{"风控", "被限制", "无法接收", "收不到"}) {
            assertFalse(msg.contains(forbidden),
                    "描述不得断言原因，我们分不清是平台限制、协议变更还是真的没人说话：命中「" + forbidden + "」");
        }
    }

    @Test
    @DisplayName("重连后清空历史，不跨连接累计")
    void resetClearsHistory() {
        BilibiliLiveRoomRiskDetector d = detector();
        d.accept(new Window(20, 0, 15));
        d.accept(new Window(20, 0, 15));

        d.reset();

        assertFalse(d.accept(new Window(20, 0, 15)).isPresent(), "重连前的窗口不该与重连后的混在一起");
    }

    @Test
    @DisplayName("窗口数配成 0 或负数时按 1 处理")
    void requiredWindowsAtLeastOne() {
        BilibiliLiveRoomRiskDetector d = new BilibiliLiveRoomRiskDetector(0);

        assertTrue(d.accept(new Window(20, 0, 15)).isPresent());
    }

    @Test
    @DisplayName("恢复后再次断流仍能报")
    void reportsAgainAfterRecovery() {
        BilibiliLiveRoomRiskDetector d = detector();
        assertTrue(feed(d, new Window(20, 0, 15), new Window(20, 0, 15), new Window(20, 0, 15)).isPresent());

        assertFalse(d.accept(new Window(20, 5, 10)).isPresent(), "有业务消息了，恢复");

        // 那个有业务消息的窗口要被挤出滑动窗口，需要满 N 个零窗口，不是 N-1 个
        assertFalse(d.accept(new Window(20, 0, 15)).isPresent());
        assertFalse(d.accept(new Window(20, 0, 15)).isPresent());
        assertTrue(d.accept(new Window(20, 0, 15)).isPresent(), "再攒够连续 N 个零窗口应能再次报出");
    }

    @Test
    @DisplayName("滑动窗口只看最近 N 个")
    void onlyLooksAtRecentWindows() {
        BilibiliLiveRoomRiskDetector d = detector();
        d.accept(new Window(50, 50, 0));   // 很久以前业务消息很多

        assertFalse(d.accept(new Window(20, 0, 15)).isPresent());
        assertFalse(d.accept(new Window(20, 0, 15)).isPresent());
        assertTrue(d.accept(new Window(20, 0, 15)).isPresent(),
                "陈旧的窗口应被挤出，否则一次繁忙就能永久掩盖后续断流");
    }

    @Test
    @DisplayName("描述里带上进房数作为辅助信号")
    void includesInteractCountAsAuxiliary() {
        String msg = feed(detector(),
                new Window(20, 0, 15),
                new Window(18, 0, 13),
                new Window(22, 0, 17)).orElseThrow();

        assertTrue(msg.contains("45"), "三个窗口的进房数应累加为 45，便于人判断是不是「只剩进房」");
        assertEquals(1, msg.split("进房").length - 1);
    }
}
