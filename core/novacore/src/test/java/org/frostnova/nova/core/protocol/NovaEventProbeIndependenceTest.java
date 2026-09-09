package org.frostnova.nova.core.protocol;

import org.frostnova.nova.core.protocol.NovaEventSlowConsumerHarness.Reading;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.frostnova.nova.core.protocol.NovaEventSlowConsumerHarness.AUTH;
import static org.frostnova.nova.core.protocol.NovaEventSlowConsumerHarness.CLIENT_TIMEOUT;
import static org.frostnova.nova.core.protocol.NovaEventSlowConsumerHarness.GRACE;
import static org.frostnova.nova.core.protocol.NovaEventSlowConsumerHarness.PING;
import static org.frostnova.nova.core.protocol.NovaEventSlowConsumerHarness.BREAK;
import static org.frostnova.nova.core.protocol.NovaEventSlowConsumerHarness.reading;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 三条判据互不代劳
 * <p>
 * 修后三条全绿。<b>但「三条都绿」有两种成因：真的三件事都好着，或者三条探针其实只在量同一件事。</b>
 * 后者在全绿时和前者长得一模一样。这三条要各自盯住三件<b>不同</b>的事，不是一件事的三种说法。
 * <p>
 * 这一组逐条把<b>只属于该条</b>的下游人为破坏掉（把它的时限推到 {@link NovaEventSlowConsumerHarness#BREAK}
 * 毫秒，那件事在判据窗口内一定不会发生），然后要求：<b>对应那条单独红，另两条不动</b>。
 * <ul>
 *   <li>判据 1 的下游 —— 周期性 ping（{@code pingInterval}）</li>
 *   <li>判据 2 的下游 —— 未认证超时（{@code authTimeout}）</li>
 *   <li>判据 3 的下游 —— 回补窗口到点后的 {@code goLive}（{@code resumeGrace}）</li>
 * </ul>
 * 破坏的是<b>被测端点的时限</b>，不是探针：探针一旦跟着被改，这一组量的就成了别的东西。
 * <p>
 * 🔴 三条探针取自 {@link NovaEventSlowConsumerHarness}，与 {@link NovaEventSlowConsumerTest} 用的是
 * <b>同一份</b>。抄一份过来的话，这里证明的独立性对真正在用的那三条一句都不算。
 */
@DisplayName("三条判据互不代劳")
class NovaEventProbeIndependenceTest {

    @TempDir
    Path dir;

    private NovaEventSlowConsumerHarness harness;

    @AfterEach
    void tearDown() {
        if (harness != null) {
            harness.close();
            harness = null;
        }
    }

    /**
     * 在「慢客户端已支起」的修后全绿态下，用给定时限跑完三条探针。
     *
     * @param 时限 已被破坏一处的时限组
     */
    private boolean[] threeItems(NovaEventEndpoint.Timings timings) throws Exception {
        harness = new NovaEventSlowConsumerHarness(dir, false, timings);
        harness.bringUpSlowClient();

        NovaEventEndpointTest.FakeSession healthy = harness.connectAndAuthenticate("healthy");
        NovaEventEndpointTest.FakeSession silent = harness.connection("silent");
        NovaEventEndpointTest.FakeSession otherConnection = harness.connectAndAuthenticate("other");

        Reading one = harness.probe1HealthyClientGetsPing(healthy);
        Reading three = harness.probe3OtherConnectionEntersLiveStream(otherConnection);
        Reading two = harness.probe2AuthGateCloses(silent);
        return new boolean[]{one.green(), two.green(), three.green()};
    }

    private void verdict(String brokenPart, boolean[] green, int theOneThatShouldBeRed) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("破坏的下游", brokenPart);
        m.put("该红的判据", theOneThatShouldBeRed);
        m.put("判据1", green[0]);
        m.put("判据2", green[1]);
        m.put("判据3", green[2]);
        reading("独立性-" + brokenPart, m);

        for (int i = 0; i < 3; i++) {
            int item = i + 1;
            if (item == theOneThatShouldBeRed) {
                assertFalse(green[i], "破坏了「" + brokenPart + "」，判据 " + item + " 却还是绿的——"
                        + "它没在量自己那条下游，它的绿说明不了那件事还好着");
            } else {
                assertTrue(green[i], "只破坏了「" + brokenPart + "」，判据 " + item + " 也跟着红了——"
                        + "三条判据没有各量各的，红一片时分不清到底是哪件事坏了");
            }
        }
    }

    @Test
    @DisplayName("只掐掉周期性 ping —— 只有判据 1 该红")
    void breakHeartbeatPeriodOnlyCriterion1Red() throws Exception {
        verdict("周期性 ping", threeItems(new NovaEventEndpoint.Timings(BREAK, CLIENT_TIMEOUT, AUTH, GRACE)), 1);
    }

    @Test
    @DisplayName("只掐掉未认证超时 —— 只有判据 2 该红")
    void breakAuthTimeoutOnlyCriterion2Red() throws Exception {
        verdict("未认证超时", threeItems(new NovaEventEndpoint.Timings(PING, CLIENT_TIMEOUT, BREAK, GRACE)), 2);
    }

    @Test
    @DisplayName("只掐掉回补窗口到点的 goLive —— 只有判据 3 该红")
    void breakReplayWindowOnlyCriterion3Red() throws Exception {
        verdict("回补窗口到点的 goLive", threeItems(new NovaEventEndpoint.Timings(PING, CLIENT_TIMEOUT, AUTH, BREAK)), 3);
    }
}
