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
 * QQ 对 @全体成员 有每日上限，用超之后**平台会静默忽略**——消息照发，
 * 但那一下 @ 不会生效，而配置的人往往过很久才发现「怎么没人被 @ 到」。
 * 因此在自己这一侧先记账：超额时主动把 @全体成员 摘掉，
 * 而不是把额度花在一次注定无效的调用上。
 * <p>
 * <b>配额有两个维度，缺一不可</b>（2026-08-05 用 OneBot 的
 * {@code get_group_at_all_remain} 实测得出）：
 * <ul>
 *     <li><b>按账号</b>：每天 10 次，<b>所有群共享同一份额度</b></li>
 *     <li><b>按群</b>：每天 20 次，各群独立</li>
 * </ul>
 * 起初只按群计数、上限 10，那是错的：推 3 个群时会放行 30 次，
 * 而平台在第 10 次就截断了——守卫等于没守住。**真正卡住的是账号维度。**
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
     * 计数表，键为「账号维度的 platform」或「会话维度的 platform:num」
     */
    private final Map<String, DailyCount> counts = new ConcurrentHashMap<>();

    @Autowired
    public AtAllQuotaService(StarBotCoreProperties properties) {
        this.properties = properties;
    }

    /**
     * 消耗一次配额
     * <p>
     * 两个维度都有余额才放行。<b>先查后记</b>：任一维度已满就不再增加另一维度的计数，
     * 否则被拒的那些次数会把另一维度的额度一起吃掉。
     * @param platform 推送平台
     * @param num 会话号
     * @return 是否还有额度
     */
    public synchronized boolean tryConsume(@NonNull String platform, @NonNull Long num) {
        int botLimit = properties.getPush().getAtAllDailyLimit();
        int sessionLimit = properties.getPush().getAtAllSessionDailyLimit();
        LocalDate today = LocalDate.now(ZONE);

        String botKey = platform;
        String sessionKey = platform + ":" + num;

        if (botLimit > 0 && used(botKey, today) >= botLimit) {
            log.warn("推送平台 {} 今日的 @全体成员 已用满 {} 次（该额度由全部会话共享），本条将退化为普通消息。" +
                    "如需调整请改 starbot.core.push.at-all-daily-limit", platform, botLimit);
            return false;
        }
        if (sessionLimit > 0 && used(sessionKey, today) >= sessionLimit) {
            log.warn("会话 {} 今日的 @全体成员 已用满 {} 次，本条将退化为普通消息。" +
                    "如需调整请改 starbot.core.push.at-all-session-daily-limit", num, sessionLimit);
            return false;
        }

        increment(botKey, today);
        increment(sessionKey, today);
        return true;
    }

    /**
     * 某个会话今日已用的次数，供排障查看
     */
    public int used(@NonNull String platform, @NonNull Long num) {
        return used(platform + ":" + num, LocalDate.now(ZONE));
    }

    /**
     * 某个推送平台今日已用的次数，即账号维度的用量
     */
    public int usedByBot(@NonNull String platform) {
        return used(platform, LocalDate.now(ZONE));
    }

    private int used(String key, LocalDate today) {
        DailyCount current = counts.get(key);
        return current == null || !current.date().equals(today) ? 0 : current.used();
    }

    private void increment(String key, LocalDate today) {
        counts.compute(key, (ignored, current) ->
                current == null || !current.date().equals(today)
                        ? new DailyCount(today, 1)
                        : new DailyCount(today, current.used() + 1));
    }

    /**
     * 某一天的计数
     */
    private record DailyCount(LocalDate date, int used) {
    }
}
