package org.frostnova.nova.core.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
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

    /**
     * 失败的注入：临时文件的位置先被占成一个目录。
     * 直写目标文件的实现碰不到这个目录，保存仍会改掉原文件；
     * 先写临时文件再换上的实现写不进去，原文件应原样留下。
     */
    @Test
    @DisplayName("保存运行状态写到一半失败时，原来的订阅名单还在")
    void failedSaveLeavesPreviousSubscriptions(@TempDir Path dir) throws Exception {
        Path state = dir.resolve("state.json");
        String original = "{\"AtSubscriptions\":{\"qq:10001:20001:live\":{\"30001\":1}}}";
        Files.writeString(state, original);
        Files.createDirectory(dir.resolve("state.json.tmp"));

        NovaStateStore opened = storeAt(dir);
        opened.onApplicationReadyEvent();
        try {
            opened.write("AtSubscriptions", data -> data.put("qq:10001:20002:live",
                    new JSONObject().fluentPut("30002", 1)));
            opened.save();

            assertEquals(original, Files.readString(state), "写到一半失败时盘上的状态文件被改掉了");

            NovaStateStore restarted = storeAt(dir);
            restarted.onApplicationReadyEvent();
            try {
                JSONObject kept = restarted.namespace("AtSubscriptions").getJSONObject("qq:10001:20001:live");
                assertNotNull(kept, "重启后原来的订阅名单没了");
                assertEquals(1, kept.getIntValue("30001"));
                assertFalse(restarted.namespace("AtSubscriptions").containsKey("qq:10001:20002:live"),
                        "没写成功的新订阅出现在了重启后的名单里");
            } finally {
                restarted.onContextClosedEvent();
            }
        } finally {
            opened.onContextClosedEvent();
        }
    }

    @Test
    @DisplayName("状态文件已是半截时仍能启动，坏件改名留底且不被之后的保存盖掉")
    void corruptStateIsParkedAndNotOverwritten(@TempDir Path dir) throws Exception {
        Path state = dir.resolve("state.json");
        byte[] broken = "{\"AtSubscriptions\":{\"qq:1:2:live\":".getBytes(StandardCharsets.UTF_8);
        Files.write(state, broken);

        NovaStateStore opened = storeAt(dir);
        opened.onApplicationReadyEvent();
        try {
            assertTrue(opened.namespace("AtSubscriptions").isEmpty(),
                    "半截状态文件不该让程序起不来，也不该读出半份名单");

            Path parked = parkedCopy(dir, "state.json.bad-");
            assertArrayEquals(broken, Files.readAllBytes(parked), "留底的坏件字节变了");

            opened.write("AtSubscriptions", data -> data.put("qq:9:8:live",
                    new JSONObject().fluentPut("30001", 1)));
            opened.save();

            assertArrayEquals(broken, Files.readAllBytes(parked), "之后的保存把留底的坏件盖掉了");
            assertFalse(Arrays.equals(broken, Files.readAllBytes(state)), "坏内容还占着状态文件的位置");
            assertNotNull(JSONObject.parseObject(Files.readString(state)), "补上的保存不是一份能读的状态");
        } finally {
            opened.onContextClosedEvent();
        }
    }

    /**
     * 注入：状态文件停在多字节字「名」的中间。
     * 字节读到了，解不成文本。这不是权限问题，应按解析不了改名留底，随后的保存照常写盘。
     */
    @Test
    @DisplayName("状态文件停在多字节字的中间时，按解析不了改名留底，之后的保存照常写盘")
    void truncatedMultibyteStateIsParkedAndWritten(@TempDir Path dir) throws Exception {
        Path state = dir.resolve("state.json");
        byte[] broken = cutInsideCharacter("{\"AtSubscriptions\":{\"主播名\":{\"30001\":1}}}");
        Files.write(state, broken);

        NovaStateStore opened = storeAt(dir);
        opened.onApplicationReadyEvent();
        try {
            assertTrue(opened.namespace("AtSubscriptions").isEmpty(),
                    "半截多字节状态文件不该读出半份名单");

            Path parked = parkedCopy(dir, "state.json.bad-");
            assertArrayEquals(broken, Files.readAllBytes(parked), "留底的半截多字节坏件字节变了");

            opened.write("AtSubscriptions", data -> data.put("qq:9:8:live",
                    new JSONObject().fluentPut("30001", 1)));
            opened.save();

            assertArrayEquals(broken, Files.readAllBytes(parked), "之后的保存把留底的坏件盖掉了");
            assertFalse(Arrays.equals(broken, Files.readAllBytes(state)), "坏内容还占着状态文件的位置");
            JSONObject saved = JSONObject.parseObject(Files.readString(state));
            assertNotNull(saved, "半截多字节被当成读不了，这一轮没有写盘");
            assertNotNull(saved.getJSONObject("AtSubscriptions").getJSONObject("qq:9:8:live"),
                    "半截多字节被当成读不了，这一轮没有写盘");
        } finally {
            opened.onContextClosedEvent();
        }
    }

    /**
     * 注入：把状态文件的读权限拿掉，跑完再放回。
     * 权限不足时文件本身是好的；改名挪走之后，权限修好了也读不回原来的订阅名单。
     */
    @Test
    @DisplayName("状态文件一时读不了时，文件留在原名、字节不动，这一轮自动保存和收尾保存都不写盘，修好后重启订阅名单还在")
    void unreadableStateFileIsLeftUntouched(@TempDir Path dir) throws Exception {
        Path state = dir.resolve("state.json");
        byte[] original = "{\"AtSubscriptions\":{\"qq:10001:20001:live\":{\"30001\":1}}}"
                .getBytes(StandardCharsets.UTF_8);
        Files.write(state, original);
        denyRead(state);

        Logger logger = (Logger) LoggerFactory.getLogger(NovaStateStore.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            NovaStateStore opened = storeAt(dir);
            try {
                opened.onApplicationReadyEvent();
                try {
                    assertTrue(opened.namespace("AtSubscriptions").isEmpty(),
                            "读不了时仍应以内空状态跑");
                    opened.write("AtSubscriptions", data -> data.put("qq:9:8:live",
                            new JSONObject().fluentPut("30009", 1)));
                    opened.save();
                } finally {
                    opened.onContextClosedEvent();
                }
            } finally {
                if (Files.exists(state)) {
                    allowOwnerReadWrite(state);
                }
            }

            assertFalse(anyNameStartsWith(dir, "state.json.bad-"), "读不了时把状态文件改名挪走了");
            assertArrayEquals(original, Files.readAllBytes(state), "读不了时自动保存或收尾保存把状态文件盖掉了");

            NovaStateStore restarted = storeAt(dir);
            restarted.onApplicationReadyEvent();
            try {
                JSONObject kept = restarted.namespace("AtSubscriptions").getJSONObject("qq:10001:20001:live");
                assertNotNull(kept, "修好后重启，原来的订阅名单没了");
                assertEquals(1, kept.getIntValue("30001"));
                assertFalse(restarted.namespace("AtSubscriptions").containsKey("qq:9:8:live"),
                        "这一轮没落盘的新订阅出现在了重启后的名单里");
            } finally {
                restarted.onContextClosedEvent();
            }

            assertTrue(appender.list.stream().anyMatch(event ->
                            event.getLevel() == Level.ERROR
                                    && event.getFormattedMessage().contains("常见原因是文件权限")),
                    "启动时没说明常见原因是文件权限");
            long skipNotes = appender.list.stream()
                    .filter(event -> event.getLevel() == Level.WARN)
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(message -> message.contains("这次运行里的改动都不会存盘，修好后重启才恢复"))
                    .count();
            assertEquals(1L, skipNotes, "挂起后第一次跳过保存应记一条，第二次不再记");
        } finally {
            logger.detachAppender(appender);
        }
    }

    /**
     * 注入：进入停机前把当前线程的中断标记置上。
     * 等待自动保存停下时一发现标记就离开等待；若先把标记恢复再去写盘，
     * 写盘通道会当场关掉，收尾保存失败，盘上仍是旧快照。
     * 状态文件与直播数据两条停机路径都走一遍。
     */
    @Test
    @DisplayName("停机等待时线程被中断，收尾保存照样写成，文件里是最新快照")
    void interruptedShutdownStillSavesLatestSnapshot(@TempDir Path dir) throws Exception {
        try {
            Path state = dir.resolve("state.json");
            Files.writeString(state, "{\"AtSubscriptions\":{\"qq:10001:20001:live\":{\"30001\":1}}}");
            NovaStateStore opened = storeAt(dir);
            opened.onApplicationReadyEvent();
            opened.write("AtSubscriptions", data -> data.put("qq:10001:20002:live",
                    new JSONObject().fluentPut("30002", 1)));
            Thread.currentThread().interrupt();
            opened.onContextClosedEvent();
            assertTrue(Thread.interrupted(), "状态收尾之后中断标记应还在");

            NovaStateStore restarted = storeAt(dir);
            restarted.onApplicationReadyEvent();
            try {
                JSONObject subs = restarted.namespace("AtSubscriptions");
                JSONObject kept = subs.getJSONObject("qq:10001:20001:live");
                assertNotNull(kept, "收尾之后原来的订阅名单没了");
                assertEquals(1, kept.getIntValue("30001"));
                JSONObject added = subs.getJSONObject("qq:10001:20002:live");
                assertNotNull(added, "停机等待被中断时状态文件的收尾保存没写成");
                assertEquals(1, added.getIntValue("30002"));
            } finally {
                restarted.onContextClosedEvent();
            }

            Path data = dir.resolve("live-data.json");
            Files.writeString(data, "{\"LiveMetric:bilibili\":{\"10001\":{\"danmu_count\":455}}}");
            NovaCoreProperties properties = new NovaCoreProperties();
            properties.getLive().setLiveDataPath(data.toString());
            DefaultLiveDataService live = new DefaultLiveDataService(properties);
            live.onApplicationReadyEvent();
            live.incrementLiveMetric("bilibili", 10001L, "danmu_count", 1);
            Thread.currentThread().interrupt();
            live.onContextClosedEvent();
            assertTrue(Thread.interrupted(), "直播数据收尾之后中断标记应还在");

            DefaultLiveDataService reread = new DefaultLiveDataService(properties);
            reread.onApplicationReadyEvent();
            try {
                assertEquals(456.0, reread.getLiveMetric("bilibili", 10001L, "danmu_count"),
                        "停机等待被中断时直播数据的收尾保存没写成");
                assertEquals(Boolean.TRUE, reread.wasCleanShutdown().orElse(null),
                        "盘上不是停机收尾的那一份快照");
            } finally {
                reread.onContextClosedEvent();
            }
        } finally {
            Thread.interrupted();
        }
    }

    /** 在「名」这个多字节字的中间截断，留下解不成字的半截。 */
    private static byte[] cutInsideCharacter(String text) {
        int at = text.indexOf('名');
        if (at < 0) {
            throw new IllegalStateException("样例里没有用来截断的字");
        }
        byte[] head = text.substring(0, at).getBytes(StandardCharsets.UTF_8);
        byte[] character = "名".getBytes(StandardCharsets.UTF_8);
        byte[] broken = Arrays.copyOf(head, head.length + character.length - 1);
        System.arraycopy(character, 0, broken, head.length, character.length - 1);
        return broken;
    }

    private static void denyRead(Path path) throws Exception {
        Files.setPosixFilePermissions(path, Set.of());
    }

    private static void allowOwnerReadWrite(Path path) throws Exception {
        Files.setPosixFilePermissions(path, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
    }

    private static boolean anyNameStartsWith(Path dir, String prefix) throws Exception {
        try (Stream<Path> children = Files.list(dir)) {
            return children.anyMatch(path -> path.getFileName().toString().startsWith(prefix));
        }
    }

    private static Path parkedCopy(Path dir, String prefix) throws Exception {
        try (Stream<Path> children = Files.list(dir)) {
            return children.filter(path -> path.getFileName().toString().startsWith(prefix))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("坏件没有改名留底"));
        }
    }
}
