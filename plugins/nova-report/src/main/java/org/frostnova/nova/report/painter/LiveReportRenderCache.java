package org.frostnova.nova.report.painter;

import com.alibaba.fastjson2.JSON;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.frostnova.nova.bilibili.model.BilibiliLiveReportOptions;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.frostnova.nova.core.config.NovaCoreProperties;
import org.frostnova.nova.core.protocol.StandbyPhases;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.util.Optional;

/** 最近画过的成品图；场次、解析后的版式、金额与实际配色全部相同才复用。 */
@NovaComponent
public class LiveReportRenderCache implements SmartLifecycle {
    record Key(String platform, Long uid, long start, String options, ReportPalette palette) { }
    private final LiveReportRenderDiskCache disk;
    private volatile boolean running;
    private final Cache<Key, String> images = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofHours(1))
            .maximumWeight(32 * 1024 * 1024)
            .weigher((Key key, String image) -> image.length() * 2)
            .build();

    /** 不落盘，供未装配直播数据配置的调用者使用。 */
    public LiveReportRenderCache() {
        disk = new LiveReportRenderDiskCache(null);
    }

    @Autowired
    public LiveReportRenderCache(NovaCoreProperties properties) {
        disk = new LiveReportRenderDiskCache(LiveReportRenderDiskCache.directoryFor(properties.getLive().getLiveDataPath()));
    }

    public Optional<String> get(String platform, Long uid, long start,
            BilibiliLiveReportOptions options, ReportPalette palette) {
        Key key = new Key(platform, uid, start, JSON.toJSONString(options), palette);
        String image = images.getIfPresent(key);
        if (image != null) return Optional.of(image);
        return disk.read(key).map(original -> {
            images.put(key, original);
            return original;
        });
    }

    public void put(String platform, Long uid, long start, BilibiliLiveReportOptions options,
            ReportPalette palette, String image) {
        if (start > 0 && image != null && !image.isBlank())
            images.put(new Key(platform, uid, start, JSON.toJSONString(options), palette), image);
    }

    /** 只有正常推送的成品图落盘；从明细重画仍只放内存。 */
    public void putOriginal(String platform, Long uid, long start, BilibiliLiveReportOptions options,
            ReportPalette palette, String image) {
        put(platform, uid, start, options, palette, image);
        if (start > 0 && image != null && !image.isBlank())
            disk.store(new Key(platform, uid, start, JSON.toJSONString(options), palette), image);
    }

    @Override public void start() { disk.sweep(); running = true; }
    @Override public void stop() { running = false; }
    @Override public boolean isRunning() { return running; }
    @Override public int getPhase() { return StandbyPhases.AFTER_GATE; }

    @Scheduled(fixedDelay = 86_400_000L, initialDelay = 86_400_000L)
    public void sweepDaily() { disk.sweep(); }
}
