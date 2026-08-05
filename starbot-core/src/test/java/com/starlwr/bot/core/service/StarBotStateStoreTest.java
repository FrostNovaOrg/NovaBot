package com.starlwr.bot.core.service;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 运行状态存储测试
 * <p>
 * 重点是 {@code namespace()} 必须返回快照。调用方在锁外遍历它，而写入来自消息线程——
 * 返回本体的话，群里有人发「开播@我」的同时开播推送正在读订阅名单，
 * 遍历中途结构变化就会抛异常，偏偏是在最要紧的那一刻打断推送。
 */
@DisplayName("运行状态存储")
class StarBotStateStoreTest {
    private static final String NAMESPACE = "Test";

    private StarBotStateStore store;

    @BeforeEach
    void setUp() {
        store = new StarBotStateStore(new StarBotCoreProperties());
    }

    @Test
    @DisplayName("取到的命名空间与本体互不影响")
    void namespaceReturnsIndependentCopy() {
        store.write(NAMESPACE, data -> data.put("a", 1));

        JSONObject snapshot = store.namespace(NAMESPACE);
        snapshot.put("b", 2);
        store.write(NAMESPACE, data -> data.put("c", 3));

        assertFalse(store.namespace(NAMESPACE).containsKey("b"), "改快照不应影响本体");
        assertFalse(snapshot.containsKey("c"), "改本体不应影响已取出的快照");
    }

    @Test
    @DisplayName("嵌套结构也必须是拷贝, 浅拷贝会漏掉真正被遍历的那一层")
    void copyIsDeep() {
        store.write(NAMESPACE, data -> data.put("group", new JSONObject().fluentPut("1", true)));

        JSONObject nested = store.namespace(NAMESPACE).getJSONObject("group");
        nested.put("2", true);

        assertEquals(1, store.namespace(NAMESPACE).getJSONObject("group").size(),
                "订阅名单是「会话 → 订阅者」两层结构，只拷外层等于没拷");
    }

    @Test
    @DisplayName("数组同样深拷贝")
    void copyIsDeepForArrays() {
        store.write(NAMESPACE, data -> data.put("list", new com.alibaba.fastjson2.JSONArray(List.of("x"))));

        store.namespace(NAMESPACE).getJSONArray("list").add("y");

        assertEquals(1, store.namespace(NAMESPACE).getJSONArray("list").size());
    }

    @Test
    @DisplayName("一边遍历一边写入不应抛异常")
    void survivesConcurrentWriteWhileIterating() throws Exception {
        for (int i = 0; i < 200; i++) {
            int key = i;
            store.write(NAMESPACE, data -> data.put("k" + key, true));
        }

        AtomicReference<Exception> failure = new AtomicReference<>();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);

        // 读侧：模拟开播推送读订阅名单
        Thread reader = new Thread(() -> {
            try {
                started.countDown();
                for (int round = 0; round < 300; round++) {
                    List<String> seen = new ArrayList<>(store.namespace(NAMESPACE).keySet());
                    assertFalse(seen.isEmpty());
                }
            } catch (Exception e) {
                failure.compareAndSet(null, e);
            } finally {
                done.countDown();
            }
        });

        // 写侧：模拟群里不断有人订阅
        Thread writer = new Thread(() -> {
            try {
                started.await();
                for (int i = 0; i < 300; i++) {
                    int key = i;
                    store.write(NAMESPACE, data -> data.put("new" + key, true));
                }
            } catch (Exception e) {
                failure.compareAndSet(null, e);
            } finally {
                done.countDown();
            }
        });

        reader.start();
        writer.start();
        assertTrue(done.await(30, TimeUnit.SECONDS), "线程未在预期时间内结束");

        assertNull(failure.get(), "并发读写不应抛异常，实际抛出: " + failure.get());
    }

    @Test
    @DisplayName("不存在的命名空间返回空对象而非 null")
    void missingNamespaceReturnsEmpty() {
        assertTrue(store.namespace("从未写过").isEmpty());
    }

    @Test
    @DisplayName("读取单个键")
    void readsSingleKey() {
        store.write(NAMESPACE, data -> data.put("k", "v"));

        assertEquals("v", store.read(NAMESPACE, "k", data -> data.getString("k")).orElse(null));
        assertTrue(store.read(NAMESPACE, "缺失", data -> data.getString("缺失")).isEmpty());
    }
}
