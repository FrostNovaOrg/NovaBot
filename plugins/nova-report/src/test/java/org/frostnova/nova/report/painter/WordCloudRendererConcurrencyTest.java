package org.frostnova.nova.report.painter;

import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.report.util.FontUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.core.io.DefaultResourceLoader;

import java.awt.Dimension;
import java.awt.Font;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 共用的词云渲染器被两份报告同时排版
 * <p>
 * 使用者故障：两位主播差不多同时下播，或者推送和手动查报告撞在一起时，其中一份下播报告少了弹幕词云。
 * 渲染器全局只有一个，两份报告同时量词时会同时往它的字体缓存里放东西。
 * <p>
 * 两个线程的先后由门闩排定，不靠多跑几遍碰运气：两边都停在取字体里，
 * 先放一边走完、把新字体放进缓存，再放另一边。
 */
@DisplayName("词云渲染器并发量词")
class WordCloudRendererConcurrencyTest {

    private static final int SIZE = 20;

    /** 等一件「应当很快发生」的事的上限 */
    private static final long WAIT_SECONDS = 5;

    private static final Font FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 30);

    private final Map<Integer, Gate> gates = new ConcurrentHashMap<>();

    private final Map<Integer, AtomicInteger> lookups = new ConcurrentHashMap<>();

    private final FontUtil fontUtil = new FontUtil(new DefaultResourceLoader(), new NovaCoreProperties()) {
        @Override
        public Font findFontForCharacter(int codePoint) {
            lookups.computeIfAbsent(codePoint, ignored -> new AtomicInteger()).incrementAndGet();
            Gate gate = gates.get(codePoint);
            if (gate != null) {
                gate.arrived.countDown();
                try {
                    // 有上限：失败时也不把整个构建挂住
                    gate.release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return FONT;
        }
    };

    private record Gate(CountDownLatch arrived, CountDownLatch release) {
        Gate() {
            this(new CountDownLatch(1), new CountDownLatch(1));
        }
    }

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    @AfterEach
    void openGates() {
        gates.values().forEach(gate -> gate.release.countDown());
    }

    @Test
    @Timeout(30)
    @DisplayName("两个线程同时停在取字体里，一边放进新字体后另一边继续，两边都量成")
    void twoReportsMeasureAtOnce() throws Exception {
        WordCloudRenderer renderer = new WordCloudRenderer(fontUtil);
        // 先量一个字，缓存已经建好：两个线程之间只剩「往同一张表里放」这一处交错
        renderer.measure("C", SIZE);

        Gate a = new Gate();
        Gate b = new Gate();
        gates.put((int) 'A', a);
        gates.put((int) 'B', b);

        Worker first = new Worker(renderer, "A");
        Worker second = new Worker(renderer, "B");

        first.start();
        assertTrue(a.arrived.await(WAIT_SECONDS, TimeUnit.SECONDS), "锚: 第一个线程没走到取字体");
        second.start();
        assertTrue(b.arrived.await(WAIT_SECONDS, TimeUnit.SECONDS), "锚: 第二个线程没走到取字体");

        b.release.countDown();
        second.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
        assertFalse(second.isAlive(), "第二个线程放行后没走完");

        a.release.countDown();
        first.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
        assertFalse(first.isAlive(), "第一个线程放行后没走完");

        assertNull(second.failure.get(), "第二个线程量词失败: " + second.failure.get());
        assertNull(first.failure.get(), "第一个线程量词失败, 这份报告的词云会被整块跳过: " + first.failure.get());
        assertTrue(first.result.get().width > 0, "锚: 「A」量到 0 宽");
        assertTrue(second.result.get().width > 0, "锚: 「B」量到 0 宽");
    }

    @Test
    @DisplayName("单线程量词：同一个词反复量结果一致，每个字只查一次字体")
    void singleThreadMeasureIsStable() {
        WordCloudRenderer renderer = new WordCloudRenderer(fontUtil);

        Dimension once = renderer.measure("ABA", SIZE);
        Dimension again = renderer.measure("ABA", SIZE);

        assertTrue(once.width > 0 && once.height > 0, "锚: 「ABA」量到空框 " + once);
        assertEquals(once, again, "同一个词第二次量出来不一样");
        assertEquals(1, lookups.get((int) 'A').get(), "同字同字号应只查一次字体");
        assertEquals(1, lookups.get((int) 'B').get(), "同字同字号应只查一次字体");

        renderer.measure("A", SIZE + 1);
        assertEquals(2, lookups.get((int) 'A').get(), "换了字号应重新取字体");
    }

    private static final class Worker extends Thread {
        private final WordCloudRenderer renderer;

        private final String text;

        final AtomicReference<Throwable> failure = new AtomicReference<>();

        final AtomicReference<Dimension> result = new AtomicReference<>();

        Worker(WordCloudRenderer renderer, String text) {
            super("word-cloud-" + text);
            this.renderer = renderer;
            this.text = text;
            setDaemon(true);
        }

        @Override
        public void run() {
            try {
                result.set(renderer.measure(text, SIZE));
            } catch (Throwable e) {
                failure.set(e);
            }
        }
    }
}
