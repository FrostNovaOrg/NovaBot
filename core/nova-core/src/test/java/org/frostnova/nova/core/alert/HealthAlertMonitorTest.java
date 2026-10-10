package org.frostnova.nova.core.alert;

import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.health.HealthProbe;
import org.frostnova.nova.core.health.HealthStatus;
import org.frostnova.nova.core.timeline.TimelineWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 健康告警监控测试
 * <p>
 * 量的不是「状态变了会不会告警」，而是<b>变坏了之后那条更重的告警来没来</b>：
 * 某项状态先降级、收敛期内又恶化为不可用时，最该叫到人的那一条「彻底挂了」
 * 不许被前一条降级告警的收敛压掉；同时收敛还得管住来回抖时的刷屏。
 */
@DisplayName("健康告警监控")
class HealthAlertMonitorTest {
    private NovaCoreProperties properties;

    private final List<String> received = new ArrayList<>();

    private ScriptedProbe probe;

    private HealthAlertMonitor monitor;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        properties = new NovaCoreProperties();
        // 收敛窗口拉到一整天：这些用例里的每一步都落在同一窗口内，
        // 断言才是在量「收敛压没压掉」，而不是量窗口过没过
        properties.getAlert().setConvergenceInterval(86400);
        received.clear();

        probe = new ScriptedProbe();

        ObjectProvider<HealthProbe> probes = mock(ObjectProvider.class);
        when(probes.orderedStream()).thenAnswer(invocation -> List.of((HealthProbe) probe).stream());

        AlertChannel channel = new AlertChannel() {
            @Override
            public String id() {
                return "fake";
            }

            @Override
            public String name() {
                return "假通道";
            }

            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public void send(String subject, String content) {
                received.add(subject + "\n" + content);
            }
        };
        ObjectProvider<AlertChannel> channels = mock(ObjectProvider.class);
        when(channels.orderedStream()).thenAnswer(invocation -> List.of(channel).stream());

        AlertService alertService = new AlertService(properties, channels, TimelineWriter.NONE);
        monitor = new HealthAlertMonitor(probes, alertService, TimelineWriter.NONE);
    }

    /**
     * 状态由剧本指定的探针
     */
    private static final class ScriptedProbe implements HealthProbe {
        private HealthStatus next = HealthStatus.ok("一切正常");

        @Override
        public String name() {
            return "测试探针";
        }

        @Override
        public HealthStatus check() {
            return next;
        }
    }

    /**
     * 已经发出去的异常告警条数，按级别的标记区分
     */
    private long alertsContaining(String marker) {
        return received.stream().filter(text -> text.contains(marker)).count();
    }

    @Test
    @DisplayName("先降级、收敛期内又恶化为不可用：「不可用」那条必须发出来，不许被降级的收敛压掉")
    void worseningWithinConvergenceStillAlerts() {
        monitor.check();

        probe.next = HealthStatus.degraded("接口偶发超时，已降级", "稍后自动恢复");
        monitor.check();
        assertEquals(1, received.size(), "降级那一条应当发出；实际: " + received);

        probe.next = HealthStatus.down("连续失败，彻底挂了", "请检查出网");
        monitor.check();

        assertEquals(2, received.size(), "恶化为不可用是最该叫到人的一条，不许被前一条降级的收敛压掉；实际: " + received);
        assertTrue(received.get(1).contains("连续失败，彻底挂了"), "第二条应是不可用那条；实际: " + received.get(1));
    }

    @Test
    @DisplayName("在降级、不可用之间来回抖（都在收敛期内）：每个级别只发一次，不许每抖一次发一条")
    void flappingBetweenLevelsAlertsOncePerLevel() {
        monitor.check();

        probe.next = HealthStatus.degraded("接口偶发超时，已降级", "稍后自动恢复");
        monitor.check();
        probe.next = HealthStatus.down("连续失败，彻底挂了", "请检查出网");
        monitor.check();
        probe.next = HealthStatus.degraded("接口偶发超时，已降级", "稍后自动恢复");
        monitor.check();
        probe.next = HealthStatus.down("连续失败，彻底挂了", "请检查出网");
        monitor.check();

        assertEquals(1, alertsContaining("接口偶发超时"), "降级只该发一次；实际: " + received);
        assertEquals(1, alertsContaining("连续失败"), "不可用只该发一次；实际: " + received);
        assertEquals(2, received.size(), "两轮抖动合计两封，收敛期内不多发；实际: " + received);
    }

    @Test
    @DisplayName("不可用后恢复正常、恢复后又坏：恢复清掉全部收敛记录，再坏照常告警")
    void alertsAgainAfterRecovery() {
        monitor.check();

        probe.next = HealthStatus.down("连续失败，彻底挂了", "请检查出网");
        monitor.check();
        assertEquals(1, alertsContaining("连续失败"), "前置: 第一次不可用已告警；实际: " + received);

        probe.next = HealthStatus.ok("一切正常");
        monitor.check();
        assertTrue(received.stream().anyMatch(text -> text.contains("NovaBot 状态恢复")),
                "恢复那一条应当发出；实际: " + received);

        probe.next = HealthStatus.down("又一次连续失败", "请检查出网");
        monitor.check();

        assertEquals(1, alertsContaining("又一次连续失败"),
                "恢复后再坏必须照常告警，不被恢复前的收敛记录压掉；实际: " + received);
    }
}
