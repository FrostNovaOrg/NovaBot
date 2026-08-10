package com.starlwr.bot.bilibili.service;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.bilibili.enums.ConnectStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static com.starlwr.bot.bilibili.service.BilibiliConnectorHarness.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 直播间连接器测试
 * <p>
 * 连接器的缺陷从来不在单个方法的逻辑里，而在几条线程的交错次序上。
 * 这里用 {@link BilibiliConnectorHarness} 把次序变成显式可控的调用，
 * 逐条钉住已经栽过的那些坑。
 */
@DisplayName("直播间连接器")
class BilibiliLiveRoomConnectorTest {
    /**
     * 认证包的身份必须与 token 同源（{@code 8eafd67} 的回归测试）
     * <p>
     * Phase 0 修过这个 1006 死循环，但当时没有测试兜住。
     * 2026-08-04 的实况：扫码登录成功后 206 毫秒发起首次建连，32 秒内 96 次 1006，
     * 随后被平台以 -352 限流。成因是 token 在取长连接信息时拿的、uid 在握手完成后才读，
     * 中间隔着一次 WebSocket 往返；登录恰好落在这个窗口内，就发出「匿名 token + 登录 uid」。
     * 服务端遇到身份不一致会握手后立刻切断且**不发关闭帧**，因此表现为 1006 而不是明确的拒绝。
     */
    @Nested
    @DisplayName("认证包的身份与 token 同源")
    class VerifyIdentity {
        @Test
        @DisplayName("⚠️ uid 取自 ConnectInfo 快照, 不是握手后的实时身份")
        void usesIdentitySnapshotNotLiveUid() {
            BilibiliConnectorHarness harness = new BilibiliConnectorHarness();
            harness.connect();

            JSONObject verify = harness.verifyPacket().orElseThrow(() -> new AssertionError("没有发出认证包"));

            // 脚手架把「取完 token 之后登录完成了」这件事做进了打桩：
            // 快照里的 uid 是 0（匿名），而 api.getLoginUid() 返回已登录的 uid。
            // 若有人把 sendVerify 改回去读实时身份，这里会拿到 LOGGED_IN_UID
            assertEquals(SNAPSHOT_UID, verify.getLongValue("uid"),
                    "认证包必须用取 token 那一瞬间的身份，否则服务端会因身份不一致切断（1006）");
            assertNotEquals(LOGGED_IN_UID, verify.getLongValue("uid"),
                    "拿到的是握手之后的实时身份，说明 token 与 uid 又不同源了");
        }

        @Test
        @DisplayName("token 与 uid 来自同一份快照")
        void tokenAndUidComeFromTheSameSnapshot() {
            BilibiliConnectorHarness harness = new BilibiliConnectorHarness();
            harness.connect();

            JSONObject verify = harness.verifyPacket().orElseThrow();

            assertEquals(TOKEN, verify.getString("key"));
            assertEquals(SNAPSHOT_UID, verify.getLongValue("uid"));
        }

        @Test
        @DisplayName("认证包带上房间号与协议版本")
        void carriesRoomAndProtocol() {
            BilibiliConnectorHarness harness = new BilibiliConnectorHarness();
            harness.connect();

            JSONObject verify = harness.verifyPacket().orElseThrow();

            assertEquals(ROOM_ID, verify.getLongValue("roomid"));
            assertEquals("web", verify.getString("platform"));
            assertEquals(2, verify.getIntValue("type"));
        }
    }

    /**
     * 启动时的心跳竞态（Phase 1.5 第 7 项）
     * <p>
     * {@code afterConnectionEstablished} 回调发生在 {@code client.execute()} 的 future
     * 完成之前，而 {@code this.session} 要等 future 返回才赋值。回调里会启动心跳，
     * 而 Spring 的 {@code scheduleAtFixedRate} 是「尽快开始」——第一次心跳完全可能
     * 跑在赋值之前，此时 {@code send()} 看到 null session 抛异常，
     * 走进 {@code reconnect()} → {@code closeSession()}，**把刚刚建好的连接掐掉**。
     */
    @Nested
    @DisplayName("启动时的心跳竞态")
    class StartupHeartbeatRace {
        @Test
        @DisplayName("⚠️ 首个心跳跑在 session 赋值之前时, 不能把刚建好的连接掐掉")
        void firstHeartbeatMustNotKillTheFreshConnection() {
            BilibiliConnectorHarness harness = new BilibiliConnectorHarness().heartbeatFiresOnSchedule();

            harness.connect();

            // 这一条是竞态的可观测后果：连接刚建立就被自己关掉并排了一次重连
            assertFalse(harness.sessionClosed(),
                    "刚建好的连接被自己关掉了——首个心跳拿到 null session 后走了 reconnect()");
            assertEquals(0, harness.queuedReconnects(),
                    "不该因为自己的心跳时序而安排重连");
            assertEquals(1, harness.handshakes(), "不该重连，握手应当只有一次");
        }

