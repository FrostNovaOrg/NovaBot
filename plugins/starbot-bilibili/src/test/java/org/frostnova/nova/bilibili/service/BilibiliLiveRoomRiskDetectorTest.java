package org.frostnova.nova.bilibili.service;

import org.frostnova.nova.bilibili.service.BilibiliLiveRoomRiskDetector.Judgment;
import org.frostnova.nova.bilibili.service.BilibiliLiveRoomRiskDetector.Window;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 业务消息断流判定测试（枚举名仍是 RISK，见 ConnectStatus 的注释）
 * <p>
 * 这些边界是拿实测数据定的，改阈值必须同时改这些断言：
 * <ul>
 *   <li>业务断流而环境仍有量：<b>必须报</b>。这是判据的触发条件，
 *       <b>不要读成「真被限流时就长这样」</b>——实测限流是整条流按比例削减，
 *       业务与环境削得一样多（详见 {@code BilibiliLiveRoomRiskDetector} 的类注释）。
 *       判据之所以仍然成立，是因为「业务连续为零」在两种机理下都会出现</li>
 *   <li>误报案例（同日，41 万人气游戏区）：进房占比 53%，但每分钟 71 条弹幕 ——
 *       <b>必须不报</b></li>
 *   <li>冷清房间：环境与业务都很少 —— <b>必须不报</b>，「没人说话」不等于「收不到」</li>
 * </ul>
 */
@DisplayName("业务消息断流判定")
class BilibiliLiveRoomRiskDetectorTest {
    private static final int WINDOWS = 3;

    private BilibiliLiveRoomRiskDetector detector() {
        return new BilibiliLiveRoomRiskDetector(WINDOWS);
    }

    private Optional<Judgment> feed(BilibiliLiveRoomRiskDetector d, Window... windows) {
        Optional<Judgment> last = Optional.empty();
        for (Window w : windows) {
            last = d.accept(w);
        }
        return last;
    }

    @Test
    @DisplayName("业务消息断流而环境消息仍有量：报")
    void reportsWhenBusinessSilentButAmbientFlowing() {
        // 判据的触发条件：环境消息还在来，而弹幕礼物为零。
        // 注意这不是「真被限流时的形状」——实测限流两类一起按比例削，
        // 这里只是说「业务连续为零」这个可观测事实值得报出来
        Optional<Judgment> r = feed(detector(),
                new Window(20, 0, 15),
                new Window(18, 0, 13),
                new Window(22, 0, 17));

        assertTrue(r.isPresent());
        assertTrue(r.get().observation().contains("业务消息"), "描述里要写明是业务消息为零");
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
        Optional<Judgment> r = feed(detector(),
                new Window(20, 0, 15),
                new Window(18, 1, 13),
                new Window(22, 0, 17));

        assertFalse(r.isPresent(), "断流的判据是零，不是少");
    }

    @Test
    @DisplayName("误报案例：进房占比过半但弹幕照收，不报")
    void doesNotReportBusyRoomWithManyEntrances() {
        // 2026-08-07 实测：41 万人气游戏区，进房占比 53%，每分钟 71 条弹幕，到达率 93.3%
        Optional<Judgment> r = feed(detector(),
                new Window(134, 71, 71),
                new Window(140, 74, 74),
                new Window(128, 68, 68));

        assertFalse(r.isPresent(), "进房占比高不是判据，这个房间完全正常");
    }

    @Test
    @DisplayName("冷清房间不报：没人说话不等于收不到")
    void doesNotReportQuietRoom() {
        Optional<Judgment> r = feed(detector(),
                new Window(3, 0, 2),
                new Window(2, 0, 1),
                new Window(4, 0, 3));

        assertFalse(r.isPresent(), "逐用户事件不足下限时应判为冷清而非断流");
    }

