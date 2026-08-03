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

/**
 * 默认直播数据服务实现
 */
@Slf4j
@Service
public class DefaultLiveDataService implements LiveDataService {
    private final StarBotCoreProperties properties;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private JSONObject cache = new JSONObject();

    @Autowired
    public DefaultLiveDataService(StarBotCoreProperties properties) {
        this.properties = properties;
    }

    /**
     * 加载直播数据
     */
    @Order(-10000)
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReadyEvent() {
        if (properties.getLive().isSaveLiveData()) {
            String liveDataPath = properties.getLive().getLiveDataPath();
            log.info("开始从 {} 中加载直播数据", liveDataPath);
            try {
                cache = JSONObject.parseObject(Files.readString(Path.of(liveDataPath)));
            } catch (NoSuchFileException e) {
                log.warn("直播数据文件 {} 不存在, 建立新文件", liveDataPath);
            } catch (Exception e) {
                log.error("读取直播数据 {} 异常", liveDataPath, e);
            }
            log.info("直播数据加载完成");
            autoSave();
        }
    }

    /**
     * 保存直播数据
     */
    @Order(0)
    @EventListener(ContextClosedEvent.class)
    public void onContextClosedEvent() {
        // 先停掉自动保存，避免与此处的收尾保存同时写同一个文件
        scheduler.shutdownNow();

        if (cache.isEmpty()) {
            return;
        }

        if (properties.getLive().isSaveLiveData()) {
            String liveDataPath = properties.getLive().getLiveDataPath();
            log.info("开始保存直播数据至 {}", liveDataPath);
            try {
                Files.writeString(Path.of(liveDataPath), cache.toJSONString());
            } catch (Exception e) {
                log.error("保存直播数据至 {} 异常", liveDataPath, e);
            }
            log.info("直播数据已保存至 {}", liveDataPath);
        }
    }

    public void autoSave() {
        int interval = properties.getLive().getAutoSaveLiveDataInterval();
        Path path = Path.of(properties.getLive().getLiveDataPath());

        scheduler.scheduleWithFixedDelay(() -> {
            Thread.currentThread().setName("auto-save-data");

            try {
                Files.writeString(path, cache.toJSONString());
            } catch (Exception e) {
                log.error("自动保存直播数据异常", e);
            }

        }, interval, interval, TimeUnit.SECONDS);
    }

    // ================ 直播间状态 ================

    /**
     * 获取直播间状态
     *
     * @param platform 直播平台
     * @param uid      UID
     * @return 直播间状态，true：已开播，false：未开播
     */
    @Override
    public Optional<Boolean> getLiveStatus(@NonNull String platform, @NonNull Long uid) {
        String key = "LiveStatus:" + platform;
        return Optional.ofNullable(cache.getJSONObject(key)).map(data -> data.getBoolean(String.valueOf(uid)));
    }

    /**
     * 设置直播间状态
     *
     * @param platform 直播平台
     * @param uid      UID
     * @param status   直播间状态，true：已开播，false：未开播
     */
    @Override
    public void setLiveStatus(@NonNull String platform, @NonNull Long uid, boolean status) {
        String key = "LiveStatus:" + platform;
        cache.putIfAbsent(key, new JSONObject());
        cache.getJSONObject(key).put(String.valueOf(uid), status);
    }

    // ================ 直播开始时间 ================

    /**
     * 获取最近一场直播开始时间戳
     *
     * @param platform 直播平台
     * @param uid      UID
     * @return 最近一场直播开始时间戳
     */
    @Override
    public Optional<Long> getLiveStartTime(@NonNull String platform, @NonNull Long uid) {
        String key = "LiveStartTime:" + platform;
        return Optional.ofNullable(cache.getJSONObject(key)).map(data -> data.getLong(String.valueOf(uid)));
    }

    /**
     * 设置最近一场直播开始时间戳
     *
     * @param platform  直播平台
     * @param uid       UID
     * @param startTime 最近一场直播开始时间戳
     */
    @Override
    public void setLiveStartTime(@NonNull String platform, @NonNull Long uid, long startTime) {
        String key = "LiveStartTime:" + platform;
        cache.putIfAbsent(key, new JSONObject());
        cache.getJSONObject(key).put(String.valueOf(uid), startTime);
    }

    // ================ 直播结束时间 ================

    /**
     * 获取最近一场直播结束时间戳
     *
     * @param platform 直播平台
     * @param uid      UID
     * @return 最近一场直播结束时间戳
     */
    @Override
    public Optional<Long> getLiveEndTime(@NonNull String platform, @NonNull Long uid) {
        String key = "LiveEndTime:" + platform;
        return Optional.ofNullable(cache.getJSONObject(key)).map(data -> data.getLong(String.valueOf(uid)));
    }

    /**
     * 设置最近一场直播结束时间戳
     *
     * @param platform 直播平台
     * @param uid      UID
     * @param endTime  最近一场直播结束时间戳
     */
    @Override
    public void setLiveEndTime(@NonNull String platform, @NonNull Long uid, long endTime) {
        String key = "LiveEndTime:" + platform;
        cache.putIfAbsent(key, new JSONObject());
        cache.getJSONObject(key).put(String.valueOf(uid), endTime);
    }

    /**
     * 删除最近一场直播结束时间戳
     *
     * @param platform 直播平台
     * @param uid      UID
     */
    @Override
    public void deleteLiveEndTime(@NonNull String platform, @NonNull Long uid) {
        String key = "LiveEndTime:" + platform;
        Optional.ofNullable(cache.getJSONObject(key)).ifPresent(data -> data.remove(String.valueOf(uid)));
    }

    // ================ 其他操作 ================

    /**
     * 重置最近一场直播数据
     *
     * @param platform 直播平台
     * @param uid      UID
     */
    @Override
    public void resetLiveData(@NonNull String platform, @NonNull Long uid) {
    }
}
