package com.starlwr.bot.adapter.onebot.health;

import com.starlwr.bot.adapter.onebot.config.OneBotAdapterPluginProperties;
import com.starlwr.bot.core.health.HealthStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OneBot 连接健康探针测试
 * <p>
 * 除状态判定外，重点覆盖「异常时必须给出可操作的修复建议」——只说「异常」而不说下一步该做什么，
 * 使用者无从下手，这正是本项目排障成本高的根源。
 */
@DisplayName("OneBot 连接健康探针")
class OneBotHealthProbeTest {
    private final OneBotAdapterPluginProperties properties = new OneBotAdapterPluginProperties();

    private OneBotHealthProbe probe(OneBotConnectionState state) {
        return new OneBotHealthProbe(state, properties);
    }

    /**
     * 往耗时记录里塞若干个样本
     */
    private void record(OneBotConnectionState state, String sender, long... millis) {
        for (long value : millis) {
            state.recordLatency(sender, value);
        }
    }

    @Test
    @DisplayName("未配置任何机器人应判定为不可用")
    void reportsDownWhenNoSenderConfigured() {
        HealthStatus status = probe(new OneBotConnectionState()).check();

        assertEquals(HealthStatus.Level.DOWN, status.level());
        assertFalse(status.advice().isBlank(), "应给出修复建议");
    }

    @Test
    @DisplayName("HTTP 与 Websocket 均正常时判定为正常")
    void reportsOkWhenAllConnected() {
        OneBotConnectionState state = new OneBotConnectionState();
        state.httpOk("qq", "v1.0，登录账号 测试(123)");
        state.websocketConnected("qq");

        HealthStatus status = probe(state).check();

        assertEquals(HealthStatus.Level.OK, status.level());
        assertTrue(status.summary().contains("qq"), status.summary());
    }

    @Test
    @DisplayName("HTTP 不通即判定为不可用, 并指明该查什么")
    void reportsDownWhenHttpUnreachable() {
        OneBotConnectionState state = new OneBotConnectionState();
        state.httpFailed("qq", OneBotConnectionState.Kind.UNREACHABLE, "连接被拒绝");
        state.websocketConnected("qq");

        HealthStatus status = probe(state).check();

        assertEquals(HealthStatus.Level.DOWN, status.level());
        assertTrue(status.advice().contains("one-bot-address"), "应指明要核对的配置项: " + status.advice());
    }

    @Test
    @DisplayName("Token 不正确应给出针对性的建议, 而非笼统的连不上")
    void distinguishesTokenError() {
        OneBotConnectionState state = new OneBotConnectionState();
        state.httpFailed("qq", OneBotConnectionState.Kind.TOKEN_INVALID, "403");

        HealthStatus status = probe(state).check();

        assertEquals(HealthStatus.Level.DOWN, status.level());
        assertTrue(status.advice().contains("one-bot-http-token"), status.advice());
    }

    @Test
    @DisplayName("仅 Websocket 断开应判定为降级, 因消息仍可推送")
    void reportsDegradedWhenOnlyWebsocketDown() {
        OneBotConnectionState state = new OneBotConnectionState();
        state.httpOk("qq", "正常");
        state.websocketDisconnected("qq", "连接断开（1006），正在重连");

        HealthStatus status = probe(state).check();

        assertEquals(HealthStatus.Level.DEGRADED, status.level());
        assertTrue(status.advice().contains("仍可推送"), status.advice());
    }

    @Test
    @DisplayName("未启用 Websocket 不应被视为异常")
    void treatsDisabledWebsocketAsNormal() {
        OneBotConnectionState state = new OneBotConnectionState();
        state.httpOk("qq", "正常");
        state.websocketDisabled("qq");

        assertEquals(HealthStatus.Level.OK, probe(state).check().level());
    }

    @Test
    @DisplayName("接口全通但账号掉线仍应判定为不可用")
    void reportsDownWhenAccountOffline() {
        OneBotConnectionState state = new OneBotConnectionState();
        state.httpOk("qq", "服务正常");
        state.websocketConnected("qq");
        state.accountOffline("qq", "QQ 账号已掉线");

        HealthStatus status = probe(state).check();

        // 这一条最容易被漏掉：连接、端口、Token 全对，接口也返回 200，消息却谁都收不到
        assertEquals(HealthStatus.Level.DOWN, status.level());
        assertTrue(status.advice().contains("扫码"), "应指明要重新登录: " + status.advice());
    }

    @Test
    @DisplayName("账号在线状态未知不应被当成故障")
    void treatsUnknownAccountAsNormal() {
        OneBotConnectionState state = new OneBotConnectionState();
        state.httpOk("qq", "正常");
        state.websocketConnected("qq");
        state.accountUnknown("qq", "该 OneBot 实现未上报登录状态");

        assertEquals(HealthStatus.Level.OK, probe(state).check().level());
    }

    @Test
    @DisplayName("概览应分别列出 HTTP、账号与 Websocket 三项")
    void summaryListsEachDimension() {
        OneBotConnectionState state = new OneBotConnectionState();
        state.httpOk("qq", "服务正常");
        state.accountOnline("qq", "在线");
        state.websocketConnected("qq");

        String summary = probe(state).check().summary();

        assertTrue(summary.contains("HTTP 正常"), summary);
        assertTrue(summary.contains("账号 在线"), summary);
        assertTrue(summary.contains("WS 正常"), summary);
    }

