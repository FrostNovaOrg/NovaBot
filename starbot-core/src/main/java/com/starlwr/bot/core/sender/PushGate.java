package com.starlwr.bot.core.sender;

import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.util.StringUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.time.format.DateTimeParseException;

/**
 * 推送闸门
 * <p>
 * 判断当前是否允许推送，集中处理全局开关与静音时段。
 * 静音期间的消息一律丢弃而非攒着——攒下来会在静音结束的瞬间集中轰炸，
 * 比不推更糟；何况开播下播这类通知本就有时效性，过期补发没有意义。
 */
@Slf4j
@Component
public class PushGate {
    private final StarBotCoreProperties properties;

    @Autowired
    public PushGate(StarBotCoreProperties properties) {
        this.properties = properties;
    }

    /**
     * 当前是否允许推送
     * @return 允许推送时返回 true
     */
    public boolean allowed() {
        return allowedAt(LocalTime.now());
    }

    /**
     * 判断指定时刻是否允许推送
     * @param now 时刻
     * @return 允许推送时返回 true
     */
    boolean allowedAt(LocalTime now) {
        StarBotCoreProperties.Push push = properties.getPush();

        if (!push.isEnabled()) {
            return false;
        }

        return !inQuietHours(now, push.getQuietStart(), push.getQuietEnd());
    }

    /**
     * 当前被拦截的原因，供日志说明
     * @return 拦截原因
     */
    public String blockReason() {
        return properties.getPush().isEnabled() ? "处于静音时段" : "全局推送开关已关闭";
    }

    /**
     * 判断某一时刻是否落在静音时段内
     * <p>
     * 允许跨零点：开始 23:00、结束 08:00 表示当晚 23 点至次日 8 点，
     * 此时判定条件由「区间内」变为「区间外取反」。
     */
    private boolean inQuietHours(LocalTime now, String start, String end) {
        if (StringUtil.isBlank(start) || StringUtil.isBlank(end)) {
            return false;
        }

        LocalTime from;
        LocalTime to;
        try {
            from = LocalTime.parse(start.trim());
            to = LocalTime.parse(end.trim());
        } catch (DateTimeParseException e) {
            log.warn("静音时段配置格式有误（应为 HH:mm）, 已忽略: {} ~ {}", start, end);
            return false;
        }

        if (from.equals(to)) {
            // 起止相同视为未设置，而非全天静音——后者几乎不会是使用者的本意
            return false;
        }

        return from.isBefore(to)
                ? !now.isBefore(from) && now.isBefore(to)
                : !now.isBefore(from) || now.isBefore(to);
    }
}
