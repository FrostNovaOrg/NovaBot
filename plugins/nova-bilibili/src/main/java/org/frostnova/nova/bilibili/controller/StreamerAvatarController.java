package org.frostnova.nova.bilibili.controller;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import org.frostnova.nova.bilibili.util.BilibiliApiUtil;
import org.frostnova.nova.core.config.ui.ConfigUiController;
import org.frostnova.nova.core.datasource.AbstractDataSource;
import org.frostnova.nova.core.model.PushUser;
import org.frostnova.nova.core.plugin.NovaComponent;
import org.frostnova.nova.core.service.StreamerDirectory;
import org.frostnova.nova.core.util.HttpUtil;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/** 只从已配置主播的服务端资料取头像。 */
@RestController
@NovaComponent
@ConditionalOnProperty(name = "novabot.core.config-ui.enabled", havingValue = "true", matchIfMissing = true)
@RequestMapping(ConfigUiController.BASE_PATH + "/api/streamers")
public class StreamerAvatarController {
    private static final int MAX_IMAGE_BYTES = 2 * 1024 * 1024;
    private static final int CACHE_SIZE = 16;
    private static final Duration SUCCESS_TTL = Duration.ofHours(1);
    private static final Duration FAILURE_TTL = Duration.ofSeconds(30);
    private static final List<MediaType> AVATAR_TYPES = List.of(
            MediaType.IMAGE_JPEG, MediaType.IMAGE_PNG, MediaType.parseMediaType("image/webp"),
            MediaType.IMAGE_GIF, MediaType.parseMediaType("image/avif"));
    private final AbstractDataSource source;
    private final StreamerDirectory directory;
    private final BilibiliApiUtil api;
    private final Cache<String, Optional<HttpUtil.ImageBytes>> avatars = Caffeine.newBuilder()
            .maximumSize(CACHE_SIZE)
            .expireAfter(new Expiry<String, Optional<HttpUtil.ImageBytes>>() {
                @Override public long expireAfterCreate(String key, Optional<HttpUtil.ImageBytes> value, long now) {
                    return (value.isPresent() ? SUCCESS_TTL : FAILURE_TTL).toNanos();
                }
                @Override public long expireAfterUpdate(String key, Optional<HttpUtil.ImageBytes> value,
                                                         long now, long duration) {
                    return expireAfterCreate(key, value, now);
                }
                @Override public long expireAfterRead(String key, Optional<HttpUtil.ImageBytes> value,
                                                       long now, long duration) {
                    return duration;
                }
            }).build();

    public StreamerAvatarController(AbstractDataSource source, StreamerDirectory directory, BilibiliApiUtil api) {
        this.source = source;
        this.directory = directory;
        this.api = api;
    }

    @GetMapping("/{platform}/{uid}/avatar")
    public ResponseEntity<byte[]> avatar(@PathVariable String platform, @PathVariable Long uid) {
        if (!"bilibili".equals(platform) || directory.entries().stream()
                .noneMatch(entry -> platform.equals(entry.platform()) && uid.equals(entry.uid()))) {
            return ResponseEntity.notFound().build();
        }
        String face = source.getUser(platform, uid).map(PushUser::getFace).orElse(null);
        if (face == null || face.isBlank()) return ResponseEntity.notFound().build();
        return avatars.get(face, url -> api.getBilibiliImageBytes(url, MAX_IMAGE_BYTES)
                        .filter(image -> AVATAR_TYPES.stream()
                                .anyMatch(type -> type.equalsTypeAndSubtype(image.contentType()))))
                .map(image -> ResponseEntity.ok().contentType(image.contentType())
                        .header("X-Content-Type-Options", "nosniff")
                        .header("Content-Security-Policy", "default-src 'none'; sandbox")
                        .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
                        .body(image.bytes()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
