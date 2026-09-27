package org.frostnova.nova.core.model;

import org.frostnova.nova.core.enums.PushTargetType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 消息回调的登记与执行只看发送结果
 *
 * <h2>抓的是哪件用户故障</h2>
 * 插件可以先登记发送成功回调（例如 @全体成员 次数不足时改挂群待办）。编号 {@code setId}
 * 是公开可写的，旧判法把「结果还没定（PENDING）但已经有编号」当成已送达：当场只跑这一个
 * 新登记的回调，先前排着的回调从此没人跑；之后发送器真定结果时又看到状态已不是 PENDING、
 * 回空名单——先登记的那个回调永远不跑，待办漏挂、通知漏发都没有任何现象。
 *
 * <h2>口径</h2>
 * 结果只由发送器经 {@code markDelivered}／{@code markFailed} 定，编号有没有不影响：
 * PENDING 就排队，DELIVERED 当场跑成功回调，FAILED 当场跑失败回调。
 */
@DisplayName("消息回调：登记与执行只看发送结果")
class MessageDeliveryCallbacksTest {

    private static Message message() {
        return Message.create("qq-onebot", PushTargetType.GROUP, 1L, "内容").get(0);
    }

    /**
     * 抓的故障②：插件先登记成功回调，编号先被写上后再登记一个，先登记的那个永远不跑。
     * 定为送达时 A、B 必须各跑恰一次。
     */
    @Test
    @DisplayName("先登记 A、写编号、再登记 B、发送器定为送达：A、B 各跑一次")
    void bothSuccessCallbacksRunOnceWhenDelivered() {
        Message message = message();
        AtomicInteger a = new AtomicInteger();
        AtomicInteger b = new AtomicInteger();
        message.addOnSuccessCallback(a::incrementAndGet);
        message.setId("m1");
        message.addOnSuccessCallback(b::incrementAndGet);

        assertEquals(0, a.get(), "结果还没定，A 不该跑");
        assertEquals(0, b.get(), "有编号但结果还没定，B 不该当场跑");

        List<Runnable> toRun = message.markDelivered("m1");
        toRun.forEach(Runnable::run);

        assertEquals(1, a.get(), "A 应恰跑一次");
        assertEquals(1, b.get(), "B 应恰跑一次");
    }

    /**
     * 同一序列发送器定为没送达：失败回调照跑，先登记的 A 与 B 都不跑——
     * 没送达时成功回调一个都不许跑，不论编号写过没有。
     */
    @Test
    @DisplayName("定为没送达：失败回调照跑，A 与 B 都不跑")
    void successCallbacksDoNotRunWhenFailed() {
        Message message = message();
        AtomicInteger a = new AtomicInteger();
        AtomicInteger b = new AtomicInteger();
        AtomicInteger f = new AtomicInteger();
        message.addOnSuccessCallback(a::incrementAndGet);
        message.setId("m1");
        message.addOnSuccessCallback(b::incrementAndGet);
        message.addOnFailureCallback(f::incrementAndGet);

        message.markFailed().forEach(Runnable::run);

        assertEquals(1, f.get(), "失败回调应跑");
        assertEquals(0, a.get(), "没送达，A 不该跑");
        assertEquals(0, b.get(), "没送达，B 不该跑");
    }

    /**
     * PENDING 时哪怕编号已被写上，失败回调照样排队：定结果那一下它得跟着跑。
     * 旧判法有编号时失败回调既不排队也不跑——真没送达时登记方一个信号都收不到。
     */
    @Test
    @DisplayName("PENDING 时有编号，失败回调照样排队")
    void failureCallbackQueuesWhilePendingEvenWithId() {
        Message message = message();
        AtomicInteger f = new AtomicInteger();
        message.setId("m1");
        message.addOnFailureCallback(f::incrementAndGet);

        assertEquals(0, f.get(), "结果还没定，失败回调不该当场跑");

        message.markFailed().forEach(Runnable::run);

        assertEquals(1, f.get(), "排队了的失败回调应随定结果跑一次");
    }
}
