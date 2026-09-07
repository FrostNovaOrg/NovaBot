package com.starlwr.bot.core.config.ui.page;

import java.util.List;

/**
 * 控制台页面注册点
 * <p>
 * 各平台插件实现本接口并注册为 Bean，即可往控制台里添一个自己的页签。核心只保管这张清单，
 * <b>不认识任何一个具体平台</b>：页签叫什么、页面长什么样、里面调哪些接口，全在插件自己那一侧。
 * <p>
 * 存在的意义是把「装了哪些平台」这件事从编译期挪到运行期。此前平台页写死在核心的界面文件里，
 * 于是核心即使一个平台插件都没装，页签条上也照样立着那个平台的名字，点开是一张永远空着的页；
 * 反过来，想加第二个平台就得回头改核心的界面。
 */
public interface ConsolePageProvider {
    /**
     * 页签标识，同时用作页面容器的元素 id
     * <p>
     * 只接受小写字母开头的小写字母、数字与连字符：它会原样成为 DOM 里的 id 与页签的 data 属性，
     * 不符合的一律不予登记。
     * @return 页签标识
     */
    String id();

    /**
     * 页签上显示的名字
     * @return 显示名称
     */
    String displayName();

    /**
     * 页面脚本的文件名，例如 {@code bilibili.js}
     * <p>
     * 文件放在插件自己的 {@code config-ui-pages/} 资源目录下，由核心按<b>已登记的文件名</b>
     * 原样取出——名字不做任何拼接以外的解释，也不接受路径分隔符，因此取不到登记之外的东西。
     * <p>
     * <b>不可与配置界面自带的资源重名</b>（{@code core.js}、{@code main.js} 之类）。两者共用同一个
     * 取资源的地址，重名就意味着同一个地址下有两份内容，而取到的是哪一份从外面看不出来；
     * 因此重名的页一律不予登记，日志里会点名说明。换个文件名即可。
     * @return 脚本文件名
     */
    String script();

    /**
     * 同目录下还要端出去的其它 {@code .js} 文件名
     * <p>
     * 主脚本里 {@code import './x.js'} 会解析到 {@code /config/assets/x.js}。只登记
     * {@link #script()} 的话，那几个文件名对不上白名单，请求就是 404。
     * 名字与 {@link #script()} 同一套规则：只接受字母数字、下划线与连字符，扩展名只认
     * {@code .js}，不含路径分隔符。与配置界面自带资源同名、与其它插件已登记的文件名撞车，
     * 或名字不合规，这一页不予登记。
     * <p>
     * 缺省是空清单。已有的实现一个字都不用改。
     * @return 附属脚本文件名，可为空，不可为 {@code null}
     */
    default List<String> assets() {
        return List.of();
    }

    /**
     * 页签在标签条上的先后，数小的在前，相同时按标识排
     * @return 排序值
     */
    default int order() {
        return 100;
    }

    /**
     * 这一页挂到控制台的哪一处
     * <p>
     * 缺省是设置页「高级」下的一张子页，也就是本接口原先唯一的那种落法——
     * 已有的实现一个字都不用改。账号登录这类「这台机器与外面怎么连」的页申报
     * {@link ConsolePageSlot#LINKS}，会成为连接页上的一张卡。
     * 需要与首页／推送／主播等并列的一整页申报 {@link ConsolePageSlot#TOP}，
     * 导航里会多一个入口，地址是 {@code #/<页标识>}。
     * 只要在首页放一张卡、不占导航的，申报 {@link ConsolePageSlot#HOME_CARD}。
     * @return 落位
     */
    default ConsolePageSlot slot() {
        return ConsolePageSlot.SETTINGS;
    }
}
