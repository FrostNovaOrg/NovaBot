package org.frostnova.nova.interact.danmaku;

/**
 * 弹幕没发出的分类
 * <p>
 * 除 {@link #UNCERTAIN} 以外，各类都是「确定没有出现在直播间」。没发出的一律不自动重试：
 * 要不要再发、什么时候发，由调用方按分类自己定。
 */
public enum DanmakuFailure {
    /**
     * 未选好可用账号：选的账号没设好——发言账号从没登录、整个停用或与监听账号成了同一个号，
     * 或者监听账号开着免登录模式
     */
    NO_ACCOUNT("未选好可用账号"),

    /**
     * 账号未登录或失效：登录过，但此刻登录态不行（失效、暂未确认，或平台说未登录、校验失败）
     */
    ACCOUNT_UNAVAILABLE("账号未登录或失效"),

    /**
     * 字数超限：超过这个房间里本账号一条能发的字数，不截断、不拆条
     */
    TOO_LONG("字数超限"),

    /**
     * 发得太快或重复：离上一条不到最短间隔，或平台说太频繁、太快、和上一条重复
     */
    TOO_FAST("发得太快或重复"),

    /**
     * 被屏蔽词拦下：平台回了成功的代码，但带着屏蔽提示
     */
    BLOCKED_WORD("被屏蔽词拦下"),

    /**
     * 平台风控：平台回了风控类信号，或这个接口正在风控冷却中
     */
    RISK_CONTROL("平台风控"),

    /**
     * 暂停发送中：这个账号刚被平台说太频繁或风控，暂停期内一律不发
     */
    PAUSED("暂停发送中"),

    /**
     * 平台返回了别的错误，包括认不出的回包；平台的代码与提示原样带出
     */
    PLATFORM_ERROR("平台返回了别的错误"),

    /**
     * 网络失败：请求没能到达平台（连不上、找不到地址）
     */
    NETWORK("网络失败"),

    /**
     * 不确定是否发出：请求已交出去、没等到回包（超时、连接中断），可能已经出现在直播间里
     */
    UNCERTAIN("不确定是否发出"),

    /**
     * 文字不合要求：去掉两头空白后是空的，或含换行
     */
    INVALID_TEXT("文字不合要求"),

    /**
     * 房间号不属于任何已添加的主播
     */
    ROOM_NOT_ALLOWED("不是已添加主播的直播间");

    private final String description;

    DanmakuFailure(String description) {
        this.description = description;
    }

    /**
     * 中文说法
     * @return 说法
     */
    public String description() {
        return description;
    }
}
