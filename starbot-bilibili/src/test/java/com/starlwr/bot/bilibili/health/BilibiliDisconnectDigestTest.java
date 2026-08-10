package com.starlwr.bot.bilibili.health;

import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/**
 * 断线归因与摘要测试
 * <p>
 * 归因的价值全在「别指错方向」：把一次认证被拒说成网络抖动，人会去查一条好的网络；
 * 把一次网络抖动说成认证问题，人会去重扫一个好的二维码。所以判据逐条钉死。
 */
@DisplayName("断线归因与摘要")
class BilibiliDisconnectDigestTest {
    private StarBotBilibiliProperties properties;

    private BilibiliDisconnectDigest digest;

    @BeforeEach
    void setUp() {
        properties = new StarBotBilibiliProperties();
        digest = new BilibiliDisconnectDigest(properties, mock(TaskScheduler.class));
    }

    @Nested
    @DisplayName("归因")
    class Classify {
        @Test
        @DisplayName("我们自己关的一律算主动关闭，不管关闭码是什么")
        void closedByUs() {
            assertEquals(BilibiliDisconnectCause.BY_US,
                    BilibiliDisconnectCause.classify(true, 1006, true, Duration.ofHours(1)));
            assertEquals(BilibiliDisconnectCause.BY_US,
                    BilibiliDisconnectCause.classify(true, 1000, false, null));
        }

        @Test
        @DisplayName("1000 是服务端正常关闭")
        void serverClosed() {
            assertEquals(BilibiliDisconnectCause.SERVER_CLOSED,
                    BilibiliDisconnectCause.classify(false, 1000, true, Duration.ofMinutes(30)));
        }

        @Test
        @DisplayName("认证没过就断的 1006，要优先怀疑认证包而不是网络")
        void rejectedAtHandshake() {
            // 历史上 8eafd67 那个身份竞态就是这个形状：认证包带匿名 token + 登录 uid，
            // 服务端握手后立刻切断且不发关闭帧
            assertEquals(BilibiliDisconnectCause.REJECTED_AT_HANDSHAKE,
                    BilibiliDisconnectCause.classify(false, 1006, false, null));
        }

        @Test
        @DisplayName("认证过了但没撑过一个心跳周期，算被切断而不是抖动")
        void droppedEarly() {
            assertEquals(BilibiliDisconnectCause.DROPPED_EARLY,
                    BilibiliDisconnectCause.classify(false, 1006, true, Duration.ofSeconds(5)));
        }

        @Test
        @DisplayName("活了一段时间之后的 1006 才叫网络抖动")
        void networkFlap() {
            assertEquals(BilibiliDisconnectCause.NETWORK_FLAP,
                    BilibiliDisconnectCause.classify(false, 1006, true, Duration.ofMinutes(20)));
        }

        @Test
        @DisplayName("算不出存活时长时按抖动处理，不凭空指认认证有问题")
        void unknownLivedFallsBackToFlap() {
            // 宁可把一次早断说成抖动，也不要让人去查一个好的认证包
            assertEquals(BilibiliDisconnectCause.NETWORK_FLAP,
                    BilibiliDisconnectCause.classify(false, 1006, true, null));
        }

        @Test
        @DisplayName("其它关闭码单独归一类，不硬塞进上面几种")
        void otherCodes() {
            assertEquals(BilibiliDisconnectCause.OTHER,
                    BilibiliDisconnectCause.classify(false, 1011, true, Duration.ofMinutes(5)));
        }

        @Test
        @DisplayName("每种归因都得给出下一步提示，只换个说法等于没归因")
        void everyCauseHasHint() {
            for (BilibiliDisconnectCause cause : BilibiliDisconnectCause.values()) {
                assertEquals(false, cause.getHint() == null || cause.getHint().isBlank(),
                        cause.name() + " 缺少处理建议");
            }
        }
    }

    @Nested
    @DisplayName("摘要")
    class Digest {
        @Test
        @DisplayName("主动关闭不进摘要：停止监听不该算进断线率")
        void byUsIsNotCounted() {
            digest.record(1001L, BilibiliDisconnectCause.BY_US, Duration.ofMinutes(5));

            assertEquals(0, digest.pendingCount());
        }

        @Test
        @DisplayName("窗口内逐次累积，flush 之后清空")
        void accumulatesThenClears() {
            digest.record(1001L, BilibiliDisconnectCause.NETWORK_FLAP, Duration.ofMinutes(10));
            digest.record(1001L, BilibiliDisconnectCause.NETWORK_FLAP, Duration.ofMinutes(8));
            digest.record(1002L, BilibiliDisconnectCause.DROPPED_EARLY, Duration.ofSeconds(3));

            assertEquals(3, digest.pendingCount());

            digest.flush();

            assertEquals(0, digest.pendingCount());
        }

        @Test
        @DisplayName("没有断线的窗口不打日志也不报错")
        void emptyWindowIsSilent() {
            digest.flush();

            assertEquals(0, digest.pendingCount());
        }

        @Test
        @DisplayName("存活时长未知的记录不该污染平均值")
        void unknownLivedDoesNotBreakAverage() {
            digest.record(1001L, BilibiliDisconnectCause.REJECTED_AT_HANDSHAKE, null);
            digest.record(1001L, BilibiliDisconnectCause.NETWORK_FLAP, Duration.ofMinutes(2));

            // 只验不抛且清空；平均值本身走日志，这里锁的是「-1 不会被当成 0 秒算进去」
            digest.flush();

            assertEquals(0, digest.pendingCount());
        }
    }
}