        @Test
        @DisplayName("竞态窗口里的心跳不该被当成发送失败")
        void heartbeatInRaceWindowIsNotAFailure() {
            BilibiliConnectorHarness harness = new BilibiliConnectorHarness().heartbeatFiresOnSchedule();

            harness.connect();

            assertEquals(ConnectStatus.CONNECTED, harness.connector().getStatus(),
                    "连接应当仍然是已连接状态");
        }

        @Test
        @DisplayName("对照：回调推迟到握手完成之后时本来就不会复现")
        void noRaceWhenCallbackComesAfterHandshake() throws Exception {
            BilibiliConnectorHarness harness = new BilibiliConnectorHarness()
                    .callbackAfterHandshake()
                    .heartbeatFiresOnSchedule();

            harness.connect();
            harness.fireConnectionEstablished();

            // 这一格证明问题出在「回调与赋值的先后」，而不是心跳本身有毛病
            assertFalse(harness.sessionClosed());
            assertEquals(0, harness.queuedReconnects());
        }

        @Test
        @DisplayName("⚠️ 认证包必须是第一个包，心跳不能抢在它前面")
        void verifyMustBeTheFirstPacket() {
            BilibiliConnectorHarness harness = new BilibiliConnectorHarness().heartbeatFiresOnSchedule();

            harness.connect();

            // 服务端要求认证包打头。把 session 提前赋值修掉 null 竞态之后，
            // 回调里启动的心跳就「能发出去了」——而 sendVerify 还在 connect() 里排在回调之后。
            // 于是修掉一个竞态可能换来另一个：心跳抢跑，服务端收到未认证的包
            java.util.List<Integer> ops = harness.sentPackets().stream()
                    .map(com.starlwr.bot.bilibili.protocol.BilibiliPacket::getOperation)
                    .toList();

            assertFalse(ops.isEmpty(), "应当发出过包");
            assertEquals(com.starlwr.bot.bilibili.enums.DataPackType.VERIFY.getCode(), ops.get(0),
                    "第一个包必须是认证包，实际顺序: " + ops);
        }

        @Test
        @DisplayName("连接建立之后的心跳应当真的发出去")
        void heartbeatIsSentOnceConnected() {
            BilibiliConnectorHarness harness = new BilibiliConnectorHarness();

            harness.connect();
            harness.fireHeartbeat();

            assertTrue(harness.heartbeatsSent() >= 1, "连接可用时心跳应当发得出去");
        }
    }

    @Nested
    @DisplayName("脚手架能驱动的基本时序")
    class BasicSequences {
        @Test
        @DisplayName("正常连接：一次握手 + 一个认证包")
        void normalConnect() {
            BilibiliConnectorHarness harness = new BilibiliConnectorHarness();

            harness.connect();

            assertEquals(1, harness.handshakes());
            assertTrue(harness.verifyPacket().isPresent());
            assertEquals(ConnectStatus.CONNECTED, harness.connector().getStatus());
        }

        @Test
        @DisplayName("握手失败：排一次重连, 且不发认证包")
        void handshakeFailureSchedulesReconnect() {
            BilibiliConnectorHarness harness = new BilibiliConnectorHarness().failNextHandshake();

            harness.connect();

            assertTrue(harness.verifyPacket().isEmpty(), "握手都没成，不该发认证包");
            assertEquals(1, harness.queuedReconnects(), "应当排一次重连");
            assertEquals(ConnectStatus.ERROR, harness.connector().getStatus());
        }

        @Test
        @DisplayName("重连任务跑起来后会再次握手")
        void queuedReconnectReconnects() {
            BilibiliConnectorHarness harness = new BilibiliConnectorHarness().failNextHandshake();

            harness.connect();
            harness.runQueuedReconnects();

            assertEquals(2, harness.handshakes(), "第二次握手应当由闸门里的重连任务发起");
            assertTrue(harness.verifyPacket().isPresent(), "这一次应当发出认证包");
        }

        @Test
        @DisplayName("close() 之后不再重连")
        void closeStopsReconnecting() {
            BilibiliConnectorHarness harness = new BilibiliConnectorHarness();

            harness.connect();
            harness.connector().close();
            harness.runQueuedReconnects();

            assertEquals(ConnectStatus.CLOSED, harness.connector().getStatus());
            assertEquals(1, harness.handshakes(), "关闭后不该再握手");
        }
    }
}
