package com.starlwr.bot.core.alert;

/**
 * 告警通道
 * <p>
 * 告警此前只有邮件一个出口，而这类机器人的使用者大多没有配置发件账号，等于没有告警。
 * 抽象出通道后可以直接推给管理员 QQ——对本项目的使用者比邮件实用得多。
 */
public interface AlertChannel {
    /**
     * 通道标识，只含 ASCII，用于接口参数与界面上的卡片锚点
     * <p>
     * 与 {@link #name()} 分开两栏而不是拿名字当标识：名字是给人看的、会因为措辞改动而变，
     * 而标识一旦进了接口参数就成了对外的约定。合成一栏的话，某天把「邮件」改成「邮件告警」，
     * 界面上那张卡的「发一条测试」就会静静地开始报「没有这一路通道」。
     * @return 通道标识
     */
    String id();

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
