package org.frostnova.nova.bilibili.service;

import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.bilibili.BilibiliPlatform;
import org.frostnova.nova.bilibili.config.NovaBilibiliProperties;
import org.frostnova.nova.bilibili.enums.ConnectStatus;
import org.frostnova.nova.bilibili.health.BilibiliDisconnectCause;
import org.frostnova.nova.bilibili.health.BilibiliRiskMetrics;
import org.frostnova.nova.core.model.LiveGap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.frostnova.nova.bilibili.service.BilibiliConnectorHarness.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

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
                    .map(org.frostnova.nova.bilibili.protocol.BilibiliPacket::getOperation)
                    .toList();

            assertFalse(ops.isEmpty(), "应当发出过包");
            assertEquals(org.frostnova.nova.bilibili.enums.DataPackType.VERIFY.getCode(), ops.get(0),
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

    @Nested
    @DisplayName("业务消息断流：先重连一次再判")
    class StallDetection {
        /**
         * 判定所需的连续窗口数。从默认配置里取而不是写死：
         * 改了默认值时这些测试应当跟着走，而不是变成对着一个旧数字的断言
         */
        private final int WINDOWS = new NovaBilibiliProperties().getLive().getAutoDetectLiveRoomRiskWindows();

        /**
         * 攒满一段断流：每个窗口喂够逐用户事件（进房类），业务消息为零
         * @return 最后一次判定的结果
         */
        private boolean stall(BilibiliConnectorHarness harness, int windows) {
            boolean judged = false;
            for (int window = 0; window < windows; window++) {
                for (int i = 0; i < 10; i++) {
                    harness.receive("INTERACT_WORD_V2");
                }
                // 定时推送也一起喂：它们不该把样本量下限顶上去，也不该妨碍判定
                harness.receive("ONLINE_RANK_COUNT");
                harness.receive("WATCHED_CHANGE");
                judged = harness.connector().detectRisk();
            }
            return judged;
        }

        @Test
        @DisplayName("⚠️ 第一次攒满断流只重连，不判定")
        void firstStallOnlyReconnects() {
            BilibiliConnectorHarness harness = new BilibiliConnectorHarness().living();
            harness.connect();
            int handshakesBefore = harness.handshakes();

            boolean judged = stall(harness, WINDOWS);

            assertFalse(judged, "第一次攒满不该直接判定——重连是最便宜的动作，也是区分半死连接的那个实验");
            harness.fireConnectionClosed(1000);
            assertEquals(1, harness.queuedReconnects(),
                    "断流触发的这次重连同样只该排一次队：reconnect() 与关闭回调是同一次断开");
            harness.runQueuedReconnects();
            assertEquals(handshakesBefore + 1, harness.handshakes(), "应当重连一次");
            assertNotEquals(ConnectStatus.RISK, harness.connector().getStatus(), "还没到判定这一步");
        }

        @Test
        @DisplayName("重连之后仍然断流才判定")
        void judgesOnlyAfterReconnectFails() {
            BilibiliConnectorHarness harness = new BilibiliConnectorHarness().living();
            harness.connect();

            assertFalse(stall(harness, WINDOWS), "第一段只重连");
            harness.fireConnectionClosed(1000);
            harness.runQueuedReconnects();

            assertTrue(stall(harness, WINDOWS), "重连后仍然断流，这次要判定");
            assertEquals(ConnectStatus.RISK, harness.connector().getStatus());
        }

        @Test
        @DisplayName("业务消息恢复后，下一段断流仍能再重连一次")
        void reconnectChanceIsRestoredAfterRecovery() {
            BilibiliConnectorHarness harness = new BilibiliConnectorHarness().living();
            harness.connect();

            assertFalse(stall(harness, WINDOWS), "第一段只重连");
            harness.fireConnectionClosed(1000);
            harness.runQueuedReconnects();

            // 业务消息回来了：这一段断流结束
            harness.receive("DANMU_MSG");
            harness.connector().detectRisk();

            // 下一段断流应当重新获得那次重连机会，而不是直接判定
            int handshakesBefore = harness.handshakes();
            assertFalse(stall(harness, WINDOWS), "恢复过之后，新的一段断流应当再给一次重连机会");
            harness.fireConnectionClosed(1000);
            harness.runQueuedReconnects();
            assertEquals(handshakesBefore + 1, harness.handshakes());
        }

        @Test
        @DisplayName("SEND_GIFT_V2 计入业务消息：礼物灰度切 V2 后仍算恢复")
        void sendGiftV2CountsAsBusinessMessage() {
            // 2026-09-01 起平台按房间灰度改发 SEND_GIFT_V2。它若不进业务消息集合，
            // 只剩 V2 礼物的房间会被断流判据当成还没恢复，攒够窗口就重连个没完。
            // 解析器在这些测试里是桩，计数走的是连接器自己的 cmd 集合——
            // 本格只量「V2 在不在集合里」，与解析无关
            BilibiliConnectorHarness harness = new BilibiliConnectorHarness().living();
            harness.connect();

            assertFalse(stall(harness, WINDOWS), "第一段只重连");
            harness.fireConnectionClosed(1000);
            harness.runQueuedReconnects();

            // 房间里只有 SEND_GIFT_V2 这一类的业务消息：这也该算恢复
            harness.receive("SEND_GIFT_V2");
            harness.connector().detectRisk();

            int handshakesBefore = harness.handshakes();
            assertFalse(stall(harness, WINDOWS), "只靠 V2 礼物恢复过的房间，新的一段断流应当再给一次重连机会");
            // 判没判定要看状态而不是 stall 的返回值：一旦判过，后续 detectRisk 会因状态
            // 已是 RISK 直接返回 false，最后一窗的返回值会把判定遮住
            assertNotEquals(ConnectStatus.RISK, harness.connector().getStatus(), "恢复过就不该直接判定");
            harness.fireConnectionClosed(1000);
            harness.runQueuedReconnects();
            assertEquals(handshakesBefore + 1, harness.handshakes());
        }

        @Test
        @DisplayName("安静但在播：定时推送再多也不重连、不判定")
        void quietRoomNeitherReconnectsNorJudges() {
            // 2026-08-10 深夜那次误报的形状：总量被排行与看过撑起来，逐用户事件只有 1 条
            BilibiliConnectorHarness harness = new BilibiliConnectorHarness().living();
            harness.connect();
            int handshakesBefore = harness.handshakes();

            for (int window = 0; window < WINDOWS; window++) {
                harness.receive("INTERACT_WORD_V2");
                for (int i = 0; i < 12; i++) {
                    harness.receive("ONLINE_RANK_COUNT");
                }
                assertFalse(harness.connector().detectRisk());
            }

            harness.runQueuedReconnects();
            assertEquals(handshakesBefore, harness.handshakes(), "安静不是故障，不该为它重连");
            assertNotEquals(ConnectStatus.RISK, harness.connector().getStatus());
        }

        /**
         * 攒满一段「解析降级」的断流：进房类还有量（下限过得去）、业务消息为零
         * 且解析失败在涨——协议变更时的可观测形状
         */
        private boolean degradedStall(BilibiliConnectorHarness harness, int windows) {
            boolean judged = false;
            for (int window = 0; window < windows; window++) {
                for (int i = 0; i < 10; i++) {
                    harness.receive("INTERACT_WORD_V2");
                }
                for (int i = 0; i < 3; i++) {
                    // 弹幕还在到达，只是解析不出来——桩已在 parseDegradedFor 里按这个 cmd 打开
                    harness.receive("DANMU_MSG");
                }
                judged = harness.connector().detectRisk();
            }
            return judged;
        }

        @Test
        @DisplayName("⚠️ 解析失败而业务为零：缺口成因落 PARSE_DEGRADED；解析全好时绝不落")
        void parseDegradedGapRecordedOnlyWhenParseFailuresExist() {
            List<String> reds = new ArrayList<>();

            BilibiliConnectorHarness degraded = new BilibiliConnectorHarness().living();
            degraded.connect();
            degraded.parseDegradedFor("DANMU_MSG");
            assertFalse(degradedStall(degraded, WINDOWS), "第一段只重连");
            degraded.fireConnectionClosed(1000);
            degraded.runQueuedReconnects();
            assertTrue(degradedStall(degraded, WINDOWS), "重连后业务仍然全在解析失败，这次要判定");
            assertEquals(ConnectStatus.RISK, degraded.connector().getStatus());

            try {
                verify(degraded.getLiveDataService()).recordRoomOutage(
                        eq(BilibiliPlatform.BILIBILI.id()), eq(STREAMER_UID),
                        anyLong(), anyLong(), eq(LiveGap.Reason.PARSE_DEGRADED));
            } catch (AssertionError e) {
                reds.add("① " + e.getMessage());
            }

            try {
                // 阴性对照：普通断流（解析一切正常）判定的缺口，成因绝不许是 PARSE_DEGRADED——
                // 这一项一旦写歪，报告里的「断流」与「解析降级」两栏就再也分不开了
                BilibiliConnectorHarness normal = new BilibiliConnectorHarness().living();
                normal.connect();
                assertFalse(stall(normal, WINDOWS));
                normal.fireConnectionClosed(1000);
                normal.runQueuedReconnects();
                assertTrue(stall(normal, WINDOWS));
                assertEquals(ConnectStatus.RISK, normal.connector().getStatus());
                verify(normal.getLiveDataService(), never()).recordRoomOutage(
                        any(), any(), anyLong(), anyLong(), eq(LiveGap.Reason.PARSE_DEGRADED));
            } catch (AssertionError e) {
                reds.add("② " + e.getMessage());
            }

            assertTrue(reds.isEmpty(), () -> "两问中 " + reds.size() + " 问红: " + String.join("; ", reds));
        }
    }

    /**
     * 重连排队与退避阶梯
     * <p>
     * 一次断开会从两条路径各排一次队：{@code reconnect()} 自己排一次，
     * 它关掉会话后容器回调 {@code afterConnectionClosed} 又排一次。
     * 多排的那次是空转的，所以<b>行为上看不出来</b>——2026-08-11 08:46 的实况日志里
     * 两条「第 N 次重连」相隔 5 毫秒，而下一次真实重试的退避已经是设计值的两倍：
     * <pre>
     * 08:46:12.865 第 1 次重连，退避 1000 毫秒
     * 08:46:12.870 第 2 次重连，退避 2000 毫秒   ← 同一次断开
     * 08:46:26.228 第 3 次重连，退避 4000 毫秒   ← 本该是「第 2 次、2000 毫秒」
     * </pre>
     * 所以这组测试断言的是<b>退避时长</b>而不只是排队次数：只数次数的话，
     * 修好之前修好之后的握手次数完全一样，测试照样全绿。
     */
    @Nested
    @DisplayName("重连排队与退避阶梯")
    class ReconnectScheduling {
        /** 退避基准，从默认配置取：改了默认值时测试应当跟着走 */
        private final long BASE_SECONDS =
                new NovaBilibiliProperties().getLive().getLiveRoomReconnectInterval() / 1000;

        /**
         * 走一次「本端主动重连」：发包失败 → reconnect() → 关会话 + 排队，
         * 随后容器把关闭回调送回来，于是两条路径都到齐了
         */
        private BilibiliConnectorHarness disconnectOnce() {
            BilibiliConnectorHarness harness = new BilibiliConnectorHarness();
            harness.connect();
            harness.failNextSend();
            harness.fireHeartbeat();
            harness.fireConnectionClosed(1000);
            return harness;
        }

        @Test
        @DisplayName("⚠️ 一次断开只排一次重连")
        void oneDisconnectQueuesOneReconnect() {
            BilibiliConnectorHarness harness = disconnectOnce();

            assertEquals(1, harness.scheduleCount(),
                    "reconnect() 与关闭回调是同一次断开的两条路径，不该各排一次");
            assertEquals(1, harness.queuedReconnects());
            assertEquals(BASE_SECONDS, harness.lastBackoffSeconds(), "第一次重试就该按一个基准等");
        }

        @Test
        @DisplayName("⚠️ 退避阶梯每次真实重试只爬一级")
        void backoffClimbsOncePerRealRetry() {
            BilibiliConnectorHarness harness = disconnectOnce();

            // 排出去的那次重连跑起来，握手失败——这才是第二次真实重试
            harness.failNextHandshake();
            harness.runQueuedReconnects();

            assertEquals(2, harness.scheduleCount(), "一次断开加一次失败重试，一共只该排两次");
            assertEquals(1, harness.queuedReconnects(), "握手失败之后仍要排下一次重连");
            assertEquals(BASE_SECONDS * 2, harness.lastBackoffSeconds(),
                    "第二次真实重试该等两个基准；等到四个说明计数被同一次断开加了两次");
        }

        @Test
        @DisplayName("重连跑起来之后闸门要放开，否则一次失败就再也不重连了")
        void gateReopensWhenTheQueuedReconnectRuns() {
            BilibiliConnectorHarness harness = disconnectOnce();

            harness.failNextHandshake();
            harness.runQueuedReconnects();
            harness.failNextHandshake();
            harness.runQueuedReconnects();

            assertEquals(3, harness.scheduleCount());
            assertEquals(1, harness.queuedReconnects(), "连续失败要能一次接一次地排下去");
            assertEquals(BASE_SECONDS * 4, harness.lastBackoffSeconds(), "第三次真实重试等四个基准");
        }

        @Test
        @DisplayName("⚠️ 本端为重连而关的连接，归因不能说成「服务端正常关闭」")
        void localReconnectIsNotBlamedOnTheServer() {
            // 2026-08-11 早上的实况：心跳超时 → 本端 close() → 容器回调给的关闭码是 1000。
            // 旧判据只看「是否已被永久关闭」与关闭码，于是归成「服务端正常关闭」，
            // 我照着这个标签把一次笔记本合盖睡眠写成了平台断线。
            // 错的方向最坏：把本端问题说成对端问题，人会一路往外查
            BilibiliConnectorHarness harness = disconnectOnce();

            assertEquals(List.of(BilibiliDisconnectCause.LOCAL_RECONNECT), harness.recordedCauses(),
                    "关闭码里没有「是谁关的」这个信息，只有连接器自己知道");
        }

        @Test
        @DisplayName("平台关的 1000 仍然算服务端正常关闭，不能反过来错标成本端")
        void platformCloseIsStillServerClosed() {
            BilibiliConnectorHarness harness = new BilibiliConnectorHarness();
            harness.connect();

            harness.fireConnectionClosed(1000);

            assertEquals(List.of(BilibiliDisconnectCause.SERVER_CLOSED), harness.recordedCauses());
        }

        @Test
        @DisplayName("停止监听算主动关闭，不算故障")
        void stoppingIsByUs() {
            BilibiliConnectorHarness harness = new BilibiliConnectorHarness();
            harness.connect();

            harness.connector().close();
            harness.fireConnectionClosed(1000);

            assertEquals(List.of(BilibiliDisconnectCause.BY_US), harness.recordedCauses(),
                    "close() 之后的那次回调是我们自己造成的，且不该进断线率");
        }

        @Test
        @DisplayName("本端关过一次之后，下一次平台断开不能继续记在本端账上")
        void theLocalCloseMarkDoesNotLeakToTheNextDisconnect() {
            BilibiliConnectorHarness harness = disconnectOnce();

            // 重连成功，然后这一次是平台关的
            harness.runQueuedReconnects();
            harness.fireConnectionClosed(1000);

            assertEquals(
                    List.of(BilibiliDisconnectCause.LOCAL_RECONNECT, BilibiliDisconnectCause.SERVER_CLOSED),
                    harness.recordedCauses(),
                    "「本端关的」这笔记录只对那一条会话有效");
        }

        @Test
        @DisplayName("恢复连接之后新的一次断开重新从一个基准起算")
        void ladderResetsAfterMessagesResume() {
            BilibiliConnectorHarness harness = disconnectOnce();

            // 重连成功并收到消息——收到消息才是连接确实可用的证据，退避计数在那时清零
            harness.runQueuedReconnects();
            harness.receive("DANMU_MSG");

            harness.failNextSend();
            harness.fireHeartbeat();
            harness.fireConnectionClosed(1000);

            assertEquals(2, harness.scheduleCount(), "两段断流各排一次");
            assertEquals(1, harness.queuedReconnects());
            assertEquals(BASE_SECONDS, harness.lastBackoffSeconds(),
                    "上一段的失败次数已经清零，这一段该从一个基准重新起算");
        }
    }
    /**
     * 单房断线要记成采集缺口（第②批）
     * <p>
     * ⚠️ <b>这一项没接上时，归档里的缺口永远是 0——而 0 与「真的没断过」长得一模一样。</b>
     * 所以断言的不是「值对不对」，而是「到底记没记」。
     */
    @Nested
    @DisplayName("单房断线记成采集缺口")
    class RoomOutage {
        @Test
        @DisplayName("⚠️ 断线到重连认证成功之间，应记一段采集缺口")
        void recordsOutageBetweenCloseAndVerify() {
            BilibiliConnectorHarness harness = new BilibiliConnectorHarness();
            harness.connect();
            harness.fireVerifySuccess();

            harness.fireConnectionClosed(1006);
            harness.fireVerifySuccess();

            org.mockito.Mockito.verify(harness.getLiveDataService()).recordRoomOutage(
                    org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.eq(STREAMER_UID),
                    org.mockito.ArgumentMatchers.anyLong(),
                    org.mockito.ArgumentMatchers.anyLong());
        }

        @Test
        @DisplayName("从没认证成功过的连接断掉，不算采集缺口——那时本来就没在采")
        void firstHandshakeFailureIsNotAnOutage() {
            BilibiliConnectorHarness harness = new BilibiliConnectorHarness();
            harness.connect();

            // 没有 fireVerifySuccess，直接断
            harness.fireConnectionClosed(1006);
            harness.fireVerifySuccess();

            org.mockito.Mockito.verify(harness.getLiveDataService(),
                    org.mockito.Mockito.never()).recordRoomOutage(
                    org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.anyLong(),
                    org.mockito.ArgumentMatchers.anyLong(),
                    org.mockito.ArgumentMatchers.anyLong());
        }

        @Test
        @DisplayName("尺子先过阳性对照：上一条的 never() 必须真的分得出记与不记")
        void neverAssertionIsDiscriminating() {
            // 若 verify(...).recordRoomOutage 的匹配器写错，never() 会永远通过。
            // 用一个确实会记录的场景证明同一组匹配器抓得到
            BilibiliConnectorHarness harness = new BilibiliConnectorHarness();
            harness.connect();
            harness.fireVerifySuccess();
            harness.fireConnectionClosed(1006);
            harness.fireVerifySuccess();

            org.mockito.Mockito.verify(harness.getLiveDataService(),
                    org.mockito.Mockito.times(1)).recordRoomOutage(
                    org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.anyLong(),
                    org.mockito.ArgumentMatchers.anyLong(),
                    org.mockito.ArgumentMatchers.anyLong());
        }
    }

    @Nested
    @DisplayName("未知操作码")
    class UnknownOperation {
        @Test
        @DisplayName("未知 op 记 UNKNOWN_OP、已知非 NOTICE op 不记")
        void recordsUnknownOpAndIgnoresKnownNonNotice() {
            java.util.List<String> reds = new java.util.ArrayList<>();
            BilibiliRiskMetrics metrics = new BilibiliRiskMetrics();
            java.util.concurrent.ConcurrentHashMap<Integer, java.util.concurrent.atomic.AtomicLong> ledger =
                    new java.util.concurrent.ConcurrentHashMap<>();

            try {
                assertTrue(BilibiliLiveRoomConnector.isUnknownOperation(9), "9 不在枚举内，应判未知");
                BilibiliLiveRoomConnector.noteUnknownOperation(9, ledger, metrics);
                assertEquals(1, metrics.count(BilibiliRiskMetrics.Kind.UNKNOWN_OP, java.time.Duration.ofMinutes(1)),
                        "未知 op 首见应记一次");
                assertEquals("op=9", metrics.lastDetail(BilibiliRiskMetrics.Kind.UNKNOWN_OP).orElse(""));
            } catch (AssertionError e) {
                reds.add("① " + e.getMessage());
            }

            try {
                long before = metrics.count(BilibiliRiskMetrics.Kind.UNKNOWN_OP, java.time.Duration.ofMinutes(1));
                int[] known = {2, 3, 5, 7, 8};
                for (int op : known) {
                    assertFalse(BilibiliLiveRoomConnector.isUnknownOperation(op),
                            "已知码 " + op + " 不应判未知");
                    BilibiliLiveRoomConnector.noteUnknownOperation(op, ledger, metrics);
                }
                assertEquals(before, metrics.count(BilibiliRiskMetrics.Kind.UNKNOWN_OP, java.time.Duration.ofMinutes(1)),
                        "已知非 NOTICE op 不得记 UNKNOWN_OP");
            } catch (AssertionError e) {
                reds.add("② " + e.getMessage());
            }

            try {
                for (int i = 0; i < 9; i++) {
                    BilibiliLiveRoomConnector.noteUnknownOperation(9, ledger, metrics);
                }
                assertEquals(10, metrics.count(BilibiliRiskMetrics.Kind.UNKNOWN_OP, java.time.Duration.ofMinutes(1)),
                        "计数是发生次数不是写入次数，实际 "
                                + metrics.count(BilibiliRiskMetrics.Kind.UNKNOWN_OP, java.time.Duration.ofMinutes(1)));
                // 量级只管返回值（换样本、打日志），第 11 条不落量级但照样计数
                assertFalse(BilibiliLiveRoomConnector.noteUnknownOperation(9, ledger, metrics),
                        "第 11 条不在量级上，不该再要求换样本");
                assertEquals(11, metrics.count(BilibiliRiskMetrics.Kind.UNKNOWN_OP, java.time.Duration.ofMinutes(1)));
            } catch (AssertionError e) {
                reds.add("③ " + e.getMessage());
            }

            assertTrue(reds.isEmpty(), () -> "三问中 " + reds.size() + " 问红: " + String.join("; ", reds));
        }
    }

    @Nested
    @DisplayName("未知协议版本")
    class UnknownVersion {
        @Test
        @DisplayName("sink 转来的 ver 记 UNKNOWN_VER、逐条计数、样本只在量级处换")
        void recordsUnknownVersionWithMagnitudes() {
            java.util.List<String> reds = new java.util.ArrayList<>();
            BilibiliRiskMetrics metrics = new BilibiliRiskMetrics();
            java.util.concurrent.ConcurrentHashMap<Integer, java.util.concurrent.atomic.AtomicLong> ledger =
                    new java.util.concurrent.ConcurrentHashMap<>();

            try {
                assertTrue(BilibiliLiveRoomConnector.noteUnknownVersion(5, ledger, metrics),
                        "首见应返回已记一次");
                assertEquals(1, metrics.count(BilibiliRiskMetrics.Kind.UNKNOWN_VER, java.time.Duration.ofMinutes(1)),
                        "未知 ver 首见应记一次");
                assertEquals("ver=5", metrics.lastDetail(BilibiliRiskMetrics.Kind.UNKNOWN_VER).orElse(""));
            } catch (AssertionError e) {
                reds.add("① " + e.getMessage());
            }

            try {
                assertEquals(0, metrics.count(BilibiliRiskMetrics.Kind.UNKNOWN_OP, java.time.Duration.ofMinutes(1)),
                        "记 ver 不得写错到未知操作码上");
            } catch (AssertionError e) {
                reds.add("② " + e.getMessage());
            }

            try {
                for (int i = 0; i < 9; i++) {
                    BilibiliLiveRoomConnector.noteUnknownVersion(5, ledger, metrics);
                }
                assertEquals(10, metrics.count(BilibiliRiskMetrics.Kind.UNKNOWN_VER, java.time.Duration.ofMinutes(1)),
                        "计数是发生次数不是写入次数，实际 "
                                + metrics.count(BilibiliRiskMetrics.Kind.UNKNOWN_VER, java.time.Duration.ofMinutes(1)));
                // 量级只管返回值（换样本、打日志），第 11 条不落量级但照样计数
                assertFalse(BilibiliLiveRoomConnector.noteUnknownVersion(5, ledger, metrics),
                        "第 11 条不在量级上，不该再要求换样本");
                assertEquals(11, metrics.count(BilibiliRiskMetrics.Kind.UNKNOWN_VER, java.time.Duration.ofMinutes(1)));
            } catch (AssertionError e) {
                reds.add("③ " + e.getMessage());
            }

            assertTrue(reds.isEmpty(), () -> "三问中 " + reds.size() + " 问红: " + String.join("; ", reds));
        }
    }
}
