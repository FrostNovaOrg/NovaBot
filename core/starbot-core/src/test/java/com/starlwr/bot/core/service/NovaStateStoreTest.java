package com.starlwr.bot.core.service;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.config.NovaCoreProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
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
class NovaStateStoreTest {
    private static final String NAMESPACE = "Test";

    private NovaStateStore store;

    @BeforeEach
    void setUp() {
        store = new NovaStateStore(new NovaCoreProperties());
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

    // 死段清理的测试里，段名「StreamerChoice」与陪衬「CorpusMarker」都逐字写死，不从被测取——
    // 尺的名字若来自被测，被测哪天把这个名字丢了，尺就跟着量不到它，红也红不出来
    private static final String RETIRED = "StreamerChoice";
    private static final String MARKER = "CorpusMarker";

    private NovaStateStore storeAt(@TempDir Path dir) {
        NovaCoreProperties properties = new NovaCoreProperties();
        properties.getLive().setLiveDataPath(dir.resolve("data.json").toString());
        return new NovaStateStore(properties);
    }

    @Test
    @DisplayName("启动时清掉已撤功能的死段, 其余命名空间原样保留")
    void startupDropsRetiredSegmentKeepsTheRest(@TempDir Path dir) throws Exception {
        Path state = dir.resolve("state.json");
        Files.writeString(state, "{\"" + RETIRED + "\":{\"10001:20001\":30001},\"" + MARKER + "\":{\"seen\":true}}");

        NovaStateStore opened = storeAt(dir);
        opened.onApplicationReadyEvent();
        opened.onContextClosedEvent();

        String saved = Files.readString(state);
        assertFalse(saved.contains(RETIRED), "已撤功能的死段还在盘上: " + saved);
        assertTrue(saved.contains(MARKER), "清死段时误伤了别的命名空间: " + saved);

        // 重读一遍：不是「这回没看见」，而是下一次启动读到的确实已经没有它
        NovaStateStore reopened = storeAt(dir);
        reopened.onApplicationReadyEvent();
        reopened.onContextClosedEvent();
        assertTrue(reopened.namespace(MARKER).getBoolean("seen"), "陪衬段经一轮启停后内容变了");
        assertFalse(Files.readString(state).contains(RETIRED), "死段在第二次启动后又回来了: " + Files.readString(state));
    }

    @Test
    @DisplayName("没有死段时启动不惊动别的命名空间")
    void startupWithoutRetiredSegmentLeavesOthersAlone(@TempDir Path dir) throws Exception {
        Path state = dir.resolve("state.json");
        Files.writeString(state, "{\"" + MARKER + "\":{\"seen\":true}}");

        NovaStateStore opened = storeAt(dir);
        opened.onApplicationReadyEvent();
        opened.onContextClosedEvent();

        String saved = Files.readString(state);
        assertTrue(saved.contains(MARKER), "无死段可清时陪衬段被弄丢了: " + saved);
        assertFalse(saved.contains(RETIRED), "无中生有: " + saved);
    }

    @Test
    @DisplayName("死段是文件里唯一内容时也真的落了盘")
    void removingTheOnlySegmentStillPersists(@TempDir Path dir) throws Exception {
        Path state = dir.resolve("state.json");
        Files.writeString(state, "{\"" + RETIRED + "\":{\"10001:20001\":30001}}");

        NovaStateStore opened = storeAt(dir);
        opened.onApplicationReadyEvent();
        opened.onContextClosedEvent();

        String saved = Files.readString(state);
        assertFalse(saved.contains(RETIRED), "删空场景下死段留在了盘上: " + saved);
        assertTrue(JSONObject.parseObject(saved).isEmpty(), "删空后文件应是合法的空对象, 实际: " + saved);
    }

    @Test
    @DisplayName("remove 删段后不等停机就已经在盘上")
    void removePersistsToDiskImmediately(@TempDir Path dir) throws Exception {
        NovaStateStore opened = storeAt(dir);
        opened.onApplicationReadyEvent();

        opened.write(RETIRED, data -> data.put("10001:20001", 30001));
        opened.write(MARKER, data -> data.put("seen", true));
        opened.remove(RETIRED);

        String saved = Files.readString(dir.resolve("state.json"));
        assertFalse(saved.contains(RETIRED), "remove 后死段仍在盘上: " + saved);
        assertTrue(saved.contains(MARKER), "remove 误删了别的命名空间: " + saved);

        opened.onContextClosedEvent();
    }

    @Test
    @DisplayName("remove 不存在的段是安全的空操作")
    void removeMissingSegmentIsNoOp(@TempDir Path dir) throws Exception {
        NovaStateStore opened = storeAt(dir);
        opened.onApplicationReadyEvent();

        opened.write(MARKER, data -> data.put("seen", true));
        opened.save();
        opened.remove("从未写过");

        assertTrue(Files.readString(dir.resolve("state.json")).contains(MARKER), "空操作弄丢了内容");

        opened.onContextClosedEvent();
    }
}
