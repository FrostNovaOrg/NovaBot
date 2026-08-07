package com.starlwr.bot.bilibili.health;

import com.starlwr.bot.core.health.HealthStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 风控与静默降级探针测试
 * <p>
 * 阈值是产品侧给定的触发条件（例如「412 ≥3 次/7 天就要重新评估浏览器方案」），
 * 所以这里逐条钉住：差一次不告警、够数就告警。改阈值必须同时改这些断言。
 */
@DisplayName("风控与静默降级探针")
class BilibiliRiskHealthProbeTest {
    private BilibiliRiskMetrics metrics;

    private BilibiliRiskHealthProbe probe;

    @BeforeEach
    void setUp() {
        metrics = new BilibiliRiskMetrics();
        probe = new BilibiliRiskHealthProbe(metrics);
    }

    private void record(BilibiliRiskMetrics.Kind kind, int times) {
        for (int i = 0; i < times; i++) {
            metrics.record(kind, "测试桩");
        }
    }

    @Test
    @DisplayName("什么都没发生时为正常，且把各项计数显示出来")
    void okWhenQuiet() {
        HealthStatus status = probe.check();

        assertEquals(HealthStatus.Level.OK, status.level());
        assertTrue(status.summary().contains("412"), "正常时也要显示计数，否则平时无从判断趋势");
        assertTrue(status.summary().contains("快照缺失"));
    }

    @Test
    @DisplayName("412 达到 3 次/7 天才告警，2 次不告警")
    void alertsOn412Threshold() {
        record(BilibiliRiskMetrics.Kind.HTTP_412, 2);
        assertEquals(HealthStatus.Level.OK, probe.check().level(), "2 次未到阈值");

        record(BilibiliRiskMetrics.Kind.HTTP_412, 1);
        HealthStatus status = probe.check();
        assertEquals(HealthStatus.Level.DEGRADED, status.level());
        assertTrue(status.summary().contains("412"));
        assertTrue(status.advice().contains("浏览器"), "建议里要点明这是重新评估浏览器方案的触发条件");
    }

    @Test
    @DisplayName("-352 达到 5 次/小时才告警")
    void alertsOn352Threshold() {
        record(BilibiliRiskMetrics.Kind.CODE_352, 4);
        assertEquals(HealthStatus.Level.OK, probe.check().level());

        record(BilibiliRiskMetrics.Kind.CODE_352, 1);
        assertEquals(HealthStatus.Level.DEGRADED, probe.check().level());
    }

    @Test
    @DisplayName("出现任何一次风控质询即告警")
    void alertsOnAnyChallenge() {
        record(BilibiliRiskMetrics.Kind.GAIA, 1);

        HealthStatus status = probe.check();
        assertEquals(HealthStatus.Level.DEGRADED, status.level());
        assertTrue(status.advice().contains("不要自行尝试绕过"), "建议里要写明红线");
    }

    @Test
    @DisplayName("出现任何一次开播快照项缺失即告警")
    void alertsOnAnySnapshotMiss() {
        metrics.record(BilibiliRiskMetrics.Kind.SNAPSHOT_MISSING, "某主播 开播快照应记 3 项、实记 2 项，缺 大航海");

        HealthStatus status = probe.check();
        assertEquals(HealthStatus.Level.DEGRADED, status.level());
        assertTrue(status.summary().contains("快照项缺失"));
        assertTrue(status.advice().contains("卡片直接消失"),
                "必须说明表现形态：按「数值变 0」去找永远找不到");
        assertTrue(status.advice().contains("大航海"), "要带上最近一次缺了哪项");
    }

    @Test
    @DisplayName("1006 单次不告警，成串才告警")
    void alertsOnDisconnectStorm() {
        record(BilibiliRiskMetrics.Kind.DISCONNECT_1006, 9);
        assertEquals(HealthStatus.Level.OK, probe.check().level(), "偶发抖动不该打扰人");

        record(BilibiliRiskMetrics.Kind.DISCONNECT_1006, 1);
        assertEquals(HealthStatus.Level.DEGRADED, probe.check().level());
    }

    @Test
    @DisplayName("多项同时异常时逐条列出")
    void reportsAllProblems() {
        record(BilibiliRiskMetrics.Kind.HTTP_412, 3);
        record(BilibiliRiskMetrics.Kind.GAIA, 1);

        HealthStatus status = probe.check();
        assertTrue(status.summary().contains("412"));
        assertTrue(status.summary().contains("质询"));
    }

    @Test
    @DisplayName("窗口之外的记录不计入")
    void windowExcludesOldEvents() {
        record(BilibiliRiskMetrics.Kind.CODE_352, 5);

        assertEquals(5, metrics.count(BilibiliRiskMetrics.Kind.CODE_352, Duration.ofHours(1)));
        assertEquals(0, metrics.count(BilibiliRiskMetrics.Kind.CODE_352, Duration.ZERO),
                "零长窗口内不应有任何记录");
    }

    @Test
    @DisplayName("从未发生过的事件没有最近发生时刻")
    void noLastWhenNeverHappened() {
        assertFalse(metrics.last(BilibiliRiskMetrics.Kind.HTTP_412).isPresent());

        metrics.record(BilibiliRiskMetrics.Kind.HTTP_412, "x");
        assertTrue(metrics.last(BilibiliRiskMetrics.Kind.HTTP_412).isPresent());
    }
}
