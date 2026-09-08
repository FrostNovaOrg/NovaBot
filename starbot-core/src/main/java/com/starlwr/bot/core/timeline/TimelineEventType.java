package com.starlwr.bot.core.timeline;

/**
 * 时间线事件类型
 * <p>
 * <b>闭集</b>：新增一类事件就在这里加一项，而不是让调用方传一个自由字符串。
 * 自由字符串的代价在读的那一端——界面要按类型筛选，而它无从知道一共有哪几种，
 * 只能把见过的凑成一张表，于是「这台实例今天没发生过的类型」在筛选框里根本不出现。
 * <p>
 * ⚠️ <b>只进不退</b>：这些名字已经写进磁盘上的 {@code timeline/*.jsonl}。
 * 改名或删项等于让既有记录里的那一行认不出来（读的时候会被跳过），
 * 而那些行是补不回来的。要改叫法请改 {@link #getDescription() 说明}，不要改枚举名。
 * <p>
 * <b>每一项都得报出自己归哪个大类</b>（见 {@link TimelineCategory}）。大类由构造参数带着，
 * 而不是另存一张「类型 → 大类」的对照表：那张表漏一项的表现不是报错，
 * 而是那一类事件在任何一枚筛选药丸下都<b>筛不到</b>，同时在不筛的时候照常显示——
 * 没有人会因此觉得哪里不对。写成构造参数之后，新加一项不标大类就编不过。
 */
public enum TimelineEventType {
    /**
     * 处于静音时段，消息被丢弃
     */
    PUSH_MUTED("静音丢弃", TimelineCategory.PUSH),

    /**
     * 全局推送开关关闭，消息被丢弃
     */
    PUSH_PAUSED("暂停丢弃", TimelineCategory.PUSH),

    /**
     * 推送成功
     */
    PUSH_SENT("推送成功", TimelineCategory.PUSH),

    /**
     * 推送失败
     */
    PUSH_FAILED("推送失败", TimelineCategory.PUSH),

    /**
     * @全体成员 没能发出（没有权限，或当天额度用尽）
     */
    AT_ALL_SKIPPED("未 @ 全体", TimelineCategory.PUSH),

    /**
     * 某项健康状况发生变化，含恢复正常
     * <p>
     * 暂归「连接」：现有的几项健康状况问的都是「与那一头通着没有」。
     * 哪天探针开始报与连接无关的事（磁盘、内存），这一项就该拆。
     */
    PROBE_CHANGED("状态变化", TimelineCategory.LINK),

    /**
     * 账号登录态由正常转为不正常
     */
    LOGIN_LOST("登录失效", TimelineCategory.LINK),

    /**
     * 一条命令被认了出来并执行完
     */
    COMMAND_EXECUTED("命令执行", TimelineCategory.COMMAND),

    /**
     * 对机器人说了话，但认不出是哪条命令，已回菜单
     */
    COMMAND_UNKNOWN("认不出的命令", TimelineCategory.COMMAND),

    /**
     * 会话处于冷却期，这一句没有回
     * <p>
     * 与「认不出」分开记：使用者看到的都是「机器人没搭理我」，而两者的下一步完全不同——
     * 一个是等三秒再说，一个是命令名打错了。
     */
    COMMAND_COOLED_DOWN("冷却忽略", TimelineCategory.COMMAND),

    /**
     * 一路告警通道把消息发出去了
     */
    ALERT_SENT("告警发出", TimelineCategory.ALERT),

    /**
     * 告警没能发出去（通道抛了，或压根没有配好的通道）
     * <p>
     * 与 {@link #PROBE_CHANGED} 记的不是一回事：那一条记「哪一项状态变了」，
     * 这一条记「这件事报没报出去」。最要紧的一种故障恰恰是两者同时发生——
     * 出网断了，于是既该告警、又发不出告警。
     */
    ALERT_FAILED("告警发不出", TimelineCategory.ALERT),

    /**
     * 保存下来的配置改动当场落到了运行中的程序上
     */
    SETTINGS_APPLIED("设置即时生效", TimelineCategory.SETTINGS),

    /**
     * 保存下来了，但要等重启才生效
     */
    SETTINGS_RESTART_PENDING("设置待重启", TimelineCategory.SETTINGS),

    /**
     * 程序启动完成
     */
    SYSTEM_STARTED("启动", TimelineCategory.SYSTEM),

    /**
     * 程序开始退出
     * <p>
     * 记的是「开始退」而不是「退完了」：退完之后就没有谁还能往时间线上写了。
     */
    SYSTEM_STOPPING("停止", TimelineCategory.SYSTEM),

    /**
     * 超出保留份数的旧配置备份被删掉
     */
    BACKUP_PRUNED("清理旧备份", TimelineCategory.SYSTEM);

    private final String description;

    private final TimelineCategory category;

    TimelineEventType(String description, TimelineCategory category) {
        this.description = description;
        this.category = category;
    }

    /**
     * 中文说明，供界面展示与筛选
     * <p>
     * 由枚举自己带着而不是让界面按类型名硬编码一张对照表：那张表漏一项的表现是
     * 界面上出现一个英文枚举名，而漏了谁只有真发生过那类事件的人才看得见。
     * @return 中文说明
     */
    public String getDescription() {
        return description;
    }

    /**
     * 归哪个大类，供界面上那一排筛选药丸用
     * @return 大类
     */
    public TimelineCategory getCategory() {
        return category;
    }

    /**
     * 按名称解析，认不出时返回 {@code null}
     * <p>
     * 不抛异常：这个方法的两个使用者——读旧记录与解析查询参数——都不该因为
     * 一个认不出的名字而整体失败。
     * @param name 类型名，不区分大小写
     * @return 事件类型，认不出时为 {@code null}
     */
    public static TimelineEventType parse(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }

        for (TimelineEventType type : values()) {
            if (type.name().equalsIgnoreCase(name.trim())) {
                return type;
            }
        }
        return null;
    }
}
