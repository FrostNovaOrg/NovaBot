package com.starlwr.bot.adapter.onebot.health;

import com.starlwr.bot.core.health.HealthStatus;
import org.junit.jupiter.api.DisplayName;
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
    @Test
    @DisplayName("未配置任何机器人应判定为不可用")
    void reportsDownWhenNoSenderConfigured() {
        HealthStatus status = new OneBotHealthProbe(new OneBotConnectionState()).check();

        assertEquals(HealthStatus.Level.DOWN, status.level());
        assertFalse(status.advice().isBlank(), "应给出修复建议");
    }

    @Test
    @DisplayName("HTTP 与 Websocket 均正常时判定为正常")
    void reportsOkWhenAllConnected() {
        OneBotConnectionState state = new OneBotConnectionState();
        state.httpOk("qq", "v1.0，登录账号 测试(123)");
        state.websocketConnected("qq");

        HealthStatus status = new OneBotHealthProbe(state).check();

        assertEquals(HealthStatus.Level.OK, status.level());
        assertTrue(status.summary().contains("qq"), status.summary());
    }

    @Test
    @DisplayName("HTTP 不通即判定为不可用, 并指明该查什么")
    void reportsDownWhenHttpUnreachable() {
        OneBotConnectionState state = new OneBotConnectionState();
        state.httpFailed("qq", OneBotConnectionState.Kind.UNREACHABLE, "连接被拒绝");
        state.websocketConnected("qq");

        HealthStatus status = new OneBotHealthProbe(state).check();

        assertEquals(HealthStatus.Level.DOWN, status.level());
        assertTrue(status.advice().contains("one-bot-address"), "应指明要核对的配置项: " + status.advice());
    }

    @Test
    @DisplayName("Token 不正确应给出针对性的建议, 而非笼统的连不上")
    void distinguishesTokenError() {
        OneBotConnectionState state = new OneBotConnectionState();
        state.httpFailed("qq", OneBotConnectionState.Kind.TOKEN_INVALID, "403");

        HealthStatus status = new OneBotHealthProbe(state).check();

        assertEquals(HealthStatus.Level.DOWN, status.level());
        assertTrue(status.advice().contains("one-bot-http-token"), status.advice());
    }

    @Test
    @DisplayName("仅 Websocket 断开应判定为降级, 因消息仍可推送")
    void reportsDegradedWhenOnlyWebsocketDown() {
        OneBotConnectionState state = new OneBotConnectionState();
        state.httpOk("qq", "正常");
        state.websocketDisconnected("qq", "连接断开（1006），正在重连");

        HealthStatus status = new OneBotHealthProbe(state).check();

        assertEquals(HealthStatus.Level.DEGRADED, status.level());
        assertTrue(status.advice().contains("仍可推送"), status.advice());
    }

    @Test
    @DisplayName("未启用 Websocket 不应被视为异常")
    void treatsDisabledWebsocketAsNormal() {
        OneBotConnectionState state = new OneBotConnectionState();
        state.httpOk("qq", "正常");
        state.websocketDisabled("qq");

        assertEquals(HealthStatus.Level.OK, new OneBotHealthProbe(state).check().level());
    }

    @Test
    @DisplayName("接口全通但账号掉线仍应判定为不可用")
    void reportsDownWhenAccountOffline() {
        OneBotConnectionState state = new OneBotConnectionState();
        state.httpOk("qq", "服务正常");
        state.websocketConnected("qq");
        state.accountOffline("qq", "QQ 账号已掉线");

        HealthStatus status = new OneBotHealthProbe(state).check();

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

        assertEquals(HealthStatus.Level.OK, new OneBotHealthProbe(state).check().level());
    }

    @Test
    @DisplayName("概览应分别列出 HTTP、账号与 Websocket 三项")
    void summaryListsEachDimension() {
        OneBotConnectionState state = new OneBotConnectionState();
        state.httpOk("qq", "服务正常");
        state.accountOnline("qq", "在线");
        state.websocketConnected("qq");

        String summary = new OneBotHealthProbe(state).check().summary();

        assertTrue(summary.contains("HTTP 正常"), summary);
        assertTrue(summary.contains("账号 在线"), summary);
        assertTrue(summary.contains("WS 正常"), summary);
    }
}
