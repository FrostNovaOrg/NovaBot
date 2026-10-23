package org.frostnova.nova.report.painter;

import com.alibaba.fastjson2.JSON;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.frostnova.nova.bilibili.model.BilibiliLiveReportOptions;
import org.frostnova.nova.core.plugin.NovaComponent;

import java.time.Duration;
import java.util.Optional;

/** 最近画过的成品图；场次、解析后的版式、金额与实际配色全部相同才复用。 */
@NovaComponent
public class LiveReportRenderCache {
    private record Key(String platform, Long uid, long start, String options, ReportPalette palette) { }
    private final Cache<Key, String> images = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofHours(1))
            .maximumWeight(32 * 1024 * 1024)
            .weigher((Key key, String image) -> image.length() * 2)
            .build();

    public Optional<String> get(String platform, Long uid, long start,
            BilibiliLiveReportOptions options, ReportPalette palette) {
        return Optional.ofNullable(images.getIfPresent(new Key(platform, uid, start, JSON.toJSONString(options), palette)));
    }

    public void put(String platform, Long uid, long start, BilibiliLiveReportOptions options,
            ReportPalette palette, String image) {
        if (start > 0 && image != null && !image.isBlank())
            images.put(new Key(platform, uid, start, JSON.toJSONString(options), palette), image);
    }
}