    @Test
    @DisplayName("⚠️ 误报回放：安静但在播的房间，定时推送攒够总量也不报")
    void doesNotReportQuietRoomPaddedByTimerPushes() {
        // 2026-08-10 23:50:57 生产实数：判定时「13 条消息、业务 0 条、进房类 1 条」，
        // 13 条里 12 条是排行与看过这类按秒下发的定时推送。
        // 同期成对观察量到基准 3 条弹幕 / 我们 3 条——采集没停，主播那三分钟确实没人说话。
        //
        // 旧判据拿「消息总量」当样本量下限，13 ≥ 10 轻松越过，于是把「安静」读成了「断流」。
        // 这条测试守的正是那个错：**总量能被定时推送撑起来，逐用户事件不能。**
        Optional<Judgment> r = feed(detector(),
                new Window(5, 0, 1),
                new Window(4, 0, 0),
                new Window(4, 0, 0));

        assertFalse(r.isPresent(), "13 条里 12 条是定时推送，这是冷清，不是断流");
    }

    @Test
    @DisplayName("样本量下限只数逐用户事件，定时推送再多也不算")
    void timerPushesDoNotCountTowardTheFloor() {
        int min = BilibiliLiveRoomRiskDetector.MIN_USER_EVENTS;

        // 总量远超下限，但逐用户事件只有 1 条：不报
        assertFalse(feed(detector(),
                new Window(min * 10, 0, 1),
                new Window(min * 10, 0, 0),
                new Window(min * 10, 0, 0)).isPresent(), "定时推送不该把下限顶上去");

        // 逐用户事件够了：报。这就是「进房类有量而业务为零」那唯一的触发形状
        assertTrue(feed(detector(),
                new Window(min, 0, min),
                new Window(0, 0, 0),
                new Window(0, 0, 0)).isPresent());
    }

    @Test
    @DisplayName("样本量下限的边界")
    void minUserEventsBoundary() {
        int min = BilibiliLiveRoomRiskDetector.MIN_USER_EVENTS;

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
                new Window(22, 0, 17)).map(Judgment::observation).orElseThrow();

        // 原先这里写的是 contains("60") || contains("条")，而右边那半恒真——
        // 任何一句中文描述都含「条」，等于这条断言什么都没查。改成两个数都必须出现：
        // 总量 60 与逐用户事件 45，缺哪个读日志的人都判断不出这次越过了哪个门槛
        assertTrue(msg.contains("60"), "要给出消息总量: " + msg);
        assertTrue(msg.contains("45"), "要给出逐用户事件数，否则看不出判据越过了哪个门槛: " + msg);
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
    @DisplayName("描述里带上进房数，它现在参与下限判定")
    void includesInteractCount() {
        String msg = feed(detector(),
                new Window(20, 0, 15),
                new Window(18, 0, 13),
                new Window(22, 0, 17)).map(Judgment::observation).orElseThrow();

        // 进房数不再只是「说清观测」：它与业务数一起构成样本量下限的分子，
        // 所以这个数写不写进描述，决定了读日志的人能不能复核这次判定
        assertTrue(msg.contains("45"), "三个窗口的进房数应累加为 45，便于人判断是不是「只剩进房」");
    }

    @Test
    @DisplayName("⚠️ 未开播的直播间一律不判定")
    void neverJudgesWhenNotLiving() {
        // 2026-08-10 生产实测的误报形状：两个未开播/轮播的房间，三个窗口共 10 条消息、
        // 业务 0 条、进房 0 条，被判成「已被数据风控」并触发告警。
        // 没有直播就没人发弹幕，业务消息必然为零，而轮播房照旧可能有人进出——
        // 样本量下限挡的是「冷清」，挡不住「没在播」，两条各挡一件事
        BilibiliLiveRoomRiskDetector d = detector();

        assertFalse(d.accept(new Window(4, 0, 0, false)).isPresent());
        assertFalse(d.accept(new Window(3, 0, 0, false)).isPresent());
        assertFalse(d.accept(new Window(3, 0, 0, false)).isPresent(),
                "总量已达 10 条、业务 0 条，若不看直播状态就会在这里误报");
    }

    @Test
    @DisplayName("未开播的窗口不该留在历史里，等开播后凑数触发误报")
    void notLivingClearsHistory() {
        BilibiliLiveRoomRiskDetector d = detector();

        // 先攒两个「在播且业务为零」的窗口
        assertFalse(d.accept(new Window(20, 0, 15)).isPresent());
        assertFalse(d.accept(new Window(20, 0, 15)).isPresent());

        // 中间下播一次：这段历史必须作废，否则下播前后的窗口会被拼在一起判定
        assertFalse(d.accept(new Window(5, 0, 0, false)).isPresent());

        // 重新开播后只有一个窗口，离 requiredWindows 还差两个
        assertFalse(d.accept(new Window(20, 0, 15)).isPresent(),
                "下播打断后应从零开始重新累计");
    }

