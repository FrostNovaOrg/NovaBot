package org.frostnova.nova.core.alert;

import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.health.HealthProbe;
import org.frostnova.nova.core.health.HealthStatus;
import org.frostnova.nova.core.timeline.TimelineWriter;
import org.frostnova.nova.core.timeline.TimelineEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    private final List<TimelineEvent> events = new ArrayList<>();

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
        events.clear();

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
        monitor = new HealthAlertMonitor(probes, alertService, events::add);
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

    // 抓的故障：已有一间异常时第二间异常，同级告警漏掉新主播。
    @Test
    void sameLevelAlertsOnlyNewMember() {
        probe.next = degradedMembers("A");
        monitor.check();
        received.clear();
        events.clear();

        probe.next = degradedMembers("A", "B");
        monitor.check();

        assertEquals(1, received.size());
        assertTrue(received.get(0).contains("B（2）"));
        assertFalse(received.get(0).contains("A（1）"));
        assertTrue(received.get(0).contains("处理建议：请检查连接"));
        assertEquals(1, events.size());
        assertTrue(events.get(0).text().contains("B（2）"));
        assertFalse(events.get(0).text().contains("A（1）"));
        assertEquals("DEGRADED", events.get(0).detail().get("from"));
        assertEquals("DEGRADED", events.get(0).detail().get("to"));
    }

    // 抓的故障：部分房间恢复后又有新房间异常，仍被相同级别挡掉。
    @Test
    void memberRemovalIsSilentButNextAdditionAlerts() {
        probe.next = degradedMembers("A", "B");
        monitor.check();
        received.clear();
        events.clear();

        probe.next = degradedMembers("B");
        monitor.check();
        assertTrue(received.isEmpty());
        assertTrue(events.isEmpty());

        probe.next = degradedMembers("B", "C");
        monitor.check();
        assertEquals(1, received.size());
        assertTrue(received.get(0).contains("C（3）"));
        assertFalse(received.get(0).contains("B（2）"));
        assertFalse(received.get(0).contains("A（1）"));
        assertEquals(1, events.size());
    }

    // 抓的故障：没有成员信息的其他探针，仅摘要变化就反复提醒。
    @Test
    void summaryChangeWithoutMembersIsSilent() {
        probe.next = HealthStatus.degraded("连接尚未就绪", "请检查连接");
        monitor.check();
        received.clear();
        events.clear();
        probe.next = HealthStatus.degraded("部分连接已就绪", "请检查连接");
        monitor.check();
        assertTrue(received.isEmpty());
        assertTrue(events.isEmpty());
        assertTrue(probe.next.members().isEmpty());
    }

    // 抓的故障：一小时内再坏再好，第二次恢复被恢复收敛键吞掉。
    @Test
    void everyRecoveryWithinAnHourAlerts() {
        properties.getAlert().setConvergenceInterval(3600);
        monitor.check();
        for (int i = 0; i < 2; i++) {
            probe.next = HealthStatus.degraded("连接异常", "请检查连接");
            monitor.check();
            probe.next = HealthStatus.ok("一切正常");
            monitor.check();
        }
        assertEquals(2, alertsContaining("连接异常"));
        assertEquals(2, alertsContaining("NovaBot 状态恢复"));
        assertEquals(4, received.size());
        assertEquals(4, events.size());
    }

    // 抓的故障：按整批成员收敛时，旧房间会压掉别的新房间或被重复点名。
    @Test
    void membersConvergeIndividuallyAndBatchIntoOneAlert() {
        properties.getAlert().setConvergenceInterval(3600);
        probe.next = degradedMembers("A");
        monitor.check();
        probe.next = degradedMembers("A", "B", "C");
        monitor.check();
        assertEquals(2, received.size());
        assertTrue(received.get(1).contains("B（2）"));
        assertTrue(received.get(1).contains("C（3）"));
        assertFalse(received.get(1).contains("A（1）"));

        probe.next = degradedMembers("C");
        monitor.check();
        probe.next = degradedMembers("A", "B", "C", "D");
        monitor.check();
        assertEquals(3, received.size());
        assertTrue(received.get(2).contains("D（4）"));
        assertFalse(received.get(2).contains("A（1）"));
        assertFalse(received.get(2).contains("B（2）"));
        assertFalse(received.get(2).contains("C（3）"));
    }

    // 抓的故障：尚未连接已是降级时，首间房间出现无弹幕礼物也没有提醒。
    @Test
    void riskMemberAfterConnectionDegradedStillAlerts() {
        probe.next = HealthStatus.degraded("部分连接尚未就绪", "请检查连接");
        monitor.check();
        probe.next = degradedMembers("A");
        monitor.check();
        assertEquals(2, received.size());
        assertTrue(received.get(1).contains("A（1）"));
        probe.next = new HealthStatus(HealthStatus.Level.DEGRADED,
                "主播已更名", "请检查连接", "", Map.of("1", "新名字（1）"));
        monitor.check();
        assertEquals(2, received.size(), "房间标识未变，改名不应被认成新增房间");
    }

    // 抓的故障：新增六间房间时正文点名过多，或漏掉其余房间数量。
    @Test
    void additionalAlertNamesAtMostFiveMembers() {
        probe.next = degradedMembers("A");
        monitor.check();
        probe.next = degradedMembers("A", "B", "C", "D", "E", "F", "G");
        monitor.check();
        assertEquals(2, received.size());
        String text = received.get(1);
        assertTrue(text.contains("B（2）"));
        assertTrue(text.contains("F（6）"));
        assertTrue(text.contains("等 1 个"));
        assertFalse(text.contains("A（1）"));
        assertFalse(text.contains("G（7）"));
    }

    // 抓的故障：全部恢复后仍记着旧成员，影响下一轮异常与恢复通知。
    @Test
    void allMembersRecoverAndNextEpisodeAlertsNormally() {
        monitor.check();
        probe.next = degradedMembers("A");
        monitor.check();
        probe.next = HealthStatus.ok("一切正常");
        monitor.check();
        probe.next = degradedMembers("A");
        monitor.check();
        probe.next = degradedMembers("A", "B");
        monitor.check();
        probe.next = HealthStatus.ok("一切正常");
        monitor.check();
        assertEquals(5, received.size());
        assertEquals(2, alertsContaining("NovaBot 状态恢复"));
        assertTrue(received.get(3).contains("B（2）"));
        assertFalse(received.get(3).contains("A（1）"));
    }

    private static HealthStatus degradedMembers(String... names) {
        Map<String, String> members = new LinkedHashMap<>();
        for (String name : names) {
            String id = Integer.toString(name.charAt(0) - 'A' + 1);
            members.put(id, name + "（" + id + "）");
        }
        return new HealthStatus(HealthStatus.Level.DEGRADED,
                "连接异常：" + String.join("、", members.values()), "请检查连接", "", members);
    }
}
