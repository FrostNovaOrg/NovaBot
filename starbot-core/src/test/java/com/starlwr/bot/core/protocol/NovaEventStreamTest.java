package com.starlwr.bot.core.protocol;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 事件流的编号与回补测试
 * <p>
 * 这部分逻辑一旦出错，症状是下游面板<b>静默地少一段数据</b>——不报错、不断连，
 * 只是本场累计比实际少。真实连接上极难复现，因此这里覆盖到每一条边界。
 */
@DisplayName("事件流编号与回补")
class NovaEventStreamTest {
    private static JSONObject envelope(String kind) {
        JSONObject j = new JSONObject();
        j.put("v", NovaEventEndpoint.PROTOCOL_VERSION);
        j.put("kind", kind);
        return j;
    }

    /**
     * 收集帧的订阅者
     */
    private static class Collector implements NovaEventStream.Subscriber {
        private final List<NovaEventStream.Frame> frames = new ArrayList<>();

        @Override
        public void onFrame(NovaEventStream.Frame frame) {
            frames.add(frame);
        }

        private List<Long> seqs() {
            return frames.stream().map(NovaEventStream.Frame::seq).toList();
        }
    }

    @Test
    @DisplayName("seq 从 1 开始严格递增")
    void seqStartsAtOne() {
        NovaEventStream stream = new NovaEventStream(10);

        assertEquals(0, stream.snapshot().lastSeq(), "还没发过消息时 lastSeq 应为 0");
        assertEquals(1, stream.publish(envelope("danmaku")).seq());
        assertEquals(2, stream.publish(envelope("gift")).seq());
        assertEquals(3, stream.publish(envelope("enter")).seq());
    }

    @Test
    @DisplayName("seq 写进信封本身，客户端读到的与缓冲里的是同一个数")
    void seqIsWrittenIntoEnvelope() {
        NovaEventStream stream = new NovaEventStream(10);

        NovaEventStream.Frame frame = stream.publish(envelope("danmaku"));
        JSONObject parsed = JSON.parseObject(frame.json());

        assertEquals(1L, parsed.getLongValue("seq"));
        assertEquals(frame.seq(), parsed.getLongValue("seq"));
    }

    @Test
    @DisplayName("⚠️ 空值必须写进 JSON，不能被丢掉")
    void nullsAreSerialized() {
        NovaEventStream stream = new NovaEventStream(10);

        JSONObject envelope = envelope("danmaku");
        envelope.put("user", null);

        String json = stream.publish(envelope).json();

        // fastjson2 默认丢弃空值。协议里 face / medal / emoji / replyTo / blindBox /
        // combo / companionDays 都是「可以为 null」而非「可以不存在」,
        // 少了这些键，按协议写的客户端会当成字段缺失而报校验错
        assertTrue(json.contains("\"user\":null"), "空值被丢掉了: " + json);
    }

    @Test
    @DisplayName("缓冲满后丢最老的，bufferedFrom 跟着往前走")
    void bufferEvictsOldest() {
        NovaEventStream stream = new NovaEventStream(3);

        for (int i = 0; i < 5; i++) {
            stream.publish(envelope("danmaku"));
        }

        NovaEventStream.State state = stream.snapshot();
        assertEquals(5, state.lastSeq());
        assertEquals(3, state.bufferedFrom(), "缓冲只剩 3、4、5");
    }

    @Test
    @DisplayName("缓冲为空时 bufferedFrom 是 lastSeq + 1，含义是「从下一条起才有」")
    void bufferedFromOnEmptyBuffer() {
        NovaEventStream stream = new NovaEventStream(10);

        assertEquals(1, stream.snapshot().bufferedFrom());
    }

    @Test
    @DisplayName("新客户端从当前序号订阅，不会收到历史消息")
    void freshSubscriberGetsNoHistory() {
        NovaEventStream stream = new NovaEventStream(10);
        stream.publish(envelope("danmaku"));
        stream.publish(envelope("gift"));

        Collector collector = new Collector();
        assertTrue(stream.subscribe(collector, stream.snapshot().lastSeq()));
        assertEquals(List.of(), collector.seqs());

        stream.publish(envelope("enter"));
        assertEquals(List.of(3L), collector.seqs());
    }

