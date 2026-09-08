package com.starlwr.bot.core.sender;

import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.timeline.TimelineEventType;
import com.starlwr.bot.core.lang.StringUtil;
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
        return blockedBy().getDescription();
    }

    /**
     * 当前被哪一道拦下
     * <p>
     * 与 {@link #blockReason()} 同源，只是一个给人看、一个给程序判。
     * 时间线要把「静音丢弃」和「暂停丢弃」分成两类事件，而认这两类的判据<b>不能是那句中文</b>——
     * 文案是随时会改的东西，改一个字判据就静默失效，失效方向还是「从此全归成同一类」。
     * @return 拦截原因
     */
    public Block blockedBy() {
        return properties.getPush().isEnabled() ? Block.QUIET_HOURS : Block.DISABLED;
    }

    /**
     * 此刻是否落在静音时段内
     * <p>
     * 控制台首页要在顶部横条上写「静音中 hh:mm – hh:mm」，而判断「在不在静音时段」
     * 只该有这一份实现：起止时刻允许跨零点、起止相同视为未设置、格式不对时忽略，
     * 这几条规则在界面那一侧再写一遍的话，两边迟早会分叉——而分叉的表现是
     * 屏幕上写着「静音中」，推送却照发（或反过来），两种都会让人以为开关坏了。
     * @return 处于静音时段时返回 true
     */
    public boolean inQuietHours() {
        StarBotCoreProperties.Push push = properties.getPush();
        return inQuietHours(LocalTime.now(), push.getQuietStart(), push.getQuietEnd());
    }

    /**
     * 拦截原因
     */
    public enum Block {
        /**
         * 全局推送开关已关闭
         */
        DISABLED("全局推送开关已关闭"),

        /**
         * 处于静音时段
         */
        QUIET_HOURS("处于静音时段");

        private final String description;

        Block(String description) {
            this.description = description;
        }

        /**
         * 给人看的说明
         * @return 说明
         */
        public String getDescription() {
            return description;
        }

        /**
         * 这一道拦下的事件在时间线上算哪一类
         * <p>
         * 挂在枚举自己身上而不是各调用点各写一份：拦下的地方不止一处
         * （事件分发那一层与发送器各一处），各写一份的话，日后多一种拦法时
         * <b>只改到其中一处的那次没有任何现象</b>，另一处会把新的那一类
         * 静默归进现有的某一类，而界面上「静音丢弃」的条数就此开始虚高。
         * <p>
         * 写成 switch 表达式且<b>不给 default</b>：多一项时这里会编译不过，
         * 逼着加的那个人当场决定它算哪一类。
         * @return 时间线事件类型
         */
        public TimelineEventType timelineType() {
            return switch (this) {
                case QUIET_HOURS -> TimelineEventType.PUSH_MUTED;
                case DISABLED -> TimelineEventType.PUSH_PAUSED;
            };
        }
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
