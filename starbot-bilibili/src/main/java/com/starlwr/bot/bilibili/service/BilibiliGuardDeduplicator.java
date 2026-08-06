package com.starlwr.bot.bilibili.service;

import com.starlwr.bot.core.plugin.StarBotComponent;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 大航海消息去重
 * <p>
 * 同一次开通可能由两条消息各播报一次：老的 {@code USER_TOAST_MSG} 与现行的 {@code GUARD_BUY}。
 * 两条都收是有意的——观测到的 3 次开通都只来了 {@code GUARD_BUY}，但 3 例还不足以断定
 * {@code USER_TOAST_MSG} 已经废弃（也可能只在续费、特定档位或特定房间配置下才发），
 * 只认一条就有漏收的风险。
 * <p>
 * <b>但两条都收就必须去重。</b>大航海是本项目金额最大的一类事件，算两遍会让营收凭空翻倍，
 * 而且这个错误在数据上完全说得通——没有任何地方会报错。
 * <p>
 * 判重键是「谁 + 什么等级 + 买了几个月」。不带时间戳：两条消息的时间字段来源不同
 * （一个是 {@code send_time}，一个可能要从 {@code start_time} 推），
 * 拿来做键会因为差几秒而判成两次。改为在一个时间窗内认作同一次。
 */
@Slf4j
@StarBotComponent
public class BilibiliGuardDeduplicator {
    /**
     * 判重时间窗
     * <p>
     * 两条播报同一次开通的消息几乎同时到达。取 30 秒是留足余量，
     * 同时短到不会把「同一个人连着买两次同样的」误判成重复——
     * 那种情况本就罕见，而且真发生时少记一次远好过把一次记成两次。
     */
    private static final Duration WINDOW = Duration.ofSeconds(30);

    /**
     * 记录数上限，防止长时间运行后无限增长
     */
    private static final int MAX_ENTRIES = 512;

    private final Map<String, Instant> seen = new ConcurrentHashMap<>();

    /**
     * 判断这条大航海消息是否是本次开通的第一条
     * @param uid 开通者 UID
     * @param guardLevel 大航海等级
     * @param count 开通数量
     * @param at 消息时刻
     * @return 首次为 true；窗口内的重复播报为 false
     */
    public boolean firstReport(Long uid, Integer guardLevel, Integer count, Instant at) {
        if (uid == null || guardLevel == null) {
            // 认不出是谁买的就没法判重，放行——漏记比重复计费好，也比整条丢掉好
            return true;
        }

        String key = uid + ":" + guardLevel + ":" + (count == null ? 1 : count);
        Instant previous = seen.get(key);
        if (previous != null && Duration.between(previous, at).abs().compareTo(WINDOW) <= 0) {
            log.debug("大航海消息重复播报, 已忽略: uid={} level={} count={}", uid, guardLevel, count);
            return false;
        }

        if (seen.size() >= MAX_ENTRIES) {
            evictExpired(at);
        }
        seen.put(key, at);
        return true;
    }

    /**
     * 清掉已经超出时间窗的记录
     */
    private void evictExpired(Instant now) {
        seen.entrySet().removeIf(entry -> Duration.between(entry.getValue(), now).abs().compareTo(WINDOW) > 0);
    }
}
