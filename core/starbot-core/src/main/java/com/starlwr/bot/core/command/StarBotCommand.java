package com.starlwr.bot.core.command;

import java.util.List;

/**
 * 聊天命令
 * <p>
 * 实现本接口并注册为 Bean（插件中用 {@code @StarBotComponent}）即可被命令分发器发现，
 * 无需改动核心的任何注册表。
 * <p>
 * <b>命令只应在自己确实该管的会话里响应。</b>群聊机器人往往同时在多个群，
 * 在无关群里回话是最容易招致反感的行为。这一条由
 * {@link CommandDispatcher} 统一把关：只在<b>已配置推送</b>的会话里处理命令，
 * 且群聊里还要 @ 过机器人。命令实现没有放宽它的余地——留一个能放宽的开关，
 * 就等于把这条规矩交给每个命令各自守，而漏守一次就是一次投诉。
 */
public interface StarBotCommand {
    /**
     * 命令名，即触发词
     * @return 命令名
     */
    String name();

    /**
     * 命令别名
     * @return 别名列表，默认无
     */
    default List<String> aliases() {
        return List.of();
    }

    /**
     * 命令说明，用于「菜单」
     * @return 说明
     */
    String description();

    /**
     * 参数用法说明，用于「菜单」，无参数时为空
     * @return 用法说明
     */
    default String usage() {
        return "";
    }

    /**
     * 命令归属的分类，用于「菜单」分组
     * <p>
     * 命令一多，一长串平铺的清单就没人看得下去。分类名自由填写，
     * 「菜单」按各分类首次出现的顺序展示。
     * @return 分类名
     */
    default String category() {
        return "其他";
    }

    /**
     * 是否只在群聊中可用
     * @return 是否仅限群聊
     */
    default boolean groupOnly() {
        return true;
    }

    /**
     * 本命令在这台机器上是否可用
     * <p>
     * 有些命令要靠本机不一定开着的能力（如累计数据要外部存储）。这类命令<b>不出现在「菜单」里</b>——
     * 列一条发了必被拒的命令，等于请人白跑一趟；但它仍然是一条命令，收到时由自己回一句
     * 「为什么现在用不了」，而不是丢一份菜单让人再找一遍。
     * <p>
     * 与「禁用命令」的区别：那是本会话的人为选择，这是整台机器的客观能力。
     * 能力配好后自动恢复，不必重启。
     * @return 是否可用，默认可用
     */
    default boolean available() {
        return true;
    }

    /**
     * 本命令在这个会话里还有没有意义
     * <p>
     * 「菜单」问的是这一条，不是 {@link #available()}：有些命令整台机器都装得好好的，
     * 但在<b>某一个会话</b>里做什么都不会有效果——本群的开播通知配成了 @全体成员 时，
     * 「开播@我」就是这样一条。列出来只会被照着发一遍，然后收到一句解释。
     * <p>
     * 默认：本机可用，且（群聊，或本命令不限群聊）。个别命令还可以再叠一层
     * （例如本群开播通知配成了 @全体成员）。两个互不相干的开关摆在一起，
     * 漏改一处的表现是菜单与实际能不能用对不上，而这两者本就该是同一个答案的两问。
     * @param context 执行上下文
     * @return 是否在这个会话里列进菜单
     */
    default boolean availableIn(CommandContext context) {
        return available() && (context.isGroup() || !groupOnly());
    }

    /**
     * 「菜单」里跟在本命令那一行后面的一句会话相关的补充说明
     * <p>
     * 用于那种「能用，但在这个会话里跟你想的不太一样」的情形：本群配了
     * 「@全体成员，不行就 @订阅的人」时，订阅仍然有用，只是<b>平时用不上</b>。
     * 不写这一句的话，订阅了却从没被单独 @ 到的人只会以为功能坏了。
     * @param context 执行上下文
     * @return 补充说明，没有时为空串
     */
    default String menuNote(CommandContext context) {
        return "";
    }

    /**
     * 是否可被「禁用命令」关闭
     * <p>
     * 「菜单」与「启用命令」自身不可禁用：否则一旦关掉就再也没有入口把它开回来。
     * @return 是否可禁用
     */
    default boolean disableable() {
        return true;
    }

    /**
     * 是否仅管理员可用
     * <p>
     * 用于会**影响他人**的命令：「禁用命令」改变的是全群的可用功能，
     * 放任何人执行等于把机器人的开关交给了路过的人。
     * 只影响自己的命令（如「@我」）不该设为 true。
     * @return 是否仅管理员可用，默认否
     */
    default boolean requiresAdmin() {
        return false;
    }

    /**
     * 执行命令
     * @param context 执行上下文
     * @return 回复内容，为空则不回复
     */
    CommandReply execute(CommandContext context);
}