    @Test
    @DisplayName("回补：缺口在窗口内时无缺号、无重号")
    void resumeWithinWindow() {
        NovaEventStream stream = new NovaEventStream(10);
        for (int i = 0; i < 6; i++) {
            stream.publish(envelope("danmaku"));
        }

        // 客户端断线前收到了 2，回来时报 fromSeq=2
        Collector collector = new Collector();
        assertTrue(stream.subscribe(collector, 2));

        assertEquals(List.of(3L, 4L, 5L, 6L), collector.seqs());

        stream.publish(envelope("gift"));
        assertEquals(List.of(3L, 4L, 5L, 6L, 7L), collector.seqs(), "回补之后要无缝转入实时流");
    }

    @Test
    @DisplayName("⚠️ 回补：缺口超出窗口时拒绝，绝不发一段残缺的")
    void resumeOutOfWindowIsRejected() {
        NovaEventStream stream = new NovaEventStream(3);
        for (int i = 0; i < 6; i++) {
            stream.publish(envelope("danmaku"));
        }

        // 缓冲只剩 4、5、6，而客户端要的是 2 之后的全部
        Collector collector = new Collector();
        assertFalse(stream.subscribe(collector, 2));

        // 补了一半比明说补不上更危险: 下游会拿一个看起来精确、实际少算的累计去展示
        assertEquals(List.of(), collector.seqs());

        stream.publish(envelope("gift"));
        assertEquals(List.of(), collector.seqs(), "被拒绝的订阅者不该被加进实时流");
    }

    @Test
    @DisplayName("回补：缺口正好卡在窗口边界时应当放行")
    void resumeAtWindowBoundary() {
        NovaEventStream stream = new NovaEventStream(3);
        for (int i = 0; i < 6; i++) {
            stream.publish(envelope("danmaku"));
        }

        // 缓冲最老的是 4，客户端手上是 3，要的正好是 4、5、6
        Collector collector = new Collector();
        assertTrue(stream.subscribe(collector, 3), "边界上的一条不该被误判成超窗");
        assertEquals(List.of(4L, 5L, 6L), collector.seqs());
    }

    @Test
    @DisplayName("⚠️ 客户端报的序号比我们发过的还大时拒绝，那是别的会话的进度")
    void resumeFromFutureIsRejected() {
        NovaEventStream stream = new NovaEventStream(10);
        stream.publish(envelope("danmaku"));

        Collector collector = new Collector();
        assertFalse(stream.subscribe(collector, 99));
        assertEquals(List.of(), collector.seqs());
    }

    @Test
    @DisplayName("sessionId 在同一个流内不变，两个流之间不同")
    void sessionIdIdentifiesTheStream() {
        NovaEventStream one = new NovaEventStream(10);
        NovaEventStream another = new NovaEventStream(10);

        assertEquals(one.getSessionId(), one.getSessionId());
        assertNotEquals(one.getSessionId(), another.getSessionId(),
                "两条流共用 sessionId 会让客户端把重启后的第 5 条当成重启前那条 5 的重复而丢掉");
        assertFalse(one.getSessionId().isBlank());
    }

    @Test
    @DisplayName("退订后不再收到消息")
    void unsubscribeStopsDelivery() {
        NovaEventStream stream = new NovaEventStream(10);

        Collector collector = new Collector();
        stream.subscribe(collector, 0);
        stream.publish(envelope("danmaku"));
        stream.unsubscribe(collector);
        stream.publish(envelope("gift"));

        assertEquals(List.of(1L), collector.seqs());
        assertEquals(0, stream.getSubscriberCount());
    }

    @Test
    @DisplayName("多个客户端收到同一批序号")
    void allSubscribersSeeTheSameSeqs() {
        NovaEventStream stream = new NovaEventStream(10);

        Collector one = new Collector();
        Collector another = new Collector();
        stream.subscribe(one, 0);
        stream.subscribe(another, 0);

        stream.publish(envelope("danmaku"));
        stream.publish(envelope("gift"));

        assertEquals(List.of(1L, 2L), one.seqs());
        assertEquals(one.seqs(), another.seqs());
    }

    @Test
    @DisplayName("并发发布时序号不重不漏")
    void concurrentPublishKeepsSeqUnique() throws InterruptedException {
        NovaEventStream stream = new NovaEventStream(4096);

        int threads = 8;
        int perThread = 200;
        List<Thread> workers = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            Thread worker = new Thread(() -> {
                for (int i = 0; i < perThread; i++) {
                    stream.publish(envelope("danmaku"));
                }
            });
            workers.add(worker);
            worker.start();
        }
        for (Thread worker : workers) {
            worker.join();
        }

        assertEquals((long) threads * perThread, stream.snapshot().lastSeq());
    }
}
