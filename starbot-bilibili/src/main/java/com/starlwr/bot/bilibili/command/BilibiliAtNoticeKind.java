package com.starlwr.bot.bilibili.command;

import com.starlwr.bot.bilibili.handler.BilibiliDynamicPushHandler;
import com.starlwr.bot.bilibili.handler.BilibiliLiveOnPushHandler;
import com.starlwr.bot.core.handler.StarBotEventHandler;

/**
 * 「@我」订阅对应的通知类别
 * <p>
 * 一类通知的三件事——订阅名单存在哪个类型下、说给人听时叫什么、@ 模式配在哪个推送处理器的参数里——
 * <b>必须一起说</b>。此前前两件散在六个命令各自的 {@code type()} 与 {@code typeName()} 里，
 * 而第三件是新来的：命令要判断「本群这类通知是不是配成了 @全体成员」，就得知道去哪份参数里读。
 * <p>
 * 收成枚举而不是在某处写一张「live 对应哪个处理器」的对照表：对照表漏一项的表现是
 * 那一类通知的联动<b>安静地不生效</b>，菜单照列、命令照办，谁都看不出来。
 * 枚举则由命令基类的抽象方法逼着每一条命令认领一项，新增一类订阅时编译期就得回答。
 */
public enum BilibiliAtNoticeKind {
    /**
     * 开播通知
     */
    LIVE("live", "开播", BilibiliLiveOnPushHandler.class),

    /**
     * 动态通知
     */
    DYNAMIC("dynamic", "动态", BilibiliDynamicPushHandler.class);

    private final String type;

    private final String noticeName;

    private final Class<? extends StarBotEventHandler> handler;

    BilibiliAtNoticeKind(String type, String noticeName, Class<? extends StarBotEventHandler> handler) {
        this.type = type;
        this.noticeName = noticeName;
        this.handler = handler;
    }

    /**
     * 订阅名单里的类型键
     * @return 类型键
     */
    public String type() {
        return type;
    }

    /**
     * 这类通知在群里的说法，如「开播」
     * @return 名称
     */
    public String noticeName() {
        return noticeName;
    }

    /**
     * 承载这类通知的推送处理器全类名，即推送配置里 {@code handler} 的取值
     * @return 全类名
     */
    public String handlerName() {
        return handler.getName();
    }
}
