package org.frostnova.nova.core.alert;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 一条等待重投的告警
 * <p>
 * <b>{@code occurredAt} 是问题发生的时刻，不是重投的时刻。</b>补发的告警最容易被读错的
 * 就是时间：出网中断半小时后收到一条「登录已失效」，若不写明原始时刻，
 * 人会以为刚刚才失效，去查一个半小时前就结束了的现场。
 *
 * @param key 问题标识
 * @param subject 标题
 * @param content 内容
 * @param occurredAt 问题<b>首次</b>发生的时刻
 * @param attempts 已尝试投递的次数（含首次）
 */
public record PendingAlert(String key, String subject, String content, Instant occurredAt, int attempts) {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 再记一次失败
     */
    public PendingAlert retried() {
        return new PendingAlert(key, subject, content, occurredAt, attempts + 1);
    }

    /**
     * 发生时刻的本机时区表示
     * <p>
     * 运维日志里的时刻一律用本机时区：直接打 {@link Instant} 会输出 UTC，
     * 与同一份日志里其余每一行都差着时区，看的人得自己换算。
     */
    public String occurredAtText() {
        return TIME.format(occurredAt.atZone(ZoneId.systemDefault()));
    }

    /**
     * 补发时用的正文：在原文前面加一段说明它是补发的、原本发生在什么时候
     * <p>
     * 加在<b>开头</b>而不是结尾：告警常在手机通知栏里只显示前一两行。
     * @param now 当前时刻
     */
    public String contentForRedelivery(Instant now) {
        long delayed = Math.max(0, Duration.between(occurredAt, now).toSeconds());
        return "【补发】原始发生时刻 " + occurredAtText()
                + "，已延迟 " + describe(delayed)
                + "（第 " + attempts + " 次投递失败后重投）\n\n"
                + content;
    }

    /**
     * 把秒数说成人话，为零时说「不到 1 秒」而不是空字符串
     */
    private static String describe(long seconds) {
        if (seconds < 60) {
            return seconds <= 0 ? "不到 1 秒" : seconds + " 秒";
        }
        long hours = seconds / 3600;
        long minutes = seconds % 3600 / 60;
        return (hours > 0 ? hours + " 时 " : "") + (minutes > 0 ? minutes + " 分 " : "") + seconds % 60 + " 秒";
    }
}
