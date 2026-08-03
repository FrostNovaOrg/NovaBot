package com.starlwr.bot.bilibili.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.starlwr.bot.bilibili.model.Up;
import com.starlwr.bot.bilibili.util.BilibiliApiUtil;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import com.starlwr.bot.core.plugin.StarBotComponent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.Optional;

/**
 * 事件信息补全服务
 * <p>
 * 直播间消息中的用户信息经常只有 uid，启用事件补全后需要额外请求接口取得昵称、头像等。
 * 弹幕流的消息量很大，若每条消息都直接请求接口会迅速触发风控，因此结果统一缓存，
 * 且失败结果也会短暂缓存，避免对同一个不存在的 uid 反复重试。
 */
@Slf4j
@StarBotComponent
public class BilibiliApiSupport {
    /**
     * 补全结果的缓存时长
     */
    private static final Duration CACHE_DURATION = Duration.ofHours(6);

    /**
     * 缓存的最大条目数
     */
    private static final int MAX_CACHE_SIZE = 10000;

    private final BilibiliApiUtil api;

    /**
     * uid 到 UP 主信息的缓存，值为空表示曾经查询失败
     */
    private final Cache<Long, Optional<Up>> cache = Caffeine.newBuilder()
            .expireAfterWrite(CACHE_DURATION)
            .maximumSize(MAX_CACHE_SIZE)
            .build();

    @Autowired
    public BilibiliApiSupport(BilibiliApiUtil api) {
        this.api = api;
    }

    /**
     * 补全昵称
     * @param uid uid
     * @param source 触发补全的直播间，仅用于日志
     * @return 昵称
     */
    public Optional<String> completeUname(Long uid, LiveStreamerInfo source) {
        return lookup(uid, source).map(Up::getUname);
    }

    /**
     * 补全直播间号
     * @param uid uid
     * @param source 触发补全的直播间，仅用于日志
     * @return 直播间号
     */
    public Optional<Long> completeRoomId(Long uid, LiveStreamerInfo source) {
        return lookup(uid, source).map(Up::getRoomId);
    }

    /**
     * 补全头像地址
     * @param uid uid
     * @param source 触发补全的直播间，仅用于日志
     * @return 头像地址
     */
    public Optional<String> completeFace(Long uid, LiveStreamerInfo source) {
        return lookup(uid, source).map(Up::getFace);
    }

    /**
     * 查询 UP 主信息，优先取缓存
     * @param uid uid
     * @param source 触发补全的直播间，仅用于日志
     * @return UP 主信息
     */
    private Optional<Up> lookup(Long uid, LiveStreamerInfo source) {
        if (uid == null || uid == 0L) {
            return Optional.empty();
        }

        return cache.get(uid, key -> {
            try {
                return Optional.of(api.getUpInfoByUid(key));
            } catch (Exception e) {
                log.debug("补全直播间 {} 中 uid {} 的信息失败: {}",
                        source == null ? "未知" : source.getRoomId(), key, e.getMessage());
                return Optional.empty();
            }
        });
    }
}
