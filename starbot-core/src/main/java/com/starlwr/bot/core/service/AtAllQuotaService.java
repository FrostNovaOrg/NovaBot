package com.starlwr.bot.core.service;

import com.starlwr.bot.core.config.StarBotCoreProperties;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @全体成员 的每日配额
 * <p>
 * QQ 对 @全体成员 有每日次数上限，用超之后**平台会静默忽略**——消息照发，
 * 但那一下 @ 不会生效，而配置的人往往过很久才发现「怎么没人被 @ 到」。
 * <p>
 * 因此在自己这一侧先记账：超额时主动把 @全体成员 摘掉、退化为普通消息并留下日志，
 * 而不是把额度花在一次注定无效的调用上。配额按「推送平台 + 会话」分别计算，
 * 因为限制本就是按群算的。
 * <p>
 * 计数只在内存里：重启后重新计数会让当天的额度略微算多，但这是**保守方向**的误差——
 * 把它持久化则要为一个次日即失效的计数引入落盘与清理逻辑，不划算。
 */
@Slf4j
@Service
public class AtAllQuotaService {
    /**
     * 时区固定为东八区，与 QQ 的自然日一致；跟随服务器时区会让海外机器在错误的时刻重置
     */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final StarBotCoreProperties properties;

    /**
     * 「平台:会话」到当日计数的映射
     */
    private final Map<String, DailyCount> counts = new ConcurrentHashMap<>();

    @Autowired
    public AtAllQuotaService(StarBotCoreProperties properties) {
        this.properties = properties;
    }

    /**
     * 消耗一次配额
     * <p>
     * 返回 false 表示今日额度已用尽，调用方应把 @全体成员 摘掉再发。
     * @param platform 推送平台
     * @param num 会话号
     * @return 是否还有额度
     */
    public boolean tryConsume(@NonNull String platform, @NonNull Long num) {
        int limit = properties.getPush().getAtAllDailyLimit();
        if (limit <= 0) {
            // 填 0 或负数表示不限制，把判断权交回给平台自己
            return true;
        }

        String key = platform + ":" + num;
        LocalDate today = LocalDate.now(ZONE);

        DailyCount updated = counts.compute(key, (ignored, current) ->
                current == null || !current.date().equals(today)
                        ? new DailyCount(today, 1)
                        : new DailyCount(today, current.used() + 1));

        if (updated.used() > limit) {
            log.warn("会话 {} 今日的 @全体成员 已用满 {} 次, 本条将退化为普通消息。" +
                    "如需调整上限请改 starbot.core.push.at-all-daily-limit", num, limit);
            return false;
        }

        if (updated.used() == limit) {
            log.info("会话 {} 今日的 @全体成员 已用到第 {} 次, 达到上限", num, limit);
        }
        return true;
    }

    /**
     * 某个会话今日已用的次数，供界面与排障查看
     */
    public int used(@NonNull String platform, @NonNull Long num) {
        DailyCount current = counts.get(platform + ":" + num);
        return current == null || !current.date().equals(LocalDate.now(ZONE)) ? 0 : current.used();
    }

    /**
     * 某一天的计数
     */
    private record DailyCount(LocalDate date, int used) {
    }
}
