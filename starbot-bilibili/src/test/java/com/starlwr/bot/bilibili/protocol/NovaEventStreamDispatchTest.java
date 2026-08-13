package com.starlwr.bot.bilibili.protocol;

import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 分发过程中订阅者集合被改动
 * <p>
 * 🔴 这不是假想情况，是<b>设计上就会走到的那条路</b>：
 * {@code NovaEventEndpoint} 里写着「队列满说明这个客户端已经跟不上，<b>断开它</b>而不是丢消息」，
 * 而断开会调 {@link NovaEventStream#unsubscribe}。
 * <p>
 * 这一步发生在 {@code onFrame} 里，也就是发生在 {@code publish} 遍历订阅者的<b>中途</b>，
 * 且是同一个线程（锁可重入，进得去）。集合若是普通 {@code Set}，
 * 下一次 {@code next()} 就抛 {@code ConcurrentModificationException}——
 * <b>而且是抛在发布线程上，也就是整条弹幕流水线上</b>。
 */
@DisplayName("事件流分发期间的订阅者增删")
class NovaEventStreamDispatchTest {
    private JSONObject envelope() {
        JSONObject envelope = new JSONObject();
        envelope.put("v", 2);
        envelope.put("kind", "danmaku");
        return envelope;
    }

    @Test
    @DisplayName("🔴 订阅者在 onFrame 里退订自己，不能把发布线程带崩")
    void survivesUnsubscribeDuringDispatch() {
        NovaEventStream stream = new NovaEventStream(16);
        List<String> delivered = new ArrayList<>();

        // 第一个订阅者一收到就退订自己 —— 完全对应「客户端消费不过来, 断开它」
        NovaEventStream.Subscriber quitter = new NovaEventStream.Subscriber() {
            @Override
            public void onFrame(NovaEventStream.Frame frame) {
                delivered.add("quitter");
                stream.unsubscribe(this);
            }
        };
        // 必须再挂一个：只有一个订阅者时循环在退订后直接结束，撞不到那次 next()
        NovaEventStream.Subscriber stayer = frame -> delivered.add("stayer");

        stream.subscribe(quitter, 0);
        stream.subscribe(stayer, 0);

        assertDoesNotThrow(() -> stream.publish(envelope()),
                "遍历订阅者时被移除元素会抛 ConcurrentModificationException, 而且抛在发布线程上");

        assertEquals(List.of("quitter", "stayer"), delivered,
                "退订的那一位这一帧仍该收到（他是在收到之后才走的），后面的人也不能被连累漏掉");
    }

    @Test
    @DisplayName("退订在下一帧真正生效")
    void unsubscribeTakesEffectNextFrame() {
        NovaEventStream stream = new NovaEventStream(16);
        List<String> delivered = new ArrayList<>();

        NovaEventStream.Subscriber quitter = new NovaEventStream.Subscriber() {
            @Override
            public void onFrame(NovaEventStream.Frame frame) {
                delivered.add("quitter");
                stream.unsubscribe(this);
            }
        };
        NovaEventStream.Subscriber stayer = frame -> delivered.add("stayer");

        stream.subscribe(quitter, 0);
        stream.subscribe(stayer, 0);

        stream.publish(envelope());
        delivered.clear();
        stream.publish(envelope());

        assertEquals(List.of("stayer"), delivered,
                "「这一帧照收」不能变成「永远收得到」——退订必须真的生效, 否则断开慢客户端就落空了");
    }
}
