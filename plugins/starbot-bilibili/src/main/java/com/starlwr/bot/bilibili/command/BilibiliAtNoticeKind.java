package com.starlwr.bot.bilibili.command;

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
 *
 * <h2>处理器按全类名字符串认领，而那串<b>不一定是它现在的名字</b></h2>
 * 动态通知的处理器已经随报告插件搬出本模块，而本模块<b>一个字也不许提报告插件</b>
 * （边界尺格8：搬出去了还在这边指名道姓，等于没搬）。因此这里写的是它<b>搬走之前</b>
 * 的那一串——也正是老使用者 {@code datasource.json} 里写着的那一串。
 * <p>
 * 靠得住的不是这串名字本身，而是比对的方式：见 {@code BilibiliAtCommand} 的
 * {@code isKind}，除真类名外还比处理器自己声明的旧名
 * （{@code StarBotEventHandler.legacyClassNames()}），两种写法都对得上。
 * 这串名字与哪个处理器对得上，由报告插件那一侧的判据看着
 * （{@code LegacyHandlerClassNameTest}，那里两边都在类路径上）。
 */
public enum BilibiliAtNoticeKind {
    /**
     * 开播通知
     */
    LIVE("live", "开播", "com.starlwr.bot.bilibili.handler.BilibiliLiveOnPushHandler"),

    /**
     * 动态通知
     */
    DYNAMIC("dynamic", "动态", "com.starlwr.bot.bilibili.handler.BilibiliDynamicPushHandler");

    private final String type;

    private final String noticeName;

    private final String handler;

    BilibiliAtNoticeKind(String type, String noticeName, String handler) {
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
     * <p>
     * 处理器搬过模块的话，这里给的是它<b>搬走之前</b>那一串（见类注释）。因此比对时不能
     * 只与真类名比，还得比处理器自己声明的旧名，否则新配置一条也认不出来。
     * @return 全类名
     */
    public String handlerName() {
        return handler;
    }
}
