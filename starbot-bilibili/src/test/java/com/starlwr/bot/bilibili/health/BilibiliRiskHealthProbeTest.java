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

    @Test
    @DisplayName("UNKNOWN_OP 一条即 degraded；UNKNOWN_CMD 只进 summary 不降档")
    void unknownOpDegradesUnknownCmdStaysOk() {
        java.util.List<String> reds = new java.util.ArrayList<>();

        try {
            metrics.record(BilibiliRiskMetrics.Kind.UNKNOWN_OP, "op=9");
            HealthStatus status = probe.check();
            assertEquals(HealthStatus.Level.DEGRADED, status.level(), "未知操作码一条即应降档");
            assertTrue(status.summary().contains("未知操作码"), "summary 应写未知操作码，实际: " + status.summary());
            assertTrue(status.summary().contains("op=9"), "summary 应带 op=N，实际: " + status.summary());
            assertTrue(status.advice().contains("长连协议") || status.advice().contains("语料"),
                    "advice 应提示协议改了并抓语料，实际: " + status.advice());
        } catch (AssertionError e) {
            reds.add("① " + e.getMessage());
        }

        try {
            BilibiliRiskMetrics cmdOnly = new BilibiliRiskMetrics();
            BilibiliRiskHealthProbe cmdProbe = new BilibiliRiskHealthProbe(cmdOnly);
            cmdOnly.record(BilibiliRiskMetrics.Kind.UNKNOWN_CMD, "FOO count=1 unique=1");
            HealthStatus status = cmdProbe.check();
            assertEquals(HealthStatus.Level.OK, status.level(), "未知消息类型不得降档");
            assertTrue(status.summary().contains("未知消息类型"), "summary 应含未知消息类型，实际: " + status.summary());
            assertTrue(status.summary().contains("FOO"), "summary 应含最近 cmd 名，实际: " + status.summary());
        } catch (AssertionError e) {
            reds.add("② " + e.getMessage());
        }

        try {
            BilibiliRiskMetrics many = new BilibiliRiskMetrics();
            BilibiliRiskHealthProbe manyProbe = new BilibiliRiskHealthProbe(many);
            for (int i = 0; i < 20; i++) {
                many.record(BilibiliRiskMetrics.Kind.UNKNOWN_CMD, "BAR count=" + (i + 1) + " unique=1");
            }
            assertEquals(HealthStatus.Level.OK, manyProbe.check().level(),
                    "未知消息类型记多次仍不得降档");
        } catch (AssertionError e) {
            reds.add("③ " + e.getMessage());
        }

        assertTrue(reds.isEmpty(), () -> "三问中 " + reds.size() + " 问红: " + String.join("; ", reds));
    }

    @Test
    @DisplayName("UNKNOWN_VER 一条即 degraded；解析失败/缺字段/接口缺 data 只进 summary 不降档")
    void unknownVerDegradesSilentLossStaysSummary() {
        java.util.List<String> reds = new java.util.ArrayList<>();

        try {
            BilibiliRiskMetrics verOnly = new BilibiliRiskMetrics();
            BilibiliRiskHealthProbe verProbe = new BilibiliRiskHealthProbe(verOnly);
            verOnly.record(BilibiliRiskMetrics.Kind.UNKNOWN_VER, "ver=5 count=1 unique=1");
            HealthStatus status = verProbe.check();
            assertEquals(HealthStatus.Level.DEGRADED, status.level(), "未知协议版本一条即应降档");
            assertTrue(status.summary().contains("未知协议版本"), "summary 应写未知协议版本，实际: " + status.summary());
            assertTrue(status.summary().contains("ver=5"), "summary 应带 ver=N，实际: " + status.summary());
        } catch (AssertionError e) {
            reds.add("① " + e.getMessage());
        }

        try {
            BilibiliRiskMetrics silent = new BilibiliRiskMetrics();
            BilibiliRiskHealthProbe silentProbe = new BilibiliRiskHealthProbe(silent);
            silent.record(BilibiliRiskMetrics.Kind.PARSE_FAILURE, "LIVE count=1 unique=2");
            silent.record(BilibiliRiskMetrics.Kind.FIELD_MISSING, "DANMU_MSG:info<16 count=1 unique=3");
            silent.record(BilibiliRiskMetrics.Kind.API_DATA_MISSING,
                    "https://api.example.com/x count=1 unique=4");
            HealthStatus status = silentProbe.check();
            assertEquals(HealthStatus.Level.OK, status.level(), "三类静默损失只进 summary，不得降档");
            assertTrue(status.summary().contains("解析失败 2 类"), "应写解析失败类数，实际: " + status.summary());
            assertTrue(status.summary().contains("缺字段 3 类"), "应写缺字段类数，实际: " + status.summary());
            assertTrue(status.summary().contains("接口缺 data 4 个端点"), "应写缺 data 端点数，实际: " + status.summary());
            assertTrue(status.summary().contains("最近 LIVE"), "应带最近解析失败的 cmd，实际: " + status.summary());
        } catch (AssertionError e) {
            reds.add("② " + e.getMessage());
        }

        try {
            HealthStatus quiet = probe.check();
            assertFalse(quiet.summary().contains("解析失败"), "没发生就不该出现这行，实际: " + quiet.summary());
            assertFalse(quiet.summary().contains("缺字段"), "没发生就不该出现这行，实际: " + quiet.summary());
            assertFalse(quiet.summary().contains("接口缺 data"), "没发生就不该出现这行，实际: " + quiet.summary());
        } catch (AssertionError e) {
            reds.add("③ " + e.getMessage());
        }

        assertTrue(reds.isEmpty(), () -> "三问中 " + reds.size() + " 问红: " + String.join("; ", reds));
    }
}