    /**
     * 耗时维度
     * <p>
     * 补这一维度的起因：2026-08-10 生产上 OneBot 接口连续十小时每次要 2~11 秒，
     * 上面那三项判据全绿，而带图的推送一直在丢。所以这一组测试的核心是
     * 「接口全通、账号在线、WS 正常，仅仅是慢」这个组合必须能被看见。
     */
    @Nested
    @DisplayName("调用耗时")
    class Latency {
        /**
         * 造一个「三项全绿」的底子，好让测试只在耗时这一维上变化
         */
        private OneBotConnectionState healthy() {
            OneBotConnectionState state = new OneBotConnectionState();
            state.httpOk("qq", "服务正常");
            state.accountOnline("qq", "在线");
            state.websocketConnected("qq");
            return state;
        }

        @Test
        @DisplayName("⚠️ 接口全通、账号在线、WS 正常，仅仅是慢，也必须判定为降级")
        void reportsDegradedWhenOnlySlow() {
            OneBotConnectionState state = healthy();
            record(state, "qq", 2800, 3100, 2500, 9000, 2700);

            HealthStatus status = probe(state).check();

            // 这正是那次故障的形状：所有连通性判据都对，状态页却应该发黄
            assertEquals(HealthStatus.Level.DEGRADED, status.level());
            assertTrue(status.advice().contains("变慢"), status.advice());
        }

        @Test
        @DisplayName("建议里要说清怎么分辨慢在哪一侧, 而不是只说慢")
        void adviceTellsHowToLocaliseTheSlowness() {
            OneBotConnectionState state = healthy();
            record(state, "qq", 3000, 3000, 3000);

            String advice = probe(state).check().advice();

            assertTrue(advice.contains("curl"), "应给出可执行的分辨办法: " + advice);
            assertTrue(advice.contains("图"), "应说明后果是带图的推送会丢: " + advice);
        }

        @Test
        @DisplayName("健康时的偶发慢调用不应翻黄")
        void singleSpikeDoesNotTriggerDegraded() {
            OneBotConnectionState state = healthy();
            // 实测健康两天的形状：中位 0 毫秒，但 p99 有 2.4 秒的毛刺
            record(state, "qq", 0, 0, 1, 0, 2400, 0, 1, 0, 0);

            assertEquals(HealthStatus.Level.OK, probe(state).check().level(),
                    "只看最近一次就会被这种毛刺反复翻黄，所以判据取中位数");
        }

        @Test
        @DisplayName("样本不足时不作判断, 不拿两个样本硬出结论")
        void staysSilentWhenTooFewSamples() {
            OneBotConnectionState state = healthy();
            record(state, "qq", 9000, 9000);

            assertEquals(HealthStatus.Level.OK, probe(state).check().level());
        }

        @Test
        @DisplayName("阈值置 0 即关闭这项判定")
        void thresholdZeroDisablesTheCheck() {
            OneBotConnectionState state = healthy();
            record(state, "qq", 30000, 30000, 30000);
            properties.getDetect().setSlowThresholdMillis(0);

            assertEquals(HealthStatus.Level.OK, probe(state).check().level());
        }

        @Test
        @DisplayName("⚠️ Websocket 也断了时, 慢这条建议不能被挤掉")
        void slowAdviceSurvivesAlongsideWebsocketFailure() {
            OneBotConnectionState state = new OneBotConnectionState();
            state.httpOk("qq", "服务正常");
            state.websocketDisconnected("qq", "连接断开（1006）");
            record(state, "qq", 5000, 5000, 5000);

            String advice = probe(state).check().advice();

            // 两者处置办法完全不同，放进同一条 else-if 链就会丢掉一条
            assertTrue(advice.contains("Websocket"), advice);
            assertTrue(advice.contains("变慢"), advice);
        }

        @Test
        @DisplayName("HTTP 不通时仍是不可用, 不会被慢降格成降级")
        void downOutranksSlow() {
            OneBotConnectionState state = new OneBotConnectionState();
            state.httpFailed("qq", OneBotConnectionState.Kind.UNREACHABLE, "连接被拒绝");
            record(state, "qq", 5000, 5000, 5000);

            assertEquals(HealthStatus.Level.DOWN, probe(state).check().level());
        }

        @Test
        @DisplayName("概览里应带上中位耗时, 让人不必翻日志就知道有多慢")
        void summaryCarriesTheMedian() {
            OneBotConnectionState state = healthy();
            record(state, "qq", 3000, 3000, 3000);

            String summary = probe(state).check().summary();

            assertTrue(summary.contains("3.0 秒"), summary);
        }

        @Test
        @DisplayName("连接测试用的保留平台名不该进统计")
        void ignoresReservedTestSender() {
            OneBotConnectionState state = healthy();
            record(state, OneBotConnectionState.RESERVED_TEST_SENDER, 30000, 30000, 30000);

            HealthStatus status = probe(state).check();

            // 测试连接常常正是指向一个填错的地址，混进来会把真实平台的统计带歪
            assertEquals(HealthStatus.Level.OK, status.level());
            assertFalse(status.summary().contains(OneBotConnectionState.RESERVED_TEST_SENDER), status.summary());
        }

        @Test
        @DisplayName("只保留最近若干次: 早期的慢样本会被挤出去")
        void keepsOnlyRecentSamples() {
            OneBotConnectionState state = healthy();
            for (int i = 0; i < OneBotConnectionState.LATENCY_SAMPLES; i++) {
                state.recordLatency("qq", 30000);
            }
            assertEquals(HealthStatus.Level.DEGRADED, probe(state).check().level());

            // 再灌满一轮正常值，早先那批慢样本应当已被挤出
            for (int i = 0; i < OneBotConnectionState.LATENCY_SAMPLES; i++) {
                state.recordLatency("qq", 5);
            }
            assertEquals(HealthStatus.Level.OK, probe(state).check().level());
        }
    }
}
