package com.starlwr.bot.core.alert;

/**
 * 告警通道
 * <p>
 * 告警此前只有邮件一个出口，而这类机器人的使用者大多没有配置发件账号，等于没有告警。
 * 抽象出通道后可以直接推给管理员 QQ——对本项目的使用者比邮件实用得多。
 */
public interface AlertChannel {
    /**
     * 通道名称，用于日志
     * @return 通道名称
     */
    String name();

    /**
     * 当前是否可用
     * <p>
     * 未配置的通道应返回 false，以免每次告警都尝试并失败。
     * @return 是否可用
     */
    boolean isAvailable();

    /**
     * 发送告警
     * @param subject 标题
     * @param content 内容
     */
    void send(String subject, String content);
}