    @Test
    @DisplayName("三参数的 Window 视为在播，保持旧调用方语义不变")
    void threeArgWindowMeansLiving() {
        assertTrue(new Window(1, 0, 0).living());
    }

    @Test
    @DisplayName("业务为零而解析失败非零：报「解析降级」而不是「没消息」")
    void reportsParseDegradedWhenBusinessZeroButParseFailuresNonzero() {
        // 协议一变的形状：包照收（总量在涨）、进房类还有量（下限过得去）、
        // 业务消息为零而解析失败在涨——这与「房间没人说话」是两件事，必须分得开
        Optional<Judgment> r = feed(detector(),
                new Window(23, 0, 15, 3, true),
                new Window(20, 0, 13, 2, true),
                new Window(26, 0, 17, 4, true));

        assertTrue(r.isPresent());
        assertTrue(r.get().parseDegraded(), "解析失败非零时判定要带上降级标志");
        String msg = r.get().observation();
        assertTrue(msg.contains("解析降级"), "描述要说得出口这是解析出了问题: " + msg);
        assertTrue(msg.contains("9"), "三个窗口共 9 条解析失败，条数要说出来: " + msg);
        // 底数仍然要报：读日志的人得能核对总量与逐用户事件，才知道下限是怎么过的
        assertTrue(msg.contains("69"), "总量 23+20+26 应照旧说出来: " + msg);
        assertTrue(msg.contains("45"), "逐用户事件 45 应照旧说出来: " + msg);
    }

    @Test
    @DisplayName("解析失败为零：照旧报「没消息」，不提解析降级")
    void reportsQuietWhenNoParseFailures() {
        Optional<Judgment> r = feed(detector(),
                new Window(20, 0, 15, 0, true),
                new Window(18, 0, 13, 0, true),
                new Window(22, 0, 17, 0, true));

        assertTrue(r.isPresent());
        assertFalse(r.get().parseDegraded(), "没有解析失败时不得带降级标志");
        String msg = r.get().observation();
        assertFalse(msg.contains("解析降级"), "没有解析失败时描述不得提降级: " + msg);
        assertTrue(msg.contains("定时推送"), "描述应仍是原来那句观测");
    }

    @Test
    @DisplayName("解析降级的描述也不得断言原因")
    void degradedMessageStatesObservationOnly() {
        String msg = feed(detector(),
                new Window(23, 0, 15, 3, true),
                new Window(20, 0, 13, 2, true),
                new Window(26, 0, 17, 4, true)).map(Judgment::observation).orElseThrow();

        // 与 messageStatesObservationOnly 同一页纪律：说「有 N 条解析失败」是观测，
        // 说「被平台改了协议」是断言——后者我们证不了
        for (String forbidden : new String[]{"风控", "被限制", "无法接收", "收不到"}) {
            assertFalse(msg.contains(forbidden),
                    "降级描述同样不得断言原因：命中「" + forbidden + "」: " + msg);
        }
    }

    @Test
    @DisplayName("解析失败不参与样本量下限：逐用户事件不够时仍不判")
    void parseFailuresDoNotCountTowardTheFloor() {
        // 只有定时推送和解析失败、没有任何逐用户事件：下限不过，什么都不报。
        // 否则协议全断（连进房都解析不出来）时会拿解析失败自己凑下限报降级
        assertFalse(feed(detector(),
                new Window(30, 0, 0, 10, true),
                new Window(30, 0, 0, 10, true),
                new Window(30, 0, 0, 10, true)).isPresent(),
                "解析失败不是逐用户事件，不能把下限顶上去");
    }

    @Test
    @DisplayName("四参数的 Window 视为无解析失败，保持旧调用方语义不变")
    void fourArgWindowMeansNoParseFailures() {
        assertTrue(new Window(1, 0, 0, true).parseFailed() == 0);
    }
}
