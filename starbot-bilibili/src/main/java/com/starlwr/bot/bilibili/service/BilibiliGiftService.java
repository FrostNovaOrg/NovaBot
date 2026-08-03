package com.starlwr.bot.bilibili.service;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.starlwr.bot.bilibili.config.StarBotBilibiliProperties;
import com.starlwr.bot.bilibili.model.Gift;
import com.starlwr.bot.bilibili.util.BilibiliApiUtil;
import com.starlwr.bot.core.plugin.StarBotComponent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 礼物配置服务
 * <p>
 * 礼物图标与大航海图标在弹幕流中被高频引用，直接每次请求接口会迅速触发风控，
 * 因此整体缓存一份配置并按配置的过期时间刷新。
 */
@Slf4j
@StarBotComponent
public class BilibiliGiftService {
    /**
     * 缓存键，礼物配置整体作为一个条目缓存
     */
    private static final String CACHE_KEY = "gift-config";

    private final BilibiliApiUtil api;

    private final LoadingCache<String, GiftConfig> cache;

    @Autowired
    public BilibiliGiftService(BilibiliApiUtil api, StarBotBilibiliProperties properties) {
        this.api = api;
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(Math.max(60, properties.getLive().getGiftCacheExpire())))
                .maximumSize(1)
                .build(key -> load());
    }

    /**
     * 根据礼物 ID 获取礼物信息
     * @param giftId 礼物 ID
     * @return 礼物信息
     */
    public Optional<Gift> getGiftInfo(Long giftId) {
        if (giftId == null) {
            return Optional.empty();
        }

        return Optional.ofNullable(config().gifts().get(giftId));
    }

    /**
     * 根据礼物 ID 获取礼物图片地址
     * @param giftId 礼物 ID
     * @return 礼物图片地址
     */
    public Optional<String> getGiftUrl(Long giftId) {
        return getGiftInfo(giftId).map(Gift::getUrl);
    }

    /**
     * 根据大航海名称获取图标地址
     * @param name 大航海名称，例如「舰长」
     * @return 图标地址
     */
    public Optional<String> getGuardIcon(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }

        return Optional.ofNullable(config().guards().get(name));
    }

    /**
     * 取出当前缓存的配置
     * @return 礼物配置
     */
    private GiftConfig config() {
        GiftConfig config = cache.get(CACHE_KEY);
        return config == null ? GiftConfig.empty() : config;
    }

    /**
     * 从接口加载礼物配置
     * @return 礼物配置，加载失败时返回空配置
     */
    private GiftConfig load() {
        try {
            List<Gift> gifts = api.getGiftInfos();
            Map<String, String> guards = api.getGuardInfos();

            log.info("已加载 {} 个礼物配置与 {} 个大航海图标", gifts.size(), guards.size());

            return new GiftConfig(
                    gifts.stream()
                            .filter(gift -> gift.getId() != null)
                            .collect(Collectors.toMap(Gift::getId, Function.identity(), (first, second) -> first)),
                    guards
            );
        } catch (Exception e) {
            log.error("加载礼物配置失败, 礼物图标等信息将暂时缺失: {}", e.getMessage());
            return GiftConfig.empty();
        }
    }

    /**
     * 礼物配置快照
     *
     * @param gifts 礼物 ID 到礼物信息的映射
     * @param guards 大航海名称到图标地址的映射
     */
    private record GiftConfig(Map<Long, Gift> gifts, Map<String, String> guards) {
        static GiftConfig empty() {
            return new GiftConfig(Map.of(), Map.of());
        }
    }
}
