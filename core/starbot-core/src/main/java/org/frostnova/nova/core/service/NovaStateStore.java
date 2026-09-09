package org.frostnova.nova.core.service;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.frostnova.nova.core.config.NovaCoreProperties;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 通用运行状态存储
 * <p>
 * 存放**程序自己产生**的状态，如群内订阅名单、被禁用的命令。它们与 {@code datasource.json}
 * 的性质截然不同：后者是使用者手写的配置，程序去改会覆盖掉人的编辑意图，也会让配置文件
 * 在人与程序之间来回打架。因此这类状态单独落在自己的文件里。
 * <p>
 * 结构为「命名空间 → 键 → 值」的两层 JSON，各功能各用一个命名空间，互不干扰。
 */
@Slf4j
@Service
public class NovaStateStore {
    private final NovaCoreProperties properties;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private JSONObject cache = new JSONObject();

    /**
     * 写锁。命令与订阅的写入来自消息线程，与自动保存的序列化并发时
     * 会让 fastjson2 在遍历中途遇到结构变化
     */
    private final Object lock = new Object();

    /**
     * 已撤功能「记住的选择」用过的命名空间
     * <p>
     * 那一版把每个会话上次选过的主播记在这个命名空间里；功能撤掉后，代码里再没有谁
     * 写它，可老使用者的状态文件里那一段不会自己消失——写它的代码走了，数据还留在原地。
     * 启动时见一个清一个，见 {@link #onApplicationReadyEvent}。
     */
    private static final String RETIRED_CHOICE_NAMESPACE = "StreamerChoice";

    @Autowired
    public NovaStateStore(NovaCoreProperties properties) {
        this.properties = properties;
    }

    /**
     * 加载状态
     */
    @Order(-10000)
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReadyEvent() {
        Path path = path();
        try {
            cache = JSONObject.parseObject(Files.readString(path));
            log.info("运行状态已从 {} 加载", path);
        } catch (NoSuchFileException e) {
            log.info("运行状态文件 {} 不存在, 建立新文件", path);
        } catch (Exception e) {
            // 状态文件损坏不该让程序起不来：丢掉订阅名单是可接受的降级，
            // 而拒绝启动会让推送整个停摆
            log.error("读取运行状态 {} 异常, 将以空状态启动", path, e);
        }

        if (cache.containsKey(RETIRED_CHOICE_NAMESPACE)) {
            log.info("清除已撤功能「记住的选择」留在运行状态里的残留段 {}", RETIRED_CHOICE_NAMESPACE);
            remove(RETIRED_CHOICE_NAMESPACE);
        }

        int interval = properties.getLive().getAutoSaveLiveDataInterval();
        scheduler.scheduleWithFixedDelay(() -> {
            Thread.currentThread().setName("auto-save-state");
            save();
        }, interval, interval, TimeUnit.SECONDS);
    }

    /**
     * 退出前保存状态
     */
    @Order(0)
    @EventListener(ContextClosedEvent.class)
    public void onContextClosedEvent() {
        scheduler.shutdownNow();
        save();
    }

    /**
     * 读取某个命名空间下的值
     * @param namespace 命名空间
     * @param key 键
     * @param reader 从 JSON 取值的方式
     * @return 值，不存在时为空
     * @param <T> 值类型
     */
    public <T> Optional<T> read(@NonNull String namespace, @NonNull String key, @NonNull Function<JSONObject, T> reader) {
        synchronized (lock) {
            return Optional.ofNullable(cache.getJSONObject(namespace))
                    .filter(data -> data.containsKey(key))
                    .map(reader);
        }
    }

    /**
     * 修改某个命名空间
     * @param namespace 命名空间
     * @param writer 修改方式，入参为该命名空间的 JSON 对象
     */
    public void write(@NonNull String namespace, @NonNull Consumer<JSONObject> writer) {
        synchronized (lock) {
            cache.putIfAbsent(namespace, new JSONObject());
            writer.accept(cache.getJSONObject(namespace));
        }
    }

    /**
     * 取得某个命名空间的快照
     * <p>
     * <b>必须是拷贝而不是本体。</b>调用方拿到之后会在锁外遍历 {@code keySet()}，
     * 而写入来自消息线程：群里有人发「开播@我」的同时开播推送正在读订阅名单，
     * 遍历中途结构发生变化就会抛 {@link java.util.ConcurrentModificationException}——
     * 偏偏是在最要紧的那一刻把推送打断。
     * <p>
     * 拷贝必须是深的：订阅名单是「会话 → 订阅者集合」的两层结构，
     * 浅拷贝只换掉外层，真正被遍历的内层还是那个活对象。
     * @param namespace 命名空间
     * @return 该命名空间的独立快照，不存在时为空对象
     */
    public JSONObject namespace(@NonNull String namespace) {
        synchronized (lock) {
            return Optional.ofNullable(cache.getJSONObject(namespace))
                    .map(NovaStateStore::deepCopy)
                    .orElseGet(JSONObject::new);
        }
    }

    /**
     * 深拷贝一个 JSON 对象
     * <p>
     * 只有 JSONObject 与 JSONArray 需要递归，其余值都是不可变的标量
     */
    private static JSONObject deepCopy(JSONObject source) {
        JSONObject copy = new JSONObject(source.size());
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            copy.put(entry.getKey(), deepCopyValue(entry.getValue()));
        }
        return copy;
    }

    private static Object deepCopyValue(Object value) {
        if (value instanceof JSONObject nested) {
            return deepCopy(nested);
        }
        if (value instanceof JSONArray array) {
            JSONArray copy = new JSONArray(array.size());
            for (Object item : array) {
                copy.add(deepCopyValue(item));
            }
            return copy;
        }
        return value;
    }

    /**
     * 删除一个命名空间并立即落盘
     * <p>
     * 给「功能撤掉后清残留」用的口：{@link #write} 只会越写越多，一段被撤功能留下的数据
     * 没有别的途径离开这个文件。段不存在时不写盘。
     * <p>
     * 删到一份不剩也要落盘——{@link #save()} 那条「空了不写」的规矩在这里恰恰是坑：
     * 它一跳过，盘上留着的还是带着死段的旧文件，下次启动会把刚删的又读回来，
     * 删除只在内存里成立过。所以这里连空对象也照写。
     * @param namespace 命名空间
     */
    public void remove(@NonNull String namespace) {
        String content;
        synchronized (lock) {
            if (cache.remove(namespace) == null) {
                return;
            }
            content = cache.toJSONString();
        }

        write(content);
    }

    /**
     * 立即落盘
     */
    public void save() {
        String content;
        synchronized (lock) {
            if (cache.isEmpty()) {
                // 从没写过东西的机器上不该凭空多出一个只有 {} 的文件
                return;
            }
            content = cache.toJSONString();
        }

        write(content);
    }

    /**
     * 把序列化好的内容写进状态文件
     * <p>
     * 序列化在调用方的锁内、写盘在锁外：磁盘慢时不应阻塞消息线程上的订阅写入
     */
    private void write(String content) {
        try {
            Files.writeString(path(), content);
        } catch (Exception e) {
            log.error("保存运行状态至 {} 异常", path(), e);
        }
    }

    /**
     * 状态文件路径，与直播数据同目录
     */
    private Path path() {
        Path liveData = Path.of(properties.getLive().getLiveDataPath());
        Path parent = liveData.getParent();
        return parent == null ? Path.of("state.json") : parent.resolve("state.json");
    }
}
