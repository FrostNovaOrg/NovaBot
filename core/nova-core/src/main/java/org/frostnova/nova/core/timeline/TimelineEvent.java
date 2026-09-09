package org.frostnova.nova.core.timeline;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 时间线上的一条事件
 * <p>
 * 「刚才那条推了吗」「昨晚为什么没推」这两个问题此前只能翻日志文件回答，
 * 而日志是给排障用的：它按时间混在一起，含大量与这两个问题无关的行，
 * 且默认级别下丢弃的那条根本不写。时间线记的是<b>使用者关心的事</b>，
 * 结构化、按日分文件、可筛可搜。
 *
 * @param at 发生时刻，毫秒
 * @param type 事件类型
 * @param level 严重程度
 * @param streamer 相关主播，无关时为空
 * @param channel 相关推送通道（如「群 12345」），无关时为空
 * @param text 一句人话，直接展示给使用者看；写不出人话时为空
 * @param detail 补充键值，供展开细看，无补充时为空表
 */
public record TimelineEvent(long at, TimelineEventType type, Level level,
                            String streamer, String channel, String text,
                            Map<String, String> detail) {
    /**
     * 严重程度
     * <p>
     * 只分三档，因为界面上只有一个「只看问题」的开关：它筛掉的正是 {@link #INFO}。
     * 再细分下去，使用者要先学会这套分级才用得了那个开关。
     */
    public enum Level {
        /**
         * 正常发生的事
         */
        INFO,

        /**
         * 有东西没做成，但系统仍在正常工作
         */
        WARN,

        /**
         * 出故障了，需要处理
         */
        ERROR;

        /**
         * 按名称解析，认不出时返回 {@link #INFO}
         * <p>
         * 认不出的记录读成 INFO 而不是丢掉：一条读不准级别的记录仍然记着「发生过什么」，
         * 而把它判死等于让历史凭空少一行。落到 INFO 而不是 ERROR，是因为
         * 「只看问题」里多出一条来路不明的记录，比少一条更容易被当成故障去追。
         * @param name 级别名，不区分大小写
         * @return 级别
         */
        public static Level parse(String name) {
            if (name != null) {
                for (Level level : values()) {
                    if (level.name().equalsIgnoreCase(name.trim())) {
                        return level;
                    }
                }
            }
            return INFO;
        }
    }

    /**
     * 规范化：可空的四项一律收成「空即 null」，补充键值收成不可变表
     * <p>
     * 空串与 {@code null} 在筛选那一端不是一回事——按主播筛时，空串是一个筛不到任何东西的值，
     * 而 null 表示「这条与主播无关」。在入口处收成一种形态，读的那一端就不必两种都判。
     */
    public TimelineEvent {
        streamer = blankToNull(streamer);
        channel = blankToNull(channel);
        text = blankToNull(text);
        detail = detail == null || detail.isEmpty() ? Map.of() : Map.copyOf(detail);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * 开始构造一条事件
     * @param type 事件类型
     * @param level 严重程度
     * @return 构造器
     */
    public static Builder of(TimelineEventType type, Level level) {
        return new Builder(type, level);
    }

    /**
     * 事件构造器
     * <p>
     * 七个字段里有四个可空，直接调构造方法就要在调用点写四个 {@code null}，
     * 而相邻的三项都是字符串——写反了编译期一声不响，表现是界面上「主播」那一栏里出现群号。
     */
    public static final class Builder {
        private final TimelineEventType type;

        private final Level level;

        private long at = System.currentTimeMillis();

        private String streamer;

        private String channel;

        private String text;

        private final Map<String, String> detail = new LinkedHashMap<>();

        private Builder(TimelineEventType type, Level level) {
            this.type = type;
            this.level = level;
        }

        /**
         * 指定发生时刻，默认为当前时刻
         * @param instant 时刻
         * @return 构造器自身
         */
        public Builder at(Instant instant) {
            this.at = instant.toEpochMilli();
            return this;
        }

        /**
         * 相关主播
         * @param streamer 主播名
         * @return 构造器自身
         */
        public Builder streamer(String streamer) {
            this.streamer = streamer;
            return this;
        }

        /**
         * 相关推送通道
         * @param channel 通道描述
         * @return 构造器自身
         */
        public Builder channel(String channel) {
            this.channel = channel;
            return this;
        }

        /**
         * 一句人话
         * @param text 正文
         * @return 构造器自身
         */
        public Builder text(String text) {
            this.text = text;
            return this;
        }

        /**
         * 追加一项补充键值，值为空时整项不记
         * @param key 键
         * @param value 值
         * @return 构造器自身
         */
        public Builder detail(String key, String value) {
            if (value != null && !value.isBlank()) {
                detail.put(key, value);
            }
            return this;
        }

        /**
         * 构造事件
         * @return 事件
         */
        public TimelineEvent build() {
            return new TimelineEvent(at, type, level, streamer, channel, text, detail);
        }
    }
}
