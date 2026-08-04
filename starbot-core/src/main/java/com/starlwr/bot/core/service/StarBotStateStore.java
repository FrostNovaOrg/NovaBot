package com.starlwr.bot.core.service;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.config.StarBotCoreProperties;
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
public class StarBotStateStore {
    private final StarBotCoreProperties properties;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private JSONObject cache = new JSONObject();

    /**
     * 写锁。命令与订阅的写入来自消息线程，与自动保存的序列化并发时
     * 会让 fastjson2 在遍历中途遇到结构变化
     */
    private final Object lock = new Object();

    @Autowired
    public StarBotStateStore(StarBotCoreProperties properties) {
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
     * 取得某个命名空间的只读视图
     * @param namespace 命名空间
     * @return 该命名空间的 JSON 对象，不存在时为空对象
     */
    public JSONObject namespace(@NonNull String namespace) {
        synchronized (lock) {
            return Optional.ofNullable(cache.getJSONObject(namespace)).orElseGet(JSONObject::new);
        }
    }

    /**
     * 立即落盘
     */
    public void save() {
        String content;
        synchronized (lock) {
            if (cache.isEmpty()) {
                return;
            }
            content = cache.toJSONString();
        }

        // 序列化在锁内、写盘在锁外：磁盘慢时不应阻塞消息线程上的订阅写入
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
